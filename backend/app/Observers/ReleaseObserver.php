<?php

namespace App\Observers;

use App\Jobs\DeleteS3Paths;
use App\Models\Release;
use App\Services\Realtime\LibraryEvents;

class ReleaseObserver
{
    /** Новое название или обложка — приложения обновят подписи и офлайн-обложки. */
    public function updated(Release $release): void
    {
        if ($release->wasChanged(['title', 'cover_path', 'cover_status', 'artist_id'])) {
            app(LibraryEvents::class)->catalogChanged();
        }
    }

    /** Cascade-deletes tracks (FK), but cover files must be cleaned separately. */
    public function deleted(Release $release): void
    {
        DeleteS3Paths::dispatch(
            directories: ["covers/{$release->id}"],
        );

        // Треки ушли каскадом в БД — их события не сработали, сообщаем сами.
        app(LibraryEvents::class)->catalogChanged();
    }
}
