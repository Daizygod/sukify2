<?php

namespace Tests\Feature;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Jobs\AnalyzeTrackAudio;
use App\Jobs\FetchReleaseArt;
use App\Jobs\ImportBulkItem;
use App\Jobs\PrepareBulkUpload;
use App\Jobs\ProcessReleaseCover;
use App\Models\Artist;
use App\Models\BulkUpload;
use App\Models\BulkUploadItem;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use App\Services\Bulk\DiskSpace;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Facades\Storage;
use Symfony\Component\Process\Process;
use Tests\Concerns\BuildsCatalog;
use Tests\Concerns\MakesAudioFixtures;
use Tests\TestCase;

/**
 * Массовая загрузка: архив с папками и файлами разных форматов раскладывается
 * по каталогу — полные версии вместо превью, новые треки, ручной разбор.
 */
class BulkUploadTest extends TestCase
{
    use BuildsCatalog, MakesAudioFixtures, RefreshDatabase;

    private string $bulkDir;

    protected function setUp(): void
    {
        parent::setUp();

        $this->bulkDir = sys_get_temp_dir().'/sukify-bulk-'.uniqid();
        config(['bulk.dir' => $this->bulkDir]);
        Storage::fake('s3');
        $this->withoutVite();
        // Анализ темпа, обложки и Deezer — отдельные задачи со своими тестами.
        Queue::fake([AnalyzeTrackAudio::class, FetchReleaseArt::class, ProcessReleaseCover::class]);
    }

    protected function tearDown(): void
    {
        (new Process(['rm', '-rf', $this->bulkDir]))->run();
        $this->removeFixtures();
        parent::tearDown();
    }

