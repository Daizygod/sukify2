<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

#[Fillable(['track_id', 'user_id', 'played_at'])]
class TrackPlay extends Model
{
    public $timestamps = false;

    protected function casts(): array
    {
        return [
            'played_at' => 'datetime',
        ];
    }

    public function track(): BelongsTo
    {
        return $this->belongsTo(Track::class);
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
