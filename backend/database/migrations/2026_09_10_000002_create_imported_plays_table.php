<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('imported_plays', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->foreignId('spotify_import_id')->nullable()->constrained()->nullOnDelete();

            $table->timestampTz('played_at');
            $table->unsignedInteger('ms_played')->default(0);

            // Имена держим денормализованно: история покрывает 13k треков, из
            // которых в каталог попадает лишь то, что есть в библиотеке.
            $table->string('artist_name', 300);
            $table->string('track_name', 400);
            $table->string('album_name', 400)->nullable();
            $table->string('spotify_uri', 64)->nullable();

            // Проставляется, когда трек всё же оказался в каталоге.
            $table->foreignId('track_id')->nullable()->constrained('tracks')->nullOnDelete();

            $table->boolean('skipped')->default(false);
            $table->boolean('shuffle')->default(false);
            $table->string('platform', 24)->nullable();
            $table->string('reason_end', 32)->nullable();

            // md5(user|минута|артист|трек): один и тот же прослух приходит и в
            // Account Data (с точностью до минуты), и в Extended History —
            // отпечаток гасит дубли между архивами.
            $table->char('fingerprint', 32);

            $table->unique(['user_id', 'fingerprint']);
            $table->index(['user_id', 'played_at']);
            $table->index(['user_id', 'spotify_uri']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('imported_plays');
    }
};
