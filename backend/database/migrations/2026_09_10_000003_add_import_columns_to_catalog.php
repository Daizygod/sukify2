<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('artists', function (Blueprint $table) {
            $table->boolean('imported')->default(false)->after('monthly_listeners');
            $table->unsignedBigInteger('deezer_id')->nullable()->after('imported');
        });

        Schema::table('releases', function (Blueprint $table) {
            $table->boolean('imported')->default(false)->after('text_color_hex');
            $table->unsignedBigInteger('deezer_id')->nullable()->after('imported');
            $table->string('spotify_uri', 64)->nullable()->after('deezer_id');
        });

        Schema::table('playlists', function (Blueprint $table) {
            $table->boolean('imported')->default(false)->after('is_public');
        });

        Schema::table('tracks', function (Blueprint $table) {
            $table->string('spotify_uri', 64)->nullable()->after('unofficial');
            $table->unsignedBigInteger('deezer_id')->nullable()->after('spotify_uri');
            // Аудио — 30-секундное превью Deezer, а не полный трек.
            $table->boolean('preview_only')->default(false)->after('deezer_id');
            // pending | ready | notfound | failed (null — трек не из импорта).
            $table->string('enrich_status', 16)->nullable()->after('preview_only');

            $table->index('spotify_uri');
            $table->index('enrich_status');
        });
    }

    public function down(): void
    {
        Schema::table('artists', fn (Blueprint $t) => $t->dropColumn(['imported', 'deezer_id']));
        Schema::table('releases', fn (Blueprint $t) => $t->dropColumn(['imported', 'deezer_id', 'spotify_uri']));
        Schema::table('playlists', fn (Blueprint $t) => $t->dropColumn('imported'));
        Schema::table('tracks', function (Blueprint $table) {
            $table->dropIndex(['spotify_uri']);
            $table->dropIndex(['enrich_status']);
            $table->dropColumn(['spotify_uri', 'deezer_id', 'preview_only', 'enrich_status']);
        });
    }
};
