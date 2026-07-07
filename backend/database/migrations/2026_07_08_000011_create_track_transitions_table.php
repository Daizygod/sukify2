<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('track_transitions', function (Blueprint $table) {
            $table->id();
            $table->foreignId('from_track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('to_track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('created_by_user_id')->nullable()->constrained('users')->nullOnDelete();

            // Fade envelope, all relative to each track's own timeline (ms).
            $table->unsignedInteger('fade_out_start_ms');
            $table->unsignedInteger('fade_out_end_ms');
            $table->unsignedInteger('fade_in_start_ms');
            $table->unsignedInteger('fade_in_full_volume_ms');

            $table->enum('curve_type', ['linear', 'exponential', 'logarithmic', 's_curve'])
                ->default('linear');

            $table->unsignedBigInteger('likes_count')->default(0);

            $table->timestamps();

            // Default-transition lookup: best-liked transition for a track pair.
            $table->index(['from_track_id', 'to_track_id', 'likes_count'], 'transitions_pair_likes_idx');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('track_transitions');
    }
};
