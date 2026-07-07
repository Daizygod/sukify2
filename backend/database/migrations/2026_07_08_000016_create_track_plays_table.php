<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('track_plays', function (Blueprint $table) {
            $table->id();
            $table->foreignId('track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->timestamp('played_at')->useCurrent();

            // Drives "recently played" on Home and per-track play stats in admin.
            $table->index(['user_id', 'played_at']);
            $table->index(['track_id', 'played_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('track_plays');
    }
};
