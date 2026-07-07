<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            // Public profile handle (used for /user/{username} pages).
            $table->string('username')->nullable()->unique()->after('name');
            $table->string('avatar_path')->nullable()->after('email');

            // Single admin gate (no RBAC per spec §9) and ban flag.
            $table->boolean('is_admin')->default(false)->after('avatar_path');
            $table->boolean('is_banned')->default(false)->after('is_admin');
            $table->timestamp('banned_at')->nullable()->after('is_banned');
        });
    }

    public function down(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn(['username', 'avatar_path', 'is_admin', 'is_banned', 'banned_at']);
        });
    }
};
