<?php

namespace App\Services\Bulk;

use App\Enums\ReleaseType;
use App\Jobs\FetchReleaseArt;
use App\Jobs\ProcessReleaseCover;
use App\Models\BulkUpload;
use App\Models\BulkUploadItem;
use App\Models\Track;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;
use Symfony\Component\Process\Process;
use Throwable;

/**
 * Решает, что делать с каждым файлом загрузки:
 *  - совпал с треком каталога — файл в этот трек (и во все его издания);
 *  - трека нет — завести артиста, релиз и трек по тегам;
 *  - не уверены — оставить человеку (review).
 *
 * Если у трека несколько копий, берётся лучшая: без потерь, потом битрейт.
 */
class BulkPlanner
{
    private TrackMatcher $matcher;

    private ?CatalogWriter $writer = null;

    public function __construct(?TrackMatcher $matcher = null)
    {
        $this->matcher = $matcher ?? new TrackMatcher();
    }

    public function matcher(): TrackMatcher
    {
        return $this->matcher;
    }

    /** Разложить все прочитанные, но ещё не решённые файлы загрузки. */
    public function plan(BulkUpload $upload): void
    {
        $items = $upload->items()->where('status', 'pending')->whereNotNull('tags')->orderBy('id')->get();
        foreach ($items as $item) {
            $item->setRelation('upload', $upload);
        }

        $plans = [];
        foreach ($items as $item) {
            $plans[$item->id] = $this->decide($item, $upload->option('create_missing'));
        }

        // Трек достаётся лучшей копии: без потерь важнее, чем точность совпадения
        // (обе копии и так уверенные — та же песня того же артиста).
        $owner = [];
        foreach ($items as $item) {
            $plan = $plans[$item->id];
            if ($plan['kind'] !== 'attach') {
                continue;
            }
            foreach ($plan['targets'] as $trackId) {
                $rank = [$item->quality(), $plan['scores'][$trackId] ?? 0, -$item->id];
                if (! isset($owner[$trackId]) || $rank > $owner[$trackId][1]) {
                    $owner[$trackId] = [$item->id, $rank];
                }
            }
        }

        $toCreate = [];
        foreach ($items as $item) {
            $plan = $plans[$item->id];
            $item->candidates = $plan['candidates'] ?: null;
            $item->score = $plan['candidates'][0]['score'] ?? null;

            if ($plan['kind'] === 'attach') {
                $this->applyAttach($item, $plan['targets'], $owner, $upload->option('replace_full'));
            } elseif ($plan['kind'] === 'create') {
                $toCreate[$this->groupKey($item)][] = $item;

                continue;
            } else {
                $item->fill(['status' => 'review', 'action' => null, 'reason' => $plan['reason']]);
            }
            $item->save();
        }

        if ($toCreate) {
            $this->createAll($upload, $toCreate);
        }
    }

    /**
     * @return array{kind:string, targets?:list<int>, scores?:array<int,int>, reason?:string, candidates:list<array>}
     */
    private function decide(BulkUploadItem $item, bool $createMissing): array
    {
        $file = $this->matcher->describe($item->tags ?? []);
        $candidates = $this->matcher->candidates($file);
        $top = array_slice($candidates, 0, 5);
        $sure = array_values(array_filter($candidates, fn ($c) => $c['score'] >= TrackMatcher::AUTO));

        if ($sure) {
            return [
                'kind' => 'attach',
                'targets' => $this->matcher->targets($file, $sure),
                'scores' => array_column($sure, 'score', 'id'),
                'candidates' => $top,
            ];
        }
        if ($candidates && $candidates[0]['score'] >= TrackMatcher::REVIEW) {
            return ['kind' => 'review', 'reason' => 'ambiguous', 'candidates' => $top];
        }
        if ($createMissing && $this->creatable($item->tags ?? [])) {
            return ['kind' => 'create', 'candidates' => $top];
        }

        return ['kind' => 'review', 'reason' => 'no_match', 'candidates' => $top];
    }

    /**
     * Заводить новый трек можно, только если знаем и название, и артиста —
     * из тегов или из имени файла «Артист - Трек». Артист по имени папки
     * («Музыка/Альбом») слишком часто оказывается не артистом.
     */
    private function creatable(array $tags): bool
    {
        if (trim((string) ($tags['title'] ?? '')) === '' || trim((string) ($tags['artist'] ?? '')) === '') {
            return false;
        }

        return in_array($tags['artist_from'] ?? null, ['tags', 'filename'], true)
            || $this->matcher->isKnownArtist((string) $tags['artist']);
    }

