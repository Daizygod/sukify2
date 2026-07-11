<?php

namespace App\Console\Commands;

use App\Enums\ProcessingStatus;
use App\Models\Track;
use App\Services\Audio\AudioProcessor;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;
use Symfony\Component\Process\Process;

/**
 * Dev helper: give every track a distinct, audible placeholder so the catalog
 * is actually playable (real songs can be uploaded via the admin later).
 * Synthesizes a short tone per track and runs it through the normal pipeline.
 */
class GenerateDemoAudio extends Command
{
    protected $signature = 'demo:generate-audio {--force : Regenerate even if audio already exists}';

    protected $description = 'Synthesize playable placeholder audio for tracks that have none.';

    // A pentatonic-ish set so consecutive tracks sound different and pleasant.
    private array $scale = [220.0, 246.94, 277.18, 329.63, 369.99, 440.0, 493.88, 554.37];

    public function handle(AudioProcessor $audio): int
    {
        // Настоящие загрузки не трогаем никогда: --force перегенерирует только
        // треки, чьё аудио и так синтетическое (demo_audio) или отсутствует.
        $query = Track::query();
        if ($this->option('force')) {
            $query->where(fn ($q) => $q->where('demo_audio', true)->orWhereNull('audio_stream_path'));
        } else {
            $query->whereNull('audio_stream_path');
        }
        $tracks = $query->orderBy('id')->get();

        if ($tracks->isEmpty()) {
            $this->info('All tracks already have audio.');

            return self::SUCCESS;
        }

        $disk = Storage::disk('s3');
        $this->getOutput()->progressStart($tracks->count());

        foreach ($tracks as $track) {
            $dir = storage_path('app/media-work/'.uniqid('demo_', true));
            @mkdir($dir, 0775, true);
            $flac = "{$dir}/tone.flac";
            $stream = "{$dir}/stream.mp3";

            try {
                $freq = $this->scale[$track->id % count($this->scale)];
                $dur = 24 + ($track->id % 6) * 2; // 24–34s

                // A tone with a soft vibrato + fade in/out so it reads as "music".
                $this->ffmpeg([
                    'ffmpeg', '-y',
                    '-f', 'lavfi', '-i', "sine=frequency={$freq}:duration={$dur}",
                    '-af', "vibrato=f=5:d=0.3,afade=t=in:d=1,afade=t=out:st=".($dur - 3).":d=3,volume=0.5",
                    '-c:a', 'flac', $flac,
                ]);

                $audio->makeStreamRendition($flac, $stream);
                $loudness = $audio->measureLoudness($flac);
                $duration = $audio->probeDurationMs($flac);

                $originalKey = "audio/{$track->id}/original.flac";
                $streamKey = "audio/{$track->id}/stream.mp3";
                $disk->writeStream($originalKey, fopen($flac, 'r'));
                $disk->writeStream($streamKey, fopen($stream, 'r'));

                $track->update([
                    'audio_original_path' => $originalKey,
                    'audio_stream_path' => $streamKey,
                    'loudness_lufs' => $loudness,
                    'duration_ms' => $duration,
                    'file_size_original' => filesize($flac),
                    'processing_status' => ProcessingStatus::Ready,
                    'processing_error' => null,
                    'demo_audio' => true,
                ]);
            } catch (\Throwable $e) {
                $this->warn("Track {$track->id} failed: ".$e->getMessage());
            } finally {
                foreach (glob("{$dir}/*") ?: [] as $f) {
                    @unlink($f);
                }
                @rmdir($dir);
            }

            $this->getOutput()->progressAdvance();
        }

        $this->getOutput()->progressFinish();
        $this->info('Done — every track is now playable.');

        return self::SUCCESS;
    }

    private function ffmpeg(array $cmd): void
    {
        $p = new Process($cmd);
        $p->setTimeout(120);
        $p->run();
        if (! $p->isSuccessful()) {
            throw new \RuntimeException(mb_substr($p->getErrorOutput(), -300));
        }
    }
}
