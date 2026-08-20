<?php

namespace App\Services\Audio;

use Illuminate\Support\Facades\Http;
use RuntimeException;

/**
 * Клиент к сервису-анализатору (docker/analyzer): темп, сетка долей,
 * тональность в камелоте и пики волны в двух полосах.
 */
class AnalyzerClient
{
    public function __construct(
        private readonly ?string $baseUrl = null,
        private readonly int $timeout = 600,
    ) {
    }

    private function url(): string
    {
        return rtrim($this->baseUrl ?? config('services.analyzer.url', 'http://analyzer:8300'), '/');
    }

    public function available(): bool
    {
        try {
            return Http::timeout(5)->get($this->url().'/health')->successful();
        } catch (\Throwable) {
            return false;
        }
    }

    /**
     * @return array{
     *   duration_ms:int, bpm:float, beat_confidence:float, beat_offset_ms:int,
     *   beats_ms:array<int>, key:string, scale:string, key_strength:float,
     *   camelot:?string, peaks_count:int, peaks:array{full:string, bass:string}
     * }
     */
    public function analyze(string $localPath, int $buckets = 2048): array
    {
        if (! is_readable($localPath)) {
            throw new RuntimeException("Файл для анализа не читается: {$localPath}");
        }

        $response = Http::timeout($this->timeout)
            ->attach('file', fopen($localPath, 'r'), basename($localPath))
            ->post($this->url().'/analyze?buckets='.$buckets);

        if (! $response->successful()) {
            throw new RuntimeException(
                'Анализатор ответил '.$response->status().': '.mb_substr($response->body(), 0, 500)
            );
        }

        $data = $response->json();
        if (! is_array($data) || ! isset($data['bpm'])) {
            throw new RuntimeException('Анализатор вернул неожиданный ответ.');
        }

        return $data;
    }
}
