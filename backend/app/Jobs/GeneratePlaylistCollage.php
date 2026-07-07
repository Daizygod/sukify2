<?php

namespace App\Jobs;

use App\Models\Playlist;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;
use Intervention\Image\Drivers\Imagick\Driver as ImagickDriver;
use Intervention\Image\Encoders\WebpEncoder;
use Intervention\Image\ImageManager;
use Throwable;

/**
 * Builds a playlist cover from its tracks' release covers: a 2x2 collage when
 * there are 4+ distinct covers, otherwise the single most-common cover
 * (mirrors Spotify's auto playlist artwork).
 */
class GeneratePlaylistCollage implements ShouldQueue
{
    use Queueable;

    public int $timeout = 180;

    public function __construct(public int $playlistId)
    {
    }

    public function handle(): void
    {
        $playlist = Playlist::with('tracks.release')->find($this->playlistId);

        if (! $playlist || $playlist->cover_is_custom) {
            return;
        }

        // Distinct release covers that are actually ready, in track order.
        $coverKeys = $playlist->tracks
            ->map(fn ($t) => $t->release)
            ->filter(fn ($r) => $r && $r->cover_path && $r->cover_status->value === 'ready')
            ->map(fn ($r) => "covers/{$r->id}/300.webp")
            ->unique()
            ->values();

        if ($coverKeys->isEmpty()) {
            return; // nothing to build from yet
        }

        $disk = Storage::disk('s3');
        $manager = new ImageManager(new ImagickDriver());

        try {
            if ($coverKeys->count() >= 4) {
                $canvas = $manager->createImage(640, 640);
                $tiles = $coverKeys->take(4)->values();
                $positions = [[0, 0], [320, 0], [0, 320], [320, 320]];

                foreach ($tiles as $i => $key) {
                    $tile = $manager->decode($disk->get($key))->coverDown(320, 320);
                    $canvas->insert($tile, 'top-left', $positions[$i][0], $positions[$i][1]);
                }
            } else {
                $canvas = $manager->decode($disk->get($coverKeys->first()))->coverDown(640, 640);
            }

            $key = "playlists/{$playlist->id}/cover.webp";
            $disk->put($key, (string) $canvas->encode(new WebpEncoder(quality: 82)));

            $playlist->update(['cover_path' => $key, 'cover_is_custom' => false]);
        } catch (Throwable $e) {
            // Non-fatal: a playlist can live without generated art.
            report($e);
        }
    }
}
