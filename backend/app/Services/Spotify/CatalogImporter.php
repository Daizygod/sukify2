<?php

namespace App\Services\Spotify;

use App\Jobs\GeneratePlaylistCollage;
use App\Models\Artist;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

/**
 * Переносит библиотеку и плейлисты из экспорта Spotify в каталог Sukify.
 *
 * В экспорте нет ничего, кроме текста: артист, альбом, название, spotify-uri.
 * Поэтому импорт именно СОЗДАЁТ каталог, а не ищет совпадения — в Sukify
 * заводятся артисты, релизы и треки-заготовки, а обложки и 30-секундное
 * превью догружает потом Deezer ({@see \App\Jobs\EnrichSpotifyTracks}).
 *
 * Всё пишется пачками: у обычного пользователя это 3 тысячи треков, 1300
 * артистов и 2200 релизов, построчные вставки заняли бы минуты.
 */
class CatalogImporter
{
    /** @var array<string,int> norm(имя) => artists.id */
    private array $artists = [];

    /** @var array<string,int> "artistId|norm(title)" => releases.id */
    private array $releases = [];

    /** @var array<string,int> spotify:track:… => tracks.id */
    private array $tracksByUri = [];

    /** @var array<string,int> "releaseId|norm(title)" => tracks.id */
    private array $tracksByTitle = [];

    /** @var array<string,true> занятые слаги — чтобы не ходить в БД на каждую строку */
    private array $artistSlugs = [];

    /** @var array<string,true> */
    private array $releaseSlugs = [];

    private int $createdArtists = 0;

    private int $createdReleases = 0;

    private int $createdTracks = 0;

    public function __construct(private readonly User $user)
    {
        $this->preload();
    }

    private function preload(): void
    {
        foreach (Artist::query()->select('id', 'name', 'slug')->cursor() as $a) {
            $this->artists[$this->norm($a->name)] ??= $a->id;
            $this->artistSlugs[$a->slug] = true;
        }
        foreach (Release::query()->select('id', 'artist_id', 'title', 'slug')->cursor() as $r) {
            $this->releases[$r->artist_id.'|'.$this->norm($r->title)] ??= $r->id;
            $this->releaseSlugs[$r->slug] = true;
        }
        foreach (Track::query()->select('id', 'release_id', 'title', 'spotify_uri')->cursor() as $t) {
            if ($t->spotify_uri) {
                $this->tracksByUri[$t->spotify_uri] ??= $t->id;
            }
            $this->tracksByTitle[$t->release_id.'|'.$this->norm($t->title)] ??= $t->id;
        }
    }

