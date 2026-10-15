<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Concerns\ResolvesLikes;
use App\Http\Controllers\Controller;
use App\Http\Resources\TrackResource;
use App\Models\Track;
use App\Models\TrackPlay;
use App\Services\Realtime\CentrifugoService;
use App\Services\Realtime\LibraryEvents;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\DB;

class TrackController extends Controller
{
    use ResolvesLikes;

    public function show(Request $request, Track $track)
    {
        $track->load(['artists', 'release.artist']);
        $this->markLikedTracks([$track], $request->user());

        return new TrackResource($track);
    }

    /** Bulk fetch (device playback transfer) — returns tracks in the requested order. */
    public function bulk(Request $request)
    {
        $ids = collect(explode(',', (string) $request->query('ids')))
            ->map(fn ($v) => (int) $v)
            ->filter()
            ->unique()
            ->take(500)
            ->values();

        $tracks = Track::with(['artists', 'release'])
            ->whereIn('id', $ids)
            ->get()
            ->keyBy('id');

        $ordered = $ids->map(fn ($id) => $tracks->get($id))->filter()->values();
        $this->markLikedTracks($ordered, $request->user());

        return TrackResource::collection($ordered);
    }

    public function like(Request $request, Track $track)
    {
        $changed = $request->user()->likedTracks()->syncWithoutDetaching([$track->id]);

        if (! empty($changed['attached'])) {
            $track->increment('likes_count');
            app(LibraryEvents::class)->changed([$request->user()->id], 'liked');
        }

        return response()->json(['is_liked' => true]);
    }

    public function unlike(Request $request, Track $track)
    {
        $detached = $request->user()->likedTracks()->detach($track->id);

        if ($detached > 0) {
            $track->decrement('likes_count');
            app(LibraryEvents::class)->changed([$request->user()->id], 'liked');
        }

        return response()->json(['is_liked' => false]);
    }

    /** Record a play (drives Home "recently played", stats and friend activity). */
    public function logPlay(Request $request, Track $track)
    {
        $user = $request->user();

        TrackPlay::create([
            'track_id' => $track->id,
            'user_id' => $user?->id,
            'played_at' => now(),
        ]);

        // Denormalized counter; avoids a COUNT(*) on the stats page.
        $track->increment('plays_count');

        // Активность друзей: последний трек в кэше + realtime-событие.
        if ($user) {
            $this->publishActivity($user, $track, now());
        }

        return response()->json(['plays_count' => $track->plays_count]);
    }

    /**
     * Прослушивания пачкой — так приложение отдаёт то, что играло без сети.
     *
     * Время берём присланное (не старше 30 дней и не из будущего), а пачку,
     * отправленную повторно (ответ потерялся по дороге), не задваиваем:
     * совпадение трека и секунды считаем тем же прослушиванием.
     */
    public function logPlays(Request $request)
    {
        $data = $request->validate([
            'plays' => ['required', 'array', 'min:1', 'max:500'],
            'plays.*.track_id' => ['required', 'integer'],
            'plays.*.played_at' => ['required', 'date'],
        ]);

        $user = $request->user();
        $now = now();
        $oldest = $now->copy()->subDays(30);

        $plays = collect($data['plays'])
            ->map(fn ($p) => [
                'track_id' => (int) $p['track_id'],
                'played_at' => Carbon::parse($p['played_at'])->utc()->startOfSecond(),
            ])
            // Часы телефона могут чуть спешить — будущее прижимаем к «сейчас».
            ->filter(fn ($p) => $p['played_at']->gte($oldest) && $p['played_at']->lte($now->copy()->addMinutes(10)))
            ->map(fn ($p) => [...$p, 'played_at' => $p['played_at']->min($now)])
            ->unique(fn ($p) => $p['track_id'].'@'.$p['played_at']->timestamp);

        $known = Track::whereIn('id', $plays->pluck('track_id')->unique())->pluck('id')->flip();
        $plays = $plays->filter(fn ($p) => $known->has($p['track_id']))->values();

        $logged = collect();
        DB::transaction(function () use ($plays, $user, &$logged) {
            foreach ($plays as $p) {
                $duplicate = TrackPlay::where('user_id', $user->id)
                    ->where('track_id', $p['track_id'])
                    ->where('played_at', $p['played_at'])
                    ->exists();
                if ($duplicate) {
                    continue;
                }
                TrackPlay::create([
                    'track_id' => $p['track_id'],
                    'user_id' => $user->id,
                    'played_at' => $p['played_at'],
                ]);
                $logged->push($p);
            }

            // Счётчики — мимо Eloquent: updated_at трека от прослушиваний не меняется.
            foreach ($logged->countBy('track_id') as $trackId => $count) {
                Track::whereKey($trackId)->toBase()->increment('plays_count', $count);
            }
        });

        // Друзьям показываем только то, что играет прямо сейчас.
        $latest = $logged->sortByDesc(fn ($p) => $p['played_at']->timestamp)->first();
        if ($latest && $latest['played_at']->gte($now->copy()->subMinutes(10))) {
            $this->publishActivity($user, Track::find($latest['track_id']), $latest['played_at']);
        }

        return response()->json(['logged' => $logged->count()]);
    }

    private function publishActivity($user, ?Track $track, $at): void
    {
        if (! $track) {
            return;
        }

        $track->loadMissing(['artists', 'release']);
        $activity = [
            'track_id' => $track->id,
            'title' => $track->title,
            'artists' => $track->artists->pluck('name')->implode(', '),
            'release_slug' => $track->release?->slug,
            'at' => $at->toIso8601String(),
        ];
        Cache::put("activity:user:{$user->id}", $activity, now()->addHours(8));
        app(CentrifugoService::class)
            ->publish("activity:{$user->id}", ['type' => 'listening', 'user_id' => $user->id, ...$activity]);
    }
}
