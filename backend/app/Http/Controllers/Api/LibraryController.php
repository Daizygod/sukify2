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

    /** Playlists owned by (or shared with) the user — the left sidebar list. */
    public function playlists(Request $request)
    {
        $user = $request->user();

        $own = $user->playlists()->with('owner')->withCount('tracks')->get();
        $shared = \App\Models\Playlist::query()
            ->whereHas('collaborators', fn ($q) => $q->whereKey($user->id))
            ->with('owner')
            ->withCount('tracks')
            ->get();

        $playlists = $own->concat($shared)->sortByDesc('updated_at')->values();

        return PlaylistResource::collection($playlists);
    }

    /** Закреплённые элементы медиатеки. */
    public function pins(Request $request)
    {
        $pins = \App\Models\PinnedItem::where('user_id', $request->user()->id)
            ->get(['item_type', 'item_id']);

        return response()->json(['data' => $pins]);
    }

    public function pin(Request $request)
    {
        $data = $request->validate([
            'item_type' => ['required', 'in:playlist,album,artist'],
            'item_id' => ['required', 'integer'],
        ]);

        \App\Models\PinnedItem::firstOrCreate([
            'user_id' => $request->user()->id,
            'item_type' => $data['item_type'],
            'item_id' => $data['item_id'],
        ]);

        return response()->json(['pinned' => true]);
    }

    public function unpin(Request $request)
    {
        $data = $request->validate([
            'item_type' => ['required', 'in:playlist,album,artist'],
            'item_id' => ['required', 'integer'],
        ]);

        \App\Models\PinnedItem::where('user_id', $request->user()->id)
            ->where('item_type', $data['item_type'])
            ->where('item_id', $data['item_id'])
            ->delete();

        return response()->json(['pinned' => false]);
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

    /** История прослушивания: события по убыванию времени, с треками. */
    public function history(Request $request)
    {
        $plays = \App\Models\TrackPlay::query()
            ->where('user_id', $request->user()->id)
            ->orderByDesc('played_at')
            ->limit(200)
            ->get(['track_id', 'played_at']);

        $tracks = \App\Models\Track::whereIn('id', $plays->pluck('track_id')->unique())
            ->with(['artists', 'release'])
            ->get()
            ->keyBy('id');
        $this->markLikedTracks($tracks, $request->user());

        $items = $plays
            ->map(fn ($p) => [
                'played_at' => $p->played_at,
                'track' => $tracks->get($p->track_id),
            ])
            ->filter(fn ($i) => $i['track'])
            ->values()
            ->map(fn ($i) => [
                'played_at' => $i['played_at'],
                'track' => (new TrackResource($i['track']))->resolve($request),
            ]);

        return response()->json(['data' => $items]);
    }
}