    /**
     * @param  array|null  $library  Содержимое YourLibrary.json
     * @param  list<array>  $playlistFiles  Содержимое Playlist*.json
     * @return array Сводка для UI
     */
    public function import(?array $library, array $playlistFiles): array
    {
        $library ??= [];

        // --- 1. Собираем всё, что придётся завести -------------------------
        $items = [];   // ключ => позиция экспорта
        $liked = [];   // порядок лайков, как в экспорте: сверху свежие

        foreach ($library['tracks'] ?? [] as $t) {
            $item = $this->item($t['artist'] ?? '', $t['album'] ?? '', $t['track'] ?? '', $t['uri'] ?? null);
            if ($item === null) {
                continue;
            }
            $items[$item['key']] ??= $item;
            $liked[] = $item['key'];
        }

        $playlists = [];
        foreach ($playlistFiles as $file) {
            foreach ($file['playlists'] ?? [] as $p) {
                $entry = [
                    'name' => $p['name'] ?? '',
                    'description' => $p['description'] ?? null,
                    'modified' => $p['lastModifiedDate'] ?? null,
                    'items' => [],
                ];
                foreach ($p['items'] ?? [] as $row) {
                    $t = $row['track'] ?? null;
                    if (! $t || ($t['trackName'] ?? null) === null) {
                        // Локальный файл внутри плейлиста — заводим по uri.
                        $local = $this->fromLocalUri($row['localTrack']['uri'] ?? null);
                        if ($local === null) {
                            continue;
                        }
                        $item = $this->item($local['artist'], $local['album'], $local['title'], null, true, $local['duration_ms']);
                    } else {
                        $item = $this->item(
                            $t['artistName'] ?? '',
                            $t['albumName'] ?? '',
                            $t['trackName'] ?? '',
                            $t['trackUri'] ?? null,
                        );
                    }
                    if ($item === null) {
                        continue;
                    }
                    $items[$item['key']] ??= $item;
                    $entry['items'][] = ['key' => $item['key'], 'added' => $row['addedDate'] ?? null];
                }
                $playlists[] = $entry;
            }
        }

        // Локальные файлы («other» в YourLibrary) — залитая пользователем
        // музыка без метаданных Spotify; у нас это треки с флагом unofficial.
        $localLiked = [];
        foreach ($library['other'] ?? [] as $uri) {
            $local = $this->fromLocalUri(is_string($uri) ? $uri : ($uri['uri'] ?? null));
            if ($local === null) {
                continue;
            }
            $item = $this->item($local['artist'], $local['album'], $local['title'], null, true, $local['duration_ms']);
            if ($item === null) {
                continue;
            }
            $items[$item['key']] ??= $item;
            $localLiked[] = $item['key'];
        }

        // Сохранённые альбомы сами по себе треков не дают, но артиста и релиз
        // завести надо — иначе «Медиатека → Альбомы» будет пустой.
        $savedAlbums = [];
        foreach ($library['albums'] ?? [] as $a) {
            $artist = $this->clean($a['artist'] ?? '', 250);
            $album = $this->clean($a['album'] ?? '', 250);
            if ($artist === '' || $album === '') {
                continue;
            }
            $savedAlbums[] = ['artist' => $artist, 'album' => $album, 'uri' => $a['uri'] ?? null];
        }

        $followed = [];
        foreach ($library['artists'] ?? [] as $a) {
            $name = $this->clean($a['name'] ?? '', 250);
            if ($name !== '') {
                $followed[] = $name;
            }
        }

        // --- 2. Артисты ----------------------------------------------------
        $names = [];
        foreach ($items as $i) {
            $names[] = $i['artist'];
            foreach ($i['featured'] as $f) {
                $names[] = $f;
            }
        }
        foreach ($savedAlbums as $a) {
            $names[] = $a['artist'];
        }
        $this->ensureArtists(array_merge($names, $followed));

        // --- 3. Релизы -----------------------------------------------------
        $releaseKeys = [];
        foreach ($items as $key => $i) {
            $artistId = $this->artists[$this->norm($i['artist'])] ?? null;
            if (! $artistId) {
                continue;
            }
            $items[$key]['artist_id'] = $artistId;
            $releaseKeys[] = [
                'artist_id' => $artistId,
                'title' => $i['album'],
                'artist' => $i['artist'],
                // Название альбома совпало с названием трека — это сингл.
                'type' => $this->norm($i['album']) === $this->norm($i['title']) ? 'single' : 'album',
            ];
        }
        foreach ($savedAlbums as $a) {
            if ($artistId = $this->artists[$this->norm($a['artist'])] ?? null) {
                $releaseKeys[] = ['artist_id' => $artistId, 'title' => $a['album'], 'artist' => $a['artist'], 'type' => 'album'];
            }
        }
        $this->ensureReleases($releaseKeys);

        // --- 4. Треки ------------------------------------------------------
        $this->ensureTracks($items);

        // --- 5. Лайки, подписки, альбомы, плейлисты ------------------------
        $likedIds = [];
        foreach (array_merge($liked, $localLiked) as $key) {
            if ($id = $this->trackId($items[$key] ?? null)) {
                $likedIds[] = $id;
            }
        }

        $stats = [
            'artists_created' => $this->createdArtists,
            'releases_created' => $this->createdReleases,
            'tracks_created' => $this->createdTracks,
            'liked_tracks' => $this->likeTracks($likedIds),
            'liked_albums' => $this->likeAlbums($savedAlbums),
            'followed_artists' => $this->followArtists($followed),
        ];

        $playlistStats = $this->importPlaylists($playlists, $items);

        return $stats + [
            'playlists' => $playlistStats['created'],
            'playlists_skipped' => $playlistStats['skipped'],
            'playlist_tracks' => $playlistStats['tracks'],
        ];
    }

    // -- Заведение сущностей ------------------------------------------------

