<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Волна в трёх полосах: у каждой тело (RMS) и гребень (максимум).
        // Одной колонкой, а не шестью: читается всегда целиком и только
        // редактором перехода.
        Schema::table('tracks', function (Blueprint $table) {
            $table->jsonb('waveform_bands')->nullable();
        });
    }

    public function down(): void
    {
        Schema::table('tracks', function (Blueprint $table) {
            $table->dropColumn('waveform_bands');
        });
    }
};
