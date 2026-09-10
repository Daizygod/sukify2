<?php

namespace App\Services\Deezer;

use Illuminate\Http\Client\Pool;
use Illuminate\Http\Client\PendingRequest;
use Illuminate\Support\Facades\Http;

/**
 * Открытое API Deezer — источник обложек и 30-секундных превью для треков,
 * заведённых импортом из Spotify. Ключей не требует.
 *
 * Из Spotify приезжает только текст, а Deezer по «артист + название» отдаёт
 * обложку альбома 1000×1000, mp3-превью, длительность и позицию в альбоме —
 * ровно то, чего экспорту не хватает. Лимит — 50 запросов за 5 секунд на IP,
 * поэтому запросы летят пачками с паузой между ними.
 */
class DeezerClient
{
    public const BATCH = 20;

    public function __construct(private readonly string $base = 'https://api.deezer.com')
    {
    }

    private function http(): PendingRequest
    {
        return Http::timeout(20)
            ->retry(2, 500, throw: false)
            // force_ip_resolve: в докер-сети нет IPv6-маршрута наружу.
            ->withOptions(['force_ip_resolve' => 'v4'])
            ->withHeaders(['User-Agent' => 'Sukify/1.0 (+https://sukify.nepalimsya.ru)']);
    }

    /**
     * Поиск сразу для пачки треков — параллельными запросами.
     *
     * @param  list<array{artist:string,title:string,duration_ms:?int}>  $wanted
     * @return array<int,array|null> тот же порядок, что и на входе
     */
    public function searchBatch(array $wanted): array
    {
        if (! $wanted) {
            return [];
        }

        $out = $this->searchPass($wanted, fn (array $w) => trim($w['artist'].' '.$this->stripNoise($w['title'])));

        // Второй заход для промахов: «Rather Be (feat. Jess Glynne) - The
        // Magician Remix» Deezer по полному названию не находит, а по «Rather
        // Be» — сразу. Ремиксы, «- Prod. …», «- Slowed» и remaster-хвосты
        // ловятся именно так.
        $retry = [];
        foreach ($wanted as $i => $w) {
            $short = $this->core($w['title']);
            if ($out[$i] === null && $short !== '' && $short !== $this->stripNoise($w['title'])) {
                $retry[$i] = $w;
            }
        }
        if ($retry) {
            foreach ($this->searchPass($retry, fn (array $w) => trim($w['artist'].' '.$this->core($w['title']))) as $i => $match) {
                $out[$i] = $match;
            }
        }

        return $out;
    }

    /**
     * @param  array<int,array{artist:string,title:string,duration_ms:?int}>  $wanted
     * @param  callable(array):string  $queryFor
     * @return array<int,array|null>
     */
    private function searchPass(array $wanted, callable $queryFor): array
    {
        $responses = Http::pool(function (Pool $pool) use ($wanted, $queryFor) {
            $requests = [];
            foreach ($wanted as $i => $w) {
                $requests[] = $pool->as((string) $i)
                    ->timeout(20)
                    ->withOptions(['force_ip_resolve' => 'v4'])
                    ->withHeaders(['User-Agent' => 'Sukify/1.0 (+https://sukify.nepalimsya.ru)'])
                    ->get($this->base.'/search', ['q' => $queryFor($w), 'limit' => 5]);
            }

            return $requests;
        });

        $out = [];
        foreach ($wanted as $i => $w) {
            $response = $responses[(string) $i] ?? null;
            $data = $response instanceof \Illuminate\Http\Client\Response && $response->successful()
                ? $response->json()
                : null;

            if (isset($data['error'])) {
                // Скорее всего «Quota limit exceeded» — пусть решает вызывающий.
                $out[$i] = ['__throttled' => true];

                continue;
            }

            $out[$i] = $this->pickBest($data['data'] ?? [], $w);
        }

        return $out;
    }

    /** Одиночный поиск — для повторов и ручной проверки. */
    public function searchTrack(string $artist, string $title, ?int $durationMs = null): ?array
    {
        $want = ['artist' => $artist, 'title' => $title, 'duration_ms' => $durationMs];

        foreach ([$this->stripNoise($title), $this->core($title)] as $variant) {
            $response = $this->http()->get($this->base.'/search', [
                'q' => trim($artist.' '.$variant),
                'limit' => 5,
            ]);
            if (! $response->successful()) {
                continue;
            }
            if ($match = $this->pickBest($response->json('data') ?? [], $want)) {
                return $match;
            }
        }

        return null;
    }

    /**
     * Найти альбом по «артист + название».
     *
     * Нужен там, где ни один трек релиза не нашёлся по имени (редкие релизы,
     * лики, ремиксы): сам альбом в Deezer при этом часто есть, и обложку
     * взять неоткуда больше.
     */
    public function searchAlbum(string $artist, string $album): ?array
    {
        $response = $this->http()->get($this->base.'/search/album', [
            'q' => trim($artist.' '.$this->core($album)),
            'limit' => 5,
        ]);
        if (! $response->successful()) {
            return null;
        }

        $wantAlbum = $this->norm($this->core($album));
        $wantArtist = $this->norm($artist);

        foreach ($response->json('data') ?? [] as $candidate) {
            if (! is_array($candidate) || empty($candidate['id'])) {
                continue;
            }
            $title = $this->norm($this->core((string) ($candidate['title'] ?? '')));
            $name = $this->norm((string) ($candidate['artist']['name'] ?? ''));
            $artistOk = $name !== '' && ($name === $wantArtist
                || str_contains($name, $wantArtist) || str_contains($wantArtist, $name));

            if ($artistOk && $title === $wantAlbum) {
                return $candidate;
            }
        }

        return null;
    }

