<?php

namespace App\Jobs;

use App\Models\BulkUpload;
use App\Services\Bulk\ArchiveUnpacker;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

/**
 * Убрать загрузку: файлы с диска и записи о ней. Уже поставленные полные
 * версии остаются в каталоге.
 *
 * Отдельной задачей, а не в контроллере: распакованное писал воркер, и у
 * php-fpm нет прав это удалить.
 */
class DiscardBulkUpload implements ShouldQueue
{
    use Queueable;

    public int $timeout = 600;

    /** @param  bool  $keepRecord  только освободить диск (старые загрузки) */
    public function __construct(public int $uploadId, public bool $keepRecord = false)
    {
    }

    public function handle(ArchiveUnpacker $files): void
    {
        $upload = BulkUpload::find($this->uploadId);
        if (! $upload) {
            return;
        }

        $files->removeTree($upload->dir());

        if ($this->keepRecord) {
            // Файлов больше нет — ждать решения тоже нечему.
            $upload->items()->whereNotNull('local_path')->update(['local_path' => null]);
            $upload->items()->whereIn('status', ['pending', 'review', 'queued'])
                ->update(['status' => 'skipped', 'reason' => 'expired']);

            return;
        }

        $upload->delete();
    }
}
