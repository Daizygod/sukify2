<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Http\Resources\TransitionResource;
use App\Models\TrackTransition;
use Illuminate\Http\Request;
use Illuminate\Validation\Rule;

class TransitionController extends Controller
{
    /**
     * The default (best-liked) transition for a track pair.
     * GET /api/transitions?from={id}&to={id}
     * Returns null if none exists (client falls back to global crossfade).
     */
    public function forPair(Request $request)
    {
        $data = $request->validate([
            'from' => ['required', 'integer', 'exists:tracks,id'],
            'to' => ['required', 'integer', 'exists:tracks,id'],
        ]);

        // Личный выбор пользователя перекрывает лучший переход сообщества.
        $transition = null;
        if ($user = $request->user()) {
            $pref = \App\Models\TransitionPreference::query()
                ->where('user_id', $user->id)
                ->where('from_track_id', $data['from'])
                ->where('to_track_id', $data['to'])
                ->first();
            if ($pref) {
                $transition = TrackTransition::find($pref->transition_id);
            }
        }

        $transition ??= TrackTransition::query()
            ->where('from_track_id', $data['from'])
            ->where('to_track_id', $data['to'])
            ->orderByDesc('likes_count')
            ->orderByDesc('created_at')
            ->first();

        if (! $transition) {
            return response()->json(['data' => null]);
        }

        $this->markLiked($transition, $request);

        return new TransitionResource($transition);
    }

    /** All community transitions for a pair, best-liked first. */
    public function index(Request $request)
    {
        $data = $request->validate([
            'from' => ['required', 'integer', 'exists:tracks,id'],
            'to' => ['required', 'integer', 'exists:tracks,id'],
        ]);

        $transitions = TrackTransition::query()
            ->where('from_track_id', $data['from'])
            ->where('to_track_id', $data['to'])
            ->orderByDesc('likes_count')
            ->get();

        // Пометки для UI: мой личный выбор + возможность удалить своё.
        if ($user = $request->user()) {
            $prefId = \App\Models\TransitionPreference::query()
                ->where('user_id', $user->id)
                ->where('from_track_id', $data['from'])
                ->where('to_track_id', $data['to'])
                ->value('transition_id');
            $transitions->each(function ($t) use ($user, $prefId) {
                $t->is_preferred = $t->id === $prefId;
                $t->is_mine = $t->created_by_user_id === $user->id;
            });
        }

        return TransitionResource::collection($transitions);
    }

    /** «Использовать этот» — личный выбор перехода для пары. */
    public function prefer(Request $request, TrackTransition $transition)
    {
        \App\Models\TransitionPreference::updateOrCreate(
            [
                'user_id' => $request->user()->id,
                'from_track_id' => $transition->from_track_id,
                'to_track_id' => $transition->to_track_id,
            ],
            ['transition_id' => $transition->id]
        );

        return response()->json(['is_preferred' => true]);
    }

    public function unprefer(Request $request, TrackTransition $transition)
    {
        \App\Models\TransitionPreference::query()
            ->where('user_id', $request->user()->id)
            ->where('transition_id', $transition->id)
            ->delete();

        return response()->json(['is_preferred' => false]);
    }

    /** Автор может удалить свой переход. */
    public function destroy(Request $request, TrackTransition $transition)
    {
        abort_unless(
            $transition->created_by_user_id === $request->user()->id || $request->user()->is_admin,
            403,
            'Удалять можно только свои переходы.'
        );

        $transition->delete();

        return response()->json(['message' => 'Переход удалён.']);
    }

    /**
     * Best transition per pair for a whole tracklist in one round-trip.
     * GET /api/transitions/for-context?pairs=1:2,2:3 → { "1:2": {...}|null }
     */
    public function forContext(Request $request)
    {
        $raw = (string) $request->query('pairs', '');
        $pairs = collect(explode(',', $raw))
            ->map(function ($p) {
                [$from, $to] = array_pad(explode(':', $p), 2, null);
                return [(int) $from, (int) $to];
            })
            ->filter(fn ($p) => $p[0] > 0 && $p[1] > 0)
            ->take(200);

        if ($pairs->isEmpty()) {
            return response()->json(['data' => (object) []]);
        }

        $transitions = TrackTransition::query()
            ->where(function ($q) use ($pairs) {
                foreach ($pairs as [$from, $to]) {
                    $q->orWhere(fn ($qq) => $qq->where('from_track_id', $from)->where('to_track_id', $to));
                }
            })
            ->orderByDesc('likes_count')
            ->orderByDesc('created_at')
            ->get()
            ->groupBy(fn ($t) => "{$t->from_track_id}:{$t->to_track_id}");

        // Личный выбор перекрывает лучший переход сообщества: чип в списке
        // должен показывать ровно тот переход, который и заиграет.
        $preferred = collect();
        if ($user = $request->user()) {
            $preferred = \App\Models\TransitionPreference::query()
                ->where('user_id', $user->id)
                ->get()
                ->keyBy(fn ($p) => "{$p->from_track_id}:{$p->to_track_id}");
        }

        $out = [];
        foreach ($pairs as [$from, $to]) {
            $key = "{$from}:{$to}";
            $group = $transitions->get($key);
            $mine = $preferred->get($key)?->transition_id;
            $best = ($mine ? $group?->firstWhere('id', $mine) : null) ?? $group?->first();
            $out[$key] = $best ? [
                'id' => $best->id,
                'preset' => $best->preset,
                'bars' => $best->bars,
                'likes_count' => $best->likes_count,
                'is_preferred' => $best->id === $mine,
                'count' => $group->count(),
            ] : null;
        }

        return response()->json(['data' => $out]);
    }

