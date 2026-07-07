<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('listening_session_members', function (Blueprint $table) {
            $table->foreignId('session_id')->constrained('listening_sessions')->cascadeOnDelete();
            $table->foreignId('user_id')->constrained('users')->cascadeOnDelete();
            $table->enum('role', ['host', 'guest'])->default('guest');
            $table->timestamp('joined_at')->useCurrent();

            $table->primary(['session_id', 'user_id']);
            $table->index('user_id');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('listening_session_members');
    }
};
