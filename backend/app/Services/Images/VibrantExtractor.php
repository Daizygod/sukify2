<?php

namespace App\Services\Images;

use Imagick;
use Throwable;

/**
 * Достаёт «фирменный» цвет обложки так же, как это делает Spotify — через
 * подход Android Palette / Vibrant.js: строим гистограмму цветов, переводим в
 * HSL и выбираем наиболее «живой» образец, взвешивая насыщенность, целевую
 * яркость и населённость. Это даёт насыщенные оттенки (включая розовые/пурпур),
 * а не приглушённое среднее, как у наивного извлечения.
 */
class VibrantExtractor
{
    // Веса и цели из Android Palette (Target.VIBRANT).
    private const TARGET_LUMA = 0.5;
    private const MIN_LUMA = 0.3;
    private const MAX_LUMA = 0.7;
    private const TARGET_SATURATION = 1.0;
    private const MIN_SATURATION = 0.35;

    private const WEIGHT_SATURATION = 3.0;
    private const WEIGHT_LUMA = 6.0;
    private const WEIGHT_POPULATION = 1.0;

    /**
     * @return array{0:int,1:int,2:int}|null  Доминирующий «живой» цвет (RGB) или null.
     */
    public function dominant(string $sourcePath): ?array
    {
        try {
            $swatches = $this->buildSwatches($sourcePath);
        } catch (Throwable) {
            return null;
        }
        if (! $swatches) {
            return null;
        }

        $maxPopulation = max(array_column($swatches, 'count')) ?: 1;

        $best = null;
        $bestScore = -1.0;
        // Первый проход — только «живые» цвета в целевом диапазоне.
        foreach ([true, false] as $strict) {
            foreach ($swatches as $sw) {
                [$h, $s, $l] = $sw['hsl'];
                if ($strict) {
                    if ($s < self::MIN_SATURATION) {
                        continue;
                    }
                    if ($l < self::MIN_LUMA || $l > self::MAX_LUMA) {
                        continue;
                    }
                }
                $score = self::WEIGHT_SATURATION * (1 - abs($s - self::TARGET_SATURATION))
                    + self::WEIGHT_LUMA * (1 - abs($l - self::TARGET_LUMA))
                    + self::WEIGHT_POPULATION * ($sw['count'] / $maxPopulation);
                if ($score > $bestScore) {
                    $bestScore = $score;
                    $best = $sw['rgb'];
                }
            }
            if ($best !== null) {
                break; // строгий проход что-то нашёл — второго не нужно
            }
        }

        if ($best === null) {
            return null;
        }

        // Приводим яркость в комфортную «фоновую» полосу (как Spotify): очень
        // тёмные/светлые обложки всё равно дают насыщенный, читаемый фон, а не
        // почти-чёрный или блёклый. Оттенок и насыщенность сохраняем.
        return $this->normalizeLightness($best);
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     * @return array{0:int,1:int,2:int}
     */
    private function normalizeLightness(array $rgb): array
    {
        [$h, $s, $l] = self::rgbToHsl($rgb);
        $l = max(0.32, min(0.58, $l));
        // Слишком блёклые тона чуть подсыщаем, чтобы фон не был серым.
        if ($s < 0.5) {
            $s = min(0.65, $s + 0.15);
        }

        return self::hslToRgb($h, $s, $l);
    }

    /**
     * @return array{0:int,1:int,2:int}
     */
    private static function hslToRgb(float $h, float $s, float $l): array
    {
        $h = fmod($h, 360) / 360;
        if ($s == 0.0) {
            $v = (int) round($l * 255);

            return [$v, $v, $v];
        }
        $q = $l < 0.5 ? $l * (1 + $s) : $l + $s - $l * $s;
        $p = 2 * $l - $q;
        $hue = static function (float $t) use ($p, $q): float {
            if ($t < 0) {
                $t += 1;
            }
            if ($t > 1) {
                $t -= 1;
            }
            if ($t < 1 / 6) {
                return $p + ($q - $p) * 6 * $t;
            }
            if ($t < 1 / 2) {
                return $q;
            }
            if ($t < 2 / 3) {
                return $p + ($q - $p) * (2 / 3 - $t) * 6;
            }

            return $p;
        };

        return [
            (int) round($hue($h + 1 / 3) * 255),
            (int) round($hue($h) * 255),
            (int) round($hue($h - 1 / 3) * 255),
        ];
    }

    /**
     * Гистограмма уменьшенной и квантованной обложки.
     *
     * @return array<int, array{rgb: array{0:int,1:int,2:int}, hsl: array{0:float,1:float,2:float}, count: int}>
     */
    private function buildSwatches(string $sourcePath): array
    {
        $img = new Imagick();
        $img->readImage($sourcePath);
        $img->setImageColorspace(Imagick::COLORSPACE_SRGB);
        // Уменьшаем — детали не нужны, важна крупная цветовая масса.
        $img->resizeImage(80, 80, Imagick::FILTER_BOX, 1);
        // Сводим к ~48 цветам: близкие оттенки собираются в один образец.
        $img->quantizeImage(48, Imagick::COLORSPACE_RGB, 0, false, false);

        $swatches = [];
        foreach ($img->getImageHistogram() as $pixel) {
            $c = $pixel->getColor();
            $rgb = [$c['r'], $c['g'], $c['b']];
            $hsl = self::rgbToHsl($rgb);
            // Отбрасываем почти-чёрное и почти-белое — как рамки/фон.
            if ($hsl[2] <= 0.05 || $hsl[2] >= 0.95) {
                continue;
            }
            $swatches[] = ['rgb' => $rgb, 'hsl' => $hsl, 'count' => $pixel->getColorCount()];
        }
        $img->destroy();

        return $swatches;
    }

    /**
     * @param  array{0:int,1:int,2:int}  $rgb
     * @return array{0:float,1:float,2:float}  [hue 0..360, sat 0..1, luma 0..1]
     */
    private static function rgbToHsl(array $rgb): array
    {
        $r = $rgb[0] / 255;
        $g = $rgb[1] / 255;
        $b = $rgb[2] / 255;
        $max = max($r, $g, $b);
        $min = min($r, $g, $b);
        $l = ($max + $min) / 2;
        $d = $max - $min;

        if ($d == 0.0) {
            return [0.0, 0.0, $l];
        }
        $s = $l > 0.5 ? $d / (2 - $max - $min) : $d / ($max + $min);
        $h = match ($max) {
            $r => (($g - $b) / $d) + ($g < $b ? 6 : 0),
            $g => (($b - $r) / $d) + 2,
            default => (($r - $g) / $d) + 4,
        };

        return [$h * 60, $s, $l];
    }
}
