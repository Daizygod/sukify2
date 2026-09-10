<?php

namespace App\Jobs;

use App\Enums\ProcessingStatus;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\SpotifyImport;
use App\Models\Track;
use App\Services\Audio\AudioProcessor;
use App\Services\Deezer\DeezerClient;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Догружает импортированным трекам то, чего нет в экспорте Spotify: обложку
 * альбома и 30-секундное превью из Deezer.
 *
 * Работает пачками и сама себя перезапускает, пока остаются треки со статусом
 * pending — так прогресс виден на /import, а любой сбой стоит одной пачки,
 * а не всего импорта. И поиск, и скачивание превью идут параллельно: по
 * одному запросу за раз три тысячи треков ехали бы часами.
 */
class EnrichSpotifyTracks implements ShouldQueue
{
    use Queueable;

    /** Сколько треков берём за один заход (кратно размеру пачки поиска). */
    private const PER_JOB = 60;

    public int $timeout = 900;

    /**
     * Попытки не ограничиваем числом: пауза по просьбе Deezer — это release(),
     * и он тоже считается попыткой, так что при tries=3 три паузы убивали всю
     * цепочку. Ограничиваем временем, а настоящие падения — maxExceptions.
     */
    public int $tries = 0;

    public int $maxExceptions = 3;

    public function retryUntil(): \DateTimeInterface
    {
        return now()->addHours(6);
    }

    public function __construct(public int $importId)
    {
    }

    public function handle(DeezerClient $deezer, AudioProcessor $audio): void
    {
        $import = SpotifyImport::find($this->importId);
        if (! $import || $import->status === 'canceled') {
            return;
        }

        $this->releaseStaleClaims();

        $ids = $this->claim(self::PER_JOB);
        if (! $ids) {
            // Пусто может значить и «всё сделано», и «остальное разбирают
            // соседние воркеры» — во втором случае просто заходим позже.
            if (Track::where('enrich_status', 'working')->exists()) {
                EnrichSpotifyTracks::dispatch($this->importId)->delay(now()->addSeconds(20));

                return;
            }

            $import->update([
                'status' => 'done',
                'stage' => 'Готово',
                'progress' => 100,
                'enrich_done' => $import->enrich_total,
            ]);

            // Релизы, у которых ни один трек не нашёлся, остались без обложки —
            // пробуем найти сам альбом по названию.
            Release::where('imported', true)
                ->whereNull('deezer_id')
                ->where('cover_status', ProcessingStatus::Pending)
                ->pluck('id')
                ->each(fn ($id) => FetchReleaseArt::dispatch($id, 0));

            // Коллажи собирались, когда обложек ещё не было, — пересобираем по
            // готовым (свои картинки пользователя job не трогает).
            Playlist::where('user_id', $import->user_id)
                ->where('imported', true)
                ->where('cover_is_custom', false)
                ->pluck('id')
                ->each(fn ($id) => GeneratePlaylistCollage::dispatch($id));

            return;
        }

        $tracks = Track::with(['mainArtists:id,name', 'release:id,cover_status,deezer_id'])
            ->whereIn('id', $ids)
            ->orderBy('id')
            ->get();

        $workDir = storage_path('app/media-work/'.uniqid('deezer_', true));
        @mkdir($workDir, 0775, true);

        try {
            foreach ($tracks->chunk(DeezerClient::BATCH) as $chunk) {
                if ($this->processBatch($chunk->values(), $deezer, $audio, $workDir, $import) === false) {
                    // Отпускаем недоделанное, чтобы пачка не висела за нами.
                    Track::whereIn('id', $ids)->where('enrich_status', 'working')
                        ->update(['enrich_status' => 'pending']);

                    return;
                }
                // Лимит Deezer — 50 запросов к API за 5 секунд на IP.
                usleep(1_200_000);
            }
        } finally {
            $this->removeDir($workDir);
        }

        $this->reportProgress($import);

        EnrichSpotifyTracks::dispatch($this->importId);
    }

