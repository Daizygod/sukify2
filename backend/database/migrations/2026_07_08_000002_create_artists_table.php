<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('artists', function (Blueprint $table) {
            $table->id();
            $table->string('name');
            $table->string('slug')->unique();
            $table->text('bio')->nullable();

            $table->string('avatar_path')->nullable();
            $table->string('banner_path')->nullable();

            // Derived from the banner image (same pipeline as release covers).
            $table->string('dominant_color_hex', 7)->nullable();
            $table->string('text_color_hex', 7)->nullable();

            // Denormalized, recomputed by a job.
            $table->unsignedBigInteger('monthly_listeners')->default(0);

            $table->timestamps();

            $table->index('name');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('artists');
    }
};