    /** Карточка альбома: дата выхода, тип релиза, число треков. */
    public function album(int $id): ?array
    {
        $response = $this->http()->get($this->base.'/album/'.$id);
        $data = $response->successful() ? $response->json() : null;

        return is_array($data) && ! isset($data['error']) ? $data : null;
    }

    /**
     * Скачать пачку файлов параллельно. Превью и обложки лежат на CDN, а не на
     * api.deezer.com, так что лимит запросов к API это не трогает.
     *
     * @param  array<int|string,string>  $urls
     * @return array<int|string,string|null> тела файлов по тем же ключам
     */
    public function fetchBatch(array $urls): array
    {
        if (! $urls) {
            return [];
        }

        $keys = array_keys($urls);
        $responses = Http::pool(function (Pool $pool) use ($urls) {
            $requests = [];
            foreach ($urls as $key => $url) {
                $requests[] = $pool->as((string) $key)
                    ->timeout(45)
                    ->withOptions(['force_ip_resolve' => 'v4'])
                    ->withHeaders(['User-Agent' => 'Sukify/1.0 (+https://sukify.nepalimsya.ru)'])
                    ->get($url);
            }

            return $requests;
        });

        $out = [];
        foreach ($keys as $key) {
            $response = $responses[(string) $key] ?? null;
            $body = $response instanceof \Illuminate\Http\Client\Response && $response->successful()
                ? $response->body()
                : null;
            $out[$key] = $body !== null && strlen($body) >= 1024 ? $body : null;
        }

        return $out;
    }

    /** Скачать файл (обложку или превью) на локальный диск. */
    public function download(string $url, string $path): bool
    {
        $response = $this->http()->timeout(60)->get($url);
        if (! $response->successful()) {
            return false;
        }

        $body = $response->body();
        if ($body === '' || strlen($body) < 1024) {
            return false;
        }

        return file_put_contents($path, $body) !== false;
    }

    // -- Выбор лучшего совпадения ------------------------------------------

    /**
     * @param  list<array>  $candidates
     * @param  array{artist:string,title:string,duration_ms:?int}  $want
     */
    private function pickBest(array $candidates, array $want): ?array
    {
        $wantTitle = $this->norm($this->stripNoise($want['title']));
        $wantCore = $this->norm($this->core($want['title']));
        $wantArtist = $this->norm($want['artist']);
        $wantDuration = $want['duration_ms'] ?? null;

        $best = null;
        $bestScore = 0;

        foreach ($candidates as $c) {
            if (! is_array($c) || empty($c['id'])) {
                continue;
            }

            $raw = (string) ($c['title'] ?? '');
            $title = $this->norm($this->stripNoise($raw));
            $core = $this->norm($this->core($raw));
            $artist = $this->norm((string) ($c['artist']['name'] ?? ''));

            $score = match (true) {
                $title === $wantTitle => 3,
                // «Hotel California - 2013 Remaster» против «Hotel California
                // (2013 Remaster)»: скобки и тире — одно и то же уточнение.
                $core !== '' && $core === $wantCore => 2.5,
                $title !== '' && (str_starts_with($title, $wantTitle) || str_starts_with($wantTitle, $title)) => 2,
                $title !== '' && (str_contains($title, $wantTitle) || str_contains($wantTitle, $title)) => 1,
                default => 0,
            };
            if ($score === 0) {
                continue;
            }

            $score += match (true) {
                $artist === $wantArtist => 3,
                $artist !== '' && (str_contains($artist, $wantArtist) || str_contains($wantArtist, $artist)) => 2,
                default => 0,
            };

            if ($wantDuration && ! empty($c['duration'])) {
                $score += abs(((int) $c['duration']) * 1000 - $wantDuration) <= 5000 ? 1 : -1;
            }

            // При равном счёте берём популярнее — у Deezer это rank.
            $score += min(0.9, ((int) ($c['rank'] ?? 0)) / 1_000_000);

            if ($score > $bestScore) {
                $bestScore = $score;
                $best = $c;
            }
        }

        // 4 — это «название совпало хотя бы частично И артист узнан».
        return $bestScore >= 4 ? $best : null;
    }

    /** Убирает «(feat. …)», «- Remastered 2011» и прочий хвост из названия. */
    private function stripNoise(string $title): string
    {
        $title = preg_replace('/[\(\[][^\)\]]*(?:feat\.?|ft\.?|featuring|with)[^\)\]]*[\)\]]/iu', ' ', $title) ?? $title;
        $title = preg_replace('/\s+-\s+(?:remaster(?:ed)?|mono|stereo|live|radio edit|single version)\b.*$/iu', '', $title) ?? $title;

        return trim($title);
    }

    /**
     * Голое название: без скобок и без всего, что идёт после « - ».
     * «Angry toy$ - Prod. Ray Qwa» → «Angry toy$», «Rather Be (feat. Jess
     * Glynne) - The Magician Remix» → «Rather Be».
     */
    private function core(string $title): string
    {
        $title = preg_replace('/[\(\[][^\)\]]*[\)\]]/u', ' ', $title) ?? $title;
        $title = preg_split('/\s+-\s+/u', $title)[0] ?? $title;

        return trim(preg_replace('/\s+/u', ' ', $title) ?? $title);
    }

    private function norm(string $s): string
    {
        $s = mb_strtolower(trim($s));
        $s = preg_replace('/[«»"\x{2018}\x{2019}\x{201C}\x{201D}`´\'\.,!\?]/u', '', $s) ?? $s;

        return trim(preg_replace('/\s+/u', ' ', $s) ?? $s);
    }
}
