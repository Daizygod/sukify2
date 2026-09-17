<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Привязка настольного компаньона по device code flow (как у ТВ-приложений):
     * приложение показывает короткий код, пользователь подтверждает его в вебе,
     * приложение обменивает свой длинный device_code на токен Sanctum.
     */
    public function up(): void
    {
        Schema::create('desktop_devices', function (Blueprint $table) {
            $table->id();
            // NULL, пока код не подтверждён в браузере.
            $table->foreignId('user_id')->nullable()->constrained('users')->cascadeOnDelete();

            // Секрет приложения; обнуляется сразу после обмена на токен.
            $table->string('device_code', 64)->nullable()->unique();
            // То, что пользователь видит и вводит/подтверждает: SUKI-4F2A.
            $table->string('user_code', 16)->nullable()->unique();

            $table->string('name')->nullable();
            $table->string('platform', 32)->nullable();

            $table->timestamp('approved_at')->nullable();
            $table->timestamp('last_seen_at')->nullable();
            // Дедлайн для неподтверждённого кода; после обмена — NULL.
            $table->timestamp('expires_at')->nullable();

            // Токен Sanctum, выданный этому устройству, — чтобы отзывать по кнопке.
            $table->unsignedBigInteger('token_id')->nullable();

            $table->timestamps();

            $table->index(['user_id', 'approved_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('desktop_devices');
    }
};
