<?php

return [

    // Default target loudness used to seed a user's playback settings.
    // Client normalizes per track: gain = 10^((target - track_lufs)/20).
    'target_lufs' => (float) env('PLAYBACK_TARGET_LUFS', -14),

    // Default crossfade length (seconds) when a track pair has no saved transition.
    'default_crossfade_seconds' => (int) env('PLAYBACK_DEFAULT_CROSSFADE_SECONDS', 0),

];
