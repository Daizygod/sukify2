<?php

namespace App\Console\Commands;

use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;

class CleanupOrphanStorage extends Command
{
    protected $signature = 'storage:cleanup-orphans {--dry-run : List what would be deleted without deleting}';

    protected $description = 'Delete S3 media whose owning record no longer exists, plus stale tmp uploads.';

    public function handle(): int
    {
        $disk = Storage::disk('s3');
        $dryRun = (bool) $this->option('dry-run');
        $deleted = 0;

        $map = [
            'covers' => fn (array $ids) => Release::whereIn('id', $ids)->pluck('id')->all(),
            'audio' => fn (array $ids) => Track::whereIn('id', $ids)->pluck('id')->all(),
            'track-covers' => fn (array $ids) => Track::whereIn('id', $ids)->pluck('id')->all(),
            'playlists' => fn (array $ids) => Playlist::whereIn('id', $ids)->pluck('id')->all(),
        ];

        foreach ($map as $prefix => $resolver) {
            $dirs = $disk->directories($prefix); // e.g. ["covers/1", "covers/2"]
            if (empty($dirs)) {
                continue;
            }

            $ids = collect($dirs)
                ->map(fn ($d) => (int) basename($d))
                ->filter()
                ->values();

            $existing = collect($resolver($ids->all()))->flip();

            foreach ($dirs as $dir) {
                $id = (int) basename($dir);
                if ($existing->has($id)) {
                    continue;
                }

                $this->warn(($dryRun ? '[dry-run] ' : '').'orphan: '.$dir);
                if (! $dryRun) {
                    $disk->deleteDirectory($dir);
                }
                $deleted++;
            }
        }

        // Stale prematurely-abandoned uploads (belt-and-suspenders with the
        // bucket lifecycle rule on tmp-uploads/).
        foreach ($disk->files('tmp-uploads') as $file) {
            $age = now()->timestamp - $disk->lastModified($file);
            if ($age > 86400) {
                $this->warn(($dryRun ? '[dry-run] ' : '').'stale tmp: '.$file);
                if (! $dryRun) {
                    $disk->delete($file);
                }
                $deleted++;
            }
        }

        $this->info(($dryRun ? 'Would delete ' : 'Deleted ').$deleted.' orphan path(s).');

        return self::SUCCESS;
    }
}
