<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration
{
    public function up(): void
    {
        DB::statement('ALTER TABLE track_transitions DROP CONSTRAINT IF EXISTS track_transitions_curve_type_check');
        DB::statement("ALTER TABLE track_transitions ADD CONSTRAINT track_transitions_curve_type_check CHECK (curve_type IN ('linear','exponential','logarithmic','s_curve','equal_power'))");
    }

    public function down(): void
    {
        DB::statement('ALTER TABLE track_transitions DROP CONSTRAINT IF EXISTS track_transitions_curve_type_check');
        DB::statement("ALTER TABLE track_transitions ADD CONSTRAINT track_transitions_curve_type_check CHECK (curve_type IN ('linear','exponential','logarithmic','s_curve'))");
    }
};
