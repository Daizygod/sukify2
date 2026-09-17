<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\DiscordConnection;
use App\Services\Discord\DiscordOAuth;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Str;
use Throwable;

class DiscordController extends Controller
{
    /** Сколько живёт state между редиректом и колбэком. */
    private const STATE_TTL = 600;

    public function __construct(private DiscordOAuth $oauth)
    {
    }

    /** Текущая привязка + настройки трансляции. */
    public function show(Request $request)
    {
        return response()->json([
            'data' => $this->format($request->user()->discordConnection),
            'configured' => $this->oauth->isConfigured(),
        ]);
    }

    /**
     * Ссылка на экран согласия Discord. SPA уводит браузер по ней сама —
     * возвращать 302 на XHR бессмысленно, axios отработает его прозрачно.
     */
    public function redirect(Request $request)
    {
        abort_unless($this->oauth->isConfigured(), 503, 'Discord не настроен на сервере.');

        // state в кэше, а не в сессии: колбэк приходит как обычный переход
        // браузера на домен API, и полагаться там на SPA-сессию не хочется.
        $state = Str::random(40);
        Cache::put($this->stateKey($state), $request->user()->id, self::STATE_TTL);

        return response()->json(['url' => $this->oauth->authorizeUrl($state)]);
    }

    /** Колбэк Discord: обмен кода, сохранение профиля, возврат в настройки SPA. */
    public function callback(Request $request)
    {
        $state = (string) $request->query('state', '');
        $userId = Cache::pull($this->stateKey($state));

        if (! $userId || ! $request->filled('code')) {
            return $this->backToSettings('error');
        }

        try {
            $tokens = $this->oauth->exchangeCode((string) $request->query('code'));
            $profile = $this->oauth->me($tokens['access_token']);
        } catch (Throwable) {
            return $this->backToSettings('error');
        }

        // Один аккаунт Discord — один пользователь Sukify: иначе двое могли бы
        // «подтверждать» одну и ту же активность.
        $taken = DiscordConnection::where('discord_id', $profile['id'])
            ->where('user_id', '!=', $userId)
            ->exists();

        if ($taken) {
            return $this->backToSettings('taken');
        }

        DiscordConnection::updateOrCreate(['user_id' => $userId], [
            'discord_id' => $profile['id'],
            'username' => $profile['username'],
            'global_name' => $profile['global_name'] ?? null,
            'avatar' => $profile['avatar'] ?? null,
            'access_token' => $tokens['access_token'],
            'refresh_token' => $tokens['refresh_token'] ?? null,
            'token_expires_at' => now()->addSeconds((int) ($tokens['expires_in'] ?? 0)),
        ]);

        return $this->backToSettings('connected');
    }

    /** Переключатели трансляции — их читает компаньон перед каждым апдейтом. */
    public function update(Request $request)
    {
        $data = $request->validate([
            'presence_enabled' => ['sometimes', 'boolean'],
            'show_when_paused' => ['sometimes', 'boolean'],
            'show_button' => ['sometimes', 'boolean'],
            'show_cover' => ['sometimes', 'boolean'],
        ]);

        $connection = $request->user()->discordConnection;
        abort_unless($connection, 404, 'Discord не привязан.');

        $connection->update($data);

        return response()->json(['data' => $this->format($connection->fresh())]);
    }

    public function destroy(Request $request)
    {
        $connection = $request->user()->discordConnection;

        if ($connection) {
            if ($connection->access_token) {
                // Не критично, если Discord недоступен: строку всё равно удаляем.
                try {
                    $this->oauth->revoke($connection->access_token);
                } catch (Throwable) {
                }
            }
            $connection->delete();
        }

        return response()->json(['data' => null]);
    }

    private function format(?DiscordConnection $connection): ?array
    {
        if (! $connection) {
            return null;
        }

        return [
            'discord_id' => $connection->discord_id,
            'username' => $connection->username,
            'display_name' => $connection->displayName(),
            'avatar_url' => $connection->avatarUrl(),
            'presence_enabled' => $connection->presence_enabled,
            'show_when_paused' => $connection->show_when_paused,
            'show_button' => $connection->show_button,
            'show_cover' => $connection->show_cover,
            'connected_at' => $connection->created_at,
        ];
    }

    private function backToSettings(string $status)
    {
        $base = rtrim((string) config('app.frontend_url'), '/');

        return redirect()->away("{$base}/settings?discord={$status}");
    }

    private function stateKey(string $state): string
    {
        return 'discord:oauth:'.$state;
    }
}
