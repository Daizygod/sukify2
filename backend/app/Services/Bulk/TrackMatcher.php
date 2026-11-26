<?php

namespace App\Services\Bulk;

use Illuminate\Support\Facades\DB;

/**
 * Ищет для файла трек каталога.
 *
 * Каталог целиком в памяти (у пользователя тысячи треков, это мегабайты), и
 * каждый файл получает список кандидатов с баллами 0–100:
 *  - название: точно 50, без скобок и хвостов 45, латиницей 40, похоже 25–35;
 *  - артист: совпал 35, латиницей 30, одно в другом 20, не совпал −20;
 *  - альбом: до 10; длительность (только у треков с полным звуком): +5 / −25;
 *  - разные пометки версии (remix, live, минус…) — −40, у альбома — −15.
 *
 * От {@see AUTO} кладём сами, от {@see REVIEW} — спрашиваем человека.
 */
class TrackMatcher
{
    public const AUTO = 80;

    public const REVIEW = 55;

    /** @var array<int, array> id => сведения о треке */
    private array $tracks = [];

    /** @var array<string, list<int>> */
    private array $byCore = [];

    /** @var array<string, list<int>> */
    private array $byTranslit = [];

    /** @var array<string, list<int>> нормализованный артист => треки */
    private array $byArtist = [];

    /** @var array<string, true> */
    private array $knownArtists = [];

    public function __construct()
    {
        $this->load();
    }

    private function load(): void
    {
        foreach (DB::table('artists')->select('name')->cursor() as $a) {
            $this->knownArtists[Text::norm($a->name)] = true;
        }

        $names = [];
        $links = DB::table('track_artist')
            ->join('artists', 'artists.id', '=', 'track_artist.artist_id')
            ->select('track_artist.track_id', 'artists.name')
            ->cursor();
        foreach ($links as $row) {
            $names[$row->track_id][] = $row->name;
        }

        $rows = DB::table('tracks')
            ->leftJoin('releases', 'releases.id', '=', 'tracks.release_id')
            ->leftJoin('artists as ra', 'ra.id', '=', 'releases.artist_id')
            ->select(
                'tracks.id', 'tracks.title', 'tracks.duration_ms', 'tracks.preview_only',
                'tracks.audio_stream_path', 'tracks.processing_status', 'tracks.demo_audio',
                'releases.title as release_title', 'ra.name as release_artist',
            )
            ->orderBy('tracks.id')
            ->cursor();

        foreach ($rows as $r) {
            $this->add((int) $r->id, [
                'title' => (string) $r->title,
                'artists' => array_merge($names[$r->id] ?? [], $r->release_artist ? [$r->release_artist] : []),
                'release' => (string) ($r->release_title ?? ''),
                'duration_ms' => $r->duration_ms ? (int) $r->duration_ms : null,
                // Полная версия уже есть — превью, демо-тон и пустышки не в счёт.
                'full' => $r->audio_stream_path && ! $r->preview_only && ! $r->demo_audio
                    && $r->processing_status === 'ready',
            ]);
        }
    }

    /** Добавить трек в индекс — и при загрузке, и когда трек только что завели. */
    public function add(int $id, array $t): void
    {
        $artists = array_values(array_unique(array_filter(array_map([Text::class, 'norm'], $t['artists']))));
        $core = Text::core($t['title']);

        $info = [
            'id' => $id,
            'norm' => Text::norm($t['title']),
            'core' => $core,
            'core_tr' => Text::translit($core),
            'variants' => $this->variantKey($t['title']),
            'album_variants' => $this->variantKey($t['release']),
            'artists' => $artists,
            'artists_tr' => array_map([Text::class, 'translit'], $artists),
            'release' => Text::norm($t['release']),
            'release_core' => Text::core($t['release']),
            'duration_ms' => $t['duration_ms'],
            'full' => (bool) $t['full'],
        ];

        $this->tracks[$id] = $info;
        $this->byCore[$core][] = $id;
        $this->byTranslit[$info['core_tr']][] = $id;
        foreach ($artists as $a) {
            $this->byArtist[$a][] = $id;
            $this->knownArtists[$a] = true;
        }
    }

    public function isKnownArtist(string $name): bool
    {
        return isset($this->knownArtists[Text::norm($name)]);
    }

    public function hasFullAudio(int $trackId): bool
    {
        return (bool) ($this->tracks[$trackId]['full'] ?? false);
    }

    /** Поставили файл — для следующих файлов той же загрузки у трека уже полная версия. */
    public function markFull(int $trackId): void
    {
        if (isset($this->tracks[$trackId])) {
            $this->tracks[$trackId]['full'] = true;
        }
    }

    /**
     * Сведения о файле в том виде, в каком их сравнивает матчер.
     *
     * @param  array  $tags  теги из {@see TagReader}
     */
    public function describe(array $tags): array
    {
        $title = (string) ($tags['title'] ?? '');
        $artists = $this->artistsOf($tags);
        $album = (string) ($tags['album'] ?? '');
        $core = Text::core($title);
        $norm = array_map([Text::class, 'norm'], $artists);

        return [
            'title' => $title,
            'norm' => Text::norm($title),
            'core' => $core,
            'core_tr' => Text::translit($core),
            'variants' => $this->variantKey($title),
            'album_variants' => $album !== '' ? $this->variantKey($album) : null,
            'artists' => $norm,
            'artists_tr' => array_map([Text::class, 'translit'], $norm),
            'album' => Text::norm($album),
            'album_core' => Text::core($album),
            'duration_ms' => isset($tags['duration_ms']) ? (int) $tags['duration_ms'] : null,
        ];
    }

