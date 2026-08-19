<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\PlaylistResource;
use App\Http\Resources\UserResource;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;
use Intervention\Image\Drivers\Imagick\Driver as ImagickDriver;
use Intervention\Image\Encoders\WebpEncoder;
use Intervention\Image\ImageManager;

class UserProfileController extends Controller
{
    /** Публичный профиль по @handle (или по id — старые ссылки). */
    public function show(Request $request, string $username)
    {
        $user = User::findByHandle($username);
        abort_if(! $user, 404);

        $publicPlaylists = $user->playlists()
            ->where('is_public', true)
            ->withCount('tracks')
            ->latest('updated_at')
            ->get();

        $me = $request->user();

        // Отношения: подписан ли я на него, он на меня — и, значит, друзья ли.
        $iFollow = $me ? $me->following()->whereKey($user->id)->exists() : false;
        $followsMe = $me ? $user->following()->whereKey($me->id)->exists() : false;

        // Общие друзья — те, с кем у обоих взаимная подписка.
        $mutualFriends = collect();
        if ($me && $me->id !== $user->id) {
            $myFriendIds = $me->friends()->pluck('users.id');
            $mutualFriends = $user->friends()->whereIn('users.id', $myFriendIds)->limit(12)->get();
        }

        return response()->json([
            'user' => new UserResource($user),
            'playlists' => PlaylistResource::collection($publicPlaylists),
            'public_playlists_count' => $publicPlaylists->count(),
            'followers_count' => $user->followers()->count(),
            'following_count' => $user->following()->count(),
            'friends_count' => $user->friends()->count(),
            'is_followed' => $iFollow,
            'follows_me' => $followsMe,
            'is_friend' => $iFollow && $followsMe,
            'mutual_friends' => UserResource::collection($mutualFriends),
            'is_me' => $me?->id === $user->id,
        ]);
    }

    /** Поиск людей Sukify по имени и @handle. */
    public function search(Request $request)
    {
        $q = trim((string) $request->query('q', ''));
        if ($q === '') {
            return response()->json(['data' => []]);
        }

        $users = User::query()
            ->where('is_banned', false)
            ->where(fn ($w) => $w->where('name', 'ilike', "%{$q}%")->orWhere('username', 'ilike', "%{$q}%"))
            ->when($request->user(), fn ($w, $me) => $w->whereKeyNot($me->id))
            ->orderBy('name')
            ->limit(min((int) $request->query('limit', 8), 20))
            ->get();

        return UserResource::collection($users);
    }

    /** Своя аватарка. */
    public function uploadAvatar(Request $request)
    {
        $request->validate([
            'avatar' => ['required', 'image', 'mimes:jpeg,png,webp', 'max:8192'],
        ]);

        $user = $request->user();
        $manager = new ImageManager(new ImagickDriver());
        // Intervention v4: decode(), не read().
        $image = $manager->decode(file_get_contents($request->file('avatar')->getRealPath()))
            ->coverDown(320, 320);

        $key = "avatars/{$user->id}/avatar.webp";
        Storage::disk('s3')->put($key, (string) $image->encode(new WebpEncoder(quality: 85)));

        $user->update(['avatar_path' => $key]);

        return new UserResource($user->fresh());
    }

    public function deleteAvatar(Request $request)
    {
        $request->user()->update(['avatar_path' => null]);

        return new UserResource($request->user()->fresh());
    }
}