    /**
     * @param  list<int>  $targets
     * @param  array<int, array{0:int, 1:array}>  $owner
     */
    private function applyAttach(BulkUploadItem $item, array $targets, array $owner, bool $replaceFull): void
    {
        $won = array_values(array_filter($targets, fn ($id) => $owner[$id][0] === $item->id));
        if (! $won) {
            $item->fill([
                'status' => 'duplicate', 'action' => 'attach', 'reason' => 'better_copy',
                'duplicate_of_id' => $owner[$targets[0]][0],
                'track_id' => $targets[0], 'track_ids' => $targets,
            ]);

            return;
        }

        $todo = $replaceFull
            ? $won
            : array_values(array_filter($won, fn ($id) => ! $this->matcher->hasFullAudio($id)));

        if (! $todo) {
            // Полная версия уже есть — не трогаем, но помним куда: «Заменить» в админке.
            $item->fill([
                'status' => 'skipped', 'action' => 'attach', 'reason' => 'has_full_audio',
                'track_id' => $won[0], 'track_ids' => $won,
            ]);

            return;
        }

        $item->fill([
            'status' => 'queued', 'action' => 'attach', 'reason' => 'matched',
            'track_id' => $todo[0], 'track_ids' => $todo,
        ]);
    }

    /** Копии одной песни (mp3 и flac одного трека) — в одну группу. */
    private function groupKey(BulkUploadItem $item): string
    {
        $t = $item->tags ?? [];
        $artists = $this->matcher->artistsOf($t);

        return implode('|', [
            Text::norm($artists[0] ?? ''),
            Text::norm($t['album'] ?? ''),
            Text::core($t['title'] ?? ''),
            implode(',', Text::variants($t['title'] ?? '')),
        ]);
    }

    /** @param  array<string, list<BulkUploadItem>>  $groups */
    private function createAll(BulkUpload $upload, array $groups): void
    {
        // Сколько песен в каждом альбоме — одиночная песня с названием альбома
        // равным своему это сингл.
        $perAlbum = [];
        foreach ($groups as $group) {
            $perAlbum[$this->albumKey($group[0])] = ($perAlbum[$this->albumKey($group[0])] ?? 0) + 1;
        }

        $newReleases = [];
        $order = [];

        // Заводить каталог может и соседний процесс (решение из админки) —
        // по одному, иначе один альбом появился бы дважды.
        Cache::lock('bulk:catalog', 300)->block(120, function () use ($groups, $perAlbum, &$newReleases, &$order) {
            $this->writer = new CatalogWriter();

            foreach ($groups as $group) {
                usort($group, fn ($a, $b) => $b->quality() <=> $a->quality() ?: $a->id <=> $b->id);
                $best = array_shift($group);

                [$trackId, $releaseId, $isNew] = $this->createTrack($best, $perAlbum[$this->albumKey($best)] ?? 1);
                $best->fill([
                    'status' => 'queued', 'action' => 'create', 'reason' => 'created',
                    'track_id' => $trackId, 'track_ids' => [$trackId],
                ])->save();

                foreach ($group as $copy) {
                    $copy->fill([
                        'status' => 'duplicate', 'action' => 'create', 'reason' => 'better_copy',
                        'duplicate_of_id' => $best->id, 'track_id' => $trackId, 'track_ids' => [$trackId],
                    ])->save();
                }

                if ($isNew) {
                    $newReleases[$releaseId][] = $best;
                    $t = $best->tags ?? [];
                    $order[$releaseId][] = [$trackId, (int) ($t['disc'] ?? 1) ?: 1, (int) ($t['track'] ?? 999) ?: 999, $best->path];
                }
            }
        });

        $this->renumber($order);
        $this->covers($upload, $newReleases);
    }

    /**
     * Завести трек по тегам одного файла. Решение из админки («создать новый»)
     * идёт сюда же.
     *
     * @return array{0:int, 1:int, 2:bool} [трек, релиз, релиз новый]
     */
    public function createTrack(BulkUploadItem $item, int $songsInAlbum = 1): array
    {
        $this->writer ??= new CatalogWriter();
        $t = $item->tags ?? [];

        $names = $this->matcher->artistsOf($t) ?: ['Неизвестный исполнитель'];
        $releaseArtist = $this->releaseArtistOf($t, $names);
        $title = (string) ($t['title'] ?? pathinfo($item->path, PATHINFO_FILENAME));
        $album = trim((string) ($t['album'] ?? '')) ?: $title;
        $single = $songsInAlbum <= 1 && Text::core($album) === Text::core($title);

        $releaseArtistId = $this->writer->artistId($releaseArtist);
        [$releaseId, $isNew] = $this->writer->release(
            $releaseArtistId,
            $releaseArtist,
            $album,
            $single ? ReleaseType::Single : ReleaseType::Album,
            $t['date'] ?? null,
        );

        $artistIds = array_map(fn ($name) => $this->writer->artistId($name), $names);
        $trackId = $this->writer->track($releaseId, $title, $t['track'] ?? null, $artistIds);

        $this->matcher->add($trackId, [
            'title' => $title, 'artists' => $names, 'release' => $album,
            'duration_ms' => $t['duration_ms'] ?? null, 'full' => false,
        ]);

        return [$trackId, $releaseId, $isNew];
    }

    private function albumKey(BulkUploadItem $item): string
    {
        $t = $item->tags ?? [];
        $artist = $this->releaseArtistOf($t, $this->matcher->artistsOf($t));

        return Text::norm($artist).'|'.Text::norm(($t['album'] ?? null) ?: ($t['title'] ?? ''));
    }

