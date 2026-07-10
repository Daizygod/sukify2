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

        $me = $request->user();

        return response()->json([
            'user' => new UserResource($user),
            'playlists' => PlaylistResource::collection($publicPlaylists),
            'public_playlists_count' => $publicPlaylists->count(),
            'followers_count' => $user->followers()->count(),
            'following_count' => $user->following()->count(),
            'is_followed' => $me ? $me->following()->whereKey($user->id)->exists() : false,
            'is_me' => $me?->id === $user->id,
        ]);
    }
}
