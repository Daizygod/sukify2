<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Track;
use App\Models\TrackLyrics;
use Illuminate\Support\Facades\Http;

/**
 * Текст трека: бесплатный LRCLIB (lrclib.net) с кэшем в БД,
 * включая отрицательный результат (чтобы не долбить API).
 */
class LyricsController extends Controller
{
    public function show(Track $track)
    {
        $cached = TrackLyrics::where('track_id', $track->id)->first();
        // Синхронизированный текст — навсегда; обычный текст или «не найдено»
        // переспрашиваем раз в неделю (вдруг на LRCLIB появился synced-вариант).
        if ($cached && ($cached->synced_lyrics || $cached->fetched_at?->gt(now()->subWeek()))) {
            return $this->respond($cached);
        }

        $track->load(['artists', 'release']);
        $payload = [
            'track_name' => $track->title,
            'artist_name' => $track->artists->first()?->name ?? '',
            'duration' => (int) round(($track->duration_ms ?? 0) / 1000),
        ];
        if ($track->release) {
            $payload['album_name'] = $track->release->title;
        }

        $synced = null;
        $plain = null;
        try {
            // force_ip_resolve: докер-сеть без IPv6-маршрута наружу.
            // Таймаут щедрый: /search у LRCLIB бывает медленным (полнотекст).
            $client = fn () => Http::timeout(20)
                ->withOptions(['force_ip_resolve' => 'v4'])
                ->withHeaders(['User-Agent' => 'Sukify/1.0 (https://localhost)']);

            $res = $client()->get('https://lrclib.net/api/get', $payload);
            $hit = $res->successful() ? $res->json() : null;
            $synced = $hit['syncedLyrics'] ?? null;
            $plain = $hit['plainLyrics'] ?? null;

            // Точное совпадение без таймкодов (или мимо) — ищем в /search
            // вариант С синхронизацией: у LRCLIB часто несколько записей на трек.
            if (! $synced) {
                $res = $client()->get('https://lrclib.net/api/search', [
                    'track_name' => $payload['track_name'],
                    'artist_name' => $payload['artist_name'],
                ]);
                $hits = $res->successful() ? collect($res->json()) : collect();
                $dur = (int) $payload['duration'];
                $best = $hits
                    ->filter(fn ($c) => ! empty($c['syncedLyrics']))
                    // Ближайший по длительности, и не дальше 10 секунд (иначе ремикс).
                    ->sortBy(fn ($c) => abs(($c['duration'] ?? 0) - $dur))
                    ->first(fn ($c) => $dur === 0 || abs(($c['duration'] ?? 0) - $dur) <= 10);
                if ($best) {
                    $synced = $best['syncedLyrics'];
                    $plain = $plain ?? ($best['plainLyrics'] ?? null);
                } elseif (! $plain) {
                    $plain = $hits->first()['plainLyrics'] ?? null;
                }
            }
        } catch (\Throwable) {
            // офлайн/таймаут — вернём "не найдено", но перепросим позже
        }

        $lyrics = TrackLyrics::updateOrCreate(
            ['track_id' => $track->id],
            [
                'synced_lyrics' => $synced,
                'plain_lyrics' => $plain,
                'found' => $synced !== null || $plain !== null,
                'fetched_at' => now(),
            ]
        );

        return $this->respond($lyrics);
    }

    private function respond(TrackLyrics $l)
    {
        return response()->json([
            'found' => $l->found,
            'synced' => $l->synced_lyrics,
            'plain' => $l->plain_lyrics,
        ]);
    }
}
