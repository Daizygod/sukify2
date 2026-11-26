<?php

namespace App\Jobs;

use App\Models\BulkUpload;
use App\Models\BulkUploadItem;
use App\Services\Bulk\ArchiveUnpacker;
use App\Services\Bulk\BulkPlanner;
use App\Services\Bulk\DiskSpace;
use App\Services\Bulk\TagReader;
use Illuminate\Contracts\Queue\ShouldBeUnique;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use RuntimeException;
use Throwable;

/**
 * Подготовка массовой загрузки: распаковать архив, прочитать теги каждого
 * файла, разложить по трекам каталога и поставить в очередь полные версии.
 *
 * Можно запускать повторно (рестарт воркера при деплое, «Повторить» в
 * админке): каждый шаг продолжает с того места, где остановился.
 */
class PrepareBulkUpload implements ShouldBeUnique, ShouldQueue
{
    use Queueable;

    public int $timeout = 3600;

    public int $tries = 3;

    public int $uniqueFor = 3600;

    public function __construct(public int $uploadId)
    {
        $this->onQueue('bulk');
    }

    public function uniqueId(): string
    {
        return (string) $this->uploadId;
    }

    public function handle(ArchiveUnpacker $unpacker, TagReader $reader): void
    {
        $upload = BulkUpload::find($this->uploadId);
        if (! $upload || ! in_array($upload->status, ['queued', 'unpacking', 'reading', 'matching', 'failed'], true)) {
            return;
        }

        try {
            $upload->forceFill(['error' => null])->save();

            if ($upload->kind === BulkUpload::KIND_ARCHIVE && is_file($upload->archivePath())) {
                $this->unpack($upload, $unpacker);
            }

            if (! $upload->items()->where('status', '!=', 'asset')->exists()) {
                throw new RuntimeException('Музыки не нашлось: подойдут mp3, flac, m4a, ogg, opus, wav, ape, wma.');
            }

            $this->read($upload, $reader);

            $upload->forceFill(['status' => 'matching', 'stage' => 'Ищу треки в каталоге', 'progress' => 0])->save();
            (new BulkPlanner())->plan($upload);

            $this->dispatchImports($upload);
        } catch (Throwable $e) {
            $upload->forceFill([
                'status' => 'failed',
                'stage' => null,
                'error' => mb_substr($e->getMessage(), 0, 1000),
            ])->save();
            report($e);
        }
    }

    private function unpack(BulkUpload $upload, ArchiveUnpacker $unpacker): void
    {
        $upload->forceFill(['status' => 'unpacking', 'stage' => 'Распаковываю архив', 'progress' => 0])->save();

        // Прошлая попытка могла оборваться посередине — начинаем распаковку заново.
        $upload->items()->delete();
        $unpacker->removeTree($upload->dir().'/files');
        @mkdir($upload->dir().'/files', 0775, true);

        $n = 0;
        $tick = 0.0;
        $unpacker->unpack(
            $upload->archivePath(),
            $upload->archiveExt(),
            $upload->dir().'/work',
            function (string $path, string $file, int $size) use ($upload, &$n) {
                $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
                $local = 'files/'.(++$n).'.'.$ext;
                if (! @rename($file, $upload->dir().'/'.$local)) {
                    copy($file, $upload->dir().'/'.$local);
                }
                BulkUploadItem::create([
                    'bulk_upload_id' => $upload->id,
                    'path' => $path,
                    'ext' => mb_substr($ext, 0, 8),
                    'size_bytes' => $size,
                    'local_path' => $local,
                    'status' => BulkUploadItem::isImageExt($ext) ? 'asset' : 'pending',
                ]);
            },
            function (int $done, int $total) use ($upload, &$tick) {
                if (microtime(true) - $tick < 2) {
                    return;
                }
                $tick = microtime(true);
                $upload->markStage(
                    'Распаковываю: '.DiskSpace::human($done).' из '.DiskSpace::human(max($done, $total)),
                    (int) floor(100 * $done / max(1, $total)),
                );
            },
        );

        // Архив больше не нужен — место на диске важнее.
        @unlink($upload->archivePath());
        $unpacker->removeTree($upload->dir().'/work');
    }

    private function read(BulkUpload $upload, TagReader $reader): void
    {
        $total = $upload->items()->where('status', '!=', 'asset')->count();
        $done = $upload->items()->where('status', '!=', 'asset')->whereNotNull('tags')->count();
        $upload->forceFill(['status' => 'reading', 'stage' => "Читаю теги: {$done} из {$total}", 'progress' => 0])->save();

        $pending = $upload->items()->where('status', 'pending')->whereNull('tags')->orderBy('id')->get();
        foreach ($pending as $item) {
            $item->setRelation('upload', $upload);
            $file = $item->absolutePath();

            $tags = $file && is_file($file)
                ? $reader->read($file, $item->path)
                : ['ok' => false, 'error' => 'Файл пропал с диска'];

            if ($tags['ok']) {
                $item->update(['tags' => $tags]);
            } else {
                $item->update([
                    'tags' => $tags,
                    'status' => 'failed',
                    'reason' => 'unreadable',
                    'error' => $tags['error'] ?? 'Не читается',
                ]);
            }

            if (++$done % 25 === 0 || $done === $total) {
                $upload->markStage("Читаю теги: {$done} из {$total}", (int) floor(100 * $done / max(1, $total)));
            }
        }
    }

    private function dispatchImports(BulkUpload $upload): void
    {
        $queued = $upload->items()->where('status', 'queued')->orderBy('id')->pluck('id');

        $upload->forceFill([
            'status' => 'importing',
            'stage' => 'Ставлю полные версии: осталось '.$queued->count(),
            'progress' => 0,
        ])->save();

        foreach ($queued as $id) {
            ImportBulkItem::dispatch($id);
        }

        // Ставить нечего (всё уже есть или ждёт решения) — загрузка сразу готова.
        $upload->refreshCompletion();
    }
}
