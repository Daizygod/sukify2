<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Результат анализа трека: темп, сетка долей, тональность и пики волны.
        // Всё это нужно редактору переходов — без BPM нельзя считать такты, без
        // тональности нет камелота, без пиков нечего рисовать.
        Schema::table('tracks', function (Blueprint $table) {
            $table->decimal('bpm', 6, 2)->nullable();
            $table->unsignedInteger('beat_offset_ms')->nullable();
            $table->float('beat_confidence')->nullable();
            // Времена долей в миллисекундах: сетку нельзя вывести из одного BPM,
            // живые записи плывут.
            $table->jsonb('beats')->nullable();

            $table->string('musical_key', 3)->nullable();
            $table->string('musical_scale', 6)->nullable();
            $table->float('key_strength')->nullable();
            $table->string('camelot', 4)->nullable();

            // Пики волны: base64 от массива байт 0..255, отдельно полная полоса
            // и бас (его рисуем вторым цветом поверх основной волны).
            $table->text('waveform_full')->nullable();
            $table->text('waveform_bass')->nullable();
            $table->unsignedSmallInteger('waveform_buckets')->nullable();

            $table->string('analysis_status', 16)->default('pending');
            $table->text('analysis_error')->nullable();
            $table->timestamp('analyzed_at')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            $table->dropColumn([
                'bpm', 'beat_offset_ms', 'beat_confidence', 'beats',
                'musical_key', 'musical_scale', 'key_strength', 'camelot',
                'waveform_full', 'waveform_bass', 'waveform_buckets',
                'analysis_status', 'analysis_error', 'analyzed_at',
            ]);
        });
    }
};
