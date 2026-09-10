<?php

namespace App\Console\Commands\Concerns;

use App\Models\User;

/** Опция --user у консольных команд: email, @handle или id. */
trait ResolvesUser
{
    protected function resolveUser(string $raw): ?User
    {
        if ($raw === '') {
            // Единственный аккаунт — очевидный выбор, иначе просим уточнить.
            return User::count() === 1 ? User::first() : null;
        }
        if (ctype_digit($raw)) {
            return User::find((int) $raw);
        }
        if (str_contains($raw, '@') && ! str_starts_with($raw, '@')) {
            return User::where('email', $raw)->first();
        }

        return User::findByHandle(ltrim($raw, '@'));
    }
}
