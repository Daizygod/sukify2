<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('track_artist', function (Blueprint $table) {
            $table->foreignId('track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('artist_id')->constrained('artists')->cascadeOnDelete();
            $table->enum('role', ['main', 'featured'])->default('main');
            $table->unsignedInteger('position')->default(0);

            $table->primary(['track_id', 'artist_id', 'role']);
            $table->index('artist_id');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('track_artist');
    }
};
