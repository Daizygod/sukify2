<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\Request;
use Inertia\Inertia;

class UserController extends Controller
{
    public function index(Request $request)
    {
        $users = User::query()
            ->when($request->search, fn ($q, $s) => $q->where('name', 'ilike', "%{$s}%")->orWhere('email', 'ilike', "%{$s}%"))
            ->withCount('playlists')
            ->orderByDesc('created_at')
            ->paginate(25)
            ->withQueryString()
            ->through(fn ($u) => [
                'id' => $u->id,
                'name' => $u->name,
                'email' => $u->email,
                'is_admin' => (bool) $u->is_admin,
                'is_banned' => (bool) $u->is_banned,
                'playlists_count' => $u->playlists_count,
                'created_at' => $u->created_at?->toDateString(),
            ]);

        return Inertia::render('Users/Index', [
            'users' => $users,
            'filters' => $request->only('search'),
        ]);
    }

    /** Выдать/снять права админа. Себя разжаловать нельзя. */
    public function toggleAdmin(Request $request, User $user)
    {
        if ($user->id === $request->user()->id) {
            return back()->with('error', 'You cannot change your own admin rights.');
        }

        // is_admin намеренно не mass-assignable — меняем явно.
        $user->forceFill(['is_admin' => ! $user->is_admin])->save();

        return back()->with('success', $user->is_admin
            ? "{$user->name} is now an admin."
            : "Admin rights revoked from {$user->name}.");
    }

    public function toggleBan(Request $request, User $user)
    {
        if ($user->is_admin) {
            return back()->with('error', 'You cannot ban an admin.');
        }

        // is_banned/banned_at не mass-assignable — меняем явно.
        $user->forceFill([
            'is_banned' => ! $user->is_banned,
            'banned_at' => $user->is_banned ? null : now(),
        ])->save();

        return back()->with('success', $user->is_banned ? 'User banned.' : 'User unbanned.');
    }
}
