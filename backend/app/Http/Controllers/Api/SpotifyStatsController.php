<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Artist;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Storage;

/**
 * «Твой Spotify» — статистика по истории, перенесённой из экспорта.
 *
 * Считается прямо по imported_plays: это сотни тысяч строк за годы, но все
 * запросы — обычные агрегаты по индексу (user_id, played_at), поэтому проще
 * посчитать и положить в кэш на несколько минут, чем держать витрины.
 *
 * Часы и годы считаются в часовом поясе клиента: played_at лежит в UTC, а
 * «во сколько я слушаю музыку» имеет смысл только в местном времени.
 */
class SpotifyStatsController extends Controller
{
    public function show(Request $request)
    {
        $userId = $request->user()->id;
        $timezone = $this->timezone((string) $request->query('tz', ''));
        $year = $request->query('year');
        $year = is_numeric($year) ? (int) $year : null;

        $cacheKey = "spotify-stats:{$userId}:{$timezone}:".($year ?? 'all');

        return response()->json(Cache::remember($cacheKey, now()->addMinutes(5), function () use ($userId, $timezone, $year) {
            $years = $this->years($userId, $timezone);
            if (! $years) {
                return ['has_data' => false, 'years' => [], 'year' => null];
            }

            return [
                'has_data' => true,
                'year' => $year,
                'years' => $years,
                'totals' => $this->totals($userId, $timezone, $year),
                'top_artists' => $this->topArtists($userId, $timezone, $year),
                'top_tracks' => $this->topTracks($userId, $timezone, $year),
                'by_hour' => $this->buckets($userId, $timezone, $year, 'hour', 0, 23),
                'by_weekday' => $this->buckets($userId, $timezone, $year, 'isodow', 1, 7),
                'by_month' => $year ? $this->buckets($userId, $timezone, $year, 'month', 1, 12) : [],
                'platforms' => $this->platforms($userId, $timezone, $year),
            ];
        }));
    }

    /** Базовый запрос с фильтром по году в местном времени. */
    private function base(int $userId, string $timezone, ?int $year)
    {
        return DB::table('imported_plays')
            ->where('user_id', $userId)
            ->when($year !== null, fn ($q) => $q->whereRaw(
                'extract(year from played_at at time zone ?) = ?', [$timezone, $year]
            ));
    }

    private function years(int $userId, string $timezone): array
    {
        $rows = DB::table('imported_plays')
            ->where('user_id', $userId)
            ->selectRaw('extract(year from played_at at time zone ?)::int as year, count(*) as plays, coalesce(sum(ms_played), 0) as ms', [$timezone])
            ->groupByRaw('1')
            ->orderByRaw('1')
            ->get();

        return $rows->map(fn ($r) => [
            'year' => (int) $r->year,
            'plays' => (int) $r->plays,
            'ms' => (int) $r->ms,
        ])->all();
    }

