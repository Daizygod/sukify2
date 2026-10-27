<?php

namespace App\Http\Controllers\Api;

use App\Enums\ProcessingStatus;
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

        // Как в Spotify, в углу плитки — повёрнутая обложка: релиз самого
        // слушаемого исполнителя жанра. Один запрос на все жанры (DISTINCT ON).
        $covers = Release::query()
            ->selectRaw('distinct on (artists.genre) releases.*, artists.genre as genre_key')
            ->join('artists', 'artists.id', '=', 'releases.artist_id')
            ->whereNotNull('artists.genre')
            ->whereNotNull('releases.cover_path')
            ->where('releases.cover_status', ProcessingStatus::Ready)
            ->orderBy('artists.genre')
            ->orderByRaw('artists.monthly_listeners desc nulls last')
            ->orderByRaw('releases.release_date desc nulls last')
            ->get()
            ->keyBy('genre_key');

        return response()->json(['data' => $genres->map(fn ($g) => [
            'genre' => $g->genre,
            'artists_count' => (int) $g->artists_count,
            'cover' => $covers->get($g->genre)?->coverUrls(),
        ])]);
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
