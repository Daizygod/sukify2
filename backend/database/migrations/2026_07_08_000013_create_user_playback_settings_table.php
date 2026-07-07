<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('user_playback_settings', function (Blueprint $table) {
            $table->foreignId('user_id')->primary()->constrained('users')->cascadeOnDelete();

            // Client applies gain = 10^((target - track_lufs)/20). Default like Spotify.
            $table->float('target_loudness_lufs')->default(-14);
            $table->unsignedInteger('default_crossfade_seconds')->default(0);
            $table->boolean('smart_shuffle_enabled')->default(false);

            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('user_playback_settings');
    }
};
