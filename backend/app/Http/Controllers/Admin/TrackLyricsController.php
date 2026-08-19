<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\Track;
use App\Models\TrackLyrics;
use App\Services\Lyrics\LrclibClient;
use Illuminate\Http\Request;
use Inertia\Inertia;

/**
 * Редактор текста трека в админке: свой текст (он главнее LRCLIB),
 * предпросмотр синхронизации и отправка на lrclib.net.
 */
class TrackLyricsController extends Controller
{
    public function edit(Track $track)
    {
        $track->load(['artists:id,name', 'release:id,title']);
        $lyrics = TrackLyrics::where('track_id', $track->id)->first();

        return Inertia::render('Tracks/Lyrics', [
            'track' => [
                'id' => $track->id,
                'title' => $track->title,
                'artists' => $track->artists->pluck('name')->all(),
                'release' => $track->release?->title,
                'release_id' => $track->release_id,
                'duration_ms' => $track->duration_ms,
                'stream_url' => $track->streamUrl(),
            ],
            'lyrics' => [
                'plain' => $lyrics?->plain_lyrics,
                'synced' => $lyrics?->synced_lyrics,
                'is_custom' => (bool) $lyrics?->is_custom,
                'lrclib_id' => $lyrics?->lrclib_id,
                'found' => (bool) $lyrics?->found,
            ],
        ]);
    }

    public function update(Request $request, Track $track)
    {
        $data = $request->validate([
            'plain' => ['nullable', 'string', 'max:20000'],
            'synced' => ['nullable', 'string', 'max:40000'],
        ]);

        $synced = trim((string) ($data['synced'] ?? '')) ?: null;
        $plain = trim((string) ($data['plain'] ?? '')) ?: null;

        // Пустая форма — отказаться от своего текста и снова брать с LRCLIB.
        if (! $synced && ! $plain) {
            TrackLyrics::where('track_id', $track->id)->delete();

            return back()->with('success', 'Свой текст удалён — снова берём с LRCLIB.');
        }

        if ($synced && ! $plain) {
            $plain = trim(preg_replace('/^\[\d{2}:\d{2}[.:]\d{2,3}\]\s?/m', '', $synced));
        }

        TrackLyrics::updateOrCreate(
            ['track_id' => $track->id],
            [
                'synced_lyrics' => $synced,
                'plain_lyrics' => $plain,
                'found' => true,
                'is_custom' => true,
                'fetched_at' => now(),
            ]
        );

        return back()->with('success', 'Текст сохранён — в плеере он главнее LRCLIB.');
    }

    /** Отправить сохранённый текст на LRCLIB. */
    public function publish(Track $track, LrclibClient $lrclib)
    {
        $lyrics = TrackLyrics::where('track_id', $track->id)->first();
        if (! $lyrics || (! $lyrics->synced_lyrics && ! $lyrics->plain_lyrics)) {
            return back()->with('error', 'Сначала сохрани текст.');
        }

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
            return back()->with('error', $e->getMessage());
        }

        return back()->with('success', 'Текст опубликован на LRCLIB.');
    }

    /** Пожаловаться на текст, который LRCLIB отдаёт для этого трека. */
    public function flag(Request $request, Track $track, LrclibClient $lrclib)
    {
        $lyrics = TrackLyrics::where('track_id', $track->id)->first();
        $track->load(['artists', 'release']);

        $lrclibId = $lyrics?->lrclib_id ?: $lrclib->findTrackId(
            $track->title,
            $track->artists->first()?->name ?? '',
            $track->release?->title,
            (int) round(($track->duration_ms ?? 0) / 1000),
        );

        if (! $lrclibId) {
            return back()->with('error', 'Эта запись не найдена на LRCLIB.');
        }

        try {
            $lrclib->flag($lrclibId, $request->input('reason'));
        } catch (\Throwable $e) {
            return back()->with('error', $e->getMessage());
        }

        return back()->with('success', 'Жалоба отправлена на LRCLIB.');
    }
}