    public function test_archive_is_sorted_into_the_catalog(): void
    {
        // Каталог как после импорта из Spotify: у «Серпантина» превью в альбоме и
        // в сингле, у «Положения» уже полная версия.
        $markul = $this->makeArtist('Markul');
        $albumTrack = $this->previewTrack($this->makeRelease($markul, 'Sense of Human'), 'Серпантин');
        $singleTrack = $this->previewTrack($this->makeRelease($markul, 'Серпантин'), 'Серпантин');
        $full = $this->makeTrack($this->makeRelease($this->makeArtist('Скриптонит'), 'Уроборос'), 'Положение');
        // Длительность сверяется только у полных версий: та же запись — та же длина.
        $full->forceFill(['duration_ms' => 2000])->saveQuietly();

        $tags = fn (string $title, string $artist, string $album, int $n) => compact('title', 'artist', 'album') + ['track' => $n];
        $archive = $this->zip([
            'Моя музыка/Markul/Sense of Human/03 - Серпантин.flac' => $this->audio('a.flac', $tags('Серпантин', 'Markul', 'Sense of Human', 3)),
            'Моя музыка/Markul/Sense of Human/03 - Серпантин.mp3' => $this->audio('a.mp3', $tags('Серпантин', 'Markul', 'Sense of Human', 3)),
            'Моя музыка/Скриптонит/Уроборос/01 - Положение.mp3' => $this->audio('b.mp3', $tags('Положение', 'Скриптонит', 'Уроборос', 1)),
            'Моя музыка/Новый Артист/Новый альбом/02 - Вторая.m4a' => $this->audio('c.m4a', $tags('Вторая', 'Новый Артист', 'Новый альбом', 2), 550),
            'Моя музыка/Новый Артист/Новый альбом/01 - Первая.ogg' => $this->audio('d.ogg', $tags('Первая', 'Новый Артист', 'Новый альбом', 1), 660),
            'Моя музыка/Новый Артист/Новый альбом/folder.jpg' => $this->jpeg(),
            // Ни артиста, ни папки — только название и альбом: спросить человека.
            'Серпантин.ogg' => $this->audio('e.ogg', ['title' => 'Серпантин', 'album' => 'Sense of Human']),
            // Без тегов, имя в cp866 без пометки UTF-8 — как из Проводника.
            iconv('UTF-8', 'CP866', 'Кино - Спокойная ночь.wav') => $this->audio('f.wav'),
            'Сломанный.mp3' => null,
            '__MACOSX/Моя музыка/._Серпантин.mp3' => null,
        ]);

        $this->fakeHttp();
        $upload = $this->uploadArchive($archive, ['check_censorship' => false]);

        $this->assertSame('done', $upload->status, (string) $upload->error);
        $items = $upload->items()->get()->keyBy('path');
        $this->assertCount(10 - 1, $items, 'мусор __MACOSX пропущен');

        // FLAC лёг и в альбом, и в сингл; mp3 той же песни — запасная копия.
        $flac = $items['Моя музыка/Markul/Sense of Human/03 - Серпантин.flac'];
        $this->assertSame('done', $flac->status, (string) $flac->error);
        $this->assertEqualsCanonicalizing([$albumTrack->id, $singleTrack->id], $flac->targetIds());
        foreach ([$albumTrack, $singleTrack] as $track) {
            $track->refresh();
            $this->assertFalse($track->preview_only);
            $this->assertSame(ProcessingStatus::Ready, $track->processing_status);
            $this->assertGreaterThan(1500, $track->duration_ms);
            Storage::disk('s3')->assertExists("audio/{$track->id}/stream.mp3");
        }
        $mp3 = $items['Моя музыка/Markul/Sense of Human/03 - Серпантин.mp3'];
        $this->assertSame('duplicate', $mp3->status);
        $this->assertSame($flac->id, $mp3->duplicate_of_id);

        // Полную версию не трогаем без галочки «заменять».
        $kept = $items['Моя музыка/Скриптонит/Уроборос/01 - Положение.mp3'];
        $this->assertSame(['skipped', 'has_full_audio'], [$kept->status, $kept->reason]);
        $this->assertSame($full->audio_rev, $full->fresh()->audio_rev);

        // Новый артист и альбом: порядок по номерам из тегов, обложка из папки.
        $artist = Artist::where('name', 'Новый Артист')->firstOrFail();
        $release = Release::where('artist_id', $artist->id)->where('title', 'Новый альбом')->firstOrFail();
        $this->assertSame(ReleaseType::Album, $release->type);
        $this->assertSame(['Первая', 'Вторая'], $release->tracks()->pluck('title')->all());
        $this->assertSame([1, 2], $release->tracks()->pluck('track_number')->all());
        $this->assertTrue($release->tracks()->get()->every(fn ($t) => $t->processing_status === ProcessingStatus::Ready));
        Queue::assertPushed(ProcessReleaseCover::class, fn ($job) => $job->releaseId === $release->id);

        // Файл без тегов: артист и название из имени, кириллица из cp866 цела.
        $kino = $items['Кино - Спокойная ночь.wav'];
        $this->assertSame('done', $kino->status, (string) $kino->error);
        $single = Track::findOrFail($kino->track_id)->release;
        $this->assertSame(['Кино', 'Спокойная ночь', ReleaseType::Single], [$single->artist->name, $single->title, $single->type]);
        Queue::assertPushed(FetchReleaseArt::class, fn ($job) => $job->releaseId === $single->id);

        $doubt = $items['Серпантин.ogg'];
        $this->assertSame(['review', 'ambiguous'], [$doubt->status, $doubt->reason]);
        $this->assertSame($albumTrack->id, $doubt->candidates[0]['id']);

        $broken = $items['Сломанный.mp3'];
        $this->assertSame(['failed', 'unreadable'], [$broken->status, $broken->reason]);

        $this->assertSame('asset', $items['Моя музыка/Новый Артист/Новый альбом/folder.jpg']->status);
        $this->assertSame(1, $upload->counts()['review']);
        $this->assertStringContainsString('ждут решения: 1', $upload->stage);
        // Архив после распаковки удалён, файлы готовых — тоже: на диске только
        // то, что ещё может понадобиться (разбор, копии, обложка).
        $this->assertFileDoesNotExist($upload->archivePath());
        $this->assertNull($flac->local_path);
        $this->assertCount(
            $upload->items()->whereNotNull('local_path')->count(),
            glob($upload->dir().'/files/*'),
        );
    }

