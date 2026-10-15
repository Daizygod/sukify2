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
    // Ревизия аудио (md5 stream.mp3) и его размер — см. streamFileAttributes().
    'audio_rev', 'audio_size', 'audio_updated_at',
    'file_size_original', 'cover_override_path',
    'processing_status', 'processing_error',
    'spotify_uri', 'deezer_id', 'preview_only', 'enrich_status',
    // Синтетический тон из demo:generate-audio — его можно перегенерировать,
    // настоящую загрузку — никогда (ProcessTrackAudio снимает флаг).
    'demo_audio',
    // Анализ аудио для микса: темп, сетка долей, тональность, пики волны.
    'bpm', 'beat_offset_ms', 'beat_confidence', 'beats',
    'musical_key', 'musical_scale', 'key_strength', 'camelot',
    'waveform_full', 'waveform_bass', 'waveform_bands', 'waveform_buckets',
    'analysis_status', 'analysis_error', 'analyzed_at',
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
            'preview_only' => 'boolean',
            'file_size_original' => 'integer',
            'audio_size' => 'integer',
            'audio_updated_at' => 'datetime',
            'plays_count' => 'integer',
            'likes_count' => 'integer',
            'bpm' => 'float',
            'beat_offset_ms' => 'integer',
            'beat_confidence' => 'float',
            'beats' => 'array',
            'key_strength' => 'float',
            'waveform_bands' => 'array',
            'waveform_buckets' => 'integer',
            'analyzed_at' => 'datetime',
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
        // замена аудио) — иначе браузер продолжит играть закэшированную версию.
        // Версия — ревизия самого файла: updated_at сдвигается от каждого
        // прослушивания, и браузер качал бы трек заново после каждого раза.
        return Storage::disk('s3')->url($this->audio_stream_path)
            .'?v='.($this->audio_rev ?: ($this->updated_at?->getTimestamp() ?? 0));
    }

    /**
     * Поля трека после записи stream.mp3: путь, ревизия (md5) и размер файла.
     *
     * Все, кто пишут аудио (обработка загрузки, превью Deezer, демо-тоны),
     * берут поля отсюда — иначе приложение не узнает, что файл поменялся.
     */
    public static function streamFileAttributes(string $key, string $localPath): array
    {
        return [
            'audio_stream_path' => $key,
            'audio_rev' => md5_file($localPath) ?: null,
            'audio_size' => filesize($localPath) ?: null,
            'audio_updated_at' => now(),
        ];
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