    /**
     * Чей релиз: первый из альбомных артистов, иначе первый артист трека.
     * Как и импорт из Spotify — у релиза один основной артист.
     *
     * @param  list<string>  $trackArtists
     */
    private function releaseArtistOf(array $tags, array $trackArtists): string
    {
        $albumArtists = Text::splitArtists($tags['album_artist'] ?? null, fn ($n) => $this->matcher->isKnownArtist($n));

        return $albumArtists[0] ?? ($trackArtists[0] ?? 'Неизвестный исполнитель');
    }

    /**
     * Порядок треков в новых релизах — по диску и номеру из тегов, без
     * номера — по пути. Нумерация сквозная: у двухдисковых альбомов иначе
     * было бы два «трека 1».
     *
     * @param  array<int, list<array{0:int,1:int,2:int,3:string}>>  $order
     */
    private function renumber(array $order): void
    {
        foreach ($order as $releaseId => $rows) {
            $existing = Track::where('release_id', $releaseId)
                ->whereNotIn('id', array_column($rows, 0))
                ->count();
            usort($rows, fn ($a, $b) => [$a[1], $a[2], $a[3]] <=> [$b[1], $b[2], $b[3]]);
            foreach ($rows as $i => $row) {
                Track::whereKey($row[0])->update(['track_number' => $existing + $i + 1]);
            }
        }
    }

    /**
     * Обложка новым релизам: из самого файла, из картинки в папке альбома, а
     * если нет ни того, ни другого — поищем альбом у Deezer.
     *
     * @param  array<int, list<BulkUploadItem>>  $releases
     */
    public function covers(BulkUpload $upload, array $releases): void
    {
        if (! $releases) {
            return;
        }

        $assets = $upload->items()->where('status', 'asset')->get(['id', 'path', 'local_path', 'size_bytes', 'ext']);

        foreach ($releases as $releaseId => $items) {
            $source = null;
            foreach ($items as $item) {
                if (! empty($item->tags['has_cover']) && ($source = $this->extractCover($item))) {
                    break;
                }
            }
            $source ??= $this->folderImage($upload, $assets, $items);

            if ($source !== null) {
                [$path, $ext, $temporary] = $source;
                $key = 'tmp-uploads/'.Str::uuid().'.'.$ext;
                $handle = fopen($path, 'r');
                $stored = Storage::disk('s3')->writeStream($key, $handle);
                if (is_resource($handle)) {
                    fclose($handle);
                }
                if ($temporary) {
                    @unlink($path);
                }
                if ($stored) {
                    ProcessReleaseCover::dispatch($releaseId, $key, $ext);

                    continue;
                }
            }

            FetchReleaseArt::dispatch($releaseId, 0);
        }
    }

    /** @return array{0:string, 1:string, 2:bool}|null */
    private function extractCover(BulkUploadItem $item): ?array
    {
        $file = $item->absolutePath();
        if (! $file || ! is_file($file)) {
            return null;
        }

        // Во временную папку системы: каталог загрузки мог создать другой процесс.
        $out = sys_get_temp_dir().'/sukify-cover-'.$item->id.'-'.uniqid().'.jpg';
        $process = new Process(['ffmpeg', '-y', '-v', 'error', '-i', $file, '-an', '-map', '0:v:0', '-frames:v', '1', '-q:v', '2', $out]);
        $process->setTimeout(60);
        try {
            $process->run();
        } catch (Throwable) {
            return null;
        }

        return $process->isSuccessful() && is_file($out) && filesize($out) > 1024 ? [$out, 'jpg', true] : null;
    }

    /**
     * cover.jpg / folder.jpg / front.png рядом с файлами альбома.
     *
     * @param  \Illuminate\Support\Collection<int, BulkUploadItem>  $assets
     * @param  list<BulkUploadItem>  $items
     * @return array{0:string, 1:string, 2:bool}|null
     */
    private function folderImage(BulkUpload $upload, $assets, array $items): ?array
    {
        $dirs = array_unique(array_map(fn ($i) => dirname($i->path), $items));
        $best = null;
        $bestRank = null;
        foreach ($assets as $asset) {
            if (! in_array(dirname($asset->path), $dirs, true) || ! $asset->local_path) {
                continue;
            }
            $named = preg_match('/(cover|folder|front|album|art|обложка)/iu', pathinfo($asset->path, PATHINFO_FILENAME)) ? 1 : 0;
            $rank = [$named, (int) $asset->size_bytes];
            if ($bestRank === null || $rank > $bestRank) {
                $best = $asset;
                $bestRank = $rank;
            }
        }

        if (! $best) {
            return null;
        }
        $path = $upload->dir().'/'.$best->local_path;
        $ext = strtolower($best->ext) === 'jpeg' ? 'jpg' : strtolower($best->ext);

        return is_file($path) ? [$path, $ext, false] : null;
    }
}
