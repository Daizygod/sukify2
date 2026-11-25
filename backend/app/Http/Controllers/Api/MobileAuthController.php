<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\UserResource;
use App\Models\DesktopDevice;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Validation\ValidationException;
use Laravel\Sanctum\PersonalAccessToken;

/**
 * Вход из приложения для Android: вместо cookie-сессии SPA — токен Sanctum.
 *
 * Каждый вход заводит строку в desktop_devices (platform = Android): так
 * телефон виден в настройках веба рядом с компьютерами и отвязывается той же
 * кнопкой — DesktopDeviceController::destroy заодно удаляет и токен.
 */
class MobileAuthController extends Controller
{
    public function login(Request $request): JsonResponse
    {
        $data = $request->validate([
            'email' => ['required', 'string', 'email'],
            'password' => ['required', 'string'],
            'device_name' => ['nullable', 'string', 'max:80'],
        ]);

        $user = User::where('email', $data['email'])->first();

        if (! $user || ! Hash::check($data['password'], $user->password)) {
            throw ValidationException::withMessages([
                'email' => ['Неверная почта или пароль.'],
            ]);
        }

        if ($user->is_banned) {
            throw ValidationException::withMessages([
                'email' => ['Аккаунт заблокирован.'],
            ]);
        }

        return $this->issueToken($request, $user, $data['device_name'] ?? null);
    }

    /** Выход с этого телефона: гасим его токен и убираем из списка устройств. */
    public function logout(Request $request): JsonResponse
    {
        $token = $request->user()?->currentAccessToken();

        if ($token instanceof PersonalAccessToken) {
            DesktopDevice::where('token_id', $token->getKey())->delete();
            $token->delete();
        }

        return response()->json(['message' => 'Logged out.']);
    }

    private function issueToken(Request $request, User $user, ?string $deviceName): JsonResponse
    {
        $name = trim((string) $deviceName) ?: 'Android';
        $token = $user->createToken('Sukify Android — '.$name, ['mobile']);

        $device = DesktopDevice::create([
            'user_id' => $user->id,
            'name' => $name,
            'platform' => 'Android',
            'approved_at' => now(),
            'last_seen_at' => now(),
            'token_id' => $token->accessToken->getKey(),
        ]);

        // UserResource показывает почту и is_admin только «себе» — а в этом
        // запросе пользователя ещё нет, он появится со следующим.
        $request->setUserResolver(fn () => $user);

        return response()->json([
            'token' => $token->plainTextToken,
            'device_id' => $device->id,
            'user' => (new UserResource($user))->resolve($request),
        ]);
    }
}