    /**
     * Все артисты файла: из тега артиста (с разбором совместок), альбомного
     * артиста и «feat.» в названии. Первый — основной.
     *
     * @return list<string>
     */
    public function artistsOf(array $tags): array
    {
        $known = fn (string $name) => $this->isKnownArtist($name);
        $list = Text::splitArtists($tags['artist'] ?? null, $known);

        $albumArtist = $tags['album_artist'] ?? null;
        if ($albumArtist !== null) {
            // Альбомный артист среди исполнителей трека — пусть он и будет первым.
            foreach ($list as $i => $name) {
                if (Text::norm($name) === Text::norm($albumArtist)) {
                    array_splice($list, $i, 1);
                    array_unshift($list, $name);
                    break;
                }
            }
            if (! $list) {
                $list = Text::splitArtists($albumArtist, $known);
            }
        }

        foreach (Text::splitArtists(Text::featuredFromTitle($tags['title'] ?? null), $known) as $feat) {
            if (! in_array(Text::norm($feat), array_map([Text::class, 'norm'], $list), true)) {
                $list[] = $feat;
            }
        }

        return $list;
    }

    /**
     * Кандидаты для файла, лучшие сверху.
     *
     * @return list<array{id:int, score:int}>
     */
    public function candidates(array $file): array
    {
        if ($file['core'] === '') {
            return [];
        }

        $ids = array_merge($this->byCore[$file['core']] ?? [], $this->byTranslit[$file['core_tr']] ?? []);

        // Опечатки и разночтения в названии — только среди треков того же артиста.
        foreach ($file['artists'] as $artist) {
            foreach ($this->byArtist[$artist] ?? [] as $id) {
                if ($this->ratio($file['core_tr'], $this->tracks[$id]['core_tr']) >= 0.8) {
                    $ids[] = $id;
                }
            }
        }

        $out = [];
        foreach (array_unique($ids) as $id) {
            $score = $this->score($file, $this->tracks[$id]);
            if ($score > 0) {
                $out[] = ['id' => $id, 'score' => $score];
            }
        }
        usort($out, fn ($a, $b) => $b['score'] <=> $a['score'] ?: $a['id'] <=> $b['id']);

        return $out;
    }

    /**
     * Каким трекам из уверенных кандидатов отдать файл.
     *
     * Одна запись часто лежит в каталоге дважды — в сингле и в альбоме, и
     * полная версия нужна обоим. Но «Intro» у артиста на каждом альбоме своё:
     * такие названия кладём только в свой альбом.
     *
     * @param  list<array{id:int, score:int}>  $sure
     * @return list<int>
     */
    public function targets(array $file, array $sure): array
    {
        if (! $sure) {
            return [];
        }

        if (Text::isGeneric($file['core'])) {
            $own = array_values(array_filter($sure, fn ($c) => $file['album'] !== ''
                && $this->tracks[$c['id']]['release_core'] === $file['album_core']));

            return $own ? array_column($own, 'id') : [$sure[0]['id']];
        }

        return array_column($sure, 'id');
    }

    private function score(array $file, array $track): int
    {
        if ($file['norm'] === $track['norm']) {
            $s = 50;
        } elseif ($file['core'] === $track['core']) {
            $s = 45;
        } elseif ($file['core_tr'] === $track['core_tr']) {
            $s = 40;
        } else {
            $r = $this->ratio($file['core_tr'], $track['core_tr']);
            $s = $r >= 0.9 ? 35 : ($r >= 0.8 ? 25 : 0);
        }
        if ($s === 0) {
            return 0;
        }

        if ($file['artists']) {
            $a = 0;
            foreach ($file['artists'] as $i => $fa) {
                foreach ($track['artists'] as $j => $ta) {
                    if ($fa === $ta) {
                        $a = max($a, 35);
                    } elseif ($file['artists_tr'][$i] === $track['artists_tr'][$j]) {
                        $a = max($a, 30);
                    } elseif (mb_strlen($fa) >= 3 && mb_strlen($ta) >= 3 && (str_contains($fa, $ta) || str_contains($ta, $fa))) {
                        $a = max($a, 20);
                    }
                }
            }
            // Название то же, а артист другой — скорее всего, другая песня.
            $s += $a > 0 ? $a : -20;
        }

        if ($file['album'] !== '' && $track['release'] !== '') {
            if ($file['album'] === $track['release']) {
                $s += 10;
            } elseif ($file['album_core'] === $track['release_core']) {
                $s += 7;
            } elseif (str_contains($file['album'], $track['release']) || str_contains($track['release'], $file['album'])) {
                $s += 4;
            }
        }

        // У превью длительность — 30 секунд, сверять не с чем.
        if ($track['full'] && $track['duration_ms'] && $file['duration_ms']) {
            $d = abs($track['duration_ms'] - $file['duration_ms']);
            $s += $d <= 3000 ? 5 : ($d > 20000 ? -25 : 0);
        }

        // Ремикс против оригинала — другая запись. Если разница только в названии
        // альбома («Live in Moscow»), это скорее повод спросить, чем отказ.
        if ($file['variants'] !== $track['variants']) {
            $s -= 40;
        } elseif ($file['album_variants'] !== null && $file['album_variants'] !== $track['album_variants']) {
            $s -= 15;
        }

        return max(0, min(100, $s));
    }

    /** Похожесть двух строк латиницей, 0…1. */
    private function ratio(string $a, string $b): float
    {
        if ($a === '' || $b === '') {
            return 0.0;
        }
        if ($a === $b) {
            return 1.0;
        }
        $a = substr($a, 0, 200);
        $b = substr($b, 0, 200);

        return 1 - levenshtein($a, $b) / max(strlen($a), strlen($b));
    }

    private function variantKey(string $text): string
    {
        $v = Text::variants($text);
        sort($v);

        return implode(',', $v);
    }
}
