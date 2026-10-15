<?php

namespace App\Services\Realtime;

use App\Jobs\BroadcastCatalogChanged;
use App\Models\Playlist;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Log;
use Throwable;

use function Illuminate\Support\defer;

/**
 * «Медиатека изменилась» — сообщение в личный канал устройств пользователя.
 *
 * Раньше его слал только браузер и только на лайк трека. Теперь — сервер, на
 * любое изменение медиатеки и откуда угодно: вкладки перечитывают сайдбар, а
 * приложение сверяет манифест загрузок (скачать новое, удалить убранное).
 *
 * Отправка — после ответа (defer): ни медленный, ни упавший Centrifugo не
 * должен ни тормозить, ни ронять сам лайк.
 */
class LibraryEvents
{
    /** Окно, в которое правки каталога склеиваются в одно сообщение, секунд. */
    public const CATALOG_DEBOUNCE = 20;

    public function __construct(private CentrifugoService $centrifugo)
    {
    }

    /**
     * @param  iterable<int>  $userIds
     * @param  string  $what  что именно: liked | albums | artists | playlists | pins | import
     */
    public function changed(iterable $userIds, string $what = 'library'): void
    {
        $channels = collect($userIds)
            ->filter()
            ->map(fn ($id) => $this->centrifugo->playbackChannel((int) $id))
            ->unique()
            ->values()
            ->all();

        if (! $channels) {
            return;
        }

        // Без поля d: так веб отличает свои публикации от чужих, а серверная —
        // чужая для всех вкладок.
        $data = ['t' => 'library-changed', 'what' => $what, 'src' => 'server', 'ts' => now()->getTimestampMs()];

        defer(function () use ($channels, $data) {
            try {
                $this->centrifugo->broadcast($channels, $data);
            } catch (Throwable $e) {
                Log::warning('library-changed не отправилось: '.$e->getMessage());
            }
        });
    }

    /** Изменился плейлист — сообщаем владельцу и всем соавторам. */
    public function playlistChanged(Playlist $playlist, iterable $extraUserIds = []): void
    {
        $this->changed(
            $playlist->collaborators()->pluck('users.id')
                ->push($playlist->user_id)
                ->merge($extraUserIds),
            'playlists'
        );
    }

    /**
     * В каталоге что-то удалили или заменили файл — касается всех: трек мог
     * лежать у кого угодно в лайках или скачанных. Правки идут пачками (импорт,
     * перезаливка превью), поэтому склеиваем их в одно сообщение.
     */
    public function catalogChanged(): void
    {
        if (Cache::add('realtime:catalog-changed:pending', 1, self::CATALOG_DEBOUNCE)) {
            BroadcastCatalogChanged::dispatch()->delay(now()->addSeconds(self::CATALOG_DEBOUNCE));
        }
    }
}
