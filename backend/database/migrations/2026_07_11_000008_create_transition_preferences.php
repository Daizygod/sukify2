<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Личный выбор перехода для пары треков: перекрывает переход сообщества.
        Schema::create('transition_preferences', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('from_track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('to_track_id')->constrained('tracks')->cascadeOnDelete();
            $table->foreignId('transition_id')->constrained('track_transitions')->cascadeOnDelete();
            $table->timestamps();
            $table->unique(['user_id', 'from_track_id', 'to_track_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('transition_preferences');
    }
};
