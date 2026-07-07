<?php

namespace App\Observers;

use App\Jobs\DeleteS3Paths;
use App\Models\Release;

class ReleaseObserver
{
    /** Cascade-deletes tracks (FK), but cover files must be cleaned separately. */
    public function deleted(Release $release): void
    {
        DeleteS3Paths::dispatch(
            directories: ["covers/{$release->id}"],
        );
    }
}
