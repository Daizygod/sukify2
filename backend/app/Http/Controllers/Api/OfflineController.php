<?php

namespace App\Http\Controllers\Api;

use App\Enums\ProcessingStatus;
use App\Http\Controllers\Controller;
use App\Models\Artist;
use App\Models\PinnedItem;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Storage;

/**
 * Манифест для приложения: вся медиатека пользователя плюс то, что он сам
 * отметил для загрузки, одним ответом.
 *
 * По нему приложение держит локальную копию медиатеки и решает, какие файлы
 * скачать, перекачать (сменилась ревизия аудио) и удалить (трек пропал из
 * коллекции или из Sukify). Исключения из авто-загрузки сервер не знает —
 * их приложение применяет у себя.
 *
 * Формат компактный: релизы и артисты лежат отдельными словарями, у трека
 * только ссылки на них, адреса файлов собираются по шаблонам из `urls`.
 * В ответ не попадает ничего, что меняется само по себе (счётчики
 * прослушиваний и лайков), — иначе ETag менялся бы каждую минуту и
 * дешёвая проверка «ничего не изменилось» (304) не работала бы.
 */
class OfflineController extends Controller
{
    /** Сколько явных источников можно попросить за раз. */
    private const MAX_INCLUDES = 500;

    public function manifest(Request $request)
    {
        $data = $request->validate([
            'include' => ['nullable', 'string', 'max:20000'],
            'artists' => ['nullable', 'boolean'],
        ]);

        $user = $request->user();
        $includes = $this->parseIncludes($data['include'] ?? '');

        $sources = [];   // key => источник (порядок вставки сохраняется)
        $missing = [];

        // --- Медиатека: всегда целиком --------------------------------------
        $liked = DB::table('liked_tracks')
            ->where('user_id', $user->id)
            ->orderByDesc('created_at')
            ->orderByDesc('track_id')
            ->get(['track_id', 'created_at']);
        $sources['liked'] = [
            'key' => 'liked',
            'type' => 'liked',
            'track_ids' => $liked->pluck('track_id')->map(fn ($id) => (int) $id)->all(),
            'added_at' => $liked->pluck('created_at')->map(fn ($at) => $this->unix($at))->all(),
        ];

        $albumIds = DB::table('liked_albums')
            ->where('user_id', $user->id)
            ->orderByDesc('created_at')
            ->orderByDesc('release_id')
            ->pluck('release_id')
            ->map(fn ($id) => (int) $id);

        $libraryPlaylists = $this->libraryPlaylists($user);

        $followedArtistIds = DB::table('followed_artists')
            ->where('user_id', $user->id)
            ->orderByDesc('created_at')
            ->pluck('artist_id')
            ->map(fn ($id) => (int) $id);

        foreach ($libraryPlaylists as $playlist) {
            $sources["playlist:{$playlist->id}"] = $this->playlistSource($playlist, $user);
        }
        foreach ($albumIds as $releaseId) {
            $sources["release:{$releaseId}"] = $this->releaseSource($releaseId);
        }
        if ($request->boolean('artists')) {
            foreach ($followedArtistIds as $artistId) {
                $sources["artist:{$artistId}"] = $this->artistSource($artistId);
            }
        }

        // --- Явно отмеченное для загрузки -------------------------------------
        foreach ($includes as $key) {
            if (isset($sources[$key])) {
                continue;
            }
            $source = $this->resolveInclude($key, $user);
            if ($source === null) {
                $missing[] = $key;
            } else {
                $sources[$key] = $source;
            }
        }

        // Альбом мог исчезнуть из каталога, но ещё висеть в liked_albums.
        $sources = array_filter($sources, fn ($s) => $s !== null);

        // --- Треки, релизы, артисты --------------------------------------------
        $trackIds = collect($sources)->flatMap(fn ($s) => $s['track_ids'])->unique()->values();
        $tracks = $this->loadTracks($trackIds);

        $releaseIds = $tracks->pluck('release_id')
            ->merge(collect($sources)->where('type', 'release')->pluck('id'))
            ->filter()
            ->unique();
        $releases = Release::query()
            ->whereIn('id', $releaseIds)
            ->get(['id', 'artist_id', 'title', 'slug', 'type', 'release_date', 'cover_path', 'cover_status', 'dominant_color_hex', 'text_color_hex', 'updated_at'])
            ->keyBy('id');

        $artistIds = $tracks->flatMap(fn (Track $t) => $t->artists->pluck('id'))
            ->merge($releases->pluck('artist_id'))
            ->merge($followedArtistIds)
            ->merge(collect($sources)->where('type', 'artist')->pluck('id'))
            ->filter()
            ->unique();
        $detailed = $followedArtistIds->merge(collect($sources)->where('type', 'artist')->pluck('id'))->flip();
        $artists = Artist::query()
            ->whereIn('id', $artistIds)
            ->get(['id', 'name', 'slug', 'avatar_path', 'banner_path', 'dominant_color_hex', 'text_color_hex'])
            ->keyBy('id');

        $payload = [
            'version' => 1,
            'urls' => $this->urlTemplates(),
            'library' => [
                'playlists' => $libraryPlaylists->pluck('id')->all(),
                'albums' => $albumIds->filter(fn ($id) => $releases->has($id))->values()->all(),
                'artists' => $followedArtistIds->filter(fn ($id) => $artists->has($id))->values()->all(),
                'pins' => PinnedItem::where('user_id', $user->id)
                    ->orderBy('id')
                    ->get(['item_type', 'item_id'])
                    ->map(fn ($p) => "{$p->item_type}:{$p->item_id}")
                    ->all(),
            ],
            'sources' => array_values($sources),
            'missing' => $missing,
            // Словари id → данные. (object) — чтобы пустой словарь был {}, а не []:
            // клиенту иначе пришлось бы угадывать тип.
            'releases' => (object) $releases->map(fn (Release $r) => $this->releasePayload($r))->all(),
            'artists' => (object) $artists->map(fn (Artist $a) => $this->artistPayload($a, $detailed->has($a->id)))->all(),
            'tracks' => $tracks->map(fn (Track $t) => $this->trackPayload($t))->values()->all(),
        ];

        $json = json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
        $etag = '"'.md5($json).'"';

        // nginx при сжатии ослабляет ETag до W/"…" — сравниваем без префикса.
        $seen = collect(explode(',', (string) $request->header('If-None-Match')))
            ->map(fn ($v) => preg_replace('#^W/#', '', trim($v)))
            ->contains($etag);

        $headers = [
            'ETag' => $etag,
            'Cache-Control' => 'private, no-cache',
        ];

        if ($seen) {
            return new Response('', 304, $headers);
        }

        return new Response($json, 200, $headers + ['Content-Type' => 'application/json']);
    }

