<?php

namespace App\Services\Bulk;

/**
 * Свободное место на диске сервера. Отдельный класс — чтобы в тестах
 * подставить «диск почти полон».
 */
class DiskSpace
{
    /** Столько оставляем системе и базе, что бы ни грузили. */
    public const RESERVE_BYTES = 2 * 1024 * 1024 * 1024;

    public function free(string $path): int
    {
        while (! is_dir($path) && $path !== dirname($path)) {
            $path = dirname($path);
        }

        return (int) (@disk_free_space($path) ?: 0);
    }

    public static function human(int $bytes): string
    {
        $gb = $bytes / 1024 ** 3;

        return $gb >= 1
            ? str_replace('.', ',', (string) round($gb, 1)).' ГБ'
            : max(1, (int) round($bytes / 1024 ** 2)).' МБ';
    }
}