    public function store(Request $request)
    {
        $data = $request->validate([
            'from_track_id' => ['required', 'integer', 'exists:tracks,id', 'different:to_track_id'],
            'to_track_id' => ['required', 'integer', 'exists:tracks,id'],
            'fade_out_start_ms' => ['required', 'integer', 'min:0'],
            'fade_out_end_ms' => ['required', 'integer', 'gte:fade_out_start_ms'],
            'fade_in_start_ms' => ['required', 'integer', 'min:0'],
            'fade_in_full_volume_ms' => ['required', 'integer', 'gte:fade_in_start_ms'],
            'curve_type' => ['required', Rule::enum(\App\Enums\CurveType::class)],
            // Формы кривых микса. Список идентификаторов повторяет
            // frontend/src/lib/mix/shapes.js — там же лежит их математика.
            'preset' => ['sometimes', Rule::in(self::PRESETS)],
            'volume_shape' => ['sometimes', Rule::in(self::VOLUME_SHAPES)],
            'eq_shape' => ['sometimes', Rule::in(self::EQ_SHAPES)],
            'filter_shape' => ['sometimes', Rule::in(self::FILTER_SHAPES)],
            'bars' => ['sometimes', 'nullable', 'integer', Rule::in([1, 2, 4, 8, 16, 32])],
        ]);

        $transition = TrackTransition::create([
            ...$data,
            'created_by_user_id' => $request->user()->id,
        ]);

        // Свой переход сразу становится личным выбором для этой пары — иначе
        // сохранил, а играет по-прежнему чужой, самый залайканный.
        \App\Models\TransitionPreference::updateOrCreate(
            [
                'user_id' => $request->user()->id,
                'from_track_id' => $transition->from_track_id,
                'to_track_id' => $transition->to_track_id,
            ],
            ['transition_id' => $transition->id]
        );

        $transition->is_preferred = true;
        $transition->is_mine = true;

        return (new TransitionResource($transition))->response()->setStatusCode(201);
    }

    private const PRESETS = [
        'auto', 'fade', 'rise', 'blend', 'wave', 'dissolve',
        'hit', 'melt', 'burst', 'shine', 'none', 'custom',
    ];

    private const VOLUME_SHAPES = [
        'crossfade', 'slow_crossfade', 'overlap', 'rise_fade',
        'cut_in_fade_out', 'rise_cut_out', 'hard_swap', 'rise_fast_out',
    ];

    private const EQ_SHAPES = [
        'none', 'bass_swap_center', 'bass_swap_end', 'bass_swap_start',
        'three_band', 'bass_cut_hard', 'bass_cut_soft', 'start_fade', 'bass_fade',
    ];

    private const FILTER_SHAPES = [
        'none', 'lp_out', 'lp_in', 'lp_in_out', 'lp_in_hp_out',
        'hp_out', 'hp_in', 'hp_in_out', 'hp_in_lp_out', 'hp_out_half', 'noise_fade_end',
    ];

    public function like(Request $request, TrackTransition $transition)
    {
        $changed = $request->user()->transitionLikes()->syncWithoutDetaching([$transition->id]);

        if (! empty($changed['attached'])) {
            $transition->increment('likes_count');
        }

        return response()->json(['likes_count' => $transition->fresh()->likes_count, 'is_liked' => true]);
    }

    public function unlike(Request $request, TrackTransition $transition)
    {
        $detached = $request->user()->transitionLikes()->detach($transition->id);

        if ($detached > 0) {
            $transition->decrement('likes_count');
        }

        return response()->json(['likes_count' => $transition->fresh()->likes_count, 'is_liked' => false]);
    }

    private function markLiked(TrackTransition $transition, Request $request): void
    {
        $user = $request->user();
        $transition->is_liked = $user
            ? $user->transitionLikes()->where('transition_id', $transition->id)->exists()
            : false;
    }
}
