<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ReleaseResource;
use App\Models\Release;
use Illuminate\Http\Request;

class ReleaseController extends Controller
{
    use ResolvesLikes;

    public function show(Request $request, Release $release)
    {
        $release->load([
            'artist',
            'tracks.artists',
        ]);

        $this->markLikedReleases([$release], $request->user());
        $this->markLikedTracks($release->tracks, $request->user());

        return new ReleaseResource($release);
    }

    public function like(Request $request, Release $release)
    {
        $request->user()->likedAlbums()->syncWithoutDetaching([$release->id]);

        return response()->json(['is_liked' => true]);
    }

    public function unlike(Request $request, Release $release)
    {
        $request->user()->likedAlbums()->detach($release->id);

        return response()->json(['is_liked' => false]);
    }
}
