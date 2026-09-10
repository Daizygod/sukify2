<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Одна загрузка архива «Download your data» из Spotify.
 *
 * Архив может быть любым из трёх, что присылает Spotify: Account Data
 * (библиотека и плейлисты), Extended Streaming History (годы прослушиваний)
 * или Technical Log (телеметрия — из него брать нечего).
 */
#[Fillable([
    'user_id', 'original_name', 'size_bytes', 'archive_path', 'kind',
    'status', 'stage', 'progress', 'enrich_total', 'enrich_done',
    'summary', 'error',
])]
class SpotifyImport extends Model
{
    protected function casts(): array
    {
        return [
            'summary' => 'array',
            'size_bytes' => 'integer',
            'progress' => 'integer',
            'enrich_total' => 'integer',
            'enrich_done' => 'integer',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function plays(): HasMany
    {
        return $this->hasMany(ImportedPlay::class);
    }

    /** Отметить шаг разбора: строка для UI + процент. */
    public function markStage(string $stage, ?int $progress = null): void
    {
        $this->update(array_filter([
            'stage' => $stage,
            'progress' => $progress,
        ], fn ($v) => $v !== null));
    }
}
