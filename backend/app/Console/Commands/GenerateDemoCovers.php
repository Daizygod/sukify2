<?php

namespace App\Console\Commands;

use App\Enums\ProcessingStatus;
use App\Jobs\ProcessReleaseCover;
use App\Models\Release;
use App\Services\Images\CoverProcessor;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;
use Imagick;
use ImagickDraw;
use ImagickPixel;

/**
 * Dev helper: paint a distinct placeholder cover for releases that have none
 * so the catalog looks finished (real art can be uploaded via the admin later).
 * Goes through the normal ProcessReleaseCover pipeline for renditions/palette.
 */
class GenerateDemoCovers extends Command
{
    protected $signature = 'demo:generate-covers {--force : Regenerate even if a cover already exists}';

    protected $description = 'Paint placeholder cover art for releases that have none.';

    public function handle(): int
    {
        $query = Release::query()->orderBy('id');
        if (! $this->option('force')) {
            $query->where('cover_status', '!=', ProcessingStatus::Ready);
        }
        $releases = $query->get();

        if ($releases->isEmpty()) {
            $this->info('All releases already have covers.');

            return self::SUCCESS;
        }

        $disk = Storage::disk('s3');

        foreach ($releases as $release) {
            $release->loadMissing('artist');
            $jpeg = $this->paint($release);

            $tmpKey = "tmp/demo-covers/{$release->id}.jpg";
            $disk->put($tmpKey, $jpeg);

            // Synchronously — the command is a dev helper, keep it deterministic.
            (new ProcessReleaseCover($release->id, $tmpKey, 'jpg'))->handle(app(CoverProcessor::class));

            $this->line("Release {$release->id}: {$release->title} — cover generated.");
        }

        $this->info('Done — every release has cover art.');

        return self::SUCCESS;
    }

    /** Paint a 1000x1000 gradient cover with soft shapes and the title. */
    private function paint(Release $release): string
    {
        $size = 1000;
        $base = $release->dominant_color_hex ?: '#535353';
        [$dark, $light] = [$this->shade($base, -0.55), $this->shade($base, 0.25)];

        $img = new Imagick();
        $img->newPseudoImage($size, $size, "gradient:{$light}-{$dark}");
        $img->rotateImage(new ImagickPixel('none'), 90 * (($release->id % 3) - 1));
        $img->cropImage($size, $size, 0, 0);

        // Big soft translucent discs, varied per release id.
        $draw = new ImagickDraw();
        mt_srand($release->id * 7919);
        for ($i = 0; $i < 5; $i++) {
            $alpha = 0.10 + 0.05 * $i;
            $draw->setFillColor(new ImagickPixel($i % 2 ? "rgba(255,255,255,{$alpha})" : "rgba(0,0,0,{$alpha})"));
            $r = mt_rand(180, 420);
            $cx = mt_rand(-100, $size + 100);
            $cy = mt_rand(-100, $size + 100);
            $draw->circle($cx, $cy, $cx + $r, $cy);
        }
        $img->drawImage($draw);

        // Title initials, huge and centred — reads as deliberate art.
        $font = $this->findFont();
        if ($font !== null) {
            $text = mb_strtoupper(mb_substr(trim($release->title), 0, 2));
            $draw = new ImagickDraw();
            $draw->setFont($font);
            $draw->setFontSize(430);
            $draw->setFillColor(new ImagickPixel('rgba(255,255,255,0.92)'));
            $draw->setTextAlignment(Imagick::ALIGN_CENTER);
            $img->annotateImage($draw, $size / 2, $size / 2 + 150, 0, $text);

            $draw = new ImagickDraw();
            $draw->setFont($font);
            $draw->setFontSize(44);
            $draw->setFillColor(new ImagickPixel('rgba(255,255,255,0.75)'));
            $draw->setTextAlignment(Imagick::ALIGN_CENTER);
            $img->annotateImage($draw, $size / 2, $size - 72, 0, mb_strtoupper($release->artist?->name ?? ''));
        }

        $img->setImageFormat('jpeg');
        $img->setImageCompressionQuality(92);

        return (string) $img->getImageBlob();
    }

    private function findFont(): ?string
    {
        foreach ([
            '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
            '/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf',
            '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf',
        ] as $path) {
            if (is_file($path)) {
                return $path;
            }
        }

        return null;
    }

    /** Lighten (amount > 0) or darken (amount < 0) a hex color. */
    private function shade(string $hex, float $amount): string
    {
        $hex = ltrim($hex, '#');
        $rgb = array_map('hexdec', str_split($hex, 2));
        $rgb = array_map(function (int $c) use ($amount): int {
            $c = $amount >= 0 ? $c + (255 - $c) * $amount : $c * (1 + $amount);

            return (int) max(0, min(255, round($c)));
        }, $rgb);

        return sprintf('#%02x%02x%02x', ...$rgb);
    }
}
