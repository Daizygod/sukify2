<?php

namespace App\Services\Realtime;

use App\Models\User;
use Illuminate\Support\Facades\Http;

/**
 * Issues Centrifugo JWT tokens (HS256, hand-rolled — no extra dependency) and
 * publishes to channels via the server HTTP API.
 *
 * Channel conventions:
 *   - Device sync:  user:playback#{userId}   (user-limited; own devices only)
 *   - Jam session:  session:{sessionId}       (subscribe via signed token)
 */
class CentrifugoService
{
    public function connectionToken(User $user): string
    {
        return $this->jwt([
            'sub' => (string) $user->id,
            'exp' => time() + (int) config('centrifugo.connection_ttl'),
            'info' => [
                'name' => $user->name,
                'username' => $user->username,
            ],
        ]);
    }

    public function subscriptionToken(User $user, string $channel): string
    {
        return $this->jwt([
            'sub' => (string) $user->id,
            'channel' => $channel,
            'exp' => time() + (int) config('centrifugo.subscription_ttl'),
        ]);
    }

    public function playbackChannel(int $userId): string
    {
        return "user:playback#{$userId}";
    }

    public function sessionChannel(int $sessionId): string
    {
        return "session:{$sessionId}";
    }

    /** Publish a payload to a channel from the server. */
    public function publish(string $channel, array $data): bool
    {
        $response = Http::withHeaders([
            'X-API-Key' => (string) config('centrifugo.api_key'),
        ])->post(rtrim((string) config('centrifugo.api_endpoint'), '/').'/publish', [
            'channel' => $channel,
            'data' => $data,
        ]);

        return $response->successful();
    }

    // --- JWT (HS256) -------------------------------------------------------

    private function jwt(array $claims): string
    {
        $header = ['alg' => 'HS256', 'typ' => 'JWT'];

        $segments = [
            $this->b64(json_encode($header, JSON_UNESCAPED_SLASHES)),
            $this->b64(json_encode($claims, JSON_UNESCAPED_SLASHES)),
        ];

        $signingInput = implode('.', $segments);
        $signature = hash_hmac('sha256', $signingInput, (string) config('centrifugo.token_secret'), true);
        $segments[] = $this->b64($signature);

        return implode('.', $segments);
    }

    private function b64(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }
}
