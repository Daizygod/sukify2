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

        $transition = TrackTransition::query()
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

        return TransitionResource::collection($transitions);
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
        ]);

        $transition = TrackTransition::create([
            ...$data,
            'created_by_user_id' => $request->user()->id,
        ]);

        return (new TransitionResource($transition))->response()->setStatusCode(201);
    }

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
