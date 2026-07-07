<?php

namespace App\Observers;

use App\Jobs\DeleteS3Paths;
use App\Models\Track;

class TrackObserver
{
    /** When a track is deleted, remove its audio + any per-track cover from S3. */
    public function deleted(Track $track): void
    {
        DeleteS3Paths::dispatch(
            directories: [
                "audio/{$track->id}",
                "track-covers/{$track->id}",
            ],
        );
    }
}
