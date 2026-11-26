<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Массовая загрузка: архив или папка с музыкой целиком. Файлы раскладываются по
 * трекам каталога (полная версия вместо превью) или заводятся новыми.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('bulk_uploads', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->nullable()->constrained()->nullOnDelete();

            // Имя архива или папки — по нему же узнаём недокачанное.
            $table->string('name');
            // archive — один файл, докачивается кусками; folder — файлы по одному.
            $table->string('kind', 16);
            $table->unsignedBigInteger('size_bytes')->default(0);
            $table->unsignedBigInteger('received_bytes')->default(0);
            $table->unsignedInteger('files_expected')->default(0);

            // uploading → queued → unpacking → reading → matching → importing → done
            // (или failed / canceled). Файлы на диске: storage/app/bulk/{id}.
            $table->string('status', 16)->default('uploading');
            $table->string('stage')->nullable();
            $table->unsignedSmallInteger('progress')->default(0);

            // create_missing, replace_full, check_censorship.
            $table->jsonb('options')->nullable();
            $table->text('error')->nullable();
            $table->timestamp('finished_at')->nullable();
            $table->timestamps();

            $table->index(['user_id', 'status']);
        });

        Schema::create('bulk_upload_items', function (Blueprint $table) {
            $table->id();
            $table->foreignId('bulk_upload_id')->constrained()->cascadeOnDelete();

            // Путь внутри архива или папки — по нему видно, что это за файл.
            $table->text('path');
            $table->string('ext', 8);
            $table->unsignedBigInteger('size_bytes')->default(0);
            // Относительно storage/app/bulk/{id}; null — файла уже нет.
            $table->string('local_path')->nullable();

            // pending → queued → processing → done, либо review / skipped /
            // duplicate / failed. asset — картинка из папки альбома (обложка).
            $table->string('status', 16)->default('pending');
            // attach — к треку каталога, create — новый трек.
            $table->string('action', 16)->nullable();
            // Почему такой статус: matched, ambiguous, no_match, has_full_audio…
            $table->string('reason', 32)->nullable();

            // Теги, формат и длительность — из ffprobe, с подсказками из пути.
            $table->jsonb('tags')->nullable();
            // Куда лёг файл: основной трек и все, кому он достался (одна запись
            // бывает и в сингле, и в альбоме).
            $table->foreignId('track_id')->nullable()->constrained()->nullOnDelete();
            $table->jsonb('track_ids')->nullable();
            $table->unsignedSmallInteger('score')->nullable();
            $table->jsonb('candidates')->nullable();
            // Копия получше уже взята из этого же файла.
            $table->unsignedBigInteger('duplicate_of_id')->nullable();

            // Сверка с превью Deezer: clean | suspect | differs | unknown.
            $table->string('censor', 16)->nullable();
            $table->jsonb('censor_info')->nullable();

            $table->text('error')->nullable();
            $table->timestamps();

            $table->index(['bulk_upload_id', 'status']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('bulk_upload_items');
        Schema::dropIfExists('bulk_uploads');
    }
};
