<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Laravel\Scout\Searchable;

#[Fillable(['user_id', 'title', 'description', 'cover_path', 'cover_is_custom', 'is_public'])]
class Playlist extends Model
{
    use HasFactory, Searchable;

    protected function casts(): array
    {
        return [
            'cover_is_custom' => 'boolean',
            'is_public' => 'boolean',
        ];
    }

    // --- Relations ---------------------------------------------------------

    public function owner(): BelongsTo
    {
        return $this->belongsTo(User::class, 'user_id');
    }

    public function tracks(): BelongsToMany
    {
        return $this->belongsToMany(Track::class, 'playlist_track')
            ->withPivot(['id', 'position', 'added_by_user_id', 'added_at'])
            ->orderByPivot('position');
    }

    public function collaborators(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'playlist_collaborators')->withTimestamps();
    }

    public function isCollaborator(?User $user): bool
    {
        return $user !== null && $this->collaborators()->whereKey($user->id)->exists();
    }

    // --- Search ------------------------------------------------------------

    public function toSearchableArray(): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'description' => $this->description,
            'owner_name' => $this->owner?->name,
        ];
    }

    public function shouldBeSearchable(): bool
    {
        return $this->is_public;
    }
}
