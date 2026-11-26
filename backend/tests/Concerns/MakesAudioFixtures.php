<?php

namespace Tests\Concerns;

use Symfony\Component\Process\Process;
use ZipArchive;

/** Настоящие короткие аудиофайлы с тегами — ffmpeg есть в контейнере тестов. */
trait MakesAudioFixtures
{
    private ?string $fixtureDir = null;

    protected function fixtureDir(): string
    {
        if ($this->fixtureDir === null) {
            $this->fixtureDir = sys_get_temp_dir().'/sukify-fixtures-'.uniqid();
            mkdir($this->fixtureDir, 0777, true);
        }

        return $this->fixtureDir;
    }

    /** Синус на пару секунд в нужном формате (по расширению имени) и с тегами. */
    protected function audio(string $name, array $tags = [], int $freq = 440, float $seconds = 2.0, string $bitrate = '128k'): string
    {
        $path = $this->fixtureDir().'/'.uniqid().'.'.pathinfo($name, PATHINFO_EXTENSION);
        $codec = match (pathinfo($name, PATHINFO_EXTENSION)) {
            'mp3' => ['-c:a', 'libmp3lame', '-b:a', $bitrate],
            'flac' => ['-c:a', 'flac'],
            'ogg' => ['-c:a', 'libvorbis', '-q:a', '3'],
            'm4a' => ['-c:a', 'aac', '-b:a', $bitrate],
            'wav' => ['-c:a', 'pcm_s16le'],
        };

        $args = ['ffmpeg', '-y', '-v', 'error', '-f', 'lavfi', '-i', "sine=frequency={$freq}:duration={$seconds}"];
        foreach ($tags as $key => $value) {
            array_push($args, '-metadata', "{$key}={$value}");
        }
        array_push($args, ...$codec);
        $args[] = $path;
        (new Process($args))->mustRun();

        return $path;
    }

    protected function jpeg(): string
    {
        $path = $this->fixtureDir().'/'.uniqid().'.jpg';
        $image = imagecreatetruecolor(400, 400);
        imagefill($image, 0, 0, imagecolorallocate($image, 200, 40, 90));
        imagefilledrectangle($image, 50, 50, 350, 350, imagecolorallocate($image, 20, 160, 120));
        imagejpeg($image, $path, 90);
        imagedestroy($image);

        return $path;
    }

    /**
     * ZIP из файлов: путь в архиве => файл на диске (null — мусор вместо звука).
     * Путь можно дать сырыми байтами в cp866 — так пишет Проводник Windows.
     */
    protected function zip(array $entries): string
    {
        $path = $this->fixtureDir().'/'.uniqid().'.zip';
        $zip = new ZipArchive();
        $zip->open($path, ZipArchive::CREATE);
        foreach ($entries as $name => $file) {
            $file === null
                ? $zip->addFromString((string) $name, random_bytes(4096))
                : $zip->addFile($file, (string) $name);
        }
        $zip->close();

        return $path;
    }

    protected function removeFixtures(): void
    {
        if ($this->fixtureDir !== null && is_dir($this->fixtureDir)) {
            (new Process(['rm', '-rf', $this->fixtureDir]))->run();
        }
    }
}
