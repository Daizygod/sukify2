<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('track_lyrics', function (Blueprint $table) {
            $table->id();
            $table->foreignId('track_id')->unique()->constrained()->cascadeOnDelete();
            $table->longText('synced_lyrics')->nullable();
            $table->longText('plain_lyrics')->nullable();
            $table->boolean('found')->default(false);
            $table->timestamp('fetched_at')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('track_lyrics');
    }
};
