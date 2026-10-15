<?php

namespace Tests\Feature;

use App\Http\Controllers\Api\SpotifyStatsController;
use App\Models\ImportedPlay;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class SpotifyStatsCacheTest extends TestCase
{
    use RefreshDatabase;

    public function test_stats_are_recounted_right_after_an_import(): void
    {
        $user = User::factory()->create();
        Sanctum::actingAs($user, ['mobile']);

        // Открыли статистику до импорта — пустой ответ попал в кэш на пять минут.
        $this->getJson('/api/me/spotify-stats?tz=Europe/Moscow')->assertOk()->assertJsonPath('has_data', false);

        ImportedPlay::create([
            'user_id' => $user->id,
            'played_at' => now()->subYear(),
            'ms_played' => 200000,
            'artist_name' => 'Luna Waves',
            'track_name' => 'Undertow',
            'fingerprint' => 'test-1',
        ]);
        // Так делает разбор архива, когда перенёс историю.
        SpotifyStatsController::forget($user->id);

        $this->getJson('/api/me/spotify-stats?tz=Europe/Moscow')
            ->assertOk()
            ->assertJsonPath('has_data', true)
            ->assertJsonPath('totals.plays', 1);
    }
}
