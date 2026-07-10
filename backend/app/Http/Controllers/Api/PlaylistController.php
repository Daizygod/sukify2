<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\PlaylistResource;
use App\Jobs\GeneratePlaylistCollage;
use App\Models\Playlist;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class PlaylistController extends Controller
{
    use ResolvesLikes;

    public function show(Request $request, Playlist $playlist)
    {
        $this->authorize('view', $playlist);

        $playlist->load(['owner', 'tracks.artists', 'tracks.release']);
        $this->markLikedTracks($playlist->tracks, $request->user());

        return new PlaylistResource($playlist);
    }

    /** Владелец включает совместный режим — получает инвайт-ссылку. */
    public function invite(Request $request, Playlist $playlist)
    {
        abort_unless($request->user()->id === $playlist->user_id, 403);

        if (! $playlist->invite_token) {
            $playlist->invite_token = bin2hex(random_bytes(20));
            $playlist->save();
        }

        return response()->json(['invite_token' => $playlist->invite_token]);
    }

    /** Присоединиться к совместному плейлисту по токену. */
    public function join(Request $request, Playlist $playlist, string $token)
    {
        abort_unless($playlist->invite_token && hash_equals($playlist->invite_token, $token), 403);

        if ($request->user()->id !== $playlist->user_id) {
            $playlist->collaborators()->syncWithoutDetaching([$request->user()->id]);
        }

        return new PlaylistResource($playlist->load('owner'));
    }

    /** «Рекомендуем» в конце плейлиста: похожее по артистам и жанру, чего ещё нет внутри. */
    public function recommendations(Request $request, Playlist $playlist)
    {
        $this->authorize('view', $playlist);

        $playlist->load('tracks.artists');
        $inside = $playlist->tracks->pluck('id');
        $artistIds = $playlist->tracks->flatMap(fn ($t) => $t->artists->pluck('id'))->unique();
        $genres = \App\Models\Artist::whereIn('id', $artistIds)->pluck('genre')->filter()->unique();

        $recs = Track::query()
            ->whereNotIn('id', $inside)
            ->where(function ($q) use ($artistIds, $genres) {
                $q->whereHas('artists', fn ($qq) => $qq->whereIn('artists.id', $artistIds));
                if ($genres->isNotEmpty()) {
                    $q->orWhereHas('artists', fn ($qq) => $qq->whereIn('genre', $genres));
                }
            })
            ->with(['artists', 'release'])
            ->orderByDesc('plays_count')
            ->limit(10)
            ->get();

        $this->markLikedTracks($recs, $request->user());

        return \App\Http\Resources\TrackResource::collection($recs);
    }

    public function store(Request $request)
    {
        $data = $request->validate([
            'title' => ['required', 'string', 'max:255'],
            'description' => ['nullable', 'string', 'max:1000'],
            'is_public' => ['sometimes', 'boolean'],
        ]);

        $playlist = $request->user()->playlists()->create([
            'title' => $data['title'],
            'description' => $data['description'] ?? null,
            'is_public' => $data['is_public'] ?? true,
        ]);

        return (new PlaylistResource($playlist->load('owner')))
            ->response()
            ->setStatusCode(201);
    }

    public function update(Request $request, Playlist $playlist)
    {
        $this->authorize('update', $playlist);

        $data = $request->validate([
            'title' => ['sometimes', 'string', 'max:255'],
            'description' => ['nullable', 'string', 'max:1000'],
            'is_public' => ['sometimes', 'boolean'],
        ]);

        $playlist->update($data);

        return new PlaylistResource($playlist->load('owner'));
    }

    public function destroy(Request $request, Playlist $playlist)
    {
        $this->authorize('delete', $playlist);
        $playlist->delete();

        return response()->json(['message' => 'Playlist deleted.']);
    }

    public function addTrack(Request $request, Playlist $playlist)
    {
        $this->authorize('update', $playlist);

        $data = $request->validate([
            'track_id' => ['required', 'integer', 'exists:tracks,id'],
        ]);

        $nextPosition = (int) $playlist->tracks()->max('position') + 1;
        $wasEmpty = $playlist->tracks()->count() === 0;

        $playlist->tracks()->attach($data['track_id'], [
            'position' => $nextPosition,
            'added_by_user_id' => $request->user()->id,
            'added_at' => now(),
        ]);

        // Auto-collage cover on first tracks, if no custom cover set (spec §4).
        if ($wasEmpty && ! $playlist->cover_is_custom) {
            GeneratePlaylistCollage::dispatch($playlist->id);
        }

        return response()->json(['message' => 'Track added.', 'position' => $nextPosition], 201);
    }

    public function removeTrack(Request $request, Playlist $playlist, int $item)
    {
        $this->authorize('update', $playlist);

        // $item is the playlist_track pivot id (a track may appear more than once).
        DB::table('playlist_track')
            ->where('playlist_id', $playlist->id)
            ->where('id', $item)
            ->delete();

        return response()->json(['message' => 'Track removed.']);
    }

    /**
     * Reorder the playlist from a full ordered list of pivot ids.
     * Body: { "item_ids": [12, 8, 15, ...] }
     */
    public function reorder(Request $request, Playlist $playlist)
    {
        $this->authorize('update', $playlist);

        $data = $request->validate([
            'item_ids' => ['required', 'array', 'min:1'],
            'item_ids.*' => ['integer'],
        ]);

        DB::transaction(function () use ($data, $playlist) {
            foreach ($data['item_ids'] as $index => $itemId) {
                DB::table('playlist_track')
                    ->where('playlist_id', $playlist->id)
                    ->where('id', $itemId)
                    ->update(['position' => $index + 1]);
            }
        });

        return response()->json(['message' => 'Reordered.']);
    }
}
