<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('tracks', function (Blueprint $table) {
            $table->id();
            // Nullable per spec, but by default every track belongs to a release.
            $table->foreignId('release_id')->nullable()->constrained('releases')->cascadeOnDelete();

            $table->string('title');
            $table->unsignedInteger('track_number')->nullable();
            $table->unsignedInteger('duration_ms')->nullable();

            // Audio pipeline outputs.
            $table->string('audio_original_path')->nullable();   // flac (or as-uploaded)
            $table->string('audio_stream_path')->nullable();     // aac/m4a streaming rendition
            $table->float('loudness_lufs')->nullable();          // integrated LUFS from loudnorm
            $table->unsignedBigInteger('file_size_original')->nullable();

            // Optional per-track cover (compilations); otherwise inherits release cover.
            $table->string('cover_override_path')->nullable();

            $table->enum('processing_status', ['pending', 'processing', 'ready', 'failed'])->default('pending');
            $table->text('processing_error')->nullable();

            // Denormalized counters for admin stats.
            $table->unsignedBigInteger('plays_count')->default(0);
            $table->unsignedBigInteger('likes_count')->default(0);

            $table->timestamps();

            $table->index(['release_id', 'track_number']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('tracks');
    }
};
