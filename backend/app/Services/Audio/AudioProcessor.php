<?php

namespace App\Services\Audio;

use RuntimeException;
use Symfony\Component\Process\Process;

class AudioProcessor
{
    private int $timeout = 600;

    /**
     * Decode any input (mp3/flac/ape/...) to FLAC. Returns the output path.
     * Used to normalize APE (and anything else) into a lossless "original".
     */
    public function transcodeToFlac(string $input, string $output): string
    {
        $this->run([
            'ffmpeg', '-y', '-i', $input,
            '-c:a', 'flac', '-compression_level', '8',
            $output,
        ]);

        return $output;
    }

    /**
     * Produce the streaming MP3 rendition (~256 kbps CBR). MP3 decodes in every
     * browser including open-source Chromium builds (AAC/m4a does not).
     */
    public function makeStreamRendition(string $input, string $output): string
    {
        $this->run([
            'ffmpeg', '-y', '-i', $input,
            '-vn',
            '-c:a', 'libmp3lame', '-b:a', '256k',
            $output,
        ]);

        return $output;
    }

    /**
     * Measure integrated loudness (LUFS) via a loudnorm analysis pass.
     * The file is NOT re-encoded — normalization happens client-side via GainNode.
     */
    public function measureLoudness(string $input): ?float
    {
        $process = new Process([
            'ffmpeg', '-hide_banner', '-i', $input,
            '-af', 'loudnorm=I=-14:TP=-1:LRA=11:print_format=json',
            '-f', 'null', '-',
        ]);
        $process->setTimeout($this->timeout);
        $process->run();

        // loudnorm prints the JSON block to stderr.
        $stderr = $process->getErrorOutput();

        if (! preg_match('/\{[^{}]*"input_i"[^{}]*\}/s', $stderr, $m)) {
            return null;
        }

        $data = json_decode($m[0], true);
        if (! is_array($data) || ! isset($data['input_i'])) {
            return null;
        }

        $value = (float) $data['input_i'];

        // ffmpeg reports -inf/70 for silence; clamp obviously bogus values.
        return is_finite($value) ? round($value, 2) : null;
    }

    /** Duration in milliseconds via ffprobe. */
    public function probeDurationMs(string $input): ?int
    {
        $process = new Process([
            'ffprobe', '-v', 'quiet', '-print_format', 'json', '-show_format', $input,
        ]);
        $process->setTimeout($this->timeout);
        $process->run();

        if (! $process->isSuccessful()) {
            return null;
        }

        $data = json_decode($process->getOutput(), true);
        $seconds = $data['format']['duration'] ?? null;

        return $seconds !== null ? (int) round(((float) $seconds) * 1000) : null;
    }

    /**
     * @param  array<string>  $command
     */
    private function run(array $command): void
    {
        $process = new Process($command);
        $process->setTimeout($this->timeout);
        $process->run();

        if (! $process->isSuccessful()) {
            throw new RuntimeException(
                'ffmpeg failed: '.mb_substr($process->getErrorOutput(), -800)
            );
        }
    }
}
