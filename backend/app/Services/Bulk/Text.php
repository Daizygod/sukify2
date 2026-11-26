<?php

namespace App\Services\Bulk;

use Illuminate\Support\Str;
use Transliterator;

/**
 * Нормализация названий и имён для сопоставления файлов с каталогом.
 *
 * Теги в файлах и названия из Spotify пишут одно и то же по-разному: «ё» и «е»,
 * «feat.» в скобках или через тире, «- Remastered 2011». Сравниваем всегда
 * нормализованные строки.
 */
final class Text
{
    /**
     * Пометки версии. Если у файла и у трека они разные — это другая запись
     * (ремикс, живое, минус), и автоматически класть файл нельзя. Remaster,
     * radio edit, explicit — не в счёт: это та же песня.
     */
    private const VARIANTS = [
        'remix' => 're-?mix|rmx|ремикс|rework|bootleg|flip',
        'live' => 'live|лайв|концерт|concert',
        'acoustic' => 'acoustic|акустика|акустическая|unplugged',
        'instrumental' => 'instrumental|инструментал|минус|karaoke|караоке|minus',
        'slowed' => 'slowed|reverb|замедлен\w*|sped up|speed up|ускорен\w*|nightcore',
        'acapella' => 'a cappella|acapella|акапелла',
        'demo' => 'demo|демо',
        'clean' => 'clean|censored|цензур\w*',
        'extended' => 'extended',
    ];

    /** Названия, которые у одного артиста встречаются на разных альбомах. */
    private const GENERIC_TITLES = [
        'intro', 'outro', 'interlude', 'skit', 'prelude', 'bonus', 'bonus track',
        'интро', 'аутро', 'интерлюдия', 'скит', 'вступление', 'бонус',
    ];

    /** Нижний регистр, «ё» → «е», без знаков препинания и лишних пробелов. */
    public static function norm(?string $s): string
    {
        $raw = mb_strtolower(trim((string) $s));
        $s = strtr($raw, ['ё' => 'е', '&' => ' and ', '+' => ' and ']);
        $s = preg_replace('/[^\p{L}\p{N}]+/u', ' ', $s) ?? $s;
        $s = trim(preg_replace('/\s+/u', ' ', $s) ?? $s);

        // «!!!» или «#» — пусть хоть так, лишь бы не пустая строка.
        return $s !== '' ? $s : $raw;
    }

    /**
     * Голое название: без скобок, без хвоста после « - » и без «feat. …».
     * «Rather Be (feat. Jess Glynne) - The Magician Remix» → «rather be».
     */
    public static function core(?string $title): string
    {
        $t = (string) $title;
        $t = preg_replace('/[\(\[\{][^\)\]\}]*[\)\]\}]/u', ' ', $t) ?? $t;
        $t = preg_split('/\s+[-–—]\s+/u', $t)[0] ?? $t;
        $t = preg_replace('/\s(?:feat|ft|featuring|при уч|при участии)\.?\s.*$/iu', '', $t) ?? $t;

        $core = self::norm($t);

        return $core !== '' ? $core : self::norm($title);
    }

    /** Латиницей: «Скриптонит» и «Skriptonit» — одно и то же. */
    public static function translit(string $s): string
    {
        static $tr = false;
        if ($tr === false) {
            $tr = class_exists(Transliterator::class)
                ? Transliterator::create('Any-Latin; Latin-ASCII; Lower()')
                : null;
        }

        $out = $tr ? ($tr->transliterate($s) ?: $s) : Str::ascii($s);

        return self::norm($out);
    }

    /** @return list<string> пометки версии из названия (remix, live…) */
    public static function variants(?string $title): array
    {
        $t = mb_strtolower((string) $title);
        $found = [];
        foreach (self::VARIANTS as $key => $pattern) {
            if (preg_match('/(?<![\p{L}\p{N}])(?:'.$pattern.')(?![\p{L}\p{N}])/u', $t)) {
                $found[] = $key;
            }
        }

        return $found;
    }

    public static function isGeneric(string $core): bool
    {
        return in_array($core, self::GENERIC_TITLES, true);
    }

    /** Похожесть двух нормализованных строк, 0…1 (по латинице — levenshtein считает байты). */
    public static function similarity(string $a, string $b): float
    {
        $a = substr(self::translit($a), 0, 200);
        $b = substr(self::translit($b), 0, 200);
        if ($a === '' || $b === '') {
            return 0.0;
        }
        if ($a === $b) {
            return 1.0;
        }

        return 1 - levenshtein($a, $b) / max(strlen($a), strlen($b));
    }

    /**
     * «Markul, Oxxxymiron» → два артиста. Режем осторожно: «Tyler, The
     * Creator» и «AC/DC» — один, поэтому сначала спрашиваем каталог.
     *
     * @param  callable(string):bool  $known  есть ли такой артист в каталоге
     * @return list<string>
     */
    public static function splitArtists(?string $raw, callable $known): array
    {
        $raw = trim((string) $raw);
        if ($raw === '') {
            return [];
        }
        if ($known($raw)) {
            return [$raw];
        }

        // Надёжные разделители: многозначные теги и «feat.».
        $parts = preg_split('/\s*(?:;|\x{0000}|\s(?:feat|ft|featuring)\.?\s)\s*/iu', $raw) ?: [$raw];

        $out = [];
        foreach ($parts as $part) {
            $part = trim($part);
            if ($part === '') {
                continue;
            }
            if ($known($part)) {
                $out[] = $part;

                continue;
            }

            // Запятая и « x » — так подписывают совместки; «&» и «/» режем,
            // только если каталог узнаёт хоть одну половину.
            $soft = preg_split('/\s*,\s*|\s+[xх×]\s+/u', $part) ?: [$part];
            if (count($soft) > 1) {
                array_push($out, ...$soft);

                continue;
            }
            $hard = preg_split('/\s+&\s+|\s*\/\s*|\s+vs\.?\s+/iu', $part) ?: [$part];
            if (count($hard) > 1 && array_filter($hard, $known)) {
                array_push($out, ...$hard);

                continue;
            }
            $out[] = $part;
        }

        $seen = [];
        $clean = [];
        foreach ($out as $name) {
            $name = trim($name, " \t\n\r\0\x0B()[]");
            $key = self::norm($name);
            if ($name === '' || isset($seen[$key])) {
                continue;
            }
            $seen[$key] = true;
            $clean[] = mb_substr($name, 0, 250);
        }

        return $clean;
    }

