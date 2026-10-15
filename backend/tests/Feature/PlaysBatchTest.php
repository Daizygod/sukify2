<?php

namespace Tests\Feature;

use App\Models\TrackPlay;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\Sanctum;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

class PlaysBatchTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
    }

    public function test_offline_plays_are_logged_with_their_time_and_not_doubled(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $a = $this->makeTrack(null, 'A');
        $b = $this->makeTrack(null, 'B');
        Sanctum::actingAs($user, ['mobile']);

        $plays = [
            ['track_id' => $a->id, 'played_at' => now()->subHours(3)->toIso8601String()],
            ['track_id' => $b->id, 'played_at' => now()->subHours(2)->toIso8601String()],
            // Слишком старое и несуществующий трек — молча пропускаем.
            ['track_id' => $a->id, 'played_at' => now()->subDays(45)->toIso8601String()],
            ['track_id' => 999999, 'played_at' => now()->subHour()->toIso8601String()],
        ];

        $this->postJson('/api/me/plays', ['plays' => $plays])->assertOk()->assertJsonPath('logged', 2);
        // Ответ потерялся, приложение отправило пачку ещё раз.
        $this->postJson('/api/me/plays', ['plays' => $plays])->assertOk()->assertJsonPath('logged', 0);

        $this->assertSame(2, TrackPlay::where('user_id', $user->id)->count());
        $this->assertSame(1, $a->fresh()->plays_count);
        $this->assertSame(1, $b->fresh()->plays_count);

        $stored = TrackPlay::where('track_id', $a->id)->first();
        $this->assertTrue($stored->played_at->between(now()->subHours(3)->subMinute(), now()->subHours(3)->addMinute()));
    }

    public function test_future_time_is_clamped_to_now(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $track = $this->makeTrack();
        Sanctum::actingAs($user, ['mobile']);

        $this->postJson('/api/me/plays', ['plays' => [
            ['track_id' => $track->id, 'played_at' => now()->addMinutes(5)->toIso8601String()],
        ]])->assertOk()->assertJsonPath('logged', 1);

        $this->assertTrue(TrackPlay::first()->played_at->lte(now()->addSecond()));
    }

    public function test_validation(): void
    {
        Sanctum::actingAs(User::factory()->create(['username' => 'me']), ['mobile']);

        $this->postJson('/api/me/plays', ['plays' => []])->assertStatus(422);
        $this->postJson('/api/me/plays', ['plays' => [['track_id' => 'x']]])->assertStatus(422);
    }
}
