<?php

namespace App\Services\Lyrics;

use Illuminate\Http\Client\PendingRequest;
use Illuminate\Support\Facades\Http;
use RuntimeException;

/**
 * Клиент lrclib.net для операций, требующих publish-токена: жалоба на текст
 * (/api/flag) и публикация своего текста (/api/publish).
 *
 * Токен выдаётся не сервером, а вычисляется: LRCLIB присылает challenge
 * {prefix, target}, и нужно подобрать nonce, у которого sha256(prefix.nonce)
 * лексикографически меньше target. Это proof-of-work против спама; при
 * текущей сложности перебор занимает около секунды.
 */
class LrclibClient
{
    public function __construct(
        private readonly string $base = 'https://lrclib.net',
        private readonly int $maxNonce = 80_000_000,
    ) {
    }

    private function http(): PendingRequest
    {
        return Http::timeout(25)
            // force_ip_resolve: в докер-сети нет IPv6-маршрута наружу.
            ->withOptions(['force_ip_resolve' => 'v4'])
            ->withHeaders(['User-Agent' => 'Sukify/1.0 (https://sukify.nepalimsya.ru)']);
    }

    /** Пожаловаться на некорректный текст в записи LRCLIB. */
    public function flag(int $lrclibTrackId, ?string $content = null): void
    {
        $payload = ['trackId' => $lrclibTrackId];
        if ($content !== null && $content !== '') {
            $payload['content'] = $content;
        }

        $res = $this->http()
            ->withHeaders(['X-Publish-Token' => $this->publishToken()])
            ->post($this->base.'/api/flag', $payload);

        if (! $res->successful()) {
            throw new RuntimeException('LRCLIB отклонил жалобу: '.$res->body());
        }
    }

    /**
     * Опубликовать текст на LRCLIB.
     *
     * @param  array{trackName:string,artistName:string,albumName?:string,duration:int,plainLyrics?:string,syncedLyrics?:string}  $meta
     */
    public function publish(array $meta): void
    {
        $payload = [
            'trackName' => $meta['trackName'],
            'artistName' => $meta['artistName'],
            'albumName' => $meta['albumName'] ?? '',
            'duration' => (int) $meta['duration'],
            'plainLyrics' => $meta['plainLyrics'] ?? '',
            'syncedLyrics' => $meta['syncedLyrics'] ?? '',
        ];

        $res = $this->http()
            ->withHeaders(['X-Publish-Token' => $this->publishToken()])
            ->post($this->base.'/api/publish', $payload);

        if (! $res->successful()) {
            throw new RuntimeException('LRCLIB отклонил публикацию: '.$res->body());
        }
    }

    /** Найти id записи LRCLIB для трека — он нужен, чтобы на неё пожаловаться. */
    public function findTrackId(string $trackName, string $artistName, ?string $albumName, int $durationSeconds): ?int
    {
        $params = array_filter([
            'track_name' => $trackName,
            'artist_name' => $artistName,
            'album_name' => $albumName,
            'duration' => $durationSeconds ?: null,
        ]);

        $res = $this->http()->get($this->base.'/api/get', $params);
        if ($res->successful() && isset($res->json()['id'])) {
            return (int) $res->json()['id'];
        }

        // Точного совпадения нет — берём ближайший по длительности из поиска.
        $res = $this->http()->get($this->base.'/api/search', [
            'track_name' => $trackName,
            'artist_name' => $artistName,
        ]);
        if (! $res->successful()) {
            return null;
        }

        $best = collect($res->json())
            ->sortBy(fn ($c) => abs(($c['duration'] ?? 0) - $durationSeconds))
            ->first();

        return isset($best['id']) ? (int) $best['id'] : null;
    }

    /** «prefix:nonce» — доказательство работы, которое ждёт LRCLIB. */
    public function publishToken(): string
    {
        $res = $this->http()->post($this->base.'/api/request-challenge');
        if (! $res->successful()) {
            throw new RuntimeException('LRCLIB не выдал challenge: '.$res->body());
        }

        $prefix = (string) $res->json()['prefix'];
        $target = hex2bin((string) $res->json()['target']);

        for ($nonce = 0; $nonce < $this->maxNonce; $nonce++) {
            // Сырые байты: сравнение строк здесь и есть сравнение чисел.
            if (strcmp(hash('sha256', $prefix.$nonce, true), $target) < 0) {
                return $prefix.':'.$nonce;
            }
        }

        throw new RuntimeException('Не удалось подобрать nonce для LRCLIB.');
    }
}
