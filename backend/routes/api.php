<?php

use App\Http\Controllers\Api\ArtistController;
use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\HomeController;
use App\Http\Controllers\Api\ImportController;
use App\Http\Controllers\Api\LibraryController;
use App\Http\Controllers\Api\PlaybackSettingController;
use App\Http\Controllers\Api\JamController;
use App\Http\Controllers\Api\PlaylistController;
use App\Http\Controllers\Api\RealtimeController;
use App\Http\Controllers\Api\ReleaseController;
use App\Http\Controllers\Api\SearchController;
use App\Http\Controllers\Api\TrackController;
use App\Http\Controllers\Api\TransitionController;
use App\Http\Controllers\Api\UserProfileController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| Public endpoints — ТОЛЬКО вход и регистрация
|--------------------------------------------------------------------------
| Каталог полностью закрыт от гостей: без сессии не видно ни треков,
| ни артистов, ни поиска.
*/

// Auth (SPA cookie flow — hit /sanctum/csrf-cookie first).
Route::post('/register', [AuthController::class, 'register']);
Route::post('/login', [AuthController::class, 'login']);
Route::post('/logout', [AuthController::class, 'logout'])->middleware('auth:sanctum');

/*
|--------------------------------------------------------------------------
| Authenticated endpoints (Sanctum SPA session), non-banned users
|--------------------------------------------------------------------------
*/
Route::middleware(['auth:sanctum', 'not.banned'])->group(function () {
    Route::get('/me', [AuthController::class, 'me']);

    // Browse (каталог — только для залогиненных).
    Route::get('/home', HomeController::class);
    Route::get('/search', SearchController::class);
    Route::get('/genres', [\App\Http\Controllers\Api\GenreController::class, 'index']);
    Route::get('/genres/{genre}', [\App\Http\Controllers\Api\GenreController::class, 'show']);

    Route::get('/artists', [ArtistController::class, 'index']);
    Route::get('/artists/{artist:slug}', [ArtistController::class, 'show']);
    Route::get('/artists/{artist:slug}/top-tracks', [ArtistController::class, 'topTracks']);
    Route::get('/artists/{artist:slug}/releases', [ArtistController::class, 'releases']);

    Route::get('/releases/{release:slug}', [ReleaseController::class, 'show']);
    Route::get('/tracks-bulk', [TrackController::class, 'bulk']);
    Route::get('/tracks/{track}', [TrackController::class, 'show']);
    Route::get('/tracks/{track}/radio', [\App\Http\Controllers\Api\MixController::class, 'trackRadio']);
    Route::get('/tracks/{track}/lyrics', [\App\Http\Controllers\Api\LyricsController::class, 'show']);

    Route::get('/transitions', [TransitionController::class, 'forPair']);
    Route::get('/transitions/all', [TransitionController::class, 'index']);
    Route::get('/transitions/for-context', [TransitionController::class, 'forContext']);

    Route::get('/users/{username}', [UserProfileController::class, 'show']);

    // Playlist read is guarded per-policy (public or owner) inside the controller.
    Route::get('/playlists/{playlist}', [PlaylistController::class, 'show']);

    // Library.
    Route::get('/library/playlists', [LibraryController::class, 'playlists']);
    Route::get('/library/liked-tracks', [LibraryController::class, 'likedTracks']);
    Route::get('/library/liked-albums', [LibraryController::class, 'likedAlbums']);
    Route::get('/library/followed-artists', [LibraryController::class, 'followedArtists']);
    Route::get('/me/history', [LibraryController::class, 'history']);
    Route::get('/me/stats', [\App\Http\Controllers\Api\StatsController::class, 'monthly']);
    Route::get('/me/notifications', [\App\Http\Controllers\Api\StatsController::class, 'notifications']);
    Route::get('/library/pins', [LibraryController::class, 'pins']);
    Route::post('/library/pins', [LibraryController::class, 'pin']);
    Route::delete('/library/pins', [LibraryController::class, 'unpin']);

    // Collaborative playlists.
    Route::post('/playlists/{playlist}/invite', [PlaylistController::class, 'invite']);
    Route::post('/playlists/{playlist}/join/{token}', [PlaylistController::class, 'join']);

    // Auto mixes.
    Route::get('/mixes/daily', [\App\Http\Controllers\Api\MixController::class, 'daily']);
    Route::get('/mixes/daily/{n}', [\App\Http\Controllers\Api\MixController::class, 'show']);
    Route::get('/playlists/{playlist}/recommendations', [PlaylistController::class, 'recommendations']);

    // Import liked tracks from Spotify exports.
    Route::post('/import/liked', [ImportController::class, 'likedTracks']);

    // Playback settings.
    Route::get('/playback-settings', [PlaybackSettingController::class, 'show']);
    Route::put('/playback-settings', [PlaybackSettingController::class, 'update']);

    // Likes / follows.
    Route::post('/tracks/{track}/like', [TrackController::class, 'like']);
    Route::delete('/tracks/{track}/like', [TrackController::class, 'unlike']);
    Route::post('/tracks/{track}/play', [TrackController::class, 'logPlay']);

    Route::post('/releases/{release}/like', [ReleaseController::class, 'like']);
    Route::delete('/releases/{release}/like', [ReleaseController::class, 'unlike']);

    Route::post('/users/{username}/follow', [\App\Http\Controllers\Api\UserFollowController::class, 'follow']);
    Route::delete('/users/{username}/follow', [\App\Http\Controllers\Api\UserFollowController::class, 'unfollow']);
    Route::get('/me/friends-activity', [\App\Http\Controllers\Api\UserFollowController::class, 'friendsActivity']);

    Route::post('/artists/{artist:slug}/follow', [ArtistController::class, 'follow']);
    Route::delete('/artists/{artist:slug}/follow', [ArtistController::class, 'unfollow']);
    Route::get('/artists/{artist:slug}/liked', [ArtistController::class, 'liked']);

    // Playlists (mutations).
    Route::post('/playlists', [PlaylistController::class, 'store']);
    Route::put('/playlists/{playlist}', [PlaylistController::class, 'update']);
    Route::delete('/playlists/{playlist}', [PlaylistController::class, 'destroy']);
    Route::post('/playlists/{playlist}/tracks', [PlaylistController::class, 'addTrack']);
    Route::delete('/playlists/{playlist}/tracks/{item}', [PlaylistController::class, 'removeTrack']);
    Route::put('/playlists/{playlist}/order', [PlaylistController::class, 'reorder']);

    // Transitions (crossfade).
    Route::post('/transitions', [TransitionController::class, 'store']);
    Route::delete('/transitions/{transition}', [TransitionController::class, 'destroy']);
    Route::post('/transitions/{transition}/like', [TransitionController::class, 'like']);
    Route::delete('/transitions/{transition}/like', [TransitionController::class, 'unlike']);
    Route::post('/transitions/{transition}/prefer', [TransitionController::class, 'prefer']);
    Route::delete('/transitions/{transition}/prefer', [TransitionController::class, 'unprefer']);

    // Realtime (Centrifugo) — device sync + Jam tokens.
    Route::post('/realtime/connection-token', [RealtimeController::class, 'connectionToken']);
    Route::post('/realtime/subscription-token', [RealtimeController::class, 'subscriptionToken']);

    // Jam (listening sessions).
    Route::post('/jam/sessions', [JamController::class, 'store']);
    Route::post('/jam/sessions/join', [JamController::class, 'join']);
    Route::get('/jam/sessions/{session}', [JamController::class, 'show']);
    Route::post('/jam/sessions/{session}/leave', [JamController::class, 'leave']);
    Route::post('/jam/sessions/{session}/end', [JamController::class, 'end']);
});