    public function test_doubtful_file_is_resolved_by_hand(): void
    {
        $markul = $this->makeArtist('Markul');
        $track = $this->previewTrack($this->makeRelease($markul, 'Sense of Human'), 'Серпантин');
        $other = $this->previewTrack($this->makeRelease($markul, 'Другой'), 'Ещё один');
        $this->fakeHttp();

        $upload = $this->uploadArchive($this->zip([
            'Серпантин.ogg' => $this->audio('e.ogg', ['title' => 'Серпантин', 'album' => 'Sense of Human']),
            'x.ogg' => $this->audio('x.ogg', ['title' => 'Что-то']),
        ]), ['create_missing' => true, 'check_censorship' => false]);

        $doubt = $upload->items()->where('path', 'Серпантин.ogg')->firstOrFail();
        $nameless = $upload->items()->where('path', 'x.ogg')->firstOrFail();
        $this->assertSame(['review', 'review'], [$doubt->status, $nameless->status]);
        // Без артиста новым не заводим — только выбрать трек.
        $this->assertSame('no_match', $nameless->reason);

        // Поиск трека для ручного выбора: слова — в названии, артисте, альбоме.
        $this->getJson('/admin/uploads/tracks?q=markul серп')
            ->assertOk()
            ->assertJsonPath('data.0.id', $track->id)
            ->assertJsonCount(1, 'data');

        $this->post("/admin/uploads/{$upload->id}/items/{$nameless->id}", ['action' => 'create'])
            ->assertSessionHas('error');
        $this->post("/admin/uploads/{$upload->id}/items/{$doubt->id}", ['action' => 'attach', 'track_id' => $other->id])
            ->assertSessionHas('success');

        $this->assertSame('done', $doubt->fresh()->status);
        $this->assertFalse($other->fresh()->preview_only);
        $this->assertTrue($track->fresh()->preview_only);

        $this->post("/admin/uploads/{$upload->id}/resolve-all", ['action' => 'skip'])->assertSessionHas('success');
        $this->assertSame('skipped', $nameless->fresh()->status);
        $this->assertSame('done', $upload->fresh()->status);
    }

    public function test_archive_upload_resumes_after_a_break(): void
    {
        Queue::fake([PrepareBulkUpload::class]);
        $this->actingAs($this->admin());
        $bytes = random_bytes(3000);

        $id = $this->postJson('/admin/uploads', ['name' => 'музыка.zip', 'size' => 3000, 'kind' => 'archive'])
            ->assertCreated()
            ->assertJsonPath('received_bytes', 0)
            ->json('id');

        $this->chunk($id, 0, substr($bytes, 0, 1000))->assertOk()->assertJsonPath('received_bytes', 1000);
        // Браузер переспросил после обрыва и прислал не с того места.
        $this->chunk($id, 0, substr($bytes, 0, 1000))->assertStatus(409)->assertJsonPath('received_bytes', 1000);

        // Тот же файл снова — это та же загрузка, с того же места.
        $this->postJson('/admin/uploads', ['name' => 'музыка.zip', 'size' => 3000, 'kind' => 'archive'])
            ->assertOk()
            ->assertJsonPath('id', $id)
            ->assertJsonPath('received_bytes', 1000);

        $this->chunk($id, 1000, substr($bytes, 1000))->assertOk()->assertJsonPath('complete', true);

        $upload = BulkUpload::findOrFail($id);
        $this->assertSame('queued', $upload->status);
        $this->assertSame($bytes, file_get_contents($upload->archivePath()));
        Queue::assertPushed(PrepareBulkUpload::class, fn ($job) => $job->uploadId === $id);

        // Готовую загрузку больше не дописать.
        $this->chunk($id, 3000, 'x')->assertStatus(409);

        // «Пауза» оборвала ответ, а не сам последний кусок: тот же файл снова —
        // та же загрузка, слать нечего.
        $this->postJson('/admin/uploads', ['name' => 'музыка.zip', 'size' => 3000, 'kind' => 'archive'])
            ->assertOk()
            ->assertJsonPath('id', $id)
            ->assertJsonPath('received_bytes', 3000);
        $this->assertSame(1, BulkUpload::count());
    }

    public function test_not_an_archive_or_not_enough_space_is_refused_up_front(): void
    {
        $this->actingAs($this->admin());
        $this->app->instance(DiskSpace::class, new class extends DiskSpace
        {
            public function free(string $path): int
            {
                return 5 * 1024 ** 3;
            }
        });

        $this->postJson('/admin/uploads', ['name' => 'song.mp3', 'size' => 10, 'kind' => 'archive'])
            ->assertStatus(422)
            ->assertJsonPath('message', 'Это не архив: подойдут ZIP, RAR, 7z или TAR.');

        // Архив на 2 ГБ: на диске нужно 4 (архив + распакованное), свободно 3.
        $this->postJson('/admin/uploads', ['name' => 'big.rar', 'size' => 2 * 1024 ** 3, 'kind' => 'archive'])
            ->assertStatus(422)
            ->assertJsonPath('message', 'Не хватит места на сервере: нужно 4 ГБ, свободно 3 ГБ. Разбей музыку на несколько архивов поменьше.');

        $this->assertSame(0, BulkUpload::count());
    }

