<?php

use App\Models\User;
use Illuminate\Database\Migrations\Migration;

/**
 * У зарегистрировавшихся через форму username оставался пустым, поэтому их
 * профиль (/user/{username}) был недоступен — ссылка вида /user/3 открывала
 * пустой экран. Проставляем @handle всем, у кого его нет.
 */
return new class extends Migration
{
    public function up(): void
    {
        User::whereNull('username')->orWhere('username', '')->each(function (User $user) {
            $user->username = User::generateUsername($user->name ?? '', $user->email ?? '');
            $user->saveQuietly();
        });
    }

    public function down(): void
    {
        // Обратно чистить нечего: пустой username был багом, а не состоянием.
    }
};
