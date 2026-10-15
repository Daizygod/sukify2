<?php

namespace Tests\Feature;

use App\Models\Playlist;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\Sanctum;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

class LibraryEventsTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
        // defer() иначе отработает после ответа — в тесте нужно сразу.
        $this->withoutDefer();
    }

    /** @return list<array{channels: array, data: array}> */
    private function broadcasts(): array
    {
        return Http::recorded()
            ->map(fn ($pair) => $pair[0])
            ->filter(fn (Request $r) => str_ends_with($r->url(), '/broadcast'))
            ->map(fn (Request $r) => $r->data())
            ->values()
            ->all();
    }

    public function test_like_notifies_own_devices(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $track = $this->makeTrack();
        Sanctum::actingAs($user, ['mobile']);

        $this->postJson("/api/tracks/{$track->id}/like")->assertOk();

        $sent = $this->broadcasts();
        $this->assertCount(1, $sent);
        $this->assertSame(["user:playback#{$user->id}"], $sent[0]['channels']);
        $this->assertSame('library-changed', $sent[0]['data']['t']);
        $this->assertSame('liked', $sent[0]['data']['what']);
        $this->assertArrayNotHasKey('d', $sent[0]['data']);

        // Повторный лайк ничего не меняет — и не шумит.
        $this->postJson("/api/tracks/{$track->id}/like")->assertOk();
        $this->assertCount(1, $this->broadcasts());
    }

    public function test_playlist_change_reaches_owner_and_collaborators(): void
    {
        $owner = User::factory()->create(['username' => 'owner']);
        $friend = User::factory()->create(['username' => 'friend']);
        $playlist = Playlist::create(['user_id' => $owner->id, 'title' => 'Вместе', 'is_public' => true]);
        $playlist->collaborators()->attach($friend->id);
        $track = $this->makeTrack();
        Sanctum::actingAs($friend, ['mobile']);

        $this->postJson("/api/playlists/{$playlist->id}/tracks", ['track_id' => $track->id])->assertCreated();

        $sent = collect($this->broadcasts())->firstWhere('data.what', 'playlists');
        $this->assertNotNull($sent);
        $this->assertEqualsCanonicalizing(
            ["user:playback#{$owner->id}", "user:playback#{$friend->id}"],
            $sent['channels']
        );
    }

    public function test_deleting_track_tells_everyone_once(): void
    {
        $users = User::factory()->count(3)->sequence(
            ['username' => 'u1'], ['username' => 'u2'], ['username' => 'u3'],
        )->create();
        $a = $this->makeTrack(null, 'A');
        $b = $this->makeTrack(null, 'B');

        // Пачка удалений склеивается в одно сообщение.
        $a->delete();
        $b->delete();

        $catalog = collect($this->broadcasts())->where('data.t', 'catalog-changed')->values();
        $this->assertCount(1, $catalog);
        $this->assertEqualsCanonicalizing(
            $users->map(fn ($u) => "user:playback#{$u->id}")->all(),
            $catalog[0]['channels']
        );
    }

    public function test_counters_do_not_count_as_catalog_change(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $track = $this->makeTrack();
        Sanctum::actingAs($user, ['mobile']);

        $this->postJson("/api/tracks/{$track->id}/play")->assertOk();
        $this->postJson("/api/tracks/{$track->id}/like")->assertOk();

        $this->assertCount(0, collect($this->broadcasts())->where('data.t', 'catalog-changed'));

        $track->update(['audio_rev' => md5('replaced')]);
        $this->assertCount(1, collect($this->broadcasts())->where('data.t', 'catalog-changed'));
    }
}
