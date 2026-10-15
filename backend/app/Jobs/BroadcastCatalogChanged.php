<?php

namespace App\Jobs;

use App\Models\User;
use App\Services\Realtime\CentrifugoService;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

/**
 * «Каталог изменился» во все личные каналы: приложения сверят манифест и
 * удалят исчезнувшие треки или перекачают заменённые файлы.
 *
 * Ставится с задержкой из LibraryEvents::catalogChanged() — за это время
 * успевают накопиться все правки пачки.
 */
class BroadcastCatalogChanged implements ShouldQueue
{
    use Queueable;

    public int $tries = 3;

    public function handle(CentrifugoService $centrifugo): void
    {
        $data = ['t' => 'catalog-changed', 'src' => 'server', 'ts' => now()->getTimestampMs()];

        User::query()
            ->where('is_banned', false)
            ->select('id')
            ->chunkById(500, function ($users) use ($centrifugo, $data) {
                $centrifugo->broadcast(
                    $users->map(fn (User $u) => $centrifugo->playbackChannel($u->id))->all(),
                    $data
                );
            });
    }
}