    private function totals(int $userId, string $timezone, ?int $year): array
    {
        $row = $this->base($userId, $timezone, $year)
            ->selectRaw("
                count(*) as plays,
                coalesce(sum(ms_played), 0) as ms,
                count(distinct coalesce(spotify_uri, lower(artist_name) || '|' || lower(track_name))) as tracks,
                count(distinct lower(artist_name)) as artists,
                count(*) filter (where skipped) as skipped,
                count(*) filter (where shuffle) as shuffled,
                count(*) filter (where ms_played >= 30000) as full_plays,
                min(played_at) as first_at,
                max(played_at) as last_at
            ")
            ->first();

        return [
            'plays' => (int) $row->plays,
            'ms' => (int) $row->ms,
            'tracks' => (int) $row->tracks,
            'artists' => (int) $row->artists,
            'skipped' => (int) $row->skipped,
            'shuffled' => (int) $row->shuffled,
            'full_plays' => (int) $row->full_plays,
            'first_at' => $row->first_at,
            'last_at' => $row->last_at,
        ];
    }

    private function topArtists(int $userId, string $timezone, ?int $year): array
    {
        $rows = $this->base($userId, $timezone, $year)
            ->selectRaw('artist_name, count(*) as plays, coalesce(sum(ms_played), 0) as ms')
            ->groupBy('artist_name')
            ->orderByDesc('ms')
            ->limit(25)
            ->get();

        // Подтягиваем артистов каталога, чтобы из статистики можно было уйти
        // на страницу исполнителя.
        $names = $rows->map(fn ($r) => mb_strtolower($r->artist_name))->all();
        $known = Artist::query()
            ->whereIn(DB::raw('lower(name)'), $names)
            ->get(['id', 'name', 'slug', 'avatar_path', 'banner_path'])
            ->keyBy(fn ($a) => mb_strtolower($a->name));

        return $rows->map(function ($r) use ($known) {
            $artist = $known->get(mb_strtolower($r->artist_name));

            return [
                'name' => $r->artist_name,
                'plays' => (int) $r->plays,
                'ms' => (int) $r->ms,
                'slug' => $artist?->slug,
                'image_url' => match (true) {
                    (bool) $artist?->avatar_path => Storage::disk('s3')->url($artist->avatar_path),
                    (bool) $artist?->banner_path => Storage::disk('s3')->url($artist->banner_path),
                    default => null,
                },
            ];
        })->all();
    }

    private function topTracks(int $userId, string $timezone, ?int $year): array
    {
        $rows = $this->base($userId, $timezone, $year)
            ->selectRaw('artist_name, track_name, count(*) as plays, coalesce(sum(ms_played), 0) as ms, max(track_id) as track_id')
            ->groupBy('artist_name', 'track_name')
            ->orderByDesc('plays')
            ->limit(25)
            ->get();

        $tracks = Track::with('release:id,cover_path,cover_status,updated_at')
            ->whereIn('id', $rows->pluck('track_id')->filter()->all())
            ->get(['id', 'release_id', 'cover_override_path'])
            ->keyBy('id');

        return $rows->map(function ($r) use ($tracks) {
            $track = $r->track_id ? $tracks->get($r->track_id) : null;

            return [
                'title' => $r->track_name,
                'artist' => $r->artist_name,
                'plays' => (int) $r->plays,
                'ms' => (int) $r->ms,
                'track_id' => $r->track_id ? (int) $r->track_id : null,
                'cover' => $track?->coverUrls(),
            ];
        })->all();
    }

    /** Раскладка по часам/дням недели/месяцам с нулями там, где не слушали. */
    private function buckets(int $userId, string $timezone, ?int $year, string $part, int $min, int $max): array
    {
        $rows = $this->base($userId, $timezone, $year)
            ->selectRaw("extract({$part} from played_at at time zone ?)::int as bucket, count(*) as plays, coalesce(sum(ms_played), 0) as ms", [$timezone])
            ->groupByRaw('1')
            ->get()
            ->keyBy('bucket');

        $out = [];
        for ($i = $min; $i <= $max; $i++) {
            $row = $rows->get($i);
            $out[] = [
                'bucket' => $i,
                'plays' => (int) ($row->plays ?? 0),
                'ms' => (int) ($row->ms ?? 0),
            ];
        }

        return $out;
    }

    private function platforms(int $userId, string $timezone, ?int $year): array
    {
        return $this->base($userId, $timezone, $year)
            ->whereNotNull('platform')
            ->selectRaw('platform, count(*) as plays, coalesce(sum(ms_played), 0) as ms')
            ->groupBy('platform')
            ->orderByDesc('ms')
            ->get()
            ->map(fn ($r) => ['platform' => $r->platform, 'plays' => (int) $r->plays, 'ms' => (int) $r->ms])
            ->all();
    }

    private function timezone(string $raw): string
    {
        return in_array($raw, timezone_identifiers_list(), true) ? $raw : 'UTC';
    }
}
