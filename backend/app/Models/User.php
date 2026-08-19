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
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
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

    /**
     * Свободный @handle для ссылки /user/{username}.
     *
     * Без него профиль был доступен только по числовому id, а роут искал
     * строго по username — ссылка вида /user/3 отдавала пустой экран.
     */
    public static function generateUsername(string $name, string $email): string
    {
        $base = Str::slug(Str::ascii($name), '_');
        if ($base === '') {
            $base = Str::slug(Str::ascii(Str::before($email, '@')), '_');
        }
        $base = Str::limit(trim($base, '_') ?: 'user', 24, '');

        $candidate = $base;
        $n = 1;
        while (static::where('username', $candidate)->exists()) {
            $candidate = $base.'_'.(++$n);
        }

        return $candidate;
    }

    /** Профиль по @handle или по числовому id (старые ссылки). */
    public static function findByHandle(string $handle): ?self
    {
        return static::where('username', $handle)
            ->when(ctype_digit($handle), fn ($q) => $q->orWhere('id', (int) $handle))
            ->first();
    }

    /** Взаимная подписка — «друзья» (как в активности друзей Spotify). */
    public function isFriendOf(self $other): bool
    {
        return $this->following()->whereKey($other->id)->exists()
            && $other->following()->whereKey($this->id)->exists();
    }

    /** Друзья: те, с кем подписки взаимны. */
    public function friends(): BelongsToMany
    {
        return $this->following()->whereIn(
            'users.id',
            DB::table('user_follows')->where('followee_id', $this->id)->select('follower_id')
        );
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

    /** Пользователи, на которых подписан этот юзер. */
    public function following(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'user_follows', 'follower_id', 'followee_id')
            ->withTimestamps();
    }

    /** Подписчики этого юзера. */
    public function followers(): BelongsToMany
    {
        return $this->belongsToMany(User::class, 'user_follows', 'followee_id', 'follower_id')
            ->withTimestamps();
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
