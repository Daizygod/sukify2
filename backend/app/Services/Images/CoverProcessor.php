<?php

namespace App\Services\Images;

use App\Support\Color;
use Intervention\Image\Drivers\Imagick\Driver as ImagickDriver;
use Intervention\Image\Encoders\JpegEncoder;
use Intervention\Image\Encoders\WebpEncoder;
use Intervention\Image\ImageManager;
use League\ColorExtractor\Color as ExtractorColor;
use League\ColorExtractor\ColorExtractor;
use League\ColorExtractor\Palette;

class CoverProcessor
{
    /** @var array<int> */
    public const SIZES = [64, 160, 300, 640, 1000];

    private ImageManager $manager;

    public function __construct()
    {
        // Imagick gives better downscaling quality than GD for cover art.
        $this->manager = new ImageManager(new ImagickDriver());
    }

    /**
     * Produce WebP + JPEG renditions at every size and extract the palette.
     *
     * @return array{
     *   renditions: array<int, array{webp:string, jpg:string}>,
     *   dominant_color_hex: string,
     *   text_color_hex: string
     * }
     */
    public function process(string $sourcePath): array
    {
        $renditions = [];

        foreach (self::SIZES as $size) {
            // coverDown crops to a square and never upscales past the original.
            $image = $this->manager->decode($sourcePath)->coverDown($size, $size);

            $renditions[$size] = [
                'webp' => (string) $image->encode(new WebpEncoder(quality: 90)),
                'jpg' => (string) $image->encode(new JpegEncoder(quality: 90)),
            ];
        }

        [$dominant, $text] = $this->extractColors($sourcePath);

        return [
            'renditions' => $renditions,
            'dominant_color_hex' => $dominant,
            'text_color_hex' => $text,
        ];
    }

    /**
     * @return array{0:string,1:string} [dominantHex, textHex]
     */
    public function extractColors(string $sourcePath): array
    {
        $palette = Palette::fromFilename($sourcePath);
        $extractor = new ColorExtractor($palette);

        // Grab a handful and pick the most saturated for a punchy gradient,
        // like the dominant colour Spotify pulls from an album cover.
        $colors = $extractor->extract(5);

        if (empty($colors)) {
            return ['#333333', '#FFFFFF'];
        }

        $best = $this->mostVivid($colors);
        $rgb = ExtractorColor::fromIntToRgb($best);
        $rgbTuple = [$rgb['r'], $rgb['g'], $rgb['b']];

        return [
            Color::toHex($rgbTuple),
            Color::textColorFor($rgbTuple),
        ];
    }

    /**
     * Pick the most saturated colour among candidates (avoids muddy greys),
     * falling back to the first (most dominant) if all are equally dull.
     *
     * @param  array<int>  $colors
     */
    private function mostVivid(array $colors): int
    {
        $bestInt = $colors[0];
        $bestScore = -1.0;

        foreach ($colors as $int) {
            $rgb = ExtractorColor::fromIntToRgb($int);
            $max = max($rgb['r'], $rgb['g'], $rgb['b']);
            $min = min($rgb['r'], $rgb['g'], $rgb['b']);
            $saturation = $max === 0 ? 0 : ($max - $min) / $max;

            if ($saturation > $bestScore) {
                $bestScore = $saturation;
                $bestInt = $int;
            }
        }

        return $bestInt;
    }
}
