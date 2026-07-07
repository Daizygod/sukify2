<?php

namespace App\Support;

class Color
{
    /**
     * Relative luminance per the WCAG 2.x formula (0..1).
     *
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public static function relativeLuminance(array $rgb): float
    {
        $channels = [];
        foreach ($rgb as $value) {
            $c = $value / 255;
            $channels[] = $c <= 0.03928
                ? $c / 12.92
                : (($c + 0.055) / 1.055) ** 2.4;
        }

        [$r, $g, $b] = $channels;

        return 0.2126 * $r + 0.7152 * $g + 0.0722 * $b;
    }

    /**
     * Choose #000000 or #FFFFFF for text over the given background — the same
     * white/black switch Spotify uses over different covers.
     *
     * @param  array{0:int,1:int,2:int}  $bgRgb
     */
    public static function textColorFor(array $bgRgb): string
    {
        // Luminance threshold ~0.4 biases toward white text (matches Spotify's
        // tendency to keep white text unless the cover is quite light).
        return self::relativeLuminance($bgRgb) > 0.45 ? '#000000' : '#FFFFFF';
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     */
    public static function toHex(array $rgb): string
    {
        return sprintf('#%02X%02X%02X', $rgb[0], $rgb[1], $rgb[2]);
    }

    /**
     * @return array{0:int,1:int,2:int}
     */
    public static function fromHex(string $hex): array
    {
        $hex = ltrim($hex, '#');

        return [
            (int) hexdec(substr($hex, 0, 2)),
            (int) hexdec(substr($hex, 2, 2)),
            (int) hexdec(substr($hex, 4, 2)),
        ];
    }
}
