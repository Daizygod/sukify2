<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            // Аудио сгенерировано demo:generate-audio (можно безопасно перегенерить).
            $table->boolean('demo_audio')->default(false)->after('unofficial');
        });

        // Всё нынешнее демо-аудио — синтетические тоны с original.flac.
        DB::table('tracks')
            ->where('audio_original_path', 'like', '%original.flac')
            ->update(['demo_audio' => true]);
    }

    public function down(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            $table->dropColumn('demo_audio');
        });
    }
};
