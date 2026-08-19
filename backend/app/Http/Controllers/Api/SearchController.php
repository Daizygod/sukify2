<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ArtistResource;
use App\Http\Resources\PlaylistResource;
use App\Http\Resources\ReleaseResource;
use App\Http\Resources\TrackResource;
use App\Http\Resources\UserResource;
use App\Models\Artist;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use Illuminate\Http\Request;

class SearchController extends Controller
{
    use ResolvesLikes;

    public function __invoke(Request $request)
    {
        $query = trim((string) $request->query('q', ''));

        if ($query === '') {
            return response()->json([
                'tracks' => [], 'releases' => [], 'artists' => [], 'playlists' => [], 'users' => [],
            ]);
        }

        $limit = min((int) $request->query('limit', 8), 20);

        $tracks = Track::search($query)->take($limit)->get()
            ->load(['artists', 'release']);
        $releases = Release::search($query)->take($limit)->get()->load('artist');
        $artists = Artist::search($query)->take($limit)->get();
        $playlists = Playlist::search($query)->take($limit)->get()->load('owner');

        // Люди Sukify — обычным LIKE: пользователей в поисковый индекс не кладём.
        $users = User::query()
            ->where('is_banned', false)
            ->where(fn ($w) => $w->where('name', 'ilike', "%{$query}%")->orWhere('username', 'ilike', "%{$query}%"))
            ->when($request->user(), fn ($w, $me) => $w->whereKeyNot($me->id))
            ->orderBy('name')
            ->limit($limit)
            ->get();

        $this->markLikedTracks($tracks, $request->user());
        $this->markLikedReleases($releases, $request->user());
        $this->markFollowedArtists($artists, $request->user());

        return response()->json([
            'tracks' => TrackResource::collection($tracks),
            'releases' => ReleaseResource::collection($releases),
            'artists' => ArtistResource::collection($artists),
            'playlists' => PlaylistResource::collection($playlists),
            'users' => UserResource::collection($users),
        ]);
    }
}
