<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\ArtistResource;
use App\Http\Resources\ReleaseResource;
use App\Models\Artist;
use App\Models\Release;
use Illuminate\Http\Request;

class GenreController extends Controller
{
    use ResolvesLikes;

    /** Плитки «Обзор» на странице поиска. */
    public function index()
    {
        $genres = Artist::query()
            ->whereNotNull('genre')
            ->selectRaw('genre, count(*) as artists_count')
            ->groupBy('genre')
            ->orderByDesc('artists_count')
            ->get();

        return response()->json(['data' => $genres]);
    }

    /** Страница жанра: исполнители и их релизы. */
    public function show(Request $request, string $genre)
    {
        $artists = Artist::query()
            ->where('genre', $genre)
            ->orderByDesc('monthly_listeners')
            ->get();

        $releases = Release::query()
            ->whereIn('artist_id', $artists->pluck('id'))
            ->with('artist')
            ->withCount('tracks')
            ->orderByDesc('release_date')
            ->get();

        $this->markFollowedArtists($artists, $request->user());
        $this->markLikedReleases($releases, $request->user());

        return response()->json([
            'genre' => $genre,
            'artists' => ArtistResource::collection($artists),
            'releases' => ReleaseResource::collection($releases),
        ]);
    }
}
