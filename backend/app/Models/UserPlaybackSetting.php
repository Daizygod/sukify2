<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

#[Fillable(['user_id', 'target_loudness_lufs', 'default_crossfade_seconds', 'smart_shuffle_enabled'])]
class UserPlaybackSetting extends Model
{
    // PK is user_id (one row per user), not an auto-increment id.
    protected $primaryKey = 'user_id';

    public $incrementing = false;

    protected $keyType = 'int';

    protected function casts(): array
    {
        return [
            'target_loudness_lufs' => 'float',
            'default_crossfade_seconds' => 'integer',
            'smart_shuffle_enabled' => 'boolean',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
