<?php

namespace App\Console\Commands;

use App\Models\Artist;
use App\Models\Release;
use App\Services\Images\CoverProcessor;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Перегенерирует «фирменные» цвета обложек/аватаров новым извлекателем
 * (VibrantExtractor). Запускать после изменения алгоритма извлечения цвета.
 */
class RecolorCovers extends Command
{
    protected $signature = 'covers:recolor {--releases} {--artists} {--force}';

    protected $description = 'Re-extract dominant/text colours for releases and artists';

    public function handle(CoverProcessor $processor): int
    {
        // Без флагов — обрабатываем и релизы, и артистов.
        $both = ! $this->option('releases') && ! $this->option('artists');

        if ($both || $this->option('releases')) {
            $this->recolorReleases($processor);
        }
        if ($both || $this->option('artists')) {
            $this->recolorArtists($processor);
        }

        return self::SUCCESS;
    }

    private function recolorReleases(CoverProcessor $processor): void
    {
        $disk = Storage::disk('s3');
        $releases = Release::whereNotNull('cover_path')->get();
        $this->info("Releases: {$releases->count()}");
        $bar = $this->output->createProgressBar($releases->count());

        foreach ($releases as $release) {
            try {
                $key = $release->cover_original_path;
                if (! $key || ! $disk->exists($key)) {
                    $key = $release->cover_path.'/1000.jpg';
                }
                if (! $disk->exists($key)) {
                    $bar->advance();

                    continue;
                }
                $tmp = tempnam(sys_get_temp_dir(), 'recolor_');
                file_put_contents($tmp, $disk->get($key));
                [$dominant, $text] = $processor->extractColors($tmp);
                @unlink($tmp);

                $release->update(['dominant_color_hex' => $dominant, 'text_color_hex' => $text]);
            } catch (Throwable $e) {
                $this->warn("  #{$release->id} {$release->title}: {$e->getMessage()}");
            }
            $bar->advance();
        }
        $bar->finish();
        $this->newLine(2);
    }

    private function recolorArtists(CoverProcessor $processor): void
    {
        $disk = Storage::disk('s3');
        // Цвет тянем из баннера, а если его нет — из аватара.
        $artists = Artist::where(fn ($q) => $q->whereNotNull('banner_path')->orWhereNotNull('avatar_path'))->get();
        $this->info("Artists: {$artists->count()}");
        $bar = $this->output->createProgressBar($artists->count());

        foreach ($artists as $artist) {
            try {
                $key = $artist->banner_path ?: $artist->avatar_path;
                if (! $key || ! $disk->exists($key)) {
                    $bar->advance();

                    continue;
                }
                $tmp = tempnam(sys_get_temp_dir(), 'recolor_');
                file_put_contents($tmp, $disk->get($key));
                [$dominant, $text] = $processor->extractColors($tmp);
                @unlink($tmp);

                $artist->update(['dominant_color_hex' => $dominant, 'text_color_hex' => $text]);
            } catch (Throwable $e) {
                $this->warn("  #{$artist->id} {$artist->name}: {$e->getMessage()}");
            }
            $bar->advance();
        }
        $bar->finish();
        $this->newLine(2);
    }
}
