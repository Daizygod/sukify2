<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;
use Inertia\Inertia;

class UserController extends Controller
{
    public function index(Request $request)
    {
        $users = User::query()
            ->when($request->search, fn ($q, $s) => $q->where('name', 'ilike', "%{$s}%")
                ->orWhere('username', 'ilike', "%{$s}%")
                ->orWhere('email', 'ilike', "%{$s}%"))
            ->withCount('playlists')
            ->orderByDesc('created_at')
            ->paginate(25)
            ->withQueryString()
            ->through(fn ($u) => [
                'id' => $u->id,
                'name' => $u->name,
                'username' => $u->username,
                'email' => $u->email,
                'is_admin' => (bool) $u->is_admin,
                'is_banned' => (bool) $u->is_banned,
                'playlists_count' => $u->playlists_count,
                'created_at' => $u->created_at?->toDateString(),
            ]);

        return Inertia::render('Users/Index', [
            'users' => $users,
            'filters' => $request->only('search'),
            // Для текста приглашения: куда человеку идти входить.
            'siteUrl' => rtrim((string) config('app.frontend_url'), '/'),
        ]);
    }

    /**
     * Завести аккаунт: регистрации нет, приглашает админ. Пароль придумываем
     * сами и показываем один раз — в базе остаётся только хеш.
     */
    public function store(Request $request): RedirectResponse
    {
        // При входе почта сравнивается буква в букву — храним строчными.
        $request->merge(['email' => Str::lower((string) $request->input('email'))]);

        $data = $request->validate([
            'email' => ['required', 'string', 'email', 'max:255', 'unique:users,email'],
            'name' => ['required', 'string', 'max:255'],
        ], [], ['name' => 'nickname']);

        $password = User::generatePassword();

        $user = User::create([
            'name' => $data['name'],
            'username' => User::generateUsername($data['name'], $data['email']),
            'email' => $data['email'],
            'password' => Hash::make($password),
        ]);

        $user->settings(); // настройки воспроизведения по умолчанию

        return $this->showPasswordOnce($user, $password);
    }

    /** Новый пароль взамен потерянного. Админам — только через свой профиль. */
    public function resetPassword(User $user): RedirectResponse
    {
        if ($user->is_admin) {
            return back()->with('error', "You cannot reset an admin's password.");
        }

        $password = User::generatePassword();
        $user->update(['password' => Hash::make($password)]);

        return $this->showPasswordOnce($user, $password);
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

        // Телефоны и компаньоны ходят с токенами — их тоже гасим, иначе они
        // так и висели бы привязанными (сессию браузера рвёт not.banned).
        if ($user->is_banned) {
            $user->tokens()->delete();
            $user->desktopDevices()->delete();
        }

        return back()->with('success', $user->is_banned ? 'User banned.' : 'User unbanned.');
    }

    /**
     * Inertia::flash, а не обычный пропс: страница получает пароль один раз, и
     * в истории браузера он не остаётся.
     */
    private function showPasswordOnce(User $user, string $password): RedirectResponse
    {
        Inertia::flash('issued', [
            'name' => $user->name,
            'username' => $user->username,
            'email' => $user->email,
            'password' => $password,
        ]);

        return back();
    }
}
