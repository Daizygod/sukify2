<?php

namespace Tests\Feature;

use App\Enums\ProcessingStatus;
use App\Jobs\ProcessTrackAudio;
use App\Models\Track;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Facades\Storage;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

class ReplaceAudioTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    public function test_admin_replaces_audio_of_existing_track(): void
    {
        Storage::fake('s3');
        Queue::fake();
        $admin = User::factory()->create(['username' => 'boss']);
        $admin->forceFill(['is_admin' => true])->save();
        $track = $this->makeTrack();

        $this->actingAs($admin)
            ->post("/admin/tracks/{$track->id}/audio", [
                'audio' => UploadedFile::fake()->create('full-version.mp3', 2048, 'audio/mpeg'),
            ])
            ->assertRedirect();

        $this->assertSame(ProcessingStatus::Pending, $track->fresh()->processing_status);
        Queue::assertPushed(ProcessTrackAudio::class, fn (ProcessTrackAudio $job) => $job->trackId === $track->id
            && str_starts_with($job->tmpUploadPath, 'tmp-uploads/')
            && $job->originalExt === 'mp3');
    }

    public function test_regular_user_cannot_replace_audio(): void
    {
        Storage::fake('s3');
        Queue::fake();
        $user = User::factory()->create(['username' => 'me']);
        $track = $this->makeTrack();

        $this->actingAs($user)
            ->post("/admin/tracks/{$track->id}/audio", [
                'audio' => UploadedFile::fake()->create('x.mp3', 10, 'audio/mpeg'),
            ])
            ->assertForbidden();

        // Scout ставит свои задачи на сохранение моделей — смотрим только обработку.
        Queue::assertNotPushed(ProcessTrackAudio::class);
    }

    public function test_stream_file_attributes_describe_the_file(): void
    {
        $path = tempnam(sys_get_temp_dir(), 'snd');
        file_put_contents($path, 'not really an mp3');

        $attrs = Track::streamFileAttributes('audio/7/stream.mp3', $path);

        $this->assertSame('audio/7/stream.mp3', $attrs['audio_stream_path']);
        $this->assertSame(md5('not really an mp3'), $attrs['audio_rev']);
        $this->assertSame(strlen('not really an mp3'), $attrs['audio_size']);
        $this->assertNotNull($attrs['audio_updated_at']);

        @unlink($path);
    }
}
