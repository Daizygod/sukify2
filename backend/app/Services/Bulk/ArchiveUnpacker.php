<?php

namespace App\Services\Bulk;

use App\Models\BulkUploadItem;
use FilesystemIterator;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use RuntimeException;
use Symfony\Component\Process\Process;
use ZipArchive;

/**
 * Достаёт из архива музыку и картинки (обложки), остальное пропускает.
 *
 * ZIP читаем сами: Проводник Windows пишет русские имена в cp866 без пометки
 * о кодировке, и только по сырым байтам можно понять, что там «Кино», а не
 * «ТЁэю». Всё остальное (RAR, 7z, TAR) — через bsdtar из libarchive.
 */
class ArchiveUnpacker
{
    /** Форматы, которые принимаем. tar.gz и прочие узнаются по последнему расширению. */
    public const EXTENSIONS = ['zip', 'rar', '7z', 'tar', 'tgz', 'gz', 'tbz2', 'bz2', 'txz', 'xz'];

    /** Больше этого один музыкальный файл не весит — дальше подозрительно. */
    private const MAX_ENTRY_BYTES = 4 * 1024 * 1024 * 1024;

    /** Служебный мусор архиваторов и ОС. */
    private const JUNK = ['.ds_store', 'thumbs.db', 'desktop.ini'];

    public function __construct(private readonly DiskSpace $disk)
    {
    }

    /**
     * @param  callable(string $path, string $file, int $size): void  $onFile
     *         path — путь внутри архива, file — уже распакованный временный файл;
     *         обработчик должен его забрать (переместить) или удалить.
     * @param  callable(int $done, int $total): void  $progress  байты
     */
    public function unpack(string $archive, string $ext, string $workDir, callable $onFile, callable $progress): void
    {
        @mkdir($workDir, 0775, true);

        if ($ext === 'zip' || $this->looksLikeZip($archive)) {
            $this->unpackZip($archive, $workDir, $onFile, $progress);

            return;
        }

        $this->unpackOther($archive, $workDir, $onFile, $progress);
    }

    private function looksLikeZip(string $archive): bool
    {
        $head = @file_get_contents($archive, false, null, 0, 4);

        return $head === "PK\x03\x04";
    }

    private function unpackZip(string $archive, string $workDir, callable $onFile, callable $progress): void
    {
        $zip = new ZipArchive();
        $code = $zip->open($archive, ZipArchive::RDONLY);
        if ($code !== true) {
            throw new RuntimeException('ZIP не открывается — файл повреждён или докачан не до конца (код '.$code.').');
        }

        try {
            $entries = [];
            $total = 0;
            for ($i = 0; $i < $zip->numFiles; $i++) {
                $stat = $zip->statIndex($i, ZipArchive::FL_ENC_RAW);
                if (! $stat || str_ends_with($stat['name'], '/')) {
                    continue;
                }
                $path = $this->safePath($this->decodeName($stat['name']));
                if ($path === null || ! $this->wanted($path)) {
                    continue;
                }
                if (($stat['encryption_method'] ?? 0) !== 0) {
                    throw new RuntimeException('Архив под паролем — распакуй его и закинь папку или архив без пароля.');
                }
                if ($stat['size'] > self::MAX_ENTRY_BYTES) {
                    continue;
                }
                $entries[] = [$i, $path, (int) $stat['size']];
                $total += (int) $stat['size'];
            }

            $this->ensureSpace($workDir, $total);

            $done = 0;
            foreach ($entries as [$index, $path, $size]) {
                $tmp = $workDir.'/entry.tmp';
                $in = $zip->getStreamIndex($index);
                if ($in === false) {
                    continue;
                }
                $out = fopen($tmp, 'wb');
                stream_copy_to_stream($in, $out);
                fclose($out);
                fclose($in);

                $onFile($path, $tmp, $size);
                @unlink($tmp);

                $done += $size;
                $progress($done, $total);
            }
        } finally {
            $zip->close();
        }
    }

    private function unpackOther(string $archive, string $workDir, callable $onFile, callable $progress): void
    {
        $target = $workDir.'/extract';
        $this->removeTree($target);
        @mkdir($target, 0775, true);

        // Сжатую музыку архиватор почти не жмёт: распакованное ≈ архив.
        $expected = (int) filesize($archive);
        $this->ensureSpace($workDir, $expected);

        // Без UTF-8 в локали bsdtar не может записать на диск кириллические
        // имена из 7z и RAR («cannot be converted from UTF-16LE»).
        $process = new Process([
            'bsdtar', '-x', '-f', $archive, '-C', $target,
            '--no-same-owner', '--no-same-permissions', '--no-xattrs', '--no-acls', '--no-fflags',
        ], null, ['LC_ALL' => 'C.UTF-8', 'LANG' => 'C.UTF-8']);
        $process->setTimeout(6 * 3600);
        $process->start();
        while ($process->isRunning()) {
            usleep(2_000_000);
            $progress(min($expected, $this->treeSize($target)), $expected);
        }

        if (! $process->isSuccessful()) {
            $this->removeTree($target);
            $error = trim($process->getErrorOutput());
            throw new RuntimeException(match (true) {
                stripos($error, 'passphrase') !== false || stripos($error, 'encrypt') !== false
                    => 'Архив под паролем — распакуй его и закинь папку или архив без пароля.',
                stripos($error, 'Unrecognized archive format') !== false
                    => 'Не понял формат архива. Подойдут ZIP, RAR, 7z и TAR.',
                $process->getExitCode() === 127 || stripos($error, 'not found') !== false
                    => 'На сервере нет bsdtar — RAR и 7z пока не распаковать, нужен ZIP.',
                default => 'Архив не распаковался: '.mb_substr($error, -300),
            });
        }

        $files = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($target, FilesystemIterator::SKIP_DOTS),
            RecursiveIteratorIterator::LEAVES_ONLY,
        );
        $found = [];
        foreach ($files as $file) {
            // Ссылки из архива могут вести куда угодно на диске — не ходим по ним.
            if ($file->isLink() || ! $file->isFile()) {
                continue;
            }
            $relative = substr($file->getPathname(), strlen($target) + 1);
            $path = $this->safePath($relative);
            if ($path !== null && $this->wanted($path) && $file->getSize() <= self::MAX_ENTRY_BYTES) {
                $found[] = [$file->getPathname(), $path, (int) $file->getSize()];
            }
        }
        usort($found, fn ($a, $b) => strcmp($a[1], $b[1]));

