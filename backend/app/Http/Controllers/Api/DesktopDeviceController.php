<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\DesktopDevice;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

/**
 * Привязка настольного компаньона по device code flow (RFC 8628 в миниатюре).
 *
 * Компаньон не умеет принимать редиректы и не должен знать пароль: он просит
 * пару кодов, показывает короткий человеку, а длинный меняет на токен Sanctum,
 * как только человек подтвердил привязку в браузере.
 */
class DesktopDeviceController extends Controller
{
    /** Неподтверждённый код живёт 10 минут. */
    private const CODE_TTL = 600;

    /** Как часто компаньону разрешено опрашивать обмен, секунд. */
    private const POLL_INTERVAL = 3;

    /** Без O/0/I/1 — код диктуют и перепечатывают руками. */
    private const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

    /** Шаг 1 (компаньон, без авторизации): получить пару кодов. */
    public function requestCode(Request $request)
    {
        $data = $request->validate([
            'name' => ['nullable', 'string', 'max:80'],
            'platform' => ['nullable', 'string', 'max:32'],
        ]);

        // Подчищаем свой мусор: просроченные неподтверждённые коды.
        DesktopDevice::whereNull('approved_at')
            ->whereNotNull('expires_at')
            ->where('expires_at', '<', now())
            ->delete();

        $device = DesktopDevice::create([
            'device_code' => Str::random(64),
            'user_code' => $this->uniqueUserCode(),
            'name' => $data['name'] ?? 'Sukify Desktop',
            'platform' => $data['platform'] ?? null,
            'expires_at' => now()->addSeconds(self::CODE_TTL),
        ]);

        $base = rtrim((string) config('app.frontend_url'), '/');

        return response()->json([
            'device_code' => $device->device_code,
            'user_code' => $device->user_code,
            'verification_uri' => $base.'/link-device',
            'verification_uri_complete' => $base.'/link-device?code='.$device->user_code,
            'interval' => self::POLL_INTERVAL,
            'expires_in' => self::CODE_TTL,
        ]);
    }

    /** Шаг 2 (браузер, авторизован): что за устройство просит доступ. */
    public function pending(Request $request, string $userCode)
    {
        $device = $this->findPending($userCode);

        abort_unless($device, 404, 'Код не найден или истёк.');

        return response()->json(['data' => [
            'user_code' => $device->user_code,
            'name' => $device->name,
            'platform' => $device->platform,
            'expires_at' => $device->expires_at,
        ]]);
    }

    /** Шаг 3 (браузер, авторизован): подтвердить привязку. */
    public function approve(Request $request)
    {
        $data = $request->validate([
            'user_code' => ['required', 'string', 'max:16'],
        ]);

        $device = $this->findPending($data['user_code']);

        abort_unless($device, 404, 'Код не найден или истёк.');

        $device->update([
            'user_id' => $request->user()->id,
            'approved_at' => now(),
        ]);

        return response()->json(['data' => ['name' => $device->name]]);
    }

    /**
     * Шаг 4 (компаньон, без авторизации): обменять device_code на токен.
     * Коды ошибок — как в RFC 8628, чтобы компаньону было что различать.
     */
    public function token(Request $request)
    {
        $data = $request->validate([
            'device_code' => ['required', 'string', 'max:64'],
        ]);

        $device = DesktopDevice::where('device_code', $data['device_code'])->first();

        if (! $device) {
            return response()->json(['error' => 'invalid_grant'], 400);
        }

        if ($device->isExpired()) {
            $device->delete();

            return response()->json(['error' => 'expired_token'], 400);
        }

        if ($device->isPending()) {
            return response()->json(['error' => 'authorization_pending'], 400);
        }

        $user = $device->user;

        if (! $user || $user->is_banned) {
            $device->delete();

            return response()->json(['error' => 'access_denied'], 400);
        }

        $token = $user->createToken('Sukify Desktop — '.$device->name, ['desktop']);

        // Коды одноразовые: после обмена строка остаётся только как запись
        // о привязанном устройстве (и как ручка, чтобы отозвать токен).
        $device->update([
            'device_code' => null,
            'user_code' => null,
            'expires_at' => null,
            'token_id' => $token->accessToken->getKey(),
            'last_seen_at' => now(),
        ]);

        return response()->json([
            'token' => $token->plainTextToken,
            'device_id' => $device->id,
            'user' => [
                'id' => $user->id,
                'name' => $user->name,
                'username' => $user->username,
            ],
        ]);
    }

    /**
     * Пульс компаньона (Bearer): отмечаем «на связи» и отдаём настройки
     * трансляции, чтобы переключатель в вебе доезжал без перезапуска.
     */
    public function heartbeat(Request $request)
    {
        $data = $request->validate([
            'device_id' => ['nullable', 'integer'],
            // Кого компаньон видит в запущенном клиенте Discord (из READY).
            'discord_id' => ['nullable', 'string', 'max:32'],
        ]);

        $user = $request->user();

        if (! empty($data['device_id'])) {
            DesktopDevice::where('id', $data['device_id'])
                ->where('user_id', $user->id)
                ->update(['last_seen_at' => now()]);
        }

        $connection = $user->discordConnection;

        return response()->json(['data' => [
            // Нет привязки — трансляцию не глушим: RPC от неё не зависит,
            // привязка нужна только для сверки «тот ли аккаунт».
            'presence_enabled' => $connection?->presence_enabled ?? true,
            'show_when_paused' => $connection?->show_when_paused ?? false,
            'show_button' => $connection?->show_button ?? true,
            'show_cover' => $connection?->show_cover ?? true,
            'linked_discord_id' => $connection?->discord_id,
            'matches_linked_account' => $connection === null
                || empty($data['discord_id'])
                || $connection->discord_id === $data['discord_id'],
        ]]);
    }

    /** Список привязанных устройств для настроек. */
    public function index(Request $request)
    {
        $devices = DesktopDevice::where('user_id', $request->user()->id)
            ->whereNotNull('approved_at')
            ->orderByDesc('last_seen_at')
            ->get()
            ->map(fn (DesktopDevice $d) => [
                'id' => $d->id,
                'name' => $d->name,
                'platform' => $d->platform,
                'approved_at' => $d->approved_at,
                'last_seen_at' => $d->last_seen_at,
            ]);

        return response()->json(['data' => $devices]);
    }

    /** Отвязать устройство: убираем и токен, иначе оно продолжит работать. */
    public function destroy(Request $request, DesktopDevice $device)
    {
        abort_unless($device->user_id === $request->user()->id, 403);

        if ($device->token_id) {
            $request->user()->tokens()->whereKey($device->token_id)->delete();
        }

        $device->delete();

        return response()->json(['data' => null]);
    }

    private function findPending(string $userCode): ?DesktopDevice
    {
        return DesktopDevice::where('user_code', strtoupper(trim($userCode)))
            ->whereNull('approved_at')
            ->where('expires_at', '>', now())
            ->first();
    }

    private function uniqueUserCode(): string
    {
        do {
            $code = $this->randomChunk(4).'-'.$this->randomChunk(4);
        } while (DesktopDevice::where('user_code', $code)->exists());

        return $code;
    }

    private function randomChunk(int $length): string
    {
        $out = '';
        for ($i = 0; $i < $length; $i++) {
            $out .= self::ALPHABET[random_int(0, strlen(self::ALPHABET) - 1)];
        }

        return $out;
    }
}
