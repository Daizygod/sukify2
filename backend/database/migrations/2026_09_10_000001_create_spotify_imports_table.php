<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('spotify_imports', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();

            $table->string('original_name');
            $table->unsignedBigInteger('size_bytes')->default(0);
            // Архив лежит на локальном диске воркера, не в S3: он нужен
            // ровно на время разбора и удаляется в конце.
            $table->string('archive_path')->nullable();

            // Что нашли внутри: account | extended | technical | mixed.
            $table->string('kind', 16)->nullable();

            // pending → parsing → enriching → done | failed
            $table->string('status', 16)->default('pending');
            $table->string('stage')->nullable();
            $table->unsignedSmallInteger('progress')->default(0);

            // Сколько треков нужно обогатить через Deezer и сколько уже сделано.
            $table->unsignedInteger('enrich_total')->default(0);
            $table->unsignedInteger('enrich_done')->default(0);

            $table->json('summary')->nullable();
            $table->text('error')->nullable();

            $table->timestamps();

            $table->index(['user_id', 'created_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('spotify_imports');
    }
};
