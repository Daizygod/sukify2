<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** Прослушивание из истории Spotify. Живёт отдельно от track_plays. */
#[Fillable([
    'user_id', 'spotify_import_id', 'played_at', 'ms_played',
    'artist_name', 'track_name', 'album_name', 'spotify_uri', 'track_id',
    'skipped', 'shuffle', 'platform', 'reason_end', 'fingerprint',
])]
class ImportedPlay extends Model
{
    public $timestamps = false;

    protected function casts(): array
    {
        return [
            'played_at' => 'datetime',
            'ms_played' => 'integer',
            'skipped' => 'boolean',
            'shuffle' => 'boolean',
        ];
    }

    public function track(): BelongsTo
    {
        return $this->belongsTo(Track::class);
    }
}
