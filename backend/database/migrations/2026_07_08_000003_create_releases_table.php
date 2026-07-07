<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('releases', function (Blueprint $table) {
            $table->id();
            $table->foreignId('artist_id')->constrained('artists')->cascadeOnDelete();

            $table->string('title');
            $table->string('slug')->index();
            $table->enum('type', ['album', 'single', 'compilation'])->default('album');
            $table->date('release_date')->nullable();

            // Cover art: original upload + the base path from which sized WebP/JPEG
            // renditions are derived ({prefix}/covers/{release_id}/{size}.webp).
            $table->string('cover_original_path')->nullable();
            $table->string('cover_path')->nullable();
            $table->enum('cover_status', ['pending', 'processing', 'ready', 'failed'])->default('pending');

            // Extracted from the cover for the Now Playing / release gradient.
            $table->string('dominant_color_hex', 7)->nullable();
            $table->string('text_color_hex', 7)->nullable();

            $table->timestamps();

            $table->index(['artist_id', 'type']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('releases');
    }
};
