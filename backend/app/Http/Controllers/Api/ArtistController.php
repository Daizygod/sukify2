<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ArtistResource;
use App\Http\Resources\ReleaseResource;
use App\Http\Resources\TrackResource;
use App\Models\Artist;
use Illuminate\Http\Request;

class ArtistController extends Controller
{
    use ResolvesLikes;

    public function index(Request $request)
    {
        $artists = Artist::query()
            ->orderByDesc('monthly_listeners')
            ->paginate(30);

        $this->markFollowedArtists($artists->getCollection(), $request->user());

        return ArtistResource::collection($artists);
    }

    public function show(Request $request, Artist $artist)
    {
        $artist->load(['releases' => fn ($q) => $q->orderByDesc('release_date')]);
        $this->markFollowedArtists([$artist], $request->user());
        $this->markLikedReleases($artist->releases, $request->user());

        return new ArtistResource($artist);
    }

    /** Top tracks by play count for the artist page. */
    public function topTracks(Request $request, Artist $artist)
    {
        $tracks = $artist->tracks()
            ->with(['artists', 'release'])
            ->orderByDesc('plays_count')
            ->limit(10)
            ->get();

        $this->markLikedTracks($tracks, $request->user());

        return TrackResource::collection($tracks);
    }

    public function releases(Artist $artist)
    {
        $releases = $artist->releases()
            ->with('artist')
            ->withCount('tracks')
            ->orderByDesc('release_date')
            ->get();

        return ReleaseResource::collection($releases);
    }

    public function follow(Request $request, Artist $artist)
    {
        $request->user()->followedArtists()->syncWithoutDetaching([$artist->id]);

        return response()->json(['is_followed' => true]);
    }

    public function unfollow(Request $request, Artist $artist)
    {
        $request->user()->followedArtists()->detach($artist->id);

        return response()->json(['is_followed' => false]);
    }
}
