<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Переход — это уже не просто четыре точки фейда: к громкости добавились
        // эквалайзер и фильтр, а длина умеет считаться в тактах.
        Schema::table('track_transitions', function (Blueprint $table) {
            $table->string('preset', 24)->default('custom');
            $table->string('volume_shape', 32)->default('crossfade');
            $table->string('eq_shape', 32)->default('none');
            $table->string('filter_shape', 32)->default('none');
            // Длина перекрытия в тактах — только когда темпы треков сходятся.
            $table->unsignedSmallInteger('bars')->nullable();
        });

        // Режим микса у плейлиста: колонки BPM/тональности и чипы переходов.
        Schema::table('playlists', function (Blueprint $table) {
            $table->boolean('mix_enabled')->default(false);
        });
    }

    public function down(): void
    {
        Schema::table('track_transitions', function (Blueprint $table) {
            $table->dropColumn(['preset', 'volume_shape', 'eq_shape', 'filter_shape', 'bars']);
        });
        Schema::table('playlists', function (Blueprint $table) {
            $table->dropColumn('mix_enabled');
        });
    }
};
