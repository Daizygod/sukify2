<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\UserResource;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;

class AuthController extends Controller
{
    /** Аккаунты заводит админ: {@see \App\Http\Controllers\Admin\UserController::store}. */
    public function registrationClosed(): JsonResponse
    {
        return response()->json([
            'message' => 'Регистрация только по приглашению — попроси аккаунт у администратора.',
        ], 403);
    }

    public function login(Request $request): JsonResponse
    {
        $data = $request->validate([
            'email' => ['required', 'string', 'email'],
            'password' => ['required', 'string'],
            'remember' => ['sometimes', 'boolean'],
        ]);

        if (! Auth::guard('web')->attempt(
            ['email' => $data['email'], 'password' => $data['password']],
            $request->boolean('remember')
        )) {
            throw ValidationException::withMessages([
                'email' => ['The provided credentials are incorrect.'],
            ]);
        }

        if (Auth::user()->is_banned) {
            Auth::guard('web')->logout();
            throw ValidationException::withMessages([
                'email' => ['Your account has been suspended.'],
            ]);
        }

        $request->session()->regenerate();

        return (new UserResource(Auth::user()))->response();
    }

    public function logout(Request $request): JsonResponse
    {
        Auth::guard('web')->logout();
        $request->session()->invalidate();
        $request->session()->regenerateToken();

        return response()->json(['message' => 'Logged out.']);
    }

    public function me(Request $request): UserResource
    {
        return new UserResource($request->user());
    }

    /** Отображаемое имя и @handle (ссылка на профиль). */
    public function updateProfile(Request $request): UserResource
    {
        $user = $request->user();

        $data = $request->validate([
            'name' => ['sometimes', 'string', 'max:255'],
            'username' => [
                'sometimes',
                'string',
                'alpha_dash',
                'min:3',
                'max:30',
                'unique:users,username,'.$user->id,
            ],
        ]);

        $user->update($data);

        return new UserResource($user->fresh());
    }
}
