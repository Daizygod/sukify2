<?php

namespace App\Jobs;

use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;

/**
 * Asynchronously removes files/directories from S3 so deleting a track/album/
 * cover doesn't block the HTTP response (spec §3).
 */
class DeleteS3Paths implements ShouldQueue
{
    use Queueable;

    /**
     * @param  array<string>  $directories  Prefixes to delete recursively.
     * @param  array<string>  $files        Individual object keys to delete.
     */
    public function __construct(
        public array $directories = [],
        public array $files = [],
    ) {
    }

    public function handle(): void
    {
        $disk = Storage::disk('s3');

        foreach ($this->directories as $dir) {
            if ($dir !== '' && $dir !== '/') {
                $disk->deleteDirectory($dir);
            }
        }

        if (! empty($this->files)) {
            $disk->delete($this->files);
        }
    }
}
