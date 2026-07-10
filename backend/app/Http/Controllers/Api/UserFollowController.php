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
        $user = User::where('username', $username)->firstOrFail();
        abort_if($user->id === $request->user()->id, 422, 'Нельзя подписаться на себя.');

        $request->user()->following()->syncWithoutDetaching([$user->id]);

        return response()->json(['is_followed' => true]);
    }

    public function unfollow(Request $request, string $username)
    {
        $user = User::where('username', $username)->firstOrFail();
        $request->user()->following()->detach($user->id);

        return response()->json(['is_followed' => false]);
    }

    /** Активность друзей: кто из подписок что слушает (кэш от logPlay). */
    public function friendsActivity(Request $request)
    {
        $friends = $request->user()->following()->get();

        $items = $friends->map(function (User $f) {
            $activity = Cache::get("activity:user:{$f->id}");

            return [
                'user' => ['id' => $f->id, 'name' => $f->name, 'username' => $f->username],
                'activity' => $activity,
            ];
        });

        return response()->json(['data' => $items]);
    }
}
