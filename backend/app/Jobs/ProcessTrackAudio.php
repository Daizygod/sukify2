<?php

namespace App\Jobs;

use App\Enums\ProcessingStatus;
use App\Models\Track;
use App\Services\Audio\AudioProcessor;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;
use Throwable;

class ProcessTrackAudio implements ShouldQueue
{
    use Queueable;

    public int $timeout = 900;
    public int $tries = 2;

    /**
     * @param  int     $trackId       Track to process.
     * @param  string  $tmpUploadPath S3 key of the raw upload (under tmp-uploads/).
     * @param  string  $originalExt   Original extension: mp3|flac|ape.
     */
    public function __construct(
        public int $trackId,
        public string $tmpUploadPath,
        public string $originalExt,
    ) {
    }

    public function handle(AudioProcessor $audio): void
    {
        $track = Track::find($this->trackId);
        if (! $track) {
            $this->cleanupTmp();

            return;
        }

        $track->update(['processing_status' => ProcessingStatus::Processing]);

        $disk = Storage::disk('s3');
        $workDir = $this->makeWorkDir();
        $ext = strtolower($this->originalExt);

        try {
            // 1. Pull the raw upload down to local disk for ffmpeg.
            $localInput = "{$workDir}/input.{$ext}";
            file_put_contents($localInput, $disk->get($this->tmpUploadPath));
            $fileSize = filesize($localInput);

            // 2. Establish the "original" we keep: APE -> FLAC, everything else as-is.
            if ($ext === 'ape') {
                $localOriginal = "{$workDir}/original.flac";
                $audio->transcodeToFlac($localInput, $localOriginal);
                $originalExt = 'flac';
            } else {
                $localOriginal = $localInput;
                $originalExt = $ext;
            }

            // 3. Streaming AAC rendition.
            $localStream = "{$workDir}/stream.m4a";
            $audio->makeStreamRendition($localOriginal, $localStream);

            // 4. Analyse (loudness + duration) from the original.
            $loudness = $audio->measureLoudness($localOriginal);
            $durationMs = $audio->probeDurationMs($localOriginal);

            // 5. Upload results to their permanent home.
            $originalKey = "audio/{$track->id}/original.{$originalExt}";
            $streamKey = "audio/{$track->id}/stream.m4a";

            $disk->writeStream($originalKey, fopen($localOriginal, 'r'));
            $disk->writeStream($streamKey, fopen($localStream, 'r'));

            $track->update([
                'audio_original_path' => $originalKey,
                'audio_stream_path' => $streamKey,
                'loudness_lufs' => $loudness,
                'duration_ms' => $durationMs,
                'file_size_original' => $fileSize,
                'processing_status' => ProcessingStatus::Ready,
                'processing_error' => null,
            ]);

            $this->cleanupTmp();
        } catch (Throwable $e) {
            $track->update([
                'processing_status' => ProcessingStatus::Failed,
                'processing_error' => mb_substr($e->getMessage(), 0, 1000),
            ]);

            throw $e;
        } finally {
            $this->removeDir($workDir);
        }
    }

    public function failed(Throwable $e): void
    {
        if ($track = Track::find($this->trackId)) {
            $track->update([
                'processing_status' => ProcessingStatus::Failed,
                'processing_error' => mb_substr($e->getMessage(), 0, 1000),
            ]);
        }
    }

    private function cleanupTmp(): void
    {
        Storage::disk('s3')->delete($this->tmpUploadPath);
    }

    private function makeWorkDir(): string
    {
        $dir = storage_path('app/media-work/'.uniqid('audio_', true));
        @mkdir($dir, 0775, true);

        return $dir;
    }

    private function removeDir(string $dir): void
    {
        if (! is_dir($dir)) {
            return;
        }
        foreach (glob("{$dir}/*") ?: [] as $file) {
            @unlink($file);
        }
        @rmdir($dir);
    }
}
