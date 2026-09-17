<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

#[Fillable([
    'user_id', 'discord_id', 'username', 'global_name', 'avatar',
    'access_token', 'refresh_token', 'token_expires_at',
    'presence_enabled', 'show_when_paused', 'show_button', 'show_cover',
])]
#[Hidden(['access_token', 'refresh_token'])]
class DiscordConnection extends Model
{
    // Одна привязка на пользователя — PK совпадает с user_id (как у настроек).
    protected $primaryKey = 'user_id';

    public $incrementing = false;

    protected $keyType = 'int';

    protected function casts(): array
    {
        return [
            // Токены лежат зашифрованными: утечка дампа БД не даёт доступ к Discord.
            'access_token' => 'encrypted',
            'refresh_token' => 'encrypted',
            'token_expires_at' => 'datetime',
            'presence_enabled' => 'boolean',
            'show_when_paused' => 'boolean',
            'show_button' => 'boolean',
            'show_cover' => 'boolean',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /** Аватарка из CDN Discord; у пользователей без неё — дефолтная по индексу. */
    public function avatarUrl(): string
    {
        if ($this->avatar) {
            $ext = str_starts_with($this->avatar, 'a_') ? 'gif' : 'png';

            return "https://cdn.discordapp.com/avatars/{$this->discord_id}/{$this->avatar}.{$ext}?size=128";
        }

        $index = ((int) $this->discord_id >> 22) % 6;

        return "https://cdn.discordapp.com/embed/avatars/{$index}.png";
    }

    /** Имя для интерфейса: global_name («отображаемое») с откатом на @username. */
    public function displayName(): string
    {
        return $this->global_name ?: $this->username;
    }
}
