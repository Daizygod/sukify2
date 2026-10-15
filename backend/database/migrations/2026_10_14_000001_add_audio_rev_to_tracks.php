<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Ревизия аудиофайла трека — md5 содержимого stream.mp3.
     *
     * По ней веб сбрасывает кэш браузера, а приложение понимает, что скачанный
     * файл устарел и его пора перекачать. updated_at для этого не годится:
     * его сдвигает каждое прослушивание и лайк (increment трогает timestamps).
     */
    public function up(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            $table->string('audio_rev', 32)->nullable()->after('audio_stream_path');
            $table->unsignedBigInteger('audio_size')->nullable()->after('audio_rev');
            $table->timestamp('audio_updated_at')->nullable()->after('audio_size');
        });
    }

    public function down(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            $table->dropColumn(['audio_rev', 'audio_size', 'audio_updated_at']);
        });
    }
};
