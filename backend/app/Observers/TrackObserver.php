<?php

namespace App\Observers;

use App\Jobs\DeleteS3Paths;
use App\Models\Track;
use App\Services\Realtime\LibraryEvents;

class TrackObserver
{
    /**
     * Заменили файл или переименовали — приложения перекачают аудио или
     * обновят подпись. Счётчики прослушиваний сюда не попадают: их пишут
     * мимо события или они не входят в список.
     */
    public function updated(Track $track): void
    {
        if ($track->wasChanged(['audio_rev', 'audio_stream_path', 'title', 'cover_override_path'])) {
            app(LibraryEvents::class)->catalogChanged();
        }
    }

    /** When a track is deleted, remove its audio + any per-track cover from S3. */
    public function deleted(Track $track): void
    {
        DeleteS3Paths::dispatch(
            directories: [
                "audio/{$track->id}",
                "track-covers/{$track->id}",
            ],
        );

        // Трек пропал у всех, у кого он был в лайках, плейлистах и загрузках.
        app(LibraryEvents::class)->catalogChanged();
    }
}