    /** @param  list<string>  $names */
    private function ensureArtists(array $names): void
    {
        $missing = [];
        foreach ($names as $name) {
            $name = $this->clean($name, 250);
            $key = $this->norm($name);
            if ($key === '' || isset($this->artists[$key]) || isset($missing[$key])) {
                continue;
            }
            $missing[$key] = $name;
        }
        if (! $missing) {
            return;
        }

        $now = now();
        foreach (array_chunk($missing, 300, true) as $chunk) {
            $rows = [];
            foreach ($chunk as $name) {
                $rows[] = [
                    'name' => $name,
                    'slug' => $this->uniqueSlug($name, $this->artistSlugs, 'artist'),
                    'imported' => true,
                    'created_at' => $now,
                    'updated_at' => $now,
                ];
            }
            DB::table('artists')->insert($rows);
            $this->createdArtists += count($rows);

            foreach (DB::table('artists')->whereIn('name', array_values($chunk))->select('id', 'name')->get() as $row) {
                $this->artists[$this->norm($row->name)] ??= $row->id;
            }
        }
    }

    /** @param  list<array{artist_id:int,title:string,artist:string,type:string}>  $wanted */
    private function ensureReleases(array $wanted): void
    {
        $missing = [];
        foreach ($wanted as $w) {
            $key = $w['artist_id'].'|'.$this->norm($w['title']);
            if ($this->norm($w['title']) === '' || isset($this->releases[$key]) || isset($missing[$key])) {
                continue;
            }
            $missing[$key] = $w;
        }
        if (! $missing) {
            return;
        }

        $now = now();
        foreach (array_chunk($missing, 300, true) as $chunk) {
            $rows = [];
            foreach ($chunk as $w) {
                $rows[] = [
                    'artist_id' => $w['artist_id'],
                    'title' => $w['title'],
                    'slug' => $this->uniqueSlug($w['artist'].' '.$w['title'], $this->releaseSlugs, 'release'),
                    'type' => $w['type'],
                    'cover_status' => 'pending',
                    'imported' => true,
                    'created_at' => $now,
                    'updated_at' => $now,
                ];
            }
            DB::table('releases')->insert($rows);
            $this->createdReleases += count($rows);

            $found = DB::table('releases')
                ->whereIn('artist_id', array_values(array_unique(array_column($chunk, 'artist_id'))))
                ->whereIn('title', array_values(array_unique(array_column($chunk, 'title'))))
                ->select('id', 'artist_id', 'title')
                ->get();
            foreach ($found as $row) {
                $this->releases[$row->artist_id.'|'.$this->norm($row->title)] ??= $row->id;
            }
        }
    }

