<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['track_id', 'synced_lyrics', 'plain_lyrics', 'found', 'fetched_at'])]
class TrackLyrics extends Model
{
    protected function casts(): array
    {
        return [
            'found' => 'boolean',
            'fetched_at' => 'datetime',
        ];
    }
}
