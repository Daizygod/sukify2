<?php

namespace Tests\Feature;

use App\Models\Playlist;
use App\Models\Track;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\Sanctum;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

class OfflineManifestTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
    }

    private function playlist(User $owner, array $tracks, bool $public = true): Playlist
    {
        $playlist = Playlist::create(['user_id' => $owner->id, 'title' => 'Плейлист', 'is_public' => $public]);
        foreach ($tracks as $i => $track) {
            $playlist->tracks()->attach($track->id, [
                'position' => $i + 1,
                'added_by_user_id' => $owner->id,
                'added_at' => now(),
            ]);
        }

        return $playlist;
    }

    public function test_manifest_contains_whole_library_and_includes(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $other = User::factory()->create(['username' => 'other']);

        $release = $this->makeRelease(title: 'Свой альбом');
        $a = $this->makeTrack($release, 'A');
        $b = $this->makeTrack($release, 'B');
        $silent = $this->makeTrack($release, 'Без звука', withAudio: false);

        $albumRelease = $this->makeRelease(title: 'Лайкнутый альбом');
        $c = $this->makeTrack($albumRelease, 'C');

        $user->likedTracks()->attach($a->id);
        $user->likedAlbums()->attach($albumRelease->id);
        $mine = $this->playlist($user, [$b, $silent]);

        $public = $this->playlist($other, [$c]);
        $private = $this->playlist($other, [$a], public: false);
        $loneTrack = $this->makeTrack(null, 'Одиночный');

        Sanctum::actingAs($user, ['mobile']);

        $response = $this->getJson('/api/offline/manifest?include='.implode(',', [
            "playlist:{$public->id}",
            "playlist:{$private->id}",
            "track:{$loneTrack->id}",
            'release:999999',
        ]))->assertOk();

        $json = $response->json();
        $sources = collect($json['sources'])->keyBy('key');

        $this->assertSame([$a->id], $sources['liked']['track_ids']);
        $this->assertSame([$b->id, $silent->id], $sources["playlist:{$mine->id}"]['track_ids']);
        $this->assertTrue($sources["playlist:{$mine->id}"]['is_owner']);
        $this->assertSame([$c->id], $sources["release:{$albumRelease->id}"]['track_ids']);
        $this->assertSame([$c->id], $sources["playlist:{$public->id}"]['track_ids']);
        $this->assertSame([$loneTrack->id], $sources["track:{$loneTrack->id}"]['track_ids']);
        $this->assertFalse($sources->has("playlist:{$private->id}"));

        $this->assertEqualsCanonicalizing(["playlist:{$private->id}", 'release:999999'], $json['missing']);
        $this->assertSame([$mine->id], $json['library']['playlists']);
        $this->assertSame([$albumRelease->id], $json['library']['albums']);

        $tracks = collect($json['tracks'])->keyBy('id');
        $this->assertEqualsCanonicalizing([$a->id, $b->id, $silent->id, $c->id, $loneTrack->id], $tracks->keys()->all());
        $this->assertSame($a->audio_rev, $tracks[$a->id]['audio']['rev']);
        $this->assertSame($a->audio_size, $tracks[$a->id]['audio']['size']);
        $this->assertArrayNotHasKey('audio', $tracks[$silent->id]);
        $this->assertSame($release->id, $tracks[$a->id]['release_id']);
        $this->assertSame('Свой альбом', $json['releases'][$release->id]['title']);
        $this->assertArrayHasKey((string) $release->artist_id, $json['artists']);
        $this->assertStringContainsString('{id}', $json['urls']['audio']);

        // Счётчиков в манифесте нет — иначе ETag менялся бы от каждого прослушивания.
        $this->assertArrayNotHasKey('plays_count', $tracks[$a->id]);
    }

    public function test_etag_turns_into_304_until_something_changes(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $track = $this->makeTrack();
        $user->likedTracks()->attach($track->id);
        Sanctum::actingAs($user, ['mobile']);

        $etag = $this->getJson('/api/offline/manifest')->assertOk()->headers->get('ETag');
        $this->assertNotEmpty($etag);

        // Прослушивания и лайки других людей манифест не меняют.
        $this->postJson("/api/tracks/{$track->id}/play")->assertOk();

        $this->withHeaders(['If-None-Match' => 'W/'.$etag])
            ->get('/api/offline/manifest')
            ->assertStatus(304);

        // Заменили файл — новая ревизия, новый ETag.
        $track->forceFill(['audio_rev' => md5('new file'), 'audio_size' => 123])->save();

        $fresh = $this->withHeaders(['If-None-Match' => $etag])->getJson('/api/offline/manifest')->assertOk();
        $this->assertNotSame($etag, $fresh->headers->get('ETag'));
        $this->assertSame(md5('new file'), collect($fresh->json('tracks'))->firstWhere('id', $track->id)['audio']['rev']);
    }

    public function test_deleted_track_disappears_everywhere(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $keep = $this->makeTrack(null, 'Остаётся');
        $gone = $this->makeTrack(null, 'Удалят');
        $user->likedTracks()->attach([$keep->id, $gone->id]);
        $playlist = $this->playlist($user, [$gone, $keep]);
        Sanctum::actingAs($user, ['mobile']);

        $gone->delete();

        $json = $this->getJson('/api/offline/manifest')->assertOk()->json();
        $sources = collect($json['sources'])->keyBy('key');

        $this->assertSame([$keep->id], $sources['liked']['track_ids']);
        $this->assertSame([$keep->id], $sources["playlist:{$playlist->id}"]['track_ids']);
        $this->assertSame([$keep->id], collect($json['tracks'])->pluck('id')->all());
    }

    public function test_followed_artist_discography_only_on_request(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $artist = $this->makeArtist('Подписка');
        $t1 = $this->makeTrack($this->makeRelease($artist, 'Первый'), 'Раз');
        $t2 = $this->makeTrack($this->makeRelease($artist, 'Второй'), 'Два');
        DB::table('followed_artists')->insert(['user_id' => $user->id, 'artist_id' => $artist->id, 'created_at' => now()]);
        Sanctum::actingAs($user, ['mobile']);

        $plain = $this->getJson('/api/offline/manifest')->assertOk()->json();
        $this->assertSame([$artist->id], $plain['library']['artists']);
        $this->assertNull(collect($plain['sources'])->firstWhere('key', "artist:{$artist->id}"));

        $full = $this->getJson('/api/offline/manifest?artists=1')->assertOk()->json();
        $source = collect($full['sources'])->firstWhere('key', "artist:{$artist->id}");
        $this->assertEqualsCanonicalizing([$t1->id, $t2->id], $source['track_ids']);
    }

    public function test_requires_authentication(): void
    {
        $this->getJson('/api/offline/manifest')->assertUnauthorized();
    }

    public function test_empty_library_keeps_maps_as_objects(): void
    {
        Sanctum::actingAs(User::factory()->create(['username' => 'me']), ['mobile']);

        $raw = $this->get('/api/offline/manifest')->assertOk()->getContent();

        $this->assertStringContainsString('"releases":{}', $raw);
        $this->assertStringContainsString('"artists":{}', $raw);
        $this->assertStringContainsString('"tracks":[]', $raw);
    }
}