    public function test_folder_is_uploaded_file_by_file(): void
    {
        $this->fakeHttp();
        $this->actingAs($this->admin());
        $track = $this->previewTrack($this->makeRelease($this->makeArtist('Markul'), 'Sense of Human'), 'Серпантин');
        $file = $this->audio('a.mp3', ['title' => 'Серпантин', 'artist' => 'Markul']);

        $id = $this->postJson('/admin/uploads', [
            'name' => 'Музыка', 'size' => filesize($file), 'kind' => 'folder', 'files' => 1,
            'options' => ['check_censorship' => false],
        ])->assertCreated()->json('id');

        $send = function () use ($id, $file) {
            copy($file, $copy = $file.'.'.uniqid().'.mp3');

            return $this->post("/admin/uploads/{$id}/files", [
                'path' => 'Музыка/Markul/Серпантин.mp3',
                'file' => new UploadedFile($copy, 'Серпантин.mp3', 'audio/mpeg', null, true),
            ], ['Accept' => 'application/json']);
        };
        $send()->assertOk();
        // Повтор после обрыва — не второй файл.
        $send()->assertOk()->assertJsonPath('duplicate', true);

        $this->postJson("/admin/uploads/{$id}/finish")->assertOk();

        $this->assertSame('done', BulkUpload::findOrFail($id)->status);
        $this->assertFalse($track->fresh()->preview_only);
    }

    public function test_seven_zip_is_unpacked_too(): void
    {
        if (! is_executable('/usr/bin/bsdtar')) {
            $this->markTestSkipped('bsdtar не установлен');
        }

        $this->fakeHttp();
        $track = $this->previewTrack($this->makeRelease($this->makeArtist('Markul'), 'Sense of Human'), 'Серпантин');
        $dir = $this->fixtureDir().'/src/Markul';
        mkdir($dir, 0777, true);
        copy($this->audio('a.flac', ['title' => 'Серпантин', 'artist' => 'Markul']), $dir.'/Серпантин.flac');
        $archive = $this->fixtureDir().'/music.7z';
        (new Process(['bsdtar', '--format', '7zip', '-cf', $archive, '-C', $this->fixtureDir().'/src', 'Markul'], null, ['LC_ALL' => 'C.UTF-8']))->mustRun();

        $upload = $this->uploadArchive($archive, ['check_censorship' => false], 'music.7z');

        $this->assertSame('done', $upload->status, (string) $upload->error);
        $this->assertSame('Markul/Серпантин.flac', $upload->items()->value('path'));
        $this->assertFalse($track->fresh()->preview_only);
    }

    public function test_censored_file_is_flagged_against_deezer_previews(): void
    {
        $markul = $this->makeArtist('madk1d');
        $track = $this->previewTrack($this->makeRelease($markul, 'мир, труд, май'), 'грустная история');

        $this->fakeHttp([
            'api.deezer.com/search*' => Http::response(['data' => [
                ['id' => 2577291232, 'title' => 'грустная история', 'duration' => 2, 'rank' => 165932,
                    'preview' => 'https://cdnt-preview.dzcdn.net/single.mp3',
                    'artist' => ['name' => 'madk1d'], 'album' => ['title' => 'мир, труд, май']],
                ['id' => 3134712131, 'title' => 'грустная история', 'duration' => 2, 'rank' => 151718,
                    'preview' => 'https://cdnt-preview.dzcdn.net/album.mp3',
                    'artist' => ['name' => 'madk1d'], 'album' => ['title' => 'Он сказал поехали!']],
                // Ремикс другой длины — не издание этой песни.
                ['id' => 1, 'title' => 'грустная история (remix)', 'duration' => 200, 'preview' => 'https://x/remix.mp3',
                    'artist' => ['name' => 'madk1d'], 'album' => ['title' => 'Remixes']],
            ]]),
            'cdnt-preview.dzcdn.net/*' => Http::response(str_repeat('ID3', 1000)),
            'analyzer:8300/compare' => Http::response(['results' => [
                // Файл совпал с синглом, а в альбомном издании голос есть там, где в файле тишина.
                ['name' => '2577291232', 'aligned' => true, 'score' => 0.95, 'offset_ms' => 0, 'missing' => [], 'extra' => [], 'differs' => []],
                ['name' => '3134712131', 'aligned' => true, 'score' => 0.9, 'offset_ms' => 0,
                    'missing' => [[41200, 41700], [52300, 52600]], 'extra' => [], 'differs' => []],
            ]]),
        ]);

        $upload = $this->uploadArchive($this->zip([
            'madk1d - грустная история.mp3' => $this->audio('a.mp3', ['title' => 'грустная история', 'artist' => 'madk1d']),
        ]));

        $item = $upload->items()->firstOrFail();
        $this->assertSame('done', $item->status, (string) $item->error);
        $this->assertSame('suspect', $item->censor, json_encode($item->censor_info, JSON_UNESCAPED_UNICODE));
        $this->assertSame(['мир, труд, май', 'Он сказал поехали!'], array_column($item->censor_info['editions'], 'album'));
        $this->assertSame([[41200, 41700], [52300, 52600]], $item->censor_info['editions'][1]['missing']);
        $this->assertSame(1, $upload->counts()['suspect']);
        $this->assertFalse($track->fresh()->preview_only);

        Http::assertSent(fn ($r) => str_contains($r->url(), 'api.deezer.com/search')
            && str_contains(urldecode($r->url()), 'artist:"madk1d" track:"грустная история"'));
    }