    /** «Daddy (feat. Rich The Kid)» → «Rich The Kid». */
    public static function featuredFromTitle(?string $title): ?string
    {
        if (preg_match('/[\(\[]\s*(?:feat\.?|ft\.?|featuring|with|при участии)\s+([^\)\]]+)[\)\]]/iu', (string) $title, $m)) {
            return trim($m[1]);
        }

        return null;
    }

    /**
     * Что можно понять из пути, если тегов нет: «Артист/Альбом/01 - Трек.mp3»,
     * «Артист - Альбом (2019)/01. Трек.flac», «Артист - Трек.mp3».
     *
     * @return array{artist:?string, album:?string, title:string, track:?int, artist_from:?string}
     */
    public static function parsePath(string $path): array
    {
        $segments = array_values(array_filter(
            explode('/', str_replace('\\', '/', $path)),
            fn ($s) => $s !== '' && $s !== '.'
        ));
        $file = array_pop($segments) ?? '';
        $name = trim(pathinfo($file, PATHINFO_FILENAME));
        $name = str_replace('_', ' ', $name);

        $track = null;
        // Номер в начале: «01 - », «01. », «1-03 », «007 ». «7 Rings» — не номер.
        if (preg_match('/^(?:\d{1,2}[-.])?(\d{1,3})\s*[-.)]\s*(.+)$/u', $name, $m)
            || preg_match('/^(0\d{1,2})\s+(.+)$/u', $name, $m)) {
            $track = (int) $m[1];
            $name = trim($m[2]);
        }

        $artist = null;
        $artistFrom = null;
        $title = $name;
        if (preg_match('/^(.+?)\s+[-–—]\s+(.+)$/u', $name, $m)) {
            $artist = trim($m[1]);
            $title = trim($m[2]);
            $artistFrom = 'filename';
        }

        $album = null;
        $albumDir = $segments ? end($segments) : null;
        if ($albumDir !== null && $albumDir !== false) {
            $albumDir = self::cleanFolder($albumDir);
            // «Артист - Альбом» в одной папке.
            if (preg_match('/^(.+?)\s+[-–—]\s+(.+)$/u', $albumDir, $m)) {
                $artist ??= trim($m[1]);
                $artistFrom ??= 'folder';
                $album = trim($m[2]);
            } else {
                $album = $albumDir;
                if (count($segments) >= 2 && $artist === null) {
                    $artist = self::cleanFolder($segments[count($segments) - 2]);
                    $artistFrom = 'folder';
                }
            }
        }

        return [
            'artist' => $artist !== '' ? $artist : null,
            'album' => $album !== '' ? $album : null,
            'title' => $title !== '' ? $title : $name,
            'track' => $track,
            'artist_from' => $artistFrom,
        ];
    }

    /** «2019 - Альбом [FLAC]» → «Альбом». */
    private static function cleanFolder(string $dir): string
    {
        // Только пометки формата: «(Bitter Sweet)» — это название, его не трогаем.
        $d = preg_replace(
            '/[\(\[][^\)\]]*(?<![a-z])(?:flac|mp3|aac|alac|\d{3}\s?kbps|320|256|web|cd\s?\d*|vinyl|\d{2}\s?-?bit|hi-?res|lossless|\d+(?:[.,]\d+)?\s?khz)(?![a-z])[^\)\]]*[\)\]]/iu',
            ' ',
            $dir
        ) ?? $dir;
        $d = preg_replace('/^\s*(?:19|20)\d{2}\s*[-–—.]\s*/u', '', $d) ?? $d;
        $d = preg_replace('/[\(\[]\s*(?:19|20)\d{2}\s*[\)\]]/u', ' ', $d) ?? $d;

        return trim(preg_replace('/\s+/u', ' ', $d) ?? $d);
    }

    /**
     * Теги в cp1251, записанные как Latin-1: «Êèíî» вместо «Кино». ffprobe
     * отдаёт их как есть — чиним, если по-русски выходит осмысленнее.
     */
    public static function fixMojibake(?string $s): ?string
    {
        if ($s === null || $s === '') {
            return $s;
        }
        if (! preg_match('/[\x{00C0}-\x{00FF}]{2,}/u', $s) || preg_match('/\p{Cyrillic}/u', $s)) {
            return $s;
        }

        $bytes = @mb_convert_encoding($s, 'ISO-8859-1', 'UTF-8');
        $fixed = $bytes !== false ? @iconv('Windows-1251', 'UTF-8//IGNORE', $bytes) : false;
        if (! is_string($fixed) || $fixed === '') {
            return $s;
        }

        // «Größe» тоже даёт пару таких букв подряд, но по-русски из неё выходит
        // каша вперемешку с латиницей. Настоящие кракозябры — почти сплошь кириллица.
        $cyr = preg_match_all('/\p{Cyrillic}/u', $fixed);
        $latin = preg_match_all('/[a-z]/iu', $fixed);

        return $cyr >= 3 && $cyr / max(1, $cyr + $latin) >= 0.6 ? $fixed : $s;
    }
}
