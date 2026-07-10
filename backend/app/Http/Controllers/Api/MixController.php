<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\TrackResource;
use App\Models\Artist;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Collection;

/**
 * Автогенерируемые подборки: «Микс дня #N» (по жанрам прослушиваний юзера)
 * и «Радио» по треку/исполнителю (тот же артист + жанровые соседи).
 */
class MixController extends Controller
{
    use ResolvesLikes;

    /** Список миксов дня для главной. */
    public function daily(Request $request)
    {
        $mixes = $this->buildMixes($request->user()?->id);

        return response()->json([
            'data' => $mixes->map(fn ($m, $i) => [
                'n' => $i + 1,
                'title' => 'Микс дня #'.($i + 1),
                'genre' => $m['genre'],
                'tracks_count' => $m['tracks']->count(),
                'artists' => $m['tracks']->flatMap(fn ($t) => $t->artists->pluck('name'))->unique()->take(3)->values(),
                'color' => $m['color'],
            ])->values(),
        ]);
    }

    /** Треки одного микса. */
    public function show(Request $request, int $n)
    {
        $mixes = $this->buildMixes($request->user()?->id);
        $mix = $mixes->values()->get($n - 1);
        abort_unless($mix, 404);

        $this->markLikedTracks($mix['tracks'], $request->user());

        return response()->json([
            'title' => 'Микс дня #'.$n,
            'genre' => $mix['genre'],
            'color' => $mix['color'],
            'tracks' => TrackResource::collection($mix['tracks']),
        ]);
    }

    /** Радио по треку: тот же исполнитель + жанровые соседи, перемешано. */
    public function trackRadio(Request $request, Track $track)
    {
        $track->load('artists');
        $artistIds = $track->artists->pluck('id');
        $genres = Artist::whereIn('id', $artistIds)->pluck('genre')->filter()->unique();

        $sameArtist = Track::whereHas('artists', fn ($q) => $q->whereIn('artists.id', $artistIds))
            ->where('id', '!=', $track->id)
            ->with(['artists', 'release'])
            ->limit(30)
            ->get();

        $genreMates = Track::whereHas('artists', fn ($q) => $q->whereIn('genre', $genres))
            ->whereNotIn('id', $sameArtist->pluck('id')->push($track->id))
            ->with(['artists', 'release'])
            ->orderByDesc('plays_count')
            ->limit(30)
            ->get();

        $radio = $sameArtist->concat($genreMates)->shuffle()->take(50)->values();
        $this->markLikedTracks($radio, $request->user());

        return TrackResource::collection($radio);
    }

    /** Общий генератор миксов: жанры лайков юзера, иначе все жанры каталога. */
    private function buildMixes(?int $userId): Collection
    {
        $colors = ['#27856a', '#1e3264', '#8d67ab', '#e8115b', '#ba5d07'];

        $genres = collect();
        if ($userId) {
            $genres = Artist::query()
                ->whereNotNull('genre')
                ->whereHas('tracks.likedByUsers', fn ($q) => $q->where('users.id', $userId))
                ->pluck('genre')
                ->unique()
                ->values();
        }
        if ($genres->isEmpty()) {
            $genres = Artist::whereNotNull('genre')->distinct()->pluck('genre')->values();
        }

        return $genres->take(3)->map(function ($genre, $i) use ($colors) {
            $tracks = Track::query()
                ->whereHas('artists', fn ($q) => $q->where('genre', $genre))
                ->with(['artists', 'release'])
                ->orderByDesc('plays_count')
                ->limit(30)
                ->get()
                // Детерминированный «дневной» порядок: сид от даты и жанра.
                ->sortBy(fn ($t) => crc32(now()->toDateString().$genre.$t->id))
                ->values();

            return ['genre' => $genre, 'tracks' => $tracks, 'color' => $colors[$i % count($colors)]];
        });
    }
}
