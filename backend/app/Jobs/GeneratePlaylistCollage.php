<?php

namespace App\Jobs;

use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

/**
 * Generates a 4-cover collage as a playlist's cover when it first gets tracks
 * and has no custom cover. Full implementation lands in Stage 3 (image pipeline);
 * this queued stub keeps the dispatch path wired up.
 */
class GeneratePlaylistCollage implements ShouldQueue
{
    use Queueable;

    public function __construct(public int $playlistId)
    {
    }

    public function handle(): void
    {
        // Implemented in Stage 3 (Intervention Image collage → WebP → S3).
    }
}