        $total = array_sum(array_column($found, 2));
        $done = 0;
        foreach ($found as [$file, $path, $size]) {
            $onFile($path, $file, $size);
            @unlink($file);
            $done += $size;
            $progress($done, $total);
        }

        $this->removeTree($target);
    }

    /**
     * Имя из ZIP в UTF-8. Если флага UTF-8 нет, байты могут быть в cp866
     * (Проводник), cp1251 или cp437 — выбираем, где осмысленнее.
     */
    public function decodeName(string $raw): string
    {
        if (mb_check_encoding($raw, 'UTF-8')) {
            return $raw;
        }

        $best = null;
        $bestScore = PHP_INT_MIN;
        foreach (['CP866', 'Windows-1251', 'CP437'] as $encoding) {
            $decoded = @iconv($encoding, 'UTF-8//IGNORE', $raw);
            if (! is_string($decoded) || $decoded === '') {
                continue;
            }
            // Русские буквы — плюс; псевдографика, редкие буквы и кириллица
            // вплотную к латинице («CafВ») — признаки не той кодировки.
            $score = 2 * preg_match_all('/[А-Яа-яЁё]/u', $decoded)
                - 3 * preg_match_all('/[\x{2500}-\x{25FF}]/u', $decoded)
                - 3 * preg_match_all('/[ЂЃЄЅІЇЈЉЊЋЌЎЏђѓєѕіїјљњћќўџҐґ]/u', $decoded)
                - 4 * preg_match_all('/[a-z][А-Яа-яЁё]|[А-Яа-яЁё][a-z]/iu', $decoded)
                // Латиница с диакритикой (é, ö, ñ) — тоже буквы, прочее — нет.
                - preg_match_all('/[^\x{0000}-\x{007F}А-Яа-яЁё\x{00C0}-\x{00D6}\x{00D8}-\x{00F6}\x{00F8}-\x{00FF}]/u', $decoded);
            if ($score > $bestScore) {
                $bestScore = $score;
                $best = $decoded;
            }
        }

        return $best ?? $raw;
    }

    /** Путь без «..», ведущих слэшей и служебных папок; null — пропустить. */
    public function safePath(string $path): ?string
    {
        $parts = [];
        foreach (explode('/', str_replace('\\', '/', $path)) as $segment) {
            if ($segment === '' || $segment === '.' || $segment === '..') {
                continue;
            }
            if ($segment === '__MACOSX') {
                return null;
            }
            $parts[] = $segment;
        }
        if (! $parts) {
            return null;
        }

        $name = end($parts);
        if (str_starts_with($name, '._') || in_array(mb_strtolower($name), self::JUNK, true)) {
            return null;
        }

        return implode('/', $parts);
    }

    private function wanted(string $path): bool
    {
        $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));

        return BulkUploadItem::isAudioExt($ext) || BulkUploadItem::isImageExt($ext);
    }

    private function ensureSpace(string $dir, int $bytes): void
    {
        $free = $this->disk->free($dir);
        $reserve = DiskSpace::RESERVE_BYTES;
        if ($bytes + $reserve > $free) {
            throw new RuntimeException(sprintf(
                'Не хватает места на сервере: распаковка займёт %s, свободно %s.',
                DiskSpace::human($bytes),
                DiskSpace::human(max(0, $free - $reserve)),
            ));
        }
    }

    private function treeSize(string $dir): int
    {
        if (! is_dir($dir)) {
            return 0;
        }
        $size = 0;
        $files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS));
        foreach ($files as $file) {
            if ($file->isFile() && ! $file->isLink()) {
                $size += $file->getSize();
            }
        }

        return $size;
    }

    public function removeTree(string $dir): void
    {
        if (! is_dir($dir) || is_link($dir)) {
            return;
        }
        $items = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($dir, FilesystemIterator::SKIP_DOTS),
            RecursiveIteratorIterator::CHILD_FIRST,
        );
        foreach ($items as $item) {
            $item->isDir() && ! $item->isLink() ? @rmdir($item->getPathname()) : @unlink($item->getPathname());
        }
        @rmdir($dir);
    }
}
