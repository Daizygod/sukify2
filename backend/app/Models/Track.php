<?php

namespace App\Models;

use App\Enums\ProcessingStatus;
use App\Observers\TrackObserver;
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
    'release_id', 'title', 'track_number', 'duration_ms',
    'audio_original_path', 'audio_stream_path', 'loudness_lufs',
    'file_size_original', 'cover_override_path',
    'processing_status', 'processing_error',
])]
#[ObservedBy([TrackObserver::class])]
class Track extends Model
{
    use HasFactory, Searchable;

    protected function casts(): array
    {
        return [
            'processing_status' => ProcessingStatus::class,
            'loudness_lufs' => 'float',
            'duration_ms' => 'integer',
            'file_size_original' => 'integer',
            'plays_count' => 'integer',
            'likes_count' => 'integer',
        ];
    }

    // --- Relations ---------------------------------------------------------

    public function release(): BelongsTo
    {
        return $this->belongsTo(Release::class);
    }

    public function artists(): BelongsToMany
    {
        return $this->belongsToMany(Artist::class, 'track_artist')
            ->withPivot(['role', 'position'])
            ->orderByPivot('position');
    }

    public function mainArtists(): BelongsToMany
    {
        return $this->artists()->wherePivot('role', 'main');
    }

    public function playlists(): BelongsToMany
    {
        return $this->belongsToMany(Playlist::class, 'playlist_track')
            ->withPivot(['position', 'added_by_user_id', 'added_at']);
    }

    public function likedByUsers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'liked_tracks');
    }

    public function plays(): HasMany
    {
        return $this->hasMany(TrackPlay::class);
    }

    /** The effective cover: per-track override or the parent release cover. */
    public function coverPath(): ?string
    {
        return $this->cover_override_path ?? $this->release?->cover_path;
    }

    /** Streaming (MP3) URL for the Web Audio player. */
    public function streamUrl(): ?string
    {
        if (! $this->audio_stream_path) {
            return null;
        }

        // Cache-buster: файл по этому пути может быть перезаписан (переобработка,
        // восстановление) — иначе браузер продолжит играть закэшированную версию.
        return Storage::disk('s3')->url($this->audio_stream_path)
            .'?v='.($this->updated_at?->getTimestamp() ?? 0);
    }

    /** Cover renditions ({size,url} list), inheriting from the release unless overridden. */
    public function coverUrls(): ?array
    {
        if ($this->cover_override_path) {
            return array_map(fn ($size) => [
                'size' => $size,
                'url' => Storage::disk('s3')->url("track-covers/{$this->id}/{$size}.webp"),
            ], Release::COVER_SIZES);
        }

        return $this->release?->coverUrls();
    }

    // --- Search ------------------------------------------------------------

    public function toSearchableArray(): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'artist_names' => $this->artists->pluck('name')->implode(', '),
            'release_title' => $this->release?->title,
        ];
    }

    public function shouldBeSearchable(): bool
    {
        return $this->processing_status === ProcessingStatus::Ready;
    }
}