    // --- Источники ---------------------------------------------------------

    /** Свои и совместные плейлисты — в том же порядке, что и в сайдбаре. */
    private function libraryPlaylists(User $user): Collection
    {
        $own = $user->playlists()->with('owner:id,name,username')->get();
        $shared = Playlist::query()
            ->whereHas('collaborators', fn ($q) => $q->whereKey($user->id))
            ->with('owner:id,name,username')
            ->get();

        return $own->concat($shared)->unique('id')->sortByDesc('updated_at')->values();
    }

    private function resolveInclude(string $key, User $user): ?array
    {
        [$type, $id] = array_pad(explode(':', $key, 2), 2, null);
        $id = (int) $id;

        return match ($type) {
            'playlist' => ($playlist = Playlist::with('owner:id,name,username')->find($id))
                && $user->can('view', $playlist)
                    ? $this->playlistSource($playlist, $user)
                    : null,
            'release' => $this->releaseSource($id),
            'artist' => $this->artistSource($id),
            'track' => Track::whereKey($id)->exists()
                ? ['key' => $key, 'type' => 'track', 'id' => $id, 'track_ids' => [$id]]
                : null,
            default => null,
        };
    }

    private function playlistSource(Playlist $playlist, User $user): array
    {
        $items = DB::table('playlist_track')
            ->where('playlist_id', $playlist->id)
            ->orderBy('position')
            ->orderBy('id')
            ->get(['id', 'track_id', 'added_at']);

        return [
            'key' => "playlist:{$playlist->id}",
            'type' => 'playlist',
            'id' => $playlist->id,
            'title' => $playlist->title,
            'description' => $playlist->description,
            'cover_url' => $playlist->cover_path
                ? Storage::disk('s3')->url($playlist->cover_path).'?v='.$playlist->updated_at?->timestamp
                : null,
            'is_public' => (bool) $playlist->is_public,
            'is_owner' => $playlist->user_id === $user->id,
            'is_collaborative' => $playlist->invite_token !== null,
            'mix_enabled' => (bool) $playlist->mix_enabled,
            'owner' => $playlist->owner ? [
                'id' => $playlist->owner->id,
                'name' => $playlist->owner->name,
                'username' => $playlist->owner->username,
            ] : null,
            'updated_at' => $playlist->updated_at?->timestamp,
            'track_ids' => $items->pluck('track_id')->map(fn ($id) => (int) $id)->all(),
            'item_ids' => $items->pluck('id')->map(fn ($id) => (int) $id)->all(),
            'added_at' => $items->pluck('added_at')->map(fn ($at) => $this->unix($at))->all(),
        ];
    }

    private function releaseSource(int $releaseId): ?array
    {
        if (! Release::whereKey($releaseId)->exists()) {
            return null;
        }

        return [
            'key' => "release:{$releaseId}",
            'type' => 'release',
            'id' => $releaseId,
            'track_ids' => Track::where('release_id', $releaseId)
                ->orderBy('track_number')
                ->orderBy('id')
                ->pluck('id')
                ->map(fn ($id) => (int) $id)
                ->all(),
        ];
    }

