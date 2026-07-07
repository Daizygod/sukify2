<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ReleaseResource;
use App\Http\Resources\TrackResource;
use App\Models\Release;
use App\Models\Track;
use App\Models\TrackPlay;
use Illuminate\Http\Request;

class HomeController extends Controller
{
    use ResolvesLikes;

    public function __invoke(Request $request)
    {
        $user = $request->user();

        // Recently played (distinct tracks, most recent first) for logged-in users.
        $recentlyPlayed = collect();
        if ($user) {
            $recentTrackIds = TrackPlay::query()
                ->where('user_id', $user->id)
                ->orderByDesc('played_at')
                ->limit(60)
                ->pluck('track_id')
                ->unique()
                ->take(12);

            $recentlyPlayed = Track::query()
                ->whereIn('id', $recentTrackIds)
                ->with(['artists', 'release'])
                ->get()
                ->sortBy(fn ($t) => $recentTrackIds->search($t->id))
                ->values();

            $this->markLikedTracks($recentlyPlayed, $user);
        }

        // Popular tracks (global) as a "Made for you" stub for stage 1/2.
        $popularTracks = Track::query()
            ->where('processing_status', 'ready')
            ->with(['artists', 'release'])
            ->orderByDesc('plays_count')
            ->limit(12)
            ->get();
        $this->markLikedTracks($popularTracks, $user);

        // New releases.
        $newReleases = Release::query()
            ->with('artist')
            ->withCount('tracks')
            ->orderByDesc('release_date')
            ->limit(12)
            ->get();
        $this->markLikedReleases($newReleases, $user);

        return response()->json([
            'recently_played' => TrackResource::collection($recentlyPlayed),
            'popular_tracks' => TrackResource::collection($popularTracks),
            'new_releases' => ReleaseResource::collection($newReleases),
        ]);
    }
}
