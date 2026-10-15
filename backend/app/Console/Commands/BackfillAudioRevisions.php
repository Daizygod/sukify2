<?php

namespace App\Console\Commands;

use App\Models\Track;
use Aws\S3\Exception\S3Exception;
use Illuminate\Console\Command;
use Illuminate\Filesystem\AwsS3V3Adapter;
use Illuminate\Support\Facades\Storage;

/**
 * Проставляет ревизию аудио (md5 stream.mp3) и размер трекам, залитым до
 * появления этих полей. Новые загрузки пишут их сами (Track::streamFileAttributes).
 *
 * Скачивать файлы не нужно: у простого PUT в S3 ETag — это и есть md5
 * содержимого, хватает HeadObject. Скачиваем только объекты, залитые
 * мультипартом (ETag вида «…-2»).
 */
class BackfillAudioRevisions extends Command
{
    protected $signature = 'tracks:audio-rev
        {--force : Пересчитать и у треков, где ревизия уже есть}
        {--chunk=200 : Сколько треков брать за раз}';

    protected $description = 'Ревизия аудио (md5) и размер файла для треков без них';

    public function handle(): int
    {
        /** @var AwsS3V3Adapter $disk */
        $disk = Storage::disk('s3');

        $query = Track::query()
            ->whereNotNull('audio_stream_path')
            ->when(! $this->option('force'), fn ($q) => $q->whereNull('audio_rev'));

        $total = (clone $query)->count();
        if ($total === 0) {
            $this->info('У всех треков с аудио ревизия уже есть.');

            return self::SUCCESS;
        }

        $bar = $this->output->createProgressBar($total);
        $missing = [];

        $query->select(['id', 'audio_stream_path'])
            ->chunkById(max(1, (int) $this->option('chunk')), function ($tracks) use ($disk, $bar, &$missing) {
                foreach ($tracks as $track) {
                    [$rev, $size] = $this->revisionOf($disk, $track->audio_stream_path);

                    if ($rev === null) {
                        $missing[] = $track->id;
                    } else {
                        // Мимо Eloquent: updated_at трогать незачем — файл не менялся.
                        Track::whereKey($track->id)->toBase()->update([
                            'audio_rev' => $rev,
                            'audio_size' => $size,
                            'audio_updated_at' => now(),
                        ]);
                    }

                    $bar->advance();
                }
            });

        $bar->finish();
        $this->newLine();

        if ($missing) {
            $this->warn('Файла нет в хранилище у '.count($missing).' трек(ов): '.implode(', ', array_slice($missing, 0, 30)));
        }

        $this->info('Готово: '.($total - count($missing)).' из '.$total.'.');

        return self::SUCCESS;
    }

    /** @return array{0: ?string, 1: ?int} [md5, размер] или [null, null], если объекта нет */
    private function revisionOf(AwsS3V3Adapter $disk, string $path): array
    {
        try {
            $head = $disk->getClient()->headObject([
                'Bucket' => config('filesystems.disks.s3.bucket'),
                // path() добавляет корневой префикс диска (STORAGE_ROOT_PREFIX).
                'Key' => $disk->path($path),
            ]);
        } catch (S3Exception $e) {
            if ($e->getStatusCode() === 404) {
                return [null, null];
            }
            throw $e;
        }

        $size = (int) $head['ContentLength'];
        $etag = strtolower(trim((string) $head['ETag'], '"'));

        if (preg_match('/^[a-f0-9]{32}$/', $etag)) {
            return [$etag, $size];
        }

        // Мультипарт: ETag — не md5 файла, считаем сами потоком.
        $stream = $disk->readStream($path);
        if (! $stream) {
            return [null, null];
        }
        $ctx = hash_init('md5');
        hash_update_stream($ctx, $stream);
        fclose($stream);

        return [hash_final($ctx), $size];
    }
}
