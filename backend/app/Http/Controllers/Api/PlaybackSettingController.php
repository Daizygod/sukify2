<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\Request;

class PlaybackSettingController extends Controller
{
    public function show(Request $request)
    {
        $settings = $request->user()->settings();

        return response()->json(['data' => $this->format($settings)]);
    }

    public function update(Request $request)
    {
        $data = $request->validate([
            'target_loudness_lufs' => ['sometimes', 'numeric', 'between:-30,0'],
            'default_crossfade_seconds' => ['sometimes', 'integer', 'between:0,12'],
            'smart_shuffle_enabled' => ['sometimes', 'boolean'],
        ]);

        $settings = $request->user()->settings();
        $settings->update($data);

        return response()->json(['data' => $this->format($settings)]);
    }

    private function format($settings): array
    {
        return [
            'target_loudness_lufs' => $settings->target_loudness_lufs,
            'default_crossfade_seconds' => $settings->default_crossfade_seconds,
            'smart_shuffle_enabled' => $settings->smart_shuffle_enabled,
        ];
    }
}
