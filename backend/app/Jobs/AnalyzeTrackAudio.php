<?php

namespace App\Jobs;

use App\Models\Track;
use App\Services\Audio\AnalyzerClient;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Считает темп, сетку долей, тональность и пики волны для трека.
 *
 * Отдельная задача, а не часть ProcessTrackAudio: анализ идёт минуты, его
 * полезно перезапускать по одному треку, и падение анализа не должно мешать
 * треку играть.
 */
class AnalyzeTrackAudio implements ShouldQueue
{
    use Queueable;

    public int $timeout = 900;
    public int $tries = 2;

    public function __construct(public int $trackId)
    {
    }

    public function handle(AnalyzerClient $analyzer): void
    {
        $track = Track::find($this->trackId);
        if (! $track || ! $track->audio_stream_path) {
            return;
        }

        $track->update(['analysis_status' => 'processing', 'analysis_error' => null]);

        $dir = storage_path('app/media-work/'.uniqid('analyze_', true));
        @mkdir($dir, 0775, true);
        $local = "{$dir}/stream.mp3";

        try {
            file_put_contents($local, Storage::disk('s3')->get($track->audio_stream_path));
            $result = $analyzer->analyze($local);

            $track->update([
                'bpm' => $result['bpm'] ?? null,
                'beat_offset_ms' => $result['beat_offset_ms'] ?? null,
                'beat_confidence' => $result['beat_confidence'] ?? null,
                'beats' => $result['beats_ms'] ?? null,
                'musical_key' => $result['key'] ?? null,
                'musical_scale' => $result['scale'] ?? null,
                'key_strength' => $result['key_strength'] ?? null,
                'camelot' => $result['camelot'] ?? null,
                'waveform_full' => $result['peaks']['full'] ?? null,
                'waveform_bass' => $result['peaks']['bass'] ?? null,
                'waveform_bands' => $result['bands'] ?? null,
                'waveform_buckets' => $result['peaks_count'] ?? null,
                'analysis_status' => 'ready',
                'analysis_error' => null,
                'analyzed_at' => now(),
            ]);
        } catch (Throwable $e) {
            $track->update([
                'analysis_status' => 'failed',
                'analysis_error' => mb_substr($e->getMessage(), 0, 1000),
            ]);

            throw $e;
        } finally {
            @unlink($local);
            @rmdir($dir);
        }
    }

    public function failed(Throwable $e): void
    {
        Track::whereKey($this->trackId)->update([
            'analysis_status' => 'failed',
            'analysis_error' => mb_substr($e->getMessage(), 0, 1000),
        ]);
    }
}
