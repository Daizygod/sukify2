<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Соавторы релиза (несколько создателей альбома, как в Spotify).
        Schema::create('release_artists', function (Blueprint $table) {
            $table->id();
            $table->foreignId('release_id')->constrained()->cascadeOnDelete();
            $table->foreignId('artist_id')->constrained()->cascadeOnDelete();
            $table->unsignedSmallInteger('position')->default(0);
            $table->unique(['release_id', 'artist_id']);
        });

        // Backfill: нынешний главный артист становится позицией 0.
        $rows = DB::table('releases')->get(['id', 'artist_id']);
        foreach ($rows as $r) {
            DB::table('release_artists')->insert([
                'release_id' => $r->id,
                'artist_id' => $r->artist_id,
                'position' => 0,
            ]);
        }
    }

    public function down(): void
    {
        Schema::dropIfExists('release_artists');
    }
};
