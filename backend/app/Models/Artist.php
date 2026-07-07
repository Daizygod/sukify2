<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Laravel\Scout\Searchable;

#[Fillable(['name', 'slug', 'bio', 'avatar_path', 'banner_path', 'dominant_color_hex', 'text_color_hex', 'monthly_listeners'])]
class Artist extends Model
{
    use HasFactory, Searchable;

    protected function casts(): array
    {
        return [
            'monthly_listeners' => 'integer',
        ];
    }

    public function getRouteKeyName(): string
    {
        return 'slug';
    }

    // --- Relations ---------------------------------------------------------

    public function releases(): HasMany
    {
        return $this->hasMany(Release::class);
    }

    public function tracks(): BelongsToMany
    {
        return $this->belongsToMany(Track::class, 'track_artist')
            ->withPivot(['role', 'position']);
    }

    public function followers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'followed_artists');
    }

    // --- Search ------------------------------------------------------------

    public function toSearchableArray(): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'bio' => $this->bio,
        ];
    }
}
