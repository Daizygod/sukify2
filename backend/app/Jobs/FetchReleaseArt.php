<?php

namespace App\Jobs;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Models\Release;
use App\Services\Deezer\DeezerClient;
use App\Services\Images\CoverProcessor;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Обложка и выходные данные релиза из Deezer — для альбомов, заведённых
 * импортом из Spotify (в экспорте нет ни картинок, ни дат выхода).
 *
 * Дальше всё идёт по обычному пайплайну обложек: те же рендиции и тот же
 * извлекатель цвета, что и у загруженных вручную.
 */
class FetchReleaseArt implements ShouldQueue
{
    use Queueable;

    public int $timeout = 300;

    public int $tries = 2;

    /**
     * @param  int  $deezerAlbumId  0 — id неизвестен, искать альбом по названию.
     */
    public function __construct(
        public int $releaseId,
        public int $deezerAlbumId,
        public ?string $coverUrl = null,
    ) {
    }

    public function handle(DeezerClient $deezer, CoverProcessor $processor): void
    {
        $release = Release::with('artist:id,name')->find($this->releaseId);
        if (! $release) {
            return;
        }

        $albumId = $this->deezerAlbumId;
        if ($albumId === 0) {
            // Ни один трек релиза не нашёлся по имени — ищем сам альбом.
            $found = $deezer->searchAlbum($release->artist?->name ?? '', $release->title);
            if (! $found) {
                return;
            }
            $albumId = (int) $found['id'];
            $this->coverUrl = $found['cover_xl'] ?? null;
        }

        $album = $deezer->album($albumId);
        $update = ['deezer_id' => $albumId];

        if ($album) {
            if (! empty($album['release_date']) && ! $release->release_date) {
                $update['release_date'] = $album['release_date'];
            }
            $update['type'] = match ($album['record_type'] ?? null) {
                'single' => ReleaseType::Single,
                'compile' => ReleaseType::Compilation,
                'album', 'ep' => ReleaseType::Album,
                default => $release->type,
            };
        }

        $url = $this->coverUrl ?: ($album['cover_xl'] ?? null);
        $needsCover = $release->cover_status !== ProcessingStatus::Ready;

        if ($needsCover && $url && ! str_contains($url, 'cover//')) {
            $this->fetchCover($release, $url, $deezer, $processor, $update);
        } else {
            $release->forceFill($update)->save();
        }
    }

    private function fetchCover(Release $release, string $url, DeezerClient $deezer, CoverProcessor $processor, array $update): void
    {
        $workDir = storage_path('app/media-work/'.uniqid('art_', true));
        @mkdir($workDir, 0775, true);
        $source = $workDir.'/cover.jpg';

        try {
            if (! $deezer->download($url, $source)) {
                $release->forceFill($update)->save();

                return;
            }

            $release->forceFill($update + ['cover_status' => ProcessingStatus::Processing])->save();

            $result = $processor->process($source);
            $disk = Storage::disk('s3');

            // Оригинал не сохраняем: Deezer и так отдаёт 1000×1000, ровно
            // столько же, сколько самая большая рендиция — лишние полгигабайта
            // на трёхтысячную библиотеку ни к чему.
            foreach ($result['renditions'] as $size => $bytes) {
                $disk->put("covers/{$release->id}/{$size}.webp", $bytes['webp']);
                $disk->put("covers/{$release->id}/{$size}.jpg", $bytes['jpg']);
            }

            $release->forceFill($update + [
                'cover_path' => "covers/{$release->id}",
                'cover_status' => ProcessingStatus::Ready,
                'dominant_color_hex' => $result['dominant_color_hex'],
                'text_color_hex' => $result['text_color_hex'],
            ])->save();
        } catch (Throwable $e) {
            $release->forceFill($update + ['cover_status' => ProcessingStatus::Failed])->save();

            throw $e;
        } finally {
            foreach (glob("{$workDir}/*") ?: [] as $file) {
                @unlink($file);
            }
            @rmdir($workDir);
        }
    }
}
