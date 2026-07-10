<?php

namespace App\Policies;

use App\Models\Playlist;
use App\Models\User;

class PlaylistPolicy
{
    public function view(?User $user, Playlist $playlist): bool
    {
        return $playlist->is_public
            || ($user && $user->id === $playlist->user_id)
            || $playlist->isCollaborator($user);
    }

    /** Владелец и участники совместного плейлиста могут менять треки. */
    public function update(User $user, Playlist $playlist): bool
    {
        return $user->id === $playlist->user_id || $playlist->isCollaborator($user);
    }

    public function delete(User $user, Playlist $playlist): bool
    {
        return $user->id === $playlist->user_id;
    }
}
