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

    /**
     * Весь каталог релизов — «Все релизы» под подборками на странице обзора.
     *
     * sort=added — недавно загруженные в Sukify (по правке/добавлению),
     * sort=date  — по дате выхода, sort=name — по алфавиту.
     */
    public function index(Request $request)
    {
        $sort = $request->query('sort', 'added');

        $releases = Release::query()
            ->with('artist')
            ->withCount('tracks')
            ->when($sort === 'date', fn ($q) => $q->orderByDesc('release_date'))
            ->when($sort === 'name', fn ($q) => $q->orderBy('title'))
            ->when(
                ! in_array($sort, ['date', 'name'], true),
                fn ($q) => $q->orderByDesc(Release::freshnessExpression())
            )
            ->orderByDesc('id')
            ->paginate(48);

        $this->markLikedReleases($releases->getCollection(), $request->user());

        return ReleaseResource::collection($releases);
    }

    public function show(Request $request, Release $release)
    {
        $release->load([
            'artist',
            'artists',
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
