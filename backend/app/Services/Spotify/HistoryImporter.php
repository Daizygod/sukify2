<?php

namespace App\Services\Spotify;

use App\Models\SpotifyImport;
use App\Models\User;
use Illuminate\Support\Facades\DB;

/**
 * Переносит историю прослушиваний из экспорта в imported_plays.
 *
 * Понимает оба формата, которые отдаёт Spotify:
 *  • Extended Streaming History — все годы, с ms_played, skipped, shuffle,
 *    платформой и spotify-uri (16 МБ архива разворачиваются в 200 МБ JSON,
 *    поэтому файлы читаются по одному и пишутся пачками);
 *  • StreamingHistory_music_*.json из Account Data — только последний год и
 *    четыре поля, время округлено до минуты.
 *
 * Дубли между архивами гасит отпечаток: минута + артист + название.
 */
class HistoryImporter
{
    private const CHUNK = 1000;

    public function __construct(
        private readonly User $user,
        private readonly SpotifyImport $import,
    ) {
    }

    /**
     * @param  callable(int,int):void  $onProgress  (обработано файлов, всего)
     * @return array{plays:int,duplicates:int,files:int,from:?string,to:?string,ms:int,extended:bool}
     */
    public function import(SpotifyArchive $archive, callable $onProgress): array
    {
        $source = $archive->historySource();
        $entries = $source['entries'];
        $total = count($entries);

        $inserted = 0;
        $seen = 0;
        $ms = 0;
        $from = null;
        $to = null;
        $buffer = [];

        foreach ($entries as $index => $entry) {
            $rows = $archive->read($entry);
            if (! is_array($rows)) {
                $onProgress($index + 1, $total);

                continue;
            }

            foreach ($rows as $row) {
                $play = $source['extended'] ? $this->fromExtended($row) : $this->fromShort($row);
                if ($play === null) {
                    continue;
                }

                $seen++;
                $ms += $play['ms_played'];
                $from = $from === null || $play['played_at'] < $from ? $play['played_at'] : $from;
                $to = $to === null || $play['played_at'] > $to ? $play['played_at'] : $to;

                $buffer[] = $play;
                if (count($buffer) >= self::CHUNK) {
                    $inserted += $this->flush($buffer);
                    $buffer = [];
                }
            }

            unset($rows);
            $onProgress($index + 1, $total);
        }

        $inserted += $this->flush($buffer);

        return [
            'plays' => $inserted,
            'duplicates' => max(0, $seen - $inserted),
            'files' => $total,
            'from' => $from,
            'to' => $to,
            'ms' => $ms,
            'extended' => $source['extended'],
        ];
    }

    /**
     * Проставить track_id тем прослушиваниям, чей трек всё-таки завёлся в
     * каталоге. Одним UPDATE … FROM, а не построчно: строк сотни тысяч.
     */
    public function linkToCatalog(): int
    {
        $byUri = DB::update(
            'update imported_plays set track_id = t.id
               from tracks t
              where t.spotify_uri = imported_plays.spotify_uri
                and imported_plays.user_id = ?
                and imported_plays.track_id is null',
            [$this->user->id]
        );

        // Локальные файлы и старый формат истории живут без uri — их цепляем
        // по «артист + название» через связку треков с артистами.
        $byName = DB::update(
            "update imported_plays set track_id = m.track_id
               from (
                    select distinct on (lower(a.name), lower(t.title)) t.id as track_id,
                           lower(a.name) as artist_key, lower(t.title) as title_key
                      from tracks t
                      join track_artist ta on ta.track_id = t.id and ta.role = 'main'
                      join artists a on a.id = ta.artist_id
                     order by lower(a.name), lower(t.title), t.id
               ) m
              where imported_plays.user_id = ?
                and imported_plays.track_id is null
                and lower(imported_plays.artist_name) = m.artist_key
                and lower(imported_plays.track_name) = m.title_key",
            [$this->user->id]
        );

        return $byUri + $byName;
    }

