<?php

namespace App\Jobs;

use App\Enums\ProcessingStatus;
use App\Models\Release;
use App\Services\Images\CoverProcessor;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;
use Throwable;

class ProcessReleaseCover implements ShouldQueue
{
    use Queueable;

    public int $timeout = 300;
    public int $tries = 2;

    public function __construct(
        public int $releaseId,
        public string $tmpUploadPath,
        public string $originalExt = 'jpg',
    ) {
    }

    public function handle(CoverProcessor $processor): void
    {
        $release = Release::find($this->releaseId);
        if (! $release) {
            Storage::disk('s3')->delete($this->tmpUploadPath);

            return;
        }

        $release->update(['cover_status' => ProcessingStatus::Processing]);

        $disk = Storage::disk('s3');
        $workDir = storage_path('app/media-work/'.uniqid('cover_', true));
        @mkdir($workDir, 0775, true);
        $localInput = "{$workDir}/source.{$this->originalExt}";

        try {
            file_put_contents($localInput, $disk->get($this->tmpUploadPath));

            $result = $processor->process($localInput);

            // Keep the original upload too (future re-processing).
            $originalKey = "covers/{$release->id}/original.{$this->originalExt}";
            $disk->writeStream($originalKey, fopen($localInput, 'r'));

            foreach ($result['renditions'] as $size => $bytes) {
                $disk->put("covers/{$release->id}/{$size}.webp", $bytes['webp']);
                $disk->put("covers/{$release->id}/{$size}.jpg", $bytes['jpg']);
            }

            $release->update([
                'cover_original_path' => $originalKey,
                'cover_path' => "covers/{$release->id}",
                'cover_status' => ProcessingStatus::Ready,
                'dominant_color_hex' => $result['dominant_color_hex'],
                'text_color_hex' => $result['text_color_hex'],
            ]);

            $disk->delete($this->tmpUploadPath);
        } catch (Throwable $e) {
            $release->update(['cover_status' => ProcessingStatus::Failed]);

            throw $e;
        } finally {
            foreach (glob("{$workDir}/*") ?: [] as $f) {
                @unlink($f);
            }
            @rmdir($workDir);
        }
    }
}
