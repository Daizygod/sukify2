<?php

namespace App\Jobs;

use App\Models\Artist;
use App\Models\Release;
use App\Models\SpotifyImport;
use App\Services\Spotify\CatalogImporter;
use App\Services\Spotify\HistoryImporter;
use App\Services\Spotify\SpotifyArchive;
use Illuminate\Contracts\Queue\ShouldBeUnique;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Разбор архива «Download your data» из Spotify.
 *
 * Долгая задача: 200 МБ JSON истории, 3 тысячи треков и 26 плейлистов. Поэтому
 * прогресс пишется в spotify_imports, а страница /import его опрашивает.
 * Обложки и превью догружаются уже отдельной очередью.
 */
class ImportSpotifyArchive implements ShouldBeUnique, ShouldQueue
{
    use Queueable;

    public int $timeout = 1800;

    public int $tries = 1;

    /** Замок на случай, если очередь успеет перевыдать задачу до её конца. */
    public int $uniqueFor = 3600;

    public function __construct(public int $importId)
    {
    }

    public function uniqueId(): string
    {
        return (string) $this->importId;
    }

    public function handle(): void
    {
        // Файлы Extended History разворачиваются в сотни мегабайт массивов.
        @ini_set('memory_limit', '1G');

        $import = SpotifyImport::with('user')->find($this->importId);
        if (! $import || ! $import->user) {
            return;
        }

        $disk = Storage::disk('s3');
        $workDir = storage_path('app/media-work');
        @mkdir($workDir, 0775, true);
        $local = $workDir.'/'.uniqid('spotify_', true).'.zip';
        $archive = null;

        try {
            $import->update(['status' => 'parsing', 'stage' => 'Открываю архив', 'progress' => 2]);

            $source = $disk->readStream($import->archive_path);
            if (! $source) {
                throw new \RuntimeException('Загруженный архив не найден в хранилище.');
            }
            $target = fopen($local, 'w');
            stream_copy_to_stream($source, $target);
            fclose($target);
            fclose($source);

            $archive = new SpotifyArchive($local);
            $kind = $archive->kind();
            $summary = ['kind' => $kind, 'extras' => $archive->extras()];

            if ($kind === 'technical' || $kind === 'unknown') {
                $summary['note'] = $kind === 'technical'
                    ? 'Это Technical Log Information — телеметрия плеера. Музыкальных данных в нём нет.'
                    : 'Не нашёл внутри знакомых файлов Spotify.';
                $import->update([
                    'kind' => $kind, 'status' => 'done', 'progress' => 100,
                    'stage' => 'Переносить нечего', 'summary' => $summary,
                ]);

                return;
            }

            $import->update(['kind' => $kind]);

            // --- Библиотека и плейлисты ------------------------------------
            $libraryEntry = $archive->libraryEntry();
            $library = $libraryEntry ? $archive->read($libraryEntry) : null;
            $playlistFiles = [];
            foreach ($archive->playlistEntries() as $entry) {
                if ($data = $archive->read($entry)) {
                    $playlistFiles[] = $data;
                }
            }

            if ($library || $playlistFiles) {
                $import->markStage('Завожу артистов, альбомы и треки', 10);
                $summary['library'] = (new CatalogImporter($import->user))->import($library, $playlistFiles);

                // Каталог заводится сырыми INSERT'ами ради скорости, так что
                // Scout о новых строках не знает — индексируем явно, иначе
                // импортированное не найдётся поиском.
                $import->markStage('Обновляю поиск', 35);
                Artist::where('imported', true)->searchable();
                Release::where('imported', true)->searchable();
            }
            unset($library, $playlistFiles);

            // --- История ---------------------------------------------------
            $history = new HistoryImporter($import->user, $import);
            $summary['history'] = $history->import($archive, function (int $done, int $total) use ($import) {
                $import->markStage(
                    "Разбираю историю прослушиваний: {$done} из {$total}",
                    40 + (int) round(45 * $done / max(1, $total)),
                );
            });

            if (($summary['history']['plays'] ?? 0) > 0) {
                $import->markStage('Связываю историю с каталогом', 90);
                $summary['history']['linked'] = $history->linkToCatalog();
            }

            $archive->close();
            $archive = null;

            // --- Обложки и превью ------------------------------------------
            $pending = DB::table('tracks')->where('enrich_status', 'pending')->count();
            $import->update([
                'status' => $pending > 0 ? 'enriching' : 'done',
                'stage' => $pending > 0 ? 'Качаю обложки и превью из Deezer' : 'Готово',
                'progress' => $pending > 0 ? 95 : 100,
                'enrich_total' => $pending,
                'enrich_done' => 0,
                'summary' => $summary,
            ]);

            if ($pending > 0) {
                EnrichSpotifyTracks::dispatch($import->id);
            }
        } catch (Throwable $e) {
            $import->update([
                'status' => 'failed',
                'stage' => null,
                'error' => mb_substr($e->getMessage(), 0, 1000),
            ]);

            throw $e;
        } finally {
            $archive?->close();
            @unlink($local);
            if ($import->archive_path) {
                $disk->delete($import->archive_path);
                $import->forceFill(['archive_path' => null])->save();
            }
        }
    }

    public function failed(Throwable $e): void
    {
        SpotifyImport::where('id', $this->importId)->update([
            'status' => 'failed',
            'error' => mb_substr($e->getMessage(), 0, 1000),
        ]);
    }
}