    /**
     * @param  \Illuminate\Support\Collection<int,Track>  $batch
     * @return bool false — Deezer попросил паузу, задача отложена
     */
    private function processBatch($batch, DeezerClient $deezer, AudioProcessor $audio, string $workDir, SpotifyImport $import): bool
    {
        $results = $deezer->searchBatch($batch->map(fn (Track $t) => [
            'artist' => $t->mainArtists->first()?->name ?? '',
            'title' => $t->title,
            'duration_ms' => $t->duration_ms,
        ])->all());

        $updates = [];   // индекс в пачке => поля трека
        $previews = [];  // индекс в пачке => url превью

        foreach ($batch as $i => $track) {
            $match = $results[$i] ?? null;

            if (is_array($match) && ($match['__throttled'] ?? false)) {
                $import->update(['stage' => 'Deezer просит паузу, жду минуту']);
                $this->release(60);

                return false;
            }

            if (! $match || empty($match['id'])) {
                $updates[$i] = ['enrich_status' => 'notfound'];

                continue;
            }

            $updates[$i] = ['deezer_id' => (int) $match['id'], 'enrich_status' => 'ready'];

            $this->queueCover($track, $match);

            if (! empty($match['preview'])) {
                $previews[$i] = $match['preview'];
            }
        }

        // Превью тянем параллельно — это самая долгая часть пачки.
        foreach ($deezer->fetchBatch($previews) as $i => $body) {
            if ($body === null) {
                continue;
            }
            $track = $batch[$i];
            $file = $workDir.'/'.$track->id.'.mp3';
            try {
                file_put_contents($file, $body);
                Storage::disk('s3')->writeStream("audio/{$track->id}/stream.mp3", fopen($file, 'r'));
                $updates[$i] += [
                    'audio_stream_path' => "audio/{$track->id}/stream.mp3",
                    // Длительность — реальная длина превью, а не трека: иначе
                    // полоса прогресса врала бы в четыре раза.
                    'duration_ms' => $audio->probeDurationMs($file) ?? 30_000,
                    'file_size_original' => strlen($body),
                    'processing_status' => ProcessingStatus::Ready,
                    'preview_only' => true,
                ];
            } catch (Throwable) {
                // Превью не критично — трек остаётся с метаданными.
            } finally {
                @unlink($file);
            }
        }

        // Scout иначе плодит по задаче на каждое сохранение: у трека
        // shouldBeSearchable() = «аудио готово», и pending-треки отправляли бы
        // RemoveFromSearch пачками. Индексируем разом только доигравшие.
        $ready = [];
        Track::withoutSyncingToSearch(function () use ($batch, $updates, &$ready) {
            foreach ($updates as $i => $fields) {
                $batch[$i]->forceFill($fields)->save();
                if (($fields['processing_status'] ?? null) === ProcessingStatus::Ready) {
                    $ready[] = $batch[$i]->id;
                }
            }
        });
        if ($ready) {
            Track::whereIn('id', $ready)->searchable();
        }

        return true;
    }

    /**
     * Забрать пачку треков себе одним запросом.
     *
     * Воркеров может быть несколько, и все они выбирали бы одни и те же строки
     * «сверху» — FOR UPDATE SKIP LOCKED раздаёт каждому свою пачку, а смена
     * статуса на working держит её за ним.
     *
     * @return list<int>
     */
    private function claim(int $limit): array
    {
        $rows = DB::select(
            "update tracks set enrich_status = 'working', updated_at = now()
              where id in (
                  select id from tracks
                   where enrich_status = 'pending'
                   order by id
                   limit ?
                     for update skip locked
              )
          returning id",
            [$limit]
        );

        return array_map(fn ($row) => (int) $row->id, $rows);
    }

    /** Пачка упавшей задачи иначе осталась бы за ней навсегда. */
    private function releaseStaleClaims(): void
    {
        Track::where('enrich_status', 'working')
            ->where('updated_at', '<', now()->subMinutes(30))
            ->update(['enrich_status' => 'pending']);
    }

    /** Обложка альбома — одна задача на релиз, дальше он уже с deezer_id. */
    private function queueCover(Track $track, array $match): void
    {
        $release = $track->release;
        $albumId = (int) ($match['album']['id'] ?? 0);
        if (! $release || ! $albumId || $release->deezer_id) {
            return;
        }

        $cover = $match['album']['cover_xl'] ?? null;
        // Пустой md5_image означает заглушку Deezer вместо обложки.
        if (! $cover || str_contains($cover, 'cover//')) {
            return;
        }

        $release->forceFill(['deezer_id' => $albumId])->save();
        FetchReleaseArt::dispatch($release->id, $albumId, $cover);
    }

    /**
     * Прогресс считаем по остатку, а не счётчиком: архивов может быть несколько,
     * и цепочек догрузки тоже — инкремент показал бы половину правды.
     */
    private function reportProgress(SpotifyImport $import): void
    {
        $remaining = Track::whereIn('enrich_status', ['pending', 'working'])->count();
        $done = max(0, $import->enrich_total - $remaining);

        $import->update([
            'enrich_done' => $done,
            'stage' => 'Качаю обложки и превью: '.$done.' из '.$import->enrich_total,
        ]);
    }

    private function removeDir(string $dir): void
    {
        if (! is_dir($dir)) {
            return;
        }
        foreach (glob("{$dir}/*") ?: [] as $file) {
            @unlink($file);
        }
        @rmdir($dir);
    }
}
