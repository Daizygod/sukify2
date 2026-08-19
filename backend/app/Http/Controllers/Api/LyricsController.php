<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Track;
use App\Models\TrackLyrics;
use App\Services\Lyrics\LrclibClient;
use Illuminate\Http\Request;
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
        // Свой текст (правил админ) — всегда главнее LRCLIB и не перезапрашивается.
        if ($cached?->is_custom) {
            return $this->respond($cached);
        }
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
        $lrclibId = null;
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
            // id записи нужен, чтобы можно было пожаловаться на неверный текст.
            $lrclibId = $hit['id'] ?? null;

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
                    $lrclibId = $best['id'] ?? $lrclibId;
                } elseif (! $plain) {
                    $plain = $hits->first()['plainLyrics'] ?? null;
                    $lrclibId = $hits->first()['id'] ?? $lrclibId;
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
                'lrclib_id' => $lrclibId,
            ]
        );

        return $this->respond($lyrics);
    }

    /**
     * Пожаловаться на неверный текст (кнопка в плеере, только админ).
     * Локальный кэш сбрасываем: при следующем открытии текст перезапросится.
     */
    public function flag(Request $request, Track $track, LrclibClient $lrclib)
    {
        abort_unless($request->user()?->is_admin, 403);

        $data = $request->validate([
            'reason' => ['nullable', 'string', 'max:2000'],
        ]);

        $lyrics = TrackLyrics::where('track_id', $track->id)->first();
        $lrclibId = $lyrics?->lrclib_id;

        if (! $lrclibId) {
            $track->load(['artists', 'release']);
            $lrclibId = $lyrics?->is_custom ? null : $lrclib->findTrackId(
                $track->title,
                $track->artists->first()?->name ?? '',
                $track->release?->title,
                (int) round(($track->duration_ms ?? 0) / 1000),
            );
        }

        if (! $lrclibId) {
            return response()->json(['message' => 'Эта запись не найдена на LRCLIB.'], 422);
        }

        try {
            $lrclib->flag($lrclibId, $data['reason'] ?? null);
        } catch (\Throwable $e) {
            return response()->json(['message' => $e->getMessage()], 502);
        }

        // Свой текст жалоба не трогает; чужой — перезапросим при следующем показе.
        if ($lyrics && ! $lyrics->is_custom) {
            $lyrics->update(['fetched_at' => now()->subYear()]);
        }

        return response()->json(['message' => 'Жалоба отправлена на LRCLIB.']);
    }

    /** Свой текст трека (админка): он главнее LRCLIB. */
    public function store(Request $request, Track $track)
    {
        abort_unless($request->user()?->is_admin, 403);

        $data = $request->validate([
            'plain' => ['nullable', 'string', 'max:20000'],
            'synced' => ['nullable', 'string', 'max:40000'],
        ]);

        $synced = trim((string) ($data['synced'] ?? '')) ?: null;
        $plain = trim((string) ($data['plain'] ?? '')) ?: null;

        // Пустая форма — снимаем свой текст и возвращаемся к LRCLIB.
        if (! $synced && ! $plain) {
            TrackLyrics::where('track_id', $track->id)->delete();

            return response()->json(['message' => 'Свой текст удалён — снова берём с LRCLIB.']);
        }

        // Из LRC можно вытащить и plain-версию, чтобы она была всегда.
        if ($synced && ! $plain) {
            $plain = trim(preg_replace('/^\[\d{2}:\d{2}[.:]\d{2,3}\]\s?/m', '', $synced));
        }

        $lyrics = TrackLyrics::updateOrCreate(
            ['track_id' => $track->id],
            [
                'synced_lyrics' => $synced,
                'plain_lyrics' => $plain,
                'found' => true,
                'is_custom' => true,
                'fetched_at' => now(),
            ]
        );

        return $this->respond($lyrics);
    }

    /** Отправить свой текст на LRCLIB (админка). */
    public function publish(Request $request, Track $track, LrclibClient $lrclib)
    {
        abort_unless($request->user()?->is_admin, 403);

        $lyrics = TrackLyrics::where('track_id', $track->id)->first();
        abort_unless($lyrics && ($lyrics->synced_lyrics || $lyrics->plain_lyrics), 422, 'Сначала сохрани текст.');

        $track->load(['artists', 'release']);

        try {
            $lrclib->publish([
                'trackName' => $track->title,
                'artistName' => $track->artists->first()?->name ?? '',
                'albumName' => $track->release?->title ?? '',
                'duration' => (int) round(($track->duration_ms ?? 0) / 1000),
                'plainLyrics' => $lyrics->plain_lyrics ?? '',
                'syncedLyrics' => $lyrics->synced_lyrics ?? '',
            ]);
        } catch (\Throwable $e) {
            return response()->json(['message' => $e->getMessage()], 502);
        }

        return response()->json(['message' => 'Текст опубликован на LRCLIB.']);
    }

    private function respond(TrackLyrics $l)
    {
        return response()->json([
            'found' => $l->found,
            'synced' => $l->synced_lyrics,
            'plain' => $l->plain_lyrics,
            'is_custom' => (bool) $l->is_custom,
            'lrclib_id' => $l->lrclib_id,
        ]);
    }
}