    /** Дискография: треки релизов артиста, свежие релизы сверху. */
    private function artistSource(int $artistId): ?array
    {
        if (! Artist::whereKey($artistId)->exists()) {
            return null;
        }

        return [
            'key' => "artist:{$artistId}",
            'type' => 'artist',
            'id' => $artistId,
            'track_ids' => Track::query()
                ->join('releases', 'releases.id', '=', 'tracks.release_id')
                ->where('releases.artist_id', $artistId)
                ->orderByDesc('releases.release_date')
                ->orderByDesc('releases.id')
                ->orderBy('tracks.track_number')
                ->orderBy('tracks.id')
                ->pluck('tracks.id')
                ->map(fn ($id) => (int) $id)
                ->all(),
        ];
    }

    /** @return list<string> */
    private function parseIncludes(string $raw): array
    {
        return collect(explode(',', $raw))
            ->map(fn ($k) => trim($k))
            ->filter(fn ($k) => preg_match('/^(liked|(playlist|release|artist|track):\d{1,12})$/', $k))
            ->unique()
            ->take(self::MAX_INCLUDES)
            ->values()
            ->all();
    }

    // --- Данные ------------------------------------------------------------

    private function loadTracks(Collection $ids): Collection
    {
        $tracks = collect();

        foreach ($ids->chunk(1000) as $chunk) {
            $tracks = $tracks->concat(
                Track::query()
                    ->whereIn('id', $chunk->all())
                    ->with('artists:id')
                    ->get([
                        'id', 'release_id', 'title', 'track_number', 'duration_ms', 'loudness_lufs',
                        'audio_stream_path', 'audio_rev', 'audio_size',
                        'cover_override_path', 'unofficial', 'preview_only', 'bpm', 'camelot',
                    ])
            );
        }

        return $tracks->sortBy('id')->values();
    }

    private function trackPayload(Track $t): array
    {
        // Играет всё, у чего есть файл, — как и в вебе (там смотрят на stream_url).
        // Неудачная замена аудио старый файл не трогает, так что статус не важен.
        // rev бывает null до бэкфилла: тогда приложение примет md5 скачанного.
        $audio = null;
        if ($t->audio_stream_path) {
            $audio = ['rev' => $t->audio_rev, 'size' => $t->audio_size];
            // Шаблон покрывает audio/{id}/stream.mp3; старые пути — явно.
            if ($t->audio_stream_path !== "audio/{$t->id}/stream.mp3") {
                $audio['url'] = Storage::disk('s3')->url($t->audio_stream_path);
            }
        }

        return array_filter([
            'id' => $t->id,
            'title' => $t->title,
            'release_id' => $t->release_id,
            'track_number' => $t->track_number,
            'duration_ms' => $t->duration_ms,
            'artist_ids' => $t->artists->pluck('id')->all(),
            'lufs' => $t->loudness_lufs,
            'bpm' => $t->bpm !== null ? round((float) $t->bpm) : null,
            'camelot' => $t->camelot,
            'preview' => $t->preview_only ?: null,
            'unofficial' => $t->unofficial ? true : null,
            // Своя обложка трека поверх обложки релиза.
            'own_cover' => $t->cover_override_path ? true : null,
            'audio' => $audio,
        ], fn ($v) => $v !== null);
    }

    private function releasePayload(Release $r): array
    {
        $coverReady = $r->cover_path && $r->cover_status === ProcessingStatus::Ready;

        return array_filter([
            'title' => $r->title,
            'slug' => $r->slug,
            'type' => $r->type?->value,
            'year' => $r->release_date?->year,
            'artist_id' => $r->artist_id,
            // Версия обложки — как ?v= в Release::coverUrl(); null — обложки нет.
            'cover_v' => $coverReady ? ($r->updated_at?->timestamp ?? 0) : null,
            'bg' => $r->dominant_color_hex,
            'fg' => $r->text_color_hex,
        ], fn ($v) => $v !== null);
    }

    private function artistPayload(Artist $a, bool $detailed): array
    {
        $out = ['name' => $a->name, 'slug' => $a->slug];

        if ($detailed) {
            $disk = Storage::disk('s3');
            $out += array_filter([
                'avatar_url' => $a->avatar_path ? $disk->url($a->avatar_path) : null,
                'banner_url' => $a->banner_path ? $disk->url($a->banner_path) : null,
                'bg' => $a->dominant_color_hex,
                'fg' => $a->text_color_hex,
            ], fn ($v) => $v !== null);
        }

        return $out;
    }

    /** Шаблоны адресов: {id} и {size} приложение подставляет само. */
    private function urlTemplates(): array
    {
        $disk = Storage::disk('s3');
        $template = fn (string $path) => str_replace(['__ID__', '__SIZE__'], ['{id}', '{size}'], $disk->url($path));

        return [
            'audio' => $template('audio/__ID__/stream.mp3'),
            'release_cover' => $template('covers/__ID__/__SIZE__.webp').'?v={v}',
            'track_cover' => $template('track-covers/__ID__/__SIZE__.webp'),
            'cover_sizes' => Release::COVER_SIZES,
        ];
    }

    private function unix(mixed $value): ?int
    {
        if ($value === null) {
            return null;
        }

        return is_numeric($value) ? (int) $value : (strtotime((string) $value) ?: null);
    }
}
