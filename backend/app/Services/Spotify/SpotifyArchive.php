<?php

namespace App\Services\Spotify;

use RuntimeException;
use ZipArchive;

/**
 * Читалка архива «Download your data» из Spotify.
 *
 * Spotify присылает данные тремя отдельными архивами, и заранее неизвестно,
 * какой из них закинули: Account Data (YourLibrary/Playlist1/StreamingHistory),
 * Extended Streaming History (Streaming_History_Audio_*.json — годы прослушек)
 * или Technical Log Information (телеметрия клиента, ничего полезного).
 * Класс определяет тип по именам файлов внутри и отдаёт нужные куски.
 *
 * Ничего не распаковывается на диск: файлы читаются из zip по одному, потому
 * что Extended History — это 200 МБ JSON при 16 МБ архива.
 */
class SpotifyArchive
{
    private ?ZipArchive $zip = null;

    /** @var list<string> */
    private array $names = [];

    /** Файл закинули как голый .json, а не архивом. */
    private bool $plain = false;

    public function __construct(private readonly string $path)
    {
        $this->open();
    }

    private function open(): void
    {
        $zip = new ZipArchive();
        if ($zip->open($this->path) === true) {
            $this->zip = $zip;
            for ($i = 0; $i < $zip->numFiles; $i++) {
                $name = $zip->getNameIndex($i);
                if ($name !== false && ! str_ends_with($name, '/')) {
                    $this->names[] = $name;
                }
            }

            return;
        }

        // Не zip — принимаем и одиночный JSON (YourLibrary.json и т.п.).
        $head = file_get_contents($this->path, false, null, 0, 64) ?: '';
        if (! preg_match('/^\s*[\{\[]/', $head)) {
            throw new RuntimeException('Это не ZIP-архив Spotify и не JSON-файл из него.');
        }
        $this->plain = true;
        $this->names = [basename($this->path)];
    }

    public function close(): void
    {
        $this->zip?->close();
        $this->zip = null;
    }

    /** @return list<string> */
    public function names(): array
    {
        return $this->names;
    }

    /** account | extended | technical | mixed | unknown */
    public function kind(): string
    {
        $account = $this->libraryEntry() !== null || $this->playlistEntries() !== [];
        $extended = $this->extendedHistoryEntries() !== [];

        return match (true) {
            $account && $extended => 'mixed',
            $account => 'account',
            $extended => 'extended',
            $this->shortHistoryEntries() !== [] => 'account',
            $this->isTechnical() => 'technical',
            default => 'unknown',
        };
    }

    public function isTechnical(): bool
    {
        foreach ($this->names as $n) {
            if (stripos($n, 'Technical Log Information') !== false) {
                return true;
            }
        }

        return false;
    }

    public function libraryEntry(): ?string
    {
        return $this->firstMatching('YourLibrary\.json');
    }

    /** @return list<string> Playlist1.json, Playlist2.json, … */
    public function playlistEntries(): array
    {
        return $this->matching('Playlist\d*\.json');
    }

    /** @return list<string> Годы прослушиваний из Extended Streaming History. */
    public function extendedHistoryEntries(): array
    {
        return $this->matching('Streaming_History_Audio_[^\/]*\.json');
    }

    /** @return list<string> Короткая история за год из Account Data. */
    public function shortHistoryEntries(): array
    {
        return $this->matching('StreamingHistory_music_\d+\.json');
    }

    /**
     * Файлы, из которых берётся история. Extended перекрывает короткую версию
     * целиком, поэтому при наличии первой вторую не читаем — иначе те же
     * прослушивания посчитались бы дважды (их гасит отпечаток, но зачем).
     *
     * @return array{entries: list<string>, extended: bool}
     */
    public function historySource(): array
    {
        $extended = $this->extendedHistoryEntries();
        if ($extended !== []) {
            sort($extended);

            return ['entries' => $extended, 'extended' => true];
        }

        $short = $this->shortHistoryEntries();
        sort($short);

        return ['entries' => $short, 'extended' => false];
    }

    /** Что ещё лежит в архиве, но в Sukify не переносится. */
    public function extras(): array
    {
        $map = [
            'Follow.json' => 'подписки на людей',
            'SearchQueries.json' => 'история поиска',
            'Wrapped2025.json' => 'Wrapped 2025',
            'YourSoundCapsule.json' => 'Sound Capsule',
            'Marquee.json' => 'рекламные показы',
            'Inferences.json' => 'рекламные интересы',
            'Identity.json' => 'профиль',
        ];

        $found = [];
        foreach ($map as $file => $label) {
            if ($this->firstMatching(preg_quote($file, '/')) !== null) {
                $found[] = $label;
            }
        }

        return $found;
    }

    /** Один файл истории — 12 МБ; всё, что сильно больше, читать не станем. */
    private const MAX_ENTRY_BYTES = 300 * 1024 * 1024;

    /** Декодировать один файл архива. */
    public function read(string $entry): ?array
    {
        // Архив приходит от пользователя: распакованный размер проверяем до
        // чтения, иначе «зип-бомба» съела бы память воркера.
        if ($this->sizeOf($entry) > self::MAX_ENTRY_BYTES) {
            return null;
        }

        $raw = $this->plain
            ? file_get_contents($this->path)
            : $this->zip?->getFromName($entry);

        if ($raw === false || $raw === null || $raw === '') {
            return null;
        }

        $data = json_decode($raw, true);
        unset($raw);

        return is_array($data) ? $data : null;
    }

    /** Размер файла внутри архива в распакованном виде. */
    public function sizeOf(string $entry): int
    {
        if ($this->plain) {
            return (int) filesize($this->path);
        }
        $stat = $this->zip?->statName($entry);

        return (int) ($stat['size'] ?? 0);
    }

    private function firstMatching(string $fileNamePattern): ?string
    {
        return $this->matching($fileNamePattern)[0] ?? null;
    }

    /**
     * Ищем по ИМЕНИ файла, а не по концу пути: в Technical Log есть
     * AddedToPlaylist.json, и незаякоренный «Playlist\d*\.json$» принимал бы
     * телеметрию за плейлисты.
     *
     * @return list<string>
     */
    private function matching(string $fileNamePattern): array
    {
        $regex = '/(?:^|\/)'.$fileNamePattern.'$/i';

        return array_values(array_filter($this->names, fn ($n) => preg_match($regex, $n) === 1));
    }
}
