<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Свой текст трека: админ может поправить его в админке, и тогда он имеет
 * приоритет над LRCLIB (и не перетирается еженедельным перезапросом).
 * lrclib_id хранит запись LRCLIB, на которую можно пожаловаться.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('track_lyrics', function (Blueprint $table) {
            $table->boolean('is_custom')->default(false)->after('found');
            $table->unsignedBigInteger('lrclib_id')->nullable()->after('is_custom');
        });
    }

    public function down(): void
    {
        Schema::table('track_lyrics', function (Blueprint $table) {
            $table->dropColumn(['is_custom', 'lrclib_id']);
        });
    }
};
