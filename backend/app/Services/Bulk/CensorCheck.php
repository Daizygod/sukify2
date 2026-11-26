<?php

namespace App\Services\Bulk;

use App\Models\Track;
use App\Services\Audio\AnalyzerClient;
use App\Services\Deezer\DeezerClient;
use Throwable;

/**
 * Не вырезаны ли в файле слова: сверка с 30-секундными превью всех изданий
 * песни у Deezer ({@see AnalyzerClient::compare}).
 *
 * Метаданные тут не помогают: у вырезанной и целой версии один ISRC, обе
 * помечены explicit, длительность та же. Видно только по звуку, и только в
 * пределах превью — остальные три минуты сверить не с чем.
 */
class CensorCheck
{
    /** Вырезано суммарно больше этого — уже не случайность. */
    private const SUSPECT_MS = 250;

    private const DIFFERS_MS = 400;

    public function __construct(
        private readonly DeezerClient $deezer,
        private readonly AnalyzerClient $analyzer,
    ) {
    }

    /**
     * @return array{status:string, info:array} status: clean | suspect | differs | unknown
     */
    public function check(Track $track, string $audioPath, ?int $durationMs): array
    {
        $artist = $track->mainArtists()->value('name')
            ?? $track->release?->artist?->name
            ?? '';

        $editions = $this->deezer->editions($artist, $track->title, $durationMs, $track->deezer_id ?: null);
        if (! $editions) {
            return ['status' => 'unknown', 'info' => ['note' => 'У Deezer нет превью этой песни — сверить не с чем.']];
        }

        $dir = storage_path('app/media-work/'.uniqid('censor_', true));
        @mkdir($dir, 0775, true);

        try {
            $bodies = $this->deezer->fetchBatch(array_column($editions, 'preview', 'id'));
            $files = [];
            foreach ($bodies as $id => $body) {
                if ($body !== null) {
                    file_put_contents($files[$id] = "{$dir}/{$id}.mp3", $body);
                }
            }
            if (! $files) {
                return ['status' => 'unknown', 'info' => ['note' => 'Превью Deezer не скачались.']];
            }

            $results = $this->analyzer->compare($audioPath, $files);
        } catch (Throwable $e) {
            return ['status' => 'unknown', 'info' => ['note' => 'Сверка не удалась: '.mb_substr($e->getMessage(), 0, 200)]];
        } finally {
            foreach (glob("{$dir}/*") ?: [] as $file) {
                @unlink($file);
            }
            @rmdir($dir);
        }

        return $this->verdict($editions, $results);
    }

    /**
     * @param  list<array{id:int, album:string}>  $editions
     * @param  array<string,array>  $results
     */
    public function verdict(array $editions, array $results): array
    {
        $rows = [];
        foreach ($editions as $edition) {
            $r = $results[(string) $edition['id']] ?? null;
            if (! $r) {
                continue;
            }
            $rows[] = [
                'deezer_id' => $edition['id'],
                'album' => $edition['album'],
                'aligned' => (bool) ($r['aligned'] ?? false),
                'offset_ms' => $r['offset_ms'] ?? null,
                'missing' => $r['missing'] ?? [],
                'extra' => $r['extra'] ?? [],
                'differs' => $r['differs'] ?? [],
            ];
        }

        $aligned = array_values(array_filter($rows, fn ($r) => $r['aligned']));
        if (! $aligned) {
            return ['status' => 'unknown', 'info' => [
                'note' => 'Превью не нашлось внутри файла — похоже, это другая версия.',
                'editions' => $rows,
            ]];
        }

        $total = fn (array $spans) => array_sum(array_map(fn ($s) => $s[1] - $s[0], $spans));

        $status = 'clean';
        foreach ($aligned as $r) {
            if ($total($r['missing']) >= self::SUSPECT_MS) {
                $status = 'suspect';
                break;
            }
            if ($total($r['differs']) >= self::DIFFERS_MS) {
                $status = 'differs';
            }
        }

        return ['status' => $status, 'info' => ['editions' => $rows]];
    }
}