    /** @param  array<string,array>  $items */
    private function ensureTracks(array &$items): void
    {
        $missing = [];
        foreach ($items as $key => $i) {
            if (! empty($i['uri']) && isset($this->tracksByUri[$i['uri']])) {
                $items[$key]['track_id'] = $this->tracksByUri[$i['uri']];
            }

            $artistId = $i['artist_id'] ?? ($this->artists[$this->norm($i['artist'])] ?? null);
            $releaseId = $artistId ? ($this->releases[$artistId.'|'.$this->norm($i['album'])] ?? null) : null;
            if (! $releaseId) {
                continue;
            }
            $items[$key]['artist_id'] = $artistId;
            $items[$key]['release_id'] = $releaseId;

            if (isset($items[$key]['track_id'])) {
                continue;
            }
            $titleKey = $releaseId.'|'.$this->norm($i['title']);
            if (isset($this->tracksByTitle[$titleKey])) {
                $items[$key]['track_id'] = $this->tracksByTitle[$titleKey];

                continue;
            }
            $missing[$key] = $items[$key];
        }

        $now = now();
        foreach (array_chunk($missing, 300, true) as $chunk) {
            $rows = [];
            foreach ($chunk as $i) {
                $rows[] = [
                    'release_id' => $i['release_id'],
                    'title' => $i['title'],
                    'duration_ms' => $i['duration_ms'],
                    'processing_status' => 'pending',
                    'unofficial' => $i['local'],
                    'spotify_uri' => $i['uri'],
                    // Локальные файлы искать в Deezer бессмысленно.
                    'enrich_status' => $i['local'] ? 'notfound' : 'pending',
                    'created_at' => $now,
                    'updated_at' => $now,
                ];
            }
            DB::table('tracks')->insert($rows);
            $this->createdTracks += count($rows);

            $found = DB::table('tracks')
                ->whereIn('release_id', array_values(array_unique(array_column($chunk, 'release_id'))))
                ->select('id', 'release_id', 'title', 'spotify_uri')
                ->get();
            foreach ($found as $row) {
                if ($row->spotify_uri) {
                    $this->tracksByUri[$row->spotify_uri] ??= $row->id;
                }
                $this->tracksByTitle[$row->release_id.'|'.$this->norm($row->title)] ??= $row->id;
            }
        }

        // Порядка треков в альбоме экспорт не знает, а сортировка релиза идёт
        // по track_number — при сплошных NULL строки тасовались бы от запроса
        // к запросу. Нумеруем по порядку появления.
        $releaseIds = array_values(array_unique(array_column($missing, 'release_id')));
        foreach (array_chunk($releaseIds, 300) as $chunk) {
            $placeholders = implode(',', array_fill(0, count($chunk), '?'));
            DB::update(
                "update tracks t set track_number = s.rn
                   from (select id, row_number() over (partition by release_id order by id) rn
                           from tracks where release_id in ({$placeholders})) s
                  where t.id = s.id and t.track_number is null",
                $chunk
            );
        }

        // Привязка артистов: основной + вытащенные из «(feat. …)» в названии.
        $links = [];
        $releaseLinks = [];
        foreach ($items as $key => $i) {
            $trackId = $this->trackId($items[$key]);
            if (! $trackId || ! ($i['artist_id'] ?? null)) {
                continue;
            }
            $items[$key]['track_id'] = $trackId;

            $links[$trackId.'|'.$i['artist_id'].'|main'] =
                ['track_id' => $trackId, 'artist_id' => $i['artist_id'], 'role' => 'main', 'position' => 0];

            $position = 1;
            foreach ($i['featured'] as $f) {
                $featuredId = $this->artists[$this->norm($f)] ?? null;
                if ($featuredId && $featuredId !== $i['artist_id']) {
                    $links[$trackId.'|'.$featuredId.'|featured'] =
                        ['track_id' => $trackId, 'artist_id' => $featuredId, 'role' => 'featured', 'position' => $position++];
                }
            }

            if ($i['release_id'] ?? null) {
                $releaseLinks[$i['release_id'].'|'.$i['artist_id']] =
                    ['release_id' => $i['release_id'], 'artist_id' => $i['artist_id'], 'position' => 0];
            }
        }

        foreach (array_chunk(array_values($links), 500) as $chunk) {
            DB::table('track_artist')->insertOrIgnore($chunk);
        }
        foreach (array_chunk(array_values($releaseLinks), 500) as $chunk) {
            DB::table('release_artists')->insertOrIgnore($chunk);
        }
    }

    // -- Библиотека ---------------------------------------------------------

    /** @param  list<int>  $trackIds  В порядке экспорта: первый — самый свежий. */
    private function likeTracks(array $trackIds): int
    {
        $trackIds = array_values(array_unique($trackIds));
        if (! $trackIds) {
            return 0;
        }

        $already = DB::table('liked_tracks')
            ->where('user_id', $this->user->id)
            ->whereIn('track_id', $trackIds)
            ->pluck('track_id')
            ->flip();

        // Дат лайков в экспорте нет, но порядок в нём — от свежих к старым.
        // Раскладываем по минуте назад от момента импорта, чтобы «Любимые
        // треки» шли ровно как в Spotify.
        $base = now();
        $rows = [];
        foreach ($trackIds as $i => $id) {
            if (isset($already[$id])) {
                continue;
            }
            $rows[] = [
                'user_id' => $this->user->id,
                'track_id' => $id,
                'created_at' => $base->copy()->subMinutes($i),
            ];
        }
        if (! $rows) {
            return 0;
        }

        foreach (array_chunk($rows, 500) as $chunk) {
            DB::table('liked_tracks')->insertOrIgnore($chunk);
        }
        foreach (array_chunk(array_column($rows, 'track_id'), 1000) as $chunk) {
            DB::table('tracks')->whereIn('id', $chunk)->increment('likes_count');
        }

        return count($rows);
    }

    /** @param  list<array{artist:string,album:string,uri:?string}>  $albums */
    private function likeAlbums(array $albums): int
    {
        $ids = [];
        foreach ($albums as $a) {
            $artistId = $this->artists[$this->norm($a['artist'])] ?? null;
            $id = $artistId ? ($this->releases[$artistId.'|'.$this->norm($a['album'])] ?? null) : null;
            if ($id) {
                $ids[$id] = $a['uri'];
            }
        }
        if (! $ids) {
            return 0;
        }

        // Заодно проставляем релизам spotify-uri: пригодится для повторного
        // импорта и для точного поиска в Deezer.
        foreach ($ids as $releaseId => $uri) {
            if ($uri) {
                DB::table('releases')->where('id', $releaseId)->whereNull('spotify_uri')->update(['spotify_uri' => $uri]);
            }
        }

        $before = DB::table('liked_albums')->where('user_id', $this->user->id)->count();
        $rows = array_map(fn ($id) => [
            'user_id' => $this->user->id,
            'release_id' => $id,
            'created_at' => now(),
        ], array_keys($ids));

        foreach (array_chunk($rows, 500) as $chunk) {
            DB::table('liked_albums')->insertOrIgnore($chunk);
        }

        return DB::table('liked_albums')->where('user_id', $this->user->id)->count() - $before;
    }

