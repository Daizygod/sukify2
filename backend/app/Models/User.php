<?php

namespace App\Models;

use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Relations\BelongsToMany;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;
use Laravel\Sanctum\HasApiTokens;

#[Fillable(['name', 'username', 'email', 'password', 'avatar_path'])]
#[Hidden(['password', 'remember_token'])]
class User extends Authenticatable
{
    /** @use HasFactory<UserFactory> */
    use HasApiTokens, HasFactory, Notifiable;

    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'password' => 'hashed',
            'is_admin' => 'boolean',
            'is_banned' => 'boolean',
            'banned_at' => 'datetime',
        ];
    }

    // --- Relations ---------------------------------------------------------

    public function playlists(): HasMany
    {
        return $this->hasMany(Playlist::class);
    }

    public function playbackSettings(): HasOne
    {
        return $this->hasOne(UserPlaybackSetting::class);
    }

    public function likedTracks(): BelongsToMany
    {
        return $this->belongsToMany(Track::class, 'liked_tracks')
            ->withPivot('created_at')
            ->orderByPivot('created_at', 'desc');
    }

    public function likedAlbums(): BelongsToMany
    {
        return $this->belongsToMany(Release::class, 'liked_albums')
            ->withPivot('created_at')
            ->orderByPivot('created_at', 'desc');
    }

    public function followedArtists(): BelongsToMany
    {
        return $this->belongsToMany(Artist::class, 'followed_artists')
            ->withPivot('created_at')
            ->orderByPivot('created_at', 'desc');
    }

    public function plays(): HasMany
    {
        return $this->hasMany(TrackPlay::class);
    }

    public function hostedSessions(): HasMany
    {
        return $this->hasMany(ListeningSession::class, 'host_user_id');
    }

    public function transitionLikes(): BelongsToMany
    {
        return $this->belongsToMany(
            TrackTransition::class,
            'transition_likes',
            'user_id',
            'transition_id'
        );
    }

    // --- Helpers -----------------------------------------------------------

    /** Lazily ensure a settings row exists with sensible defaults. */
    public function settings(): UserPlaybackSetting
    {
        return $this->playbackSettings()->firstOrCreate([], [
            'target_loudness_lufs' => (float) config('playback.target_lufs', -14),
            'default_crossfade_seconds' => (int) config('playback.default_crossfade_seconds', 0),
            'smart_shuffle_enabled' => false,
        ]);
    }
}
