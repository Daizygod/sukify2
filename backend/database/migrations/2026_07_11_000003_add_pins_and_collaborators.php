<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Закреплённые элементы медиатеки (зелёная булавка в сайдбаре).
        Schema::create('pinned_items', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('item_type', 20); // playlist | album | artist
            $table->unsignedBigInteger('item_id');
            $table->timestamps();
            $table->unique(['user_id', 'item_type', 'item_id']);
        });

        // Совместные плейлисты: инвайт-токен + участники.
        Schema::table('playlists', function (Blueprint $table) {
            $table->string('invite_token', 64)->nullable()->unique()->after('is_public');
        });

        Schema::create('playlist_collaborators', function (Blueprint $table) {
            $table->id();
            $table->foreignId('playlist_id')->constrained()->cascadeOnDelete();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->timestamps();
            $table->unique(['playlist_id', 'user_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('playlist_collaborators');
        Schema::table('playlists', fn (Blueprint $t) => $t->dropColumn('invite_token'));
        Schema::dropIfExists('pinned_items');
    }
};
