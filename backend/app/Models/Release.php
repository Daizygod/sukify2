<?php

namespace App\Models;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Observers\ReleaseObserver;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\ObservedBy;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Facades\Storage;
use Laravel\Scout\Searchable;

#[Fillable([
    'artist_id', 'title', 'slug', 'type', 'release_date',
    'cover_original_path', 'cover_path', 'cover_status',
    'dominant_color_hex', 'text_color_hex',
])]
#[ObservedBy([ReleaseObserver::class])]
class Release extends Model
{
    use HasFactory, Searchable;

    protected function casts(): array
    {
        return [
            'type' => ReleaseType::class,
            'cover_status' => ProcessingStatus::class,
            'release_date' => 'date',
        ];
    }

    public function getRouteKeyName(): string
    {
        return 'slug';
    }

    // --- Relations ---------------------------------------------------------

    public function artist(): BelongsTo
    {
        return $this->belongsTo(Artist::class);
    }

    public function tracks(): HasMany
    {
        return $this->hasMany(Track::class)->orderBy('track_number');
    }

    public function likedByUsers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'liked_albums');
    }

    // --- Cover art ---------------------------------------------------------

    /** Rendition sizes produced by the cover pipeline (spec §6). */
    public const COVER_SIZES = [64, 160, 300, 640, 1000];

    public function coverUrl(int $size = 640, string $format = 'webp'): ?string
    {
        if (! $this->cover_path || $this->cover_status !== ProcessingStatus::Ready) {
            return null;
        }

        return Storage::disk('s3')->url("covers/{$this->id}/{$size}.{$format}");
    }

    /** Map of size => WebP URL for responsive srcset, or null if no cover yet. */
    public function coverUrls(): ?array
    {
        if (! $this->cover_path || $this->cover_status !== ProcessingStatus::Ready) {
            return null;
        }

        $urls = [];
        foreach (self::COVER_SIZES as $size) {
            $urls[$size] = $this->coverUrl($size);
        }

        return $urls;
    }

    // --- Search ------------------------------------------------------------

    public function toSearchableArray(): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'type' => $this->type->value,
            'artist_name' => $this->artist?->name,
        ];
    }
}
