<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Одна массовая загрузка: архив или папка с музыкой.
 *
 * Файлы лежат на локальном диске (storage/app/bulk/{id}), а не в S3: бакет
 * маленький, а архив нужен ровно на время разбора. Каждый файл — строка
 * {@see BulkUploadItem}, у неё свой статус.
 */
#[Fillable([
    'user_id', 'name', 'kind', 'size_bytes', 'received_bytes', 'files_expected',
    'status', 'stage', 'progress', 'options', 'error', 'finished_at',
])]
class BulkUpload extends Model
{
    public const KIND_ARCHIVE = 'archive';

    public const KIND_FOLDER = 'folder';

    /** Пока в этих статусах, страница загрузки опрашивает сервер. */
    public const ACTIVE = ['queued', 'unpacking', 'reading', 'matching', 'importing'];

    public const DEFAULT_OPTIONS = [
        // Файлам, которых нет в каталоге, заводить артиста, релиз и трек.
        'create_missing' => true,
        // Трекам, у которых уже есть полная версия, файл не ставить.
        'replace_full' => false,
        // Сверять с превью Deezer: не вырезаны ли слова.
        'check_censorship' => true,
    ];

    protected function casts(): array
    {
        return [
            'options' => 'array',
            'size_bytes' => 'integer',
            'received_bytes' => 'integer',
            'files_expected' => 'integer',
            'progress' => 'integer',
            'finished_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function items(): HasMany
    {
        return $this->hasMany(BulkUploadItem::class);
    }

    /** Каталог загрузки на локальном диске (тесты подменяют корень: bulk.dir). */
    public function dir(): string
    {
        return rtrim((string) (config('bulk.dir') ?: storage_path('app/bulk')), '/').'/'.$this->id;
    }

    /** Куда докачивается архив, пока не пришёл целиком. */
    public function partPath(): string
    {
        return $this->dir().'/upload.part';
    }

    public function archivePath(): string
    {
        return $this->dir().'/archive.'.$this->archiveExt();
    }

    /** Расширение архива по имени: «music.tar.gz» → gz (bsdtar разберётся сам). */
    public function archiveExt(): string
    {
        return strtolower(pathinfo($this->name, PATHINFO_EXTENSION)) ?: 'zip';
    }

    public function option(string $key): bool
    {
        return (bool) (($this->options ?? []) + self::DEFAULT_OPTIONS)[$key];
    }

    public function isActive(): bool
    {
        return in_array($this->status, self::ACTIVE, true);
    }

    public function markStage(string $stage, ?int $progress = null): void
    {
        $this->forceFill(array_filter([
            'stage' => $stage,
            'progress' => $progress === null ? null : max(0, min(100, $progress)),
        ], fn ($v) => $v !== null))->save();
    }

    /**
     * Все файлы разобраны? Тогда загрузка закончена. Зовут после каждого
     * файла: последний закрывает загрузку и убирает за ней диск.
     */
    public function refreshCompletion(): void
    {
        $fresh = $this->fresh();
        if (! $fresh || $fresh->status !== 'importing') {
            return;
        }

        $busy = $fresh->items()->whereIn('status', ['queued', 'processing'])->exists();
        if ($busy) {
            $left = $fresh->items()->whereIn('status', ['queued', 'processing'])->count();
            $total = $fresh->items()->where('status', '!=', 'asset')->count();
            $fresh->markStage("Ставлю полные версии: осталось {$left}", 100 - (int) round(100 * $left / max(1, $total)));

            return;
        }

        $review = $fresh->items()->where('status', 'review')->count();
        $fresh->forceFill([
            'status' => 'done',
            'stage' => $review > 0 ? "Готово, ждут решения: {$review}" : 'Готово',
            'progress' => 100,
            'finished_at' => now(),
        ])->save();
    }

    /** Счётчики по статусам — для вкладок и списка загрузок. */
    public function counts(): array
    {
        $rows = $this->items()
            ->selectRaw('status, count(*) as n')
            ->groupBy('status')
            ->pluck('n', 'status');

        $get = fn (string ...$statuses) => (int) collect($statuses)->sum(fn ($s) => $rows[$s] ?? 0);

        return [
            'all' => (int) $rows->except('asset')->sum(),
            'pending' => $get('pending'),
            'review' => $get('review'),
            'importing' => $get('queued', 'processing'),
            'done' => $get('done'),
            'skipped' => $get('skipped'),
            'duplicate' => $get('duplicate'),
            'failed' => $get('failed'),
            'suspect' => $this->items()->whereIn('censor', ['suspect', 'differs'])->count(),
        ];
    }
}
