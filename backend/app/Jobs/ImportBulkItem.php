<?php

namespace App\Jobs;

use App\Enums\ProcessingStatus;
use App\Models\BulkUpload;
use App\Models\BulkUploadItem;
use App\Models\Track;
use App\Services\Audio\AudioProcessor;
use App\Services\Bulk\BulkPlanner;
use App\Services\Bulk\CensorCheck;
use App\Services\Bulk\StorageRejected;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Storage;
use RuntimeException;
use Throwable;

/**
 * Один файл массовой загрузки → полная версия трека (или нескольких: одна
 * запись бывает и в сингле, и в альбоме).
 *
 * Тот же путь, что у «Заменить аудио»: stream.mp3 256 kbps, громкость,
 * длительность, новая ревизия — приложения перекачают файл сами. Оригинал не
 * храним. По ходу — сверка с превью Deezer: не вырезаны ли слова.
 */
class ImportBulkItem implements ShouldQueue
{
    use Queueable;

    public int $timeout = 1800;

    public int $tries = 2;

    /** Столько файлов подряд не легло в хранилище — останавливаем загрузку. */
    private const STORAGE_FAILS_TO_PAUSE = 3;

    public function __construct(public int $itemId)
    {
        $this->onQueue('bulk');
    }

    public function handle(AudioProcessor $audio, CensorCheck $censor): void
    {
        $item = BulkUploadItem::with('upload')->find($this->itemId);
        if (! $item || ! $this->claim($item)) {
            return;
        }

        $upload = $item->upload;
        if ($upload->status === 'canceled') {
            $item->forceFill(['status' => 'skipped', 'reason' => 'canceled'])->save();

            return;
        }
        if ($upload->status === 'paused') {
            // Хранилище переполнено — вернём файл в очередь до «Продолжить».
            $item->forceFill(['status' => 'queued'])->save();

            return;
        }

        $work = storage_path('app/media-work/'.uniqid('bulk_', true));

        try {
            // «Завести новым» из админки: трека ещё нет — заводим здесь, под
            // тем же замком, что и раскладка, чтобы альбом не появился дважды.
            if ($item->action === 'create' && ! $item->targetIds()) {
                $planner = new BulkPlanner();
                [$trackId, $releaseId, $isNew] = Cache::lock('bulk:catalog', 300)
                    ->block(120, fn () => $planner->createTrack($item));
                $item->forceFill(['track_id' => $trackId, 'track_ids' => [$trackId]])->save();
                if ($isNew) {
                    $planner->covers($upload, [$releaseId => [$item]]);
                }
            }

            $source = $item->absolutePath();
            if (! $source || ! is_file($source)) {
                throw new RuntimeException('Файла уже нет на диске — загрузи его ещё раз.');
            }
            $tracks = Track::whereIn('id', $item->targetIds())->get();
            if ($tracks->isEmpty()) {
                throw new RuntimeException('Трек удалили из каталога.');
            }

            @mkdir($work, 0775, true);
            $input = $source;
            if ($item->ext === 'ape') {
                // APE ffmpeg читает нестабильно — сначала в FLAC, как и обычная загрузка.
                $input = $audio->transcodeToFlac($source, "{$work}/original.flac");
            }
            $stream = $audio->makeStreamRendition($input, "{$work}/stream.mp3");
            $loudness = $audio->measureLoudness($input);
            $duration = $audio->probeDurationMs($input);

            $verdict = null;
            if ($upload->option('check_censorship')) {
                try {
                    $verdict = $censor->check($tracks->first(), $stream, $duration);
                } catch (Throwable $e) {
                    $verdict = ['status' => 'unknown', 'info' => ['note' => mb_substr($e->getMessage(), 0, 200)]];
                }
            }

            $disk = Storage::disk('s3');
            foreach ($tracks as $track) {
                $key = "audio/{$track->id}/stream.mp3";
                $handle = fopen($stream, 'r');
                $stored = $disk->writeStream($key, $handle);
                if (is_resource($handle)) {
                    fclose($handle);
                }
                if (! $stored) {
                    throw new StorageRejected('Хранилище не приняло файл — возможно, в бакете кончилось место.');
                }

                if ($track->audio_original_path) {
                    $disk->delete($track->audio_original_path);
                }

                $track->update([
                    ...Track::streamFileAttributes($key, $stream),
                    'audio_original_path' => null,
                    'loudness_lufs' => $loudness,
                    'duration_ms' => $duration,
                    'file_size_original' => $item->size_bytes,
                    'processing_status' => ProcessingStatus::Ready,
                    'processing_error' => null,
                    'demo_audio' => false,
                    // Превью Deezer больше не она — теперь здесь полная версия.
                    'preview_only' => false,
                ]);

                // Темп и волну — потом: сначала всем трекам полные версии.
                AnalyzeTrackAudio::dispatch($track->id)->onQueue('bulk');
            }

            $item->forceFill([
                'status' => 'done',
                'error' => null,
                'local_path' => null,
                'censor' => $verdict['status'] ?? null,
                'censor_info' => $verdict['info'] ?? null,
            ])->save();
            @unlink($source);
            Cache::forget($this->failKey($upload));
        } catch (Throwable $e) {
            $item->forceFill(['status' => 'failed', 'error' => mb_substr($e->getMessage(), 0, 1000)])->save();
            if ($e instanceof StorageRejected) {
                $this->countStorageFailure($upload);
            }
        } finally {
            $this->removeDir($work);
            $upload->refreshCompletion();
        }
    }

    public function failed(Throwable $e): void
    {
        BulkUploadItem::whereKey($this->itemId)
            ->whereIn('status', ['queued', 'processing'])
            ->update(['status' => 'failed', 'error' => mb_substr($e->getMessage(), 0, 1000)]);

        if ($item = BulkUploadItem::with('upload')->find($this->itemId)) {
            $item->upload?->refreshCompletion();
        }
    }

    /**
     * Забрать файл себе. Задача могла прийти дважды (повтор после рестарта),
     * а «processing» дольше 40 минут — это упавший воркер, файл можно брать.
     */
    private function claim(BulkUploadItem $item): bool
    {
        return BulkUploadItem::whereKey($item->id)
            ->where(fn ($q) => $q->where('status', 'queued')->orWhere(
                fn ($q) => $q->where('status', 'processing')->where('updated_at', '<', now()->subMinutes(40))
            ))
            ->update(['status' => 'processing', 'updated_at' => now()]) === 1;
    }

    /**
     * Бакет на тарифе с потолком: когда место кончится, каждый следующий файл
     * упадёт так же. После трёх подряд ставим загрузку на паузу.
     */
    private function countStorageFailure(BulkUpload $upload): void
    {
        $key = $this->failKey($upload);
        Cache::add($key, 0, now()->addDay());
        if (Cache::increment($key) >= self::STORAGE_FAILS_TO_PAUSE) {
            BulkUpload::whereKey($upload->id)->where('status', 'importing')->update([
                'status' => 'paused',
                'stage' => 'Хранилище не принимает файлы — проверь место в бакете и нажми «Продолжить»',
            ]);
        }
    }

    private function failKey(BulkUpload $upload): string
    {
        return "bulk:{$upload->id}:storage-fails";
    }

    private function removeDir(string $dir): void
    {
        if (! is_dir($dir)) {
            return;
        }
        foreach (glob("{$dir}/*") ?: [] as $file) {
            @unlink($file);
        }
        @rmdir($dir);
    }
}