    public function test_only_admins_upload(): void
    {
        $user = User::factory()->create(['username' => 'me']);

        $this->postJson('/admin/uploads', ['name' => 'a.zip', 'size' => 1, 'kind' => 'archive'])->assertUnauthorized();
        $this->actingAs($user)->postJson('/admin/uploads', ['name' => 'a.zip', 'size' => 1, 'kind' => 'archive'])->assertForbidden();
        $this->actingAs($user)->get('/admin/uploads')->assertForbidden();
    }

    public function test_pages_render(): void
    {
        $this->fakeHttp();
        $upload = $this->uploadArchive($this->zip([
            'Серпантин.ogg' => $this->audio('e.ogg', ['title' => 'Серпантин', 'artist' => 'Кто-то']),
        ]), ['check_censorship' => false]);

        $this->get('/admin/uploads')->assertOk()->assertInertia(fn ($page) => $page
            ->component('Uploads/Index')
            ->where('uploads.0.id', $upload->id)
            ->has('limits.free_bytes'));

        $this->get("/admin/uploads/{$upload->id}?tab=done")->assertOk()->assertInertia(fn ($page) => $page
            ->component('Uploads/Show')
            ->where('counts.done', 1)
            ->where('items.data.0.tags.title', 'Серпантин')
            ->where('items.data.0.targets.0.artists', 'Кто-то'));

        $this->delete("/admin/uploads/{$upload->id}")->assertRedirect('/admin/uploads');
        $this->assertNull(BulkUpload::find($upload->id));
        $this->assertDirectoryDoesNotExist($upload->dir());
    }

    // -- Помощники -------------------------------------------------------------

    /**
     * Сеть — заглушки: Deezer, анализатор, Centrifugo. Общая заглушка идёт
     * последней — Http::fake отдаёт первую подходящую.
     */
    private function fakeHttp(array $stubs = []): void
    {
        Http::fake($stubs + ['*' => Http::response()]);
    }

    private function admin(): User
    {
        $admin = User::factory()->create(['username' => 'boss'.uniqid()]);
        $admin->forceFill(['is_admin' => true])->save();

        return $admin;
    }

    /** Трек из импорта Spotify: только 30-секундное превью Deezer. */
    private function previewTrack(Release $release, string $title): Track
    {
        $track = $this->makeTrack($release, $title);
        $track->forceFill(['preview_only' => true, 'duration_ms' => 30_000])->saveQuietly();

        return $track->fresh();
    }

    /** Архив в два куска, как из браузера; дальше очередь (в тестах — сразу). */
    private function uploadArchive(string $path, array $options = [], ?string $name = null): BulkUpload
    {
        $this->actingAs($this->admin());
        $bytes = file_get_contents($path);
        $size = strlen($bytes);

        $id = $this->postJson('/admin/uploads', [
            'name' => $name ?? 'музыка.zip', 'size' => $size, 'kind' => 'archive', 'options' => $options,
        ])->assertCreated()->json('id');

        $half = intdiv($size, 2);
        $this->chunk($id, 0, substr($bytes, 0, $half))->assertOk();
        $this->chunk($id, $half, substr($bytes, $half))->assertOk()->assertJsonPath('complete', true);

        return BulkUpload::findOrFail($id);
    }

    private function chunk(int $id, int $offset, string $bytes)
    {
        return $this->call('POST', "/admin/uploads/{$id}/chunk", [], [], [], [
            'HTTP_X_UPLOAD_OFFSET' => (string) $offset,
            'HTTP_ACCEPT' => 'application/json',
            'CONTENT_TYPE' => 'application/octet-stream',
        ], $bytes);
    }
}
