<?php

namespace Tests\Concerns;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Models\Artist;
use App\Models\Release;
use App\Models\Track;
use Illuminate\Support\Str;

/** Минимальный каталог для тестов: артист → релиз → треки. */
trait BuildsCatalog
{
    protected function makeArtist(string $name = 'Тестовый артист'): Artist
    {
        return Artist::create([
            'name' => $name,
            'slug' => Str::slug($name).'-'.Str::random(5),
        ]);
    }

    protected function makeRelease(?Artist $artist = null, string $title = 'Альбом'): Release
    {
        $artist ??= $this->makeArtist();

        return Release::create([
            'artist_id' => $artist->id,
            'title' => $title,
            'slug' => Str::slug($title).'-'.Str::random(5),
            'type' => ReleaseType::Album,
            'release_date' => '2024-01-01',
            'cover_status' => ProcessingStatus::Pending,
        ]);
    }

    /** Трек с аудио (если $withAudio) — путь и ревизия как у настоящей загрузки. */
    protected function makeTrack(?Release $release = null, string $title = 'Трек', bool $withAudio = true): Track
    {
        $release ??= $this->makeRelease();

        $track = Track::create([
            'release_id' => $release->id,
            'title' => $title,
            'track_number' => (int) Track::where('release_id', $release->id)->max('track_number') + 1,
            'duration_ms' => 180_000,
            'processing_status' => $withAudio ? ProcessingStatus::Ready : ProcessingStatus::Pending,
        ]);

        if ($withAudio) {
            // Тихо: это подготовка данных, а не «заменили файл» — иначе
            // наблюдатель разослал бы catalog-changed ещё до самого теста.
            $track->forceFill([
                'audio_stream_path' => "audio/{$track->id}/stream.mp3",
                'audio_rev' => md5('audio-'.$track->id),
                'audio_size' => 4_000_000 + $track->id,
            ])->saveQuietly();
        }

        $track->artists()->attach($release->artist_id, ['role' => 'main', 'position' => 0]);

        return $track->fresh();
    }
}