    /** @return array<string,mixed>|null */
    private function fromExtended(array $row): ?array
    {
        $title = $row['master_metadata_track_name'] ?? null;
        $artist = $row['master_metadata_album_artist_name'] ?? null;
        // Подкасты и аудиокниги в Sukify не переносим.
        if (! is_string($title) || $title === '' || ! is_string($artist) || $artist === '') {
            return null;
        }

        $ts = $this->timestamp((string) ($row['ts'] ?? ''));
        if ($ts === null) {
            return null;
        }

        return $this->play(
            $ts,
            (int) ($row['ms_played'] ?? 0),
            $artist,
            $title,
            $row['master_metadata_album_album_name'] ?? null,
            $row['spotify_track_uri'] ?? null,
            (bool) ($row['skipped'] ?? false),
            (bool) ($row['shuffle'] ?? false),
            $this->platform((string) ($row['platform'] ?? '')),
            is_string($row['reason_end'] ?? null) ? mb_substr($row['reason_end'], 0, 32) : null,
        );
    }

    /** @return array<string,mixed>|null */
    private function fromShort(array $row): ?array
    {
        $title = $row['trackName'] ?? null;
        $artist = $row['artistName'] ?? null;
        if (! is_string($title) || $title === '' || ! is_string($artist) || $artist === '') {
            return null;
        }

        // «2024-04-02 18:42» — всегда UTC, без указания зоны.
        $ts = $this->timestamp(str_replace(' ', 'T', (string) ($row['endTime'] ?? '')).':00Z');
        if ($ts === null) {
            return null;
        }

        return $this->play($ts, (int) ($row['msPlayed'] ?? 0), $artist, $title, null, null, false, false, null, null);
    }

    private function play(
        string $ts,
        int $msPlayed,
        string $artist,
        string $title,
        ?string $album,
        ?string $uri,
        bool $skipped,
        bool $shuffle,
        ?string $platform,
        ?string $reasonEnd,
    ): array {
        $artist = mb_substr(trim($artist), 0, 300);
        $title = mb_substr(trim($title), 0, 400);

        return [
            'user_id' => $this->user->id,
            'spotify_import_id' => $this->import->id,
            'played_at' => $ts,
            'ms_played' => max(0, $msPlayed),
            'artist_name' => $artist,
            'track_name' => $title,
            'album_name' => $album ? mb_substr(trim($album), 0, 400) : null,
            'spotify_uri' => $uri && str_starts_with($uri, 'spotify:track:') ? $uri : null,
            'skipped' => $skipped,
            'shuffle' => $shuffle,
            'platform' => $platform,
            'reason_end' => $reasonEnd,
            // Минута + имена: тот же прослух из Account Data и из Extended
            // History даёт один отпечаток, и второй раз он не вставится.
            'fingerprint' => md5($this->user->id.'|'.substr($ts, 0, 16).'|'.mb_strtolower($artist).'|'.mb_strtolower($title)),
        ];
    }

    /** ISO-8601 UTC или null. */
    private function timestamp(string $raw): ?string
    {
        if ($raw === '') {
            return null;
        }
        $time = strtotime($raw);

        return $time === false ? null : gmdate('Y-m-d H:i:s', $time);
    }

    /**
     * «Android OS 11 API 30 (samsung, SM-N970U1)» → android.
     * В экспорте платформа — свободная строка, в статистике нужен ярлык.
     */
    private function platform(string $raw): ?string
    {
        $raw = mb_strtolower($raw);

        return match (true) {
            $raw === '' => null,
            str_contains($raw, 'android') => 'android',
            str_contains($raw, 'windows') || str_contains($raw, 'win32') => 'windows',
            str_contains($raw, 'ios') || str_contains($raw, 'iphone') || str_contains($raw, 'ipad') => 'ios',
            str_contains($raw, 'osx') || str_contains($raw, 'os x') || str_contains($raw, 'mac') => 'mac',
            str_contains($raw, 'ps4') || str_contains($raw, 'ps5') || str_contains($raw, 'playstation') => 'playstation',
            str_contains($raw, 'xbox') => 'xbox',
            str_contains($raw, 'web') => 'web',
            str_contains($raw, 'cast') => 'cast',
            str_contains($raw, 'linux') => 'linux',
            str_contains($raw, 'partner') => 'partner',
            default => 'other',
        };
    }

    /** @param  list<array<string,mixed>>  $rows */
    private function flush(array $rows): int
    {
        if (! $rows) {
            return 0;
        }

        // Внутри пачки отпечатки тоже могут повториться — ON CONFLICT в
        // Postgres не разрешает дважды тронуть одну строку в одном INSERT.
        $unique = [];
        foreach ($rows as $row) {
            $unique[$row['fingerprint']] = $row;
        }

        return DB::table('imported_plays')->insertOrIgnore(array_values($unique));
    }
}
