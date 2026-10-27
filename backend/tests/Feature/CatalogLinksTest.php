<?php

namespace Tests\Feature;

use App\Enums\ProcessingStatus;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

/** Ссылки каталога, на которых держатся плеер и обзор. */
class CatalogLinksTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    /**
     * Треки со страницы релиза уходят в очередь плеера как есть — у каждого
     * должен быть свой релиз (slug, цвет), иначе название в плеере никуда
     * не ведёт. И без вложенного треклиста: релиз не повторяется N раз.
     */
    public function test_release_tracks_carry_light_release(): void
    {
        Sanctum::actingAs(User::factory()->create(['username' => 'me']));
        $release = $this->makeRelease(null, 'Сингл');
        $release->forceFill(['dominant_color_hex' => '#123456'])->saveQuietly();
        $this->makeTrack($release, 'Первый');
        $this->makeTrack($release, 'Второй');

        $tracks = $this->getJson("/api/releases/{$release->slug}")->assertOk()->json('data.tracks');

        $this->assertCount(2, $tracks);
        foreach ($tracks as $t) {
            $this->assertSame($release->slug, $t['release']['slug']);
            $this->assertSame('#123456', $t['release']['colors']['background']);
            $this->assertArrayNotHasKey('tracks', $t['release']);
        }
    }

    /** Плитки обзора: у жанра — обложка релиза его самого слушаемого исполнителя. */
    public function test_genre_tiles_have_top_artist_cover(): void
    {
        Sanctum::actingAs(User::factory()->create(['username' => 'me']));
        $quiet = $this->makeArtist('Тихий');
        $loud = $this->makeArtist('Громкий');
        $quiet->forceFill(['genre' => 'Рок', 'monthly_listeners' => 10])->saveQuietly();
        $loud->forceFill(['genre' => 'Рок', 'monthly_listeners' => 1000])->saveQuietly();
        $withCover = function ($artist, $title) {
            $release = $this->makeRelease($artist, $title);
            $release->forceFill([
                'cover_path' => "covers/{$title}",
                'cover_status' => ProcessingStatus::Ready,
            ])->saveQuietly();

            return $release;
        };
        $withCover($quiet, 'quiet');
        $hit = $withCover($loud, 'loud');
        $this->makeRelease($this->makeArtist('Без жанра'), 'nogenre');

        $data = $this->getJson('/api/genres')->assertOk()->json('data');

        $this->assertCount(1, $data);
        $this->assertSame('Рок', $data[0]['genre']);
        $this->assertSame(2, $data[0]['artists_count']);
        $this->assertNotNull($data[0]['cover']);
        $this->assertStringContainsString($hit->fresh()->coverUrls()[0]['url'], $data[0]['cover'][0]['url']);
    }
}