    /** @param  list<string>  $names */
    private function followArtists(array $names): int
    {
        $rows = [];
        foreach ($names as $name) {
            if ($id = $this->artists[$this->norm($name)] ?? null) {
                $rows[$id] = ['user_id' => $this->user->id, 'artist_id' => $id, 'created_at' => now()];
            }
        }
        if (! $rows) {
            return 0;
        }

        $before = DB::table('followed_artists')->where('user_id', $this->user->id)->count();
        foreach (array_chunk(array_values($rows), 500) as $chunk) {
            DB::table('followed_artists')->insertOrIgnore($chunk);
        }

        return DB::table('followed_artists')->where('user_id', $this->user->id)->count() - $before;
    }

    /**
     * @param  list<array>  $playlists
     * @param  array<string,array>  $items
     * @return array{created:int,skipped:int,tracks:int}
     */
    private function importPlaylists(array $playlists, array $items): array
    {
        $created = 0;
        $skipped = 0;
        $tracksAdded = 0;

        foreach ($playlists as $p) {
            $title = $this->clean($p['name'] ?? '', 250);
            if ($title === '') {
                continue;
            }

            $existing = Playlist::where('user_id', $this->user->id)->where('title', $title)->first();
            if ($existing && ! $existing->imported) {
                // Одноимённый плейлист пользователь собрал сам — не трогаем.
                $skipped++;

                continue;
            }

            $rows = [];
            $position = 0;
            $earliest = null;
            foreach ($p['items'] as $row) {
                $trackId = $this->trackId($items[$row['key']] ?? null);
                if (! $trackId) {
                    continue;
                }
                $added = $this->date($row['added'] ?? null);
                if ($added && ($earliest === null || $added->lt($earliest))) {
                    $earliest = $added;
                }
                $rows[] = [
                    'track_id' => $trackId,
                    'position' => $position++,
                    'added_by_user_id' => $this->user->id,
                    'added_at' => $added ?? now(),
                ];
            }

            $modified = $this->date($p['modified'] ?? null) ?? now();
            $playlist = $existing ?? new Playlist();
            $playlist->forceFill([
                'user_id' => $this->user->id,
                'title' => $title,
                'description' => $p['description'] ? $this->clean((string) $p['description'], 500) : null,
                'is_public' => false,
                'imported' => true,
                'created_at' => $earliest ?? $modified,
                'updated_at' => $modified,
            ])->save();

            if ($existing) {
                DB::table('playlist_track')->where('playlist_id', $playlist->id)->delete();
            } else {
                $created++;
            }

            foreach (array_chunk($rows, 500) as $chunk) {
                DB::table('playlist_track')->insert(array_map(
                    fn ($r) => $r + ['playlist_id' => $playlist->id],
                    $chunk
                ));
            }
            $tracksAdded += count($rows);

            if ($rows) {
                // Первый коллаж — по тем обложкам, что уже приехали; финальный
                // пересоберётся, когда догрузка Deezer закончится.
                GeneratePlaylistCollage::dispatch($playlist->id)->delay(now()->addMinutes(3));
            }
        }

        return ['created' => $created, 'skipped' => $skipped, 'tracks' => $tracksAdded];
    }

    // -- Мелочи -------------------------------------------------------------

    /**
     * Нормализованная позиция экспорта или null, если строка пустая.
     *
     * @return array{key:string,artist:string,album:string,title:string,uri:?string,featured:list<string>,local:bool,duration_ms:?int}|null
     */
    private function item(string $artist, string $album, string $title, ?string $uri, bool $local = false, ?int $durationMs = null): ?array
    {
        $artist = $this->clean($artist, 250);
        $title = $this->clean($title, 250);
        if ($title === '') {
            return null;
        }
        if ($artist === '') {
            $artist = 'Неизвестный исполнитель';
        }
        $album = $this->clean($album, 250);
        if ($album === '') {
            $album = $title;
        }
        if ($uri !== null && str_starts_with($uri, 'spotify:local')) {
            $uri = null;
            $local = true;
        }

        return [
            'key' => $uri ?: ($this->norm($artist).'|'.$this->norm($album).'|'.$this->norm($title)),
            'artist' => $artist,
            'album' => $album,
            'title' => $title,
            'uri' => $uri,
            'featured' => $this->featured($title, $artist),
            'local' => $local,
            'duration_ms' => $durationMs,
        ];
    }

