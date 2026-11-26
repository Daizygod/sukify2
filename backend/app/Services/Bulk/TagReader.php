<?php

namespace App\Services\Bulk;

use App\Models\BulkUploadItem;
use Symfony\Component\Process\Process;

/**
 * Теги, формат и длительность файла — через ffprobe. Чего нет в тегах,
 * берём из пути («Артист/Альбом/01 - Трек.flac»).
 */
class TagReader
{
    /** Пустые и служебные значения тегов — всё равно что тега нет. */
    private const NO_ARTIST = [
        'unknown', 'unknown artist', 'неизвестный исполнитель', 'неизвестен',
        'various', 'various artists', 'va', 'сборник', 'артист',
    ];

    /**
     * @return array{ok:bool, error?:string, title?:string, artist?:?string, album?:?string,
     *   album_artist?:?string, track?:?int, disc?:?int, date?:?string, year?:?int, isrc?:?string,
     *   duration_ms?:?int, codec?:string, bit_rate?:?int, sample_rate?:?int, bits?:?int,
     *   lossless?:bool, has_cover?:bool, cover_codec?:?string, artist_from?:string}
     */
    public function read(string $file, string $relPath): array
    {
        $process = new Process([
            'ffprobe', '-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', $file,
        ]);
        $process->setTimeout(120);
        $process->run();

        $data = json_decode($process->getOutput(), true);
        if (! is_array($data)) {
            return ['ok' => false, 'error' => 'Не читается: '.mb_substr(trim($process->getErrorOutput()) ?: 'ffprobe ничего не вернул', 0, 300)];
        }

        $streams = $data['streams'] ?? [];
        $audio = null;
        $cover = null;
        foreach ($streams as $s) {
            if (($s['codec_type'] ?? null) === 'audio' && $audio === null) {
                $audio = $s;
            }
            if (($s['codec_type'] ?? null) === 'video' && ! empty($s['disposition']['attached_pic'])) {
                $cover ??= $s;
            }
        }
        if ($audio === null) {
            return ['ok' => false, 'error' => 'В файле нет звука'];
        }

        // Теги лежат то в контейнере (mp3, flac, m4a), то в потоке (ogg, opus).
        $tags = [];
        foreach ([$data['format']['tags'] ?? [], $audio['tags'] ?? []] as $set) {
            foreach ($set as $key => $value) {
                $tags[strtolower((string) $key)] ??= trim((string) $value);
            }
        }
        $tag = function (string ...$keys) use ($tags): ?string {
            foreach ($keys as $k) {
                $v = Text::fixMojibake($tags[$k] ?? null);
                if ($v !== null && trim($v) !== '') {
                    return mb_substr(preg_replace('/\s+/u', ' ', trim($v)) ?? $v, 0, 250);
                }
            }

            return null;
        };

        $title = $tag('title');
        $artist = $tag('artist', 'performer', 'artists');
        $albumArtist = $tag('album_artist', 'album artist', 'albumartist');
        $album = $tag('album');
        $artistFrom = $artist !== null ? 'tags' : null;

        if ($artist !== null && in_array(mb_strtolower($artist), self::NO_ARTIST, true)) {
            $artist = null;
            $artistFrom = null;
        }
        if ($albumArtist !== null && in_array(mb_strtolower($albumArtist), self::NO_ARTIST, true)) {
            $albumArtist = null;
        }

        // Бывает «Артист - Трек» целиком в названии, а артиста нет.
        if ($artist === null && $title !== null && preg_match('/^(.+?)\s+[-–—]\s+(.+)$/u', $title, $m)) {
            $artist = trim($m[1]);
            $title = trim($m[2]);
            $artistFrom = 'tags';
        }

        $fromPath = Text::parsePath($relPath);
        if ($title === null) {
            $title = $fromPath['title'];
        }
        if ($artist === null && $albumArtist !== null) {
            $artist = $albumArtist;
            $artistFrom = 'tags';
        }
        if ($artist === null && $fromPath['artist'] !== null) {
            $artist = $fromPath['artist'];
            $artistFrom = $fromPath['artist_from'];
        }
        $album ??= $fromPath['album'];

        $date = $tag('date', 'originaldate', 'year', 'tdrc', 'tyer');
        $year = $date !== null && preg_match('/(?:19|20)\d{2}/', $date, $y) ? (int) $y[0] : null;
        $fullDate = $date !== null && preg_match('/^((?:19|20)\d{2})-(\d{2})-(\d{2})/', $date, $d)
            && checkdate((int) $d[2], (int) $d[3], (int) $d[1]) ? $d[0] : null;

        $isrc = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', (string) $tag('tsrc', 'isrc')) ?? '');

        $codec = (string) ($audio['codec_name'] ?? '');
        $durationSec = (float) ($data['format']['duration'] ?? $audio['duration'] ?? 0);

        return [
            'ok' => true,
            'title' => $title,
            'artist' => $artist,
            'artist_from' => $artistFrom ?? 'none',
            'album_artist' => $albumArtist,
            'album' => $album,
            'track' => $this->number($tag('track', 'tracknumber')) ?? $fromPath['track'],
            'disc' => $this->number($tag('disc', 'discnumber')),
            'date' => $fullDate,
            'year' => $year,
            'isrc' => strlen($isrc) === 12 ? $isrc : null,
            'duration_ms' => $durationSec > 0 ? (int) round($durationSec * 1000) : null,
            'codec' => $codec,
            'bit_rate' => isset($audio['bit_rate']) ? (int) $audio['bit_rate']
                : (isset($data['format']['bit_rate']) ? (int) $data['format']['bit_rate'] : null),
            'sample_rate' => isset($audio['sample_rate']) ? (int) $audio['sample_rate'] : null,
            'bits' => (int) ($audio['bits_per_raw_sample'] ?? 0) ?: ((int) ($audio['bits_per_sample'] ?? 0) ?: null),
            'lossless' => in_array($codec, BulkUploadItem::LOSSLESS_CODECS, true) || str_starts_with($codec, 'pcm_'),
            'has_cover' => $cover !== null,
            'cover_codec' => $cover['codec_name'] ?? null,
        ];
    }

    /** «3/12» → 3. */
    private function number(?string $value): ?int
    {
        if ($value === null || ! preg_match('/^\s*(\d{1,3})/', $value, $m)) {
            return null;
        }

        return (int) $m[1] ?: null;
    }
}
