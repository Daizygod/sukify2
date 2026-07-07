<?php

namespace App\Http\Controllers\Concerns;

use App\Models\User;

trait ResolvesLikes
{
    /**
     * Set an `is_liked` flag on each track for the given user with a single query.
     *
     * NB: uses foreach, not Collection::each(), because each() halts when the
     * callback returns false — and an assignment expression returns its value.
     */
    protected function markLikedTracks(iterable $tracks, ?User $user): void
    {
        $tracks = collect($tracks);

        if (! $user || $tracks->isEmpty()) {
            foreach ($tracks as $t) {
                $t->is_liked = false;
            }

            return;
        }

        $likedIds = $user->likedTracks()
            ->whereIn('tracks.id', $tracks->pluck('id'))
            ->pluck('tracks.id')
            ->flip();

        foreach ($tracks as $t) {
            $t->is_liked = $likedIds->has($t->id);
        }
    }

    protected function markLikedReleases(iterable $releases, ?User $user): void
    {
        $releases = collect($releases);

        if (! $user || $releases->isEmpty()) {
            foreach ($releases as $r) {
                $r->is_liked = false;
            }

            return;
        }

        $likedIds = $user->likedAlbums()
            ->whereIn('releases.id', $releases->pluck('id'))
            ->pluck('releases.id')
            ->flip();

        foreach ($releases as $r) {
            $r->is_liked = $likedIds->has($r->id);
        }
    }

    protected function markFollowedArtists(iterable $artists, ?User $user): void
    {
        $artists = collect($artists);

        if (! $user || $artists->isEmpty()) {
            foreach ($artists as $a) {
                $a->is_followed = false;
            }

            return;
        }

        $followedIds = $user->followedArtists()
            ->whereIn('artists.id', $artists->pluck('id'))
            ->pluck('artists.id')
            ->flip();

        foreach ($artists as $a) {
            $a->is_followed = $followedIds->has($a->id);
        }
    }
}
