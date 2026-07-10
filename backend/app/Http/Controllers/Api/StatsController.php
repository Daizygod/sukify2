<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ArtistResource;
use App\Http\Resources\ReleaseResource;
use App\Http\Resources\TrackResource;
use App\Models\Artist;
use App\Models\Release;
use App\Models\Track;
use App\Models\TrackPlay;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class StatsController extends Controller
{
    use ResolvesLikes;

    /** «Твоя статистика» за последние 30 дней. */
    public function monthly(Request $request)
    {
        $userId = $request->user()->id;
        $since = now()->subDays(30);

        $counts = TrackPlay::query()
            ->where('user_id', $userId)
            ->where('played_at', '>=', $since)
            ->select('track_id', DB::raw('count(*) as plays'))
            ->groupBy('track_id')
            ->orderByDesc('plays')
            ->get();

        $tracks = Track::with(['artists', 'release'])
            ->whereIn('id', $counts->pluck('track_id'))
            ->get()
            ->keyBy('id');
        $this->markLikedTracks($tracks, $request->user());

        $topTracks = $counts->take(10)->map(fn ($c) => [
            'plays' => $c->plays,
            'track' => $tracks->get($c->track_id)
                ? (new TrackResource($tracks->get($c->track_id)))->resolve($request)
                : null,
        ])->filter(fn ($i) => $i['track'])->values();

        // Топ исполнителей: раскладываем прослушивания по артистам треков.
        $artistPlays = [];
        foreach ($counts as $c) {
            $t = $tracks->get($c->track_id);
            if (! $t) {
                continue;
            }
            foreach ($t->artists as $a) {
                $artistPlays[$a->id] = ($artistPlays[$a->id] ?? 0) + $c->plays;
            }
        }
        arsort($artistPlays);
        $artists = Artist::whereIn('id', array_keys($artistPlays))->get()->keyBy('id');
        $topArtists = collect($artistPlays)->take(6)->map(fn ($plays, $id) => [
            'plays' => $plays,
            'artist' => $artists->get($id) ? (new ArtistResource($artists->get($id)))->resolve($request) : null,
        ])->filter(fn ($i) => $i['artist'])->values();

        $totalPlays = $counts->sum('plays');
        $minutes = (int) round(
            $counts->sum(fn ($c) => ($tracks->get($c->track_id)?->duration_ms ?? 0) * $c->plays) / 60000
        );

        return response()->json([
            'total_plays' => $totalPlays,
            'minutes' => $minutes,
            'top_tracks' => $topTracks,
            'top_artists' => $topArtists,
        ]);
    }

    /** Колокольчик: свежие релизы исполнителей, на которых подписан юзер. */
    public function notifications(Request $request)
    {
        $artistIds = $request->user()->followedArtists()->pluck('artists.id');

        $releases = Release::query()
            ->whereIn('artist_id', $artistIds)
            ->where('release_date', '>=', now()->subDays(90))
            ->with('artist')
            ->withCount('tracks')
            ->orderByDesc('release_date')
            ->limit(20)
            ->get();

        return ReleaseResource::collection($releases);
    }
}
