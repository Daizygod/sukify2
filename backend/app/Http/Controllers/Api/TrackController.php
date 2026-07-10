<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\TrackResource;
use App\Models\Track;
use App\Models\TrackPlay;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class TrackController extends Controller
{
    use ResolvesLikes;

    public function show(Request $request, Track $track)
    {
        $track->load(['artists', 'release.artist']);
        $this->markLikedTracks([$track], $request->user());

        return new TrackResource($track);
    }

    /** Bulk fetch (device playback transfer) — returns tracks in the requested order. */
    public function bulk(Request $request)
    {
        $ids = collect(explode(',', (string) $request->query('ids')))
            ->map(fn ($v) => (int) $v)
            ->filter()
            ->unique()
            ->take(500)
            ->values();

        $tracks = Track::with(['artists', 'release'])
            ->whereIn('id', $ids)
            ->get()
            ->keyBy('id');

        $ordered = $ids->map(fn ($id) => $tracks->get($id))->filter()->values();
        $this->markLikedTracks($ordered, $request->user());

        return TrackResource::collection($ordered);
    }

    public function like(Request $request, Track $track)
    {
        $changed = $request->user()->likedTracks()->syncWithoutDetaching([$track->id]);

        if (! empty($changed['attached'])) {
            $track->increment('likes_count');
        }

        return response()->json(['is_liked' => true]);
    }

    public function unlike(Request $request, Track $track)
    {
        $detached = $request->user()->likedTracks()->detach($track->id);

        if ($detached > 0) {
            $track->decrement('likes_count');
        }

        return response()->json(['is_liked' => false]);
    }

    /** Record a play (drives Home "recently played" and admin stats). */
    public function logPlay(Request $request, Track $track)
    {
        TrackPlay::create([
            'track_id' => $track->id,
            'user_id' => $request->user()?->id,
            'played_at' => now(),
        ]);

        // Denormalized counter; avoids a COUNT(*) on the stats page.
        $track->increment('plays_count');

        return response()->json(['plays_count' => $track->plays_count]);
    }
}
