<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('discord_connections', function (Blueprint $table) {
            $table->foreignId('user_id')->primary()->constrained('users')->cascadeOnDelete();

            // Профиль из /users/@me — показываем в настройках и в профиле Sukify.
            $table->string('discord_id')->unique();
            $table->string('username');
            $table->string('global_name')->nullable();
            $table->string('avatar')->nullable();

            // Токены нужны только чтобы обновить профиль позже; сама активность
            // ставится локальным компаньоном через IPC и OAuth не использует.
            $table->text('access_token')->nullable();
            $table->text('refresh_token')->nullable();
            $table->timestamp('token_expires_at')->nullable();

            // Настройки трансляции — читает компаньон при каждом апдейте.
            $table->boolean('presence_enabled')->default(true);
            $table->boolean('show_when_paused')->default(false);
            $table->boolean('show_button')->default(true);
            $table->boolean('show_cover')->default(true);

            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('discord_connections');
    }
};
