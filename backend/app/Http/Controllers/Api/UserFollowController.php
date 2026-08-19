<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;

class UserFollowController extends Controller
{
    public function follow(Request $request, string $username)
    {
        $user = User::findByHandle($username);
        abort_if(! $user, 404);
        abort_if($user->id === $request->user()->id, 422, 'Нельзя подписаться на себя.');

        $request->user()->following()->syncWithoutDetaching([$user->id]);

        return response()->json(['is_followed' => true]);
    }

    public function unfollow(Request $request, string $username)
    {
        $user = User::findByHandle($username);
        abort_if(! $user, 404);
        $request->user()->following()->detach($user->id);

        return response()->json(['is_followed' => false]);
    }

    /**
     * Активность друзей: кто что слушает (кэш от logPlay).
     *
     * Друг — это взаимная подписка; на кого подписан я один, тот идёт
     * отдельным списком «Вы подписаны», а те, кто подписан на меня и ждёт
     * ответной подписки, — в «Подписаны на вас».
     */
    public function friendsActivity(Request $request)
    {
        $me = $request->user();

        $followingIds = $me->following()->pluck('users.id');
        $followerIds = $me->followers()->pluck('users.id');
        $friendIds = $followingIds->intersect($followerIds)->values();

        $users = User::whereIn('id', $followingIds->merge($followerIds)->unique())->get();

        $shape = fn (User $u, string $relation) => [
            'user' => [
                'id' => $u->id,
                'name' => $u->name,
                'username' => $u->username,
                'avatar_url' => (new \App\Http\Resources\UserResource($u))->toArray($request)['avatar_url'],
            ],
            'relation' => $relation, // friend | following | follower
            // Что слушает — показываем только друзьям (взаимная подписка).
            'activity' => $relation === 'friend' ? Cache::get("activity:user:{$u->id}") : null,
        ];

        $friends = $users->whereIn('id', $friendIds)->map(fn ($u) => $shape($u, 'friend'))->values();
        $following = $users->whereIn('id', $followingIds->diff($friendIds))
            ->map(fn ($u) => $shape($u, 'following'))->values();
        $followers = $users->whereIn('id', $followerIds->diff($friendIds))
            ->map(fn ($u) => $shape($u, 'follower'))->values();

        return response()->json([
            // data — как раньше (друзья с активностью), чтобы не ломать клиентов
            'data' => $friends,
            'friends' => $friends,
            'following' => $following,
            'followers' => $followers,
        ]);
    }
}
