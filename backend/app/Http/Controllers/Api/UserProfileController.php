<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\PlaylistResource;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Http\Request;

class UserProfileController extends Controller
{
    /** Public profile by username: user + their public playlists. */
    public function show(Request $request, string $username)
    {
        $user = User::where('username', $username)->firstOrFail();

        $publicPlaylists = $user->playlists()
            ->where('is_public', true)
            ->withCount('tracks')
            ->latest('updated_at')
            ->get();

        return response()->json([
            'user' => new UserResource($user),
            'playlists' => PlaylistResource::collection($publicPlaylists),
            'public_playlists_count' => $publicPlaylists->count(),
        ]);
    }
}
