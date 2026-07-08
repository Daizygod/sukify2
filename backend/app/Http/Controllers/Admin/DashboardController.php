<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\Artist;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use Inertia\Inertia;

class DashboardController extends Controller
{
    public function index()
    {
        return Inertia::render('Dashboard', [
            'stats' => [
                'artists' => Artist::count(),
                'releases' => Release::count(),
                'tracks' => Track::count(),
                'users' => User::count(),
                'playlists' => Playlist::count(),
                'processing' => Track::whereIn('processing_status', ['pending', 'processing'])->count(),
            ],
            'topTracks' => Track::query()
                ->with('release')
                ->orderByDesc('plays_count')
                ->limit(10)
                ->get(['id', 'title', 'release_id', 'plays_count', 'likes_count'])
                ->map(fn ($t) => [
                    'id' => $t->id,
                    'title' => $t->title,
                    'release' => $t->release?->title,
                    'plays_count' => $t->plays_count,
                    'likes_count' => $t->likes_count,
                ]),
        ]);
    }
}
