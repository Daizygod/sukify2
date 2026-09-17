<?php

namespace App\Services\Discord;

use Illuminate\Support\Facades\Http;
use RuntimeException;

/**
 * Discord OAuth2 вручную, без Socialite — как и JWT в CentrifugoService:
 * поток простой (три запроса), а лишний пакет в composer.json тянуть незачем.
 *
 * Привязка нужна только для отображения («подключён как @user») и сверки с тем
 * аккаунтом, который видит настольный компаньон. Сама активность ставится
 * локально через IPC и токенов отсюда не использует.
 */
class DiscordOAuth
{
    private const API = 'https://discord.com/api/v10';

    /** Минимум прав: кто ты. Ни гильдий, ни почты не просим. */
    private const SCOPES = 'identify';

    public function isConfigured(): bool
    {
        return (bool) config('services.discord.client_id')
            && (bool) config('services.discord.client_secret');
    }

    public function authorizeUrl(string $state): string
    {
        return 'https://discord.com/oauth2/authorize?'.http_build_query([
            'client_id' => config('services.discord.client_id'),
            'redirect_uri' => config('services.discord.redirect'),
            'response_type' => 'code',
            'scope' => self::SCOPES,
            'state' => $state,
            // Всегда показываем экран согласия: иначе смена аккаунта в Discord
            // молча привязывает старый.
            'prompt' => 'consent',
        ]);
    }

    /** @return array{access_token:string,refresh_token:string,expires_in:int} */
    public function exchangeCode(string $code): array
    {
        return $this->token([
            'grant_type' => 'authorization_code',
            'code' => $code,
            'redirect_uri' => config('services.discord.redirect'),
        ]);
    }

    /** @return array{access_token:string,refresh_token:string,expires_in:int} */
    public function refresh(string $refreshToken): array
    {
        return $this->token([
            'grant_type' => 'refresh_token',
            'refresh_token' => $refreshToken,
        ]);
    }

    /** Профиль владельца токена: id, username, global_name, avatar. */
    public function me(string $accessToken): array
    {
        $response = Http::withToken($accessToken)
            ->acceptJson()
            ->get(self::API.'/users/@me');

        if (! $response->successful()) {
            throw new RuntimeException('Discord: не удалось получить профиль.');
        }

        return $response->json();
    }

    /** Отзыв токена при отвязке — чтобы приложение исчезло из списка у пользователя. */
    public function revoke(string $token): void
    {
        Http::asForm()->post(self::API.'/oauth2/token/revoke', [
            'client_id' => config('services.discord.client_id'),
            'client_secret' => config('services.discord.client_secret'),
            'token' => $token,
        ]);
    }

    private function token(array $payload): array
    {
        $response = Http::asForm()
            ->acceptJson()
            ->post(self::API.'/oauth2/token', $payload + [
                'client_id' => config('services.discord.client_id'),
                'client_secret' => config('services.discord.client_secret'),
            ]);

        if (! $response->successful()) {
            throw new RuntimeException('Discord: обмен кода на токен не удался.');
        }

        return $response->json();
    }
}
