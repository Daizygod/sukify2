<?php

namespace App\Console\Commands;

use App\Jobs\DiscardBulkUpload;
use App\Models\BulkUpload;
use Illuminate\Console\Command;

/**
 * Освобождает диск от брошенных массовых загрузок: недокачанные архивы через
 * 3 дня, файлы, которые так и ждут решения, — через 14.
 */
class CleanupBulkUploads extends Command
{
    protected $signature = 'bulk:cleanup';

    protected $description = 'Удалить с диска брошенные и давно разобранные массовые загрузки';

    public function handle(): int
    {
        $abandoned = BulkUpload::where('status', 'uploading')
            ->where('updated_at', '<', now()->subDays(3))
            ->get();
        foreach ($abandoned as $upload) {
            $upload->forceFill(['status' => 'canceled', 'stage' => 'Брошена недокачанной'])->save();
            DiscardBulkUpload::dispatch($upload->id);
        }

        $stale = BulkUpload::whereIn('status', ['done', 'failed', 'paused'])
            ->where('updated_at', '<', now()->subDays(14))
            ->whereHas('items', fn ($q) => $q->whereNotNull('local_path'))
            ->get();
        foreach ($stale as $upload) {
            DiscardBulkUpload::dispatch($upload->id, keepRecord: true);
        }

        $this->info("Брошенных: {$abandoned->count()}, освобождаю диск у старых: {$stale->count()}");

        return self::SUCCESS;
    }
}
