<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Jobs\AnalyzeTrackAudio;
use App\Models\Track;
use Illuminate\Http\Request;

/**
 * Данные анализа для редактора переходов: волна, сетка долей, темп, тональность.
 * В списке треков это лишний вес, поэтому отдаём отдельным запросом и только
 * для той пары, которую редактируют.
 */
class TrackAnalysisController extends Controller
{
    public function show(Track $track)
    {
        return response()->json(['data' => $this->payload($track)]);
    }

    /** Пара треков одним запросом — редактор всегда открывает именно пару. */
    public function pair(Request $request)
    {
        $data = $request->validate([
            'from' => ['required', 'integer', 'exists:tracks,id'],
            'to' => ['required', 'integer', 'exists:tracks,id'],
        ]);

        $tracks = Track::whereIn('id', [$data['from'], $data['to']])->get()->keyBy('id');

        return response()->json([
            'data' => [
                'from' => isset($tracks[$data['from']]) ? $this->payload($tracks[$data['from']]) : null,
                'to' => isset($tracks[$data['to']]) ? $this->payload($tracks[$data['to']]) : null,
            ],
        ]);
    }

    /** Пересчитать анализ трека (например, если он падал). */
    public function refresh(Request $request, Track $track)
    {
        abort_unless($request->user()?->is_admin, 403, 'Пересчёт доступен только администратору.');

        AnalyzeTrackAudio::dispatch($track->id);
        $track->update(['analysis_status' => 'pending', 'analysis_error' => null]);

        return response()->json(['message' => 'Анализ поставлен в очередь.']);
    }

    private function payload(Track $track): array
    {
        return [
            'id' => $track->id,
            'duration_ms' => $track->duration_ms,
            'bpm' => $track->bpm !== null ? (float) $track->bpm : null,
            'beat_offset_ms' => $track->beat_offset_ms,
            'beat_confidence' => $track->beat_confidence,
            'beats' => $track->beats,
            'musical_key' => $track->musical_key,
            'musical_scale' => $track->musical_scale,
            'camelot' => $track->camelot,
            'key_strength' => $track->key_strength,
            'waveform' => [
                'buckets' => $track->waveform_buckets,
                'full' => $track->waveform_full,
                'bass' => $track->waveform_bass,
                'bands' => $track->waveform_bands,
            ],
            'status' => $track->analysis_status,
        ];
    }
}
