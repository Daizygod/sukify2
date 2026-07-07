<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ArtistResource;
use App\Http\Resources\PlaylistResource;
use App\Http\Resources\ReleaseResource;
use App\Http\Resources\TrackResource;
use Illuminate\Http\Request;

class LibraryController extends Controller
{
    use ResolvesLikes;

    /** Playlists owned by the user — the left sidebar list. */
    public function playlists(Request $request)
    {
        $playlists = $request->user()->playlists()
            ->with('owner')
            ->withCount('tracks')
            ->latest('updated_at')
            ->get();

        return PlaylistResource::collection($playlists);
    }

    /** The Liked Songs special page. */
    public function likedTracks(Request $request)
    {
        $tracks = $request->user()->likedTracks()
            ->with(['artists', 'release'])
            ->paginate(50);

        $tracks->getCollection()->each(fn ($t) => $t->is_liked = true);

        return TrackResource::collection($tracks);
    }

    public function likedAlbums(Request $request)
    {
        $releases = $request->user()->likedAlbums()
            ->with('artist')
            ->withCount('tracks')
            ->get();

        $releases->each(fn ($r) => $r->is_liked = true);

        return ReleaseResource::collection($releases);
    }

    public function followedArtists(Request $request)
    {
        $artists = $request->user()->followedArtists()->get();
        $artists->each(fn ($a) => $a->is_followed = true);

        return ArtistResource::collection($artists);
    }
}
