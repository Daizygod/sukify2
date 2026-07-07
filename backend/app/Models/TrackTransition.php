<?php

namespace App\Models;

use App\Enums\CurveType;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;

#[Fillable([
    'from_track_id', 'to_track_id', 'created_by_user_id',
    'fade_out_start_ms', 'fade_out_end_ms', 'fade_in_start_ms', 'fade_in_full_volume_ms',
    'curve_type',
])]
class TrackTransition extends Model
{
    use HasFactory;

    protected function casts(): array
    {
        return [
            'curve_type' => CurveType::class,
            'fade_out_start_ms' => 'integer',
            'fade_out_end_ms' => 'integer',
            'fade_in_start_ms' => 'integer',
            'fade_in_full_volume_ms' => 'integer',
            'likes_count' => 'integer',
        ];
    }

    public function fromTrack(): BelongsTo
    {
        return $this->belongsTo(Track::class, 'from_track_id');
    }

    public function toTrack(): BelongsTo
    {
        return $this->belongsTo(Track::class, 'to_track_id');
    }

    public function creator(): BelongsTo
    {
        return $this->belongsTo(User::class, 'created_by_user_id');
    }

    public function likedByUsers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'transition_likes', 'transition_id', 'user_id');
    }
}