    /**
     * «Daddy (feat. Rich The Kid)» → ['Rich The Kid'].
     *
     * Запятую внутри скобок режем только если целиком строка не совпала с уже
     * известным артистом — иначе «Tyler, The Creator» развалился бы надвое.
     *
     * @return list<string>
     */
    private function featured(string $title, string $mainArtist): array
    {
        if (! preg_match('/[\(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^\)\]]+)[\)\]]/iu', $title, $m)) {
            return [];
        }

        $raw = $this->clean($m[1], 250);
        if ($raw === '') {
            return [];
        }
        if (isset($this->artists[$this->norm($raw)])) {
            return [$raw];
        }

        $parts = preg_split('/\s*(?:,|&|\bx\b|\band\b)\s*/iu', $raw) ?: [];
        $out = [];
        foreach ($parts as $part) {
            $part = $this->clean($part, 250);
            if (mb_strlen($part) >= 2 && $this->norm($part) !== $this->norm($mainArtist)) {
                $out[] = $part;
            }
        }

        return array_values(array_unique($out));
    }

    /**
     * spotify:local:Артист:Альбом:Название:секунды → части.
     * Артист и альбом чаще всего пустые, тогда пробуем «Артист - Название».
     *
     * @return array{artist:string,album:string,title:string,duration_ms:?int}|null
     */
    private function fromLocalUri(?string $uri): ?array
    {
        if (! $uri || ! str_starts_with($uri, 'spotify:local:')) {
            return null;
        }

        $parts = explode(':', substr($uri, strlen('spotify:local:')));
        $decode = fn (string $s) => trim(urldecode(str_replace('+', ' ', $s)));

        $artist = $decode($parts[0] ?? '');
        $album = $decode($parts[1] ?? '');
        $title = $decode($parts[2] ?? '');
        $seconds = (int) ($parts[3] ?? 0);

        if ($title === '') {
            return null;
        }
        if ($artist === '' && str_contains($title, ' - ')) {
            [$artist, $title] = array_map('trim', explode(' - ', $title, 2));
        }

        return [
            'artist' => $artist,
            'album' => $album,
            'title' => $title,
            'duration_ms' => $seconds > 0 ? $seconds * 1000 : null,
        ];
    }

    private function trackId(?array $item): ?int
    {
        if (! $item) {
            return null;
        }
        if (isset($item['track_id'])) {
            return $item['track_id'];
        }
        if (! empty($item['uri']) && isset($this->tracksByUri[$item['uri']])) {
            return $this->tracksByUri[$item['uri']];
        }
        if (isset($item['release_id'])) {
            return $this->tracksByTitle[$item['release_id'].'|'.$this->norm($item['title'])] ?? null;
        }

        return null;
    }

    /** @param  array<string,true>  $used */
    private function uniqueSlug(string $text, array &$used, string $fallback): string
    {
        $base = Str::limit(Str::slug($text), 180, '');
        if ($base === '') {
            $base = $fallback.'-'.substr(md5($text), 0, 8);
        }

        $slug = $base;
        $i = 2;
        while (isset($used[$slug])) {
            $slug = $base.'-'.$i++;
        }
        $used[$slug] = true;

        return $slug;
    }

    private function date(?string $value): ?Carbon
    {
        if (! $value) {
            return null;
        }

        try {
            return Carbon::parse($value);
        } catch (\Throwable) {
            return null;
        }
    }

    private function clean(string $s, int $max): string
    {
        $s = preg_replace('/\s+/u', ' ', trim($s)) ?? '';

        return mb_substr($s, 0, $max);
    }

    private function norm(string $s): string
    {
        $s = mb_strtolower(trim($s));
        $s = preg_replace('/[«»"\x{2018}\x{2019}\x{201C}\x{201D}`´\']/u', '', $s) ?? $s;

        return preg_replace('/\s+/u', ' ', $s) ?? $s;
    }
}
