<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\TrackResource;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

/**
 * Импорт «Любимых треков» из Spotify (Exportify CSV / GDPR JSON, распарсенные
 * на клиенте в нормализованный список). Матчим по названию + исполнителю
 * (+ длительность, когда она есть) и лайкаем найденное.
 */
class ImportController extends Controller
{
    public function likedTracks(Request $request)
    {
        $data = $request->validate([
            'items' => ['required', 'array', 'max:5000'],
            'items.*.title' => ['required', 'string', 'max:500'],
            'items.*.artists' => ['array'],
            'items.*.artists.*' => ['string', 'max:300'],
            'items.*.duration_ms' => ['nullable', 'integer'],
        ]);

        $user = $request->user();
        $alreadyLiked = $user->likedTracks()->pluck('tracks.id')->flip();

        // Один проход по каталогу: индекс нормализованное-название → треки.
        $catalog = Track::with('artists:id,name')->get(['id', 'title', 'duration_ms']);
        $index = [];
        foreach ($catalog as $track) {
            $index[$this->norm($track->title)][] = $track;
        }

        $matchedIds = [];
        $added = 0;
        $already = 0;
        $missing = [];

        foreach ($data['items'] as $item) {
            $track = $this->match($item, $index);
            if (! $track) {
                $missing[] = [
                    'title' => $item['title'],
                    'artists' => $item['artists'] ?? [],
                ];
                continue;
            }
            if (isset($alreadyLiked[$track->id]) || isset($matchedIds[$track->id])) {
                $already++;
                continue;
            }
            $matchedIds[$track->id] = true;
            $added++;
        }

        if ($matchedIds) {
            $user->likedTracks()->syncWithoutDetaching(array_keys($matchedIds));
            Track::whereIn('id', array_keys($matchedIds))->increment('likes_count');
        }

        return response()->json([
            'added' => $added,
            'already' => $already,
            'missing' => $missing,
            'total' => count($data['items']),
        ]);
    }

    private function match(array $item, array $index): ?Track
    {
        $title = $this->norm($item['title']);
        $candidates = $index[$title] ?? [];

        // Заголовок в каталоге может не содержать «(feat. …)» и наоборот.
        if (! $candidates) {
            $stripped = $this->norm(preg_replace('/[\(\[].*?[\)\]]/u', '', $item['title']));
            $candidates = $index[$stripped] ?? [];
        }
        if (! $candidates) {
            return null;
        }

        $wantArtists = array_map(fn ($a) => $this->norm($a), $item['artists'] ?? []);
        $wantDur = $item['duration_ms'] ?? null;

        foreach ($candidates as $track) {
            if ($wantArtists) {
                $trackArtists = $track->artists->map(fn ($a) => $this->norm($a->name))->all();
                $overlap = array_intersect($wantArtists, $trackArtists);
                if (! $overlap) {
                    continue;
                }
            }
            if ($wantDur && $track->duration_ms && abs($track->duration_ms - $wantDur) > 5000) {
                continue;
            }

            return $track;
        }

        // Без артистов в запросе — берём первый кандидат по названию.
        return $wantArtists ? null : $candidates[0];
    }

    private function norm(string $s): string
    {
        $s = Str::lower(trim($s));
        $s = preg_replace('/\s+/u', ' ', $s);

        return preg_replace('/[«»"\'’`´]/u', '', $s);
    }
}
