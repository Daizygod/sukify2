<?php

use App\Http\Controllers\Admin\ArtistController;
use App\Http\Controllers\Admin\AuthController;
use App\Http\Controllers\Admin\DashboardController;
use App\Http\Controllers\Admin\ReleaseController;
use App\Http\Controllers\Admin\TrackController;
use App\Http\Controllers\Admin\UserController;
use Illuminate\Support\Facades\Route;

Route::get('/', fn () => redirect('/admin'));

Route::prefix('admin')->name('admin.')->group(function () {
    // Guest (admin login)
    Route::middleware('guest')->group(function () {
        Route::get('login', [AuthController::class, 'show'])->name('login');
        Route::post('login', [AuthController::class, 'login'])->name('login.attempt');
    });

    // Authenticated admins only
    Route::middleware(['auth', 'admin'])->group(function () {
        Route::post('logout', [AuthController::class, 'logout'])->name('logout');

        Route::get('/', [DashboardController::class, 'index'])->name('dashboard');

        Route::get('artists/lookup', [ArtistController::class, 'lookup'])->name('artists.lookup');
        Route::resource('artists', ArtistController::class)->except('show');
        Route::resource('releases', ReleaseController::class);

        // Tracks live under a release.
        Route::post('releases/{release}/tracks', [TrackController::class, 'store'])->name('tracks.store');
        Route::put('tracks/{track}', [TrackController::class, 'update'])->name('tracks.update');
        Route::delete('tracks/{track}', [TrackController::class, 'destroy'])->name('tracks.destroy');
        Route::put('releases/{release}/tracks/order', [TrackController::class, 'reorder'])->name('tracks.reorder');
        Route::get('tracks/{track}/status', [TrackController::class, 'status'])->name('tracks.status');

        Route::get('users', [UserController::class, 'index'])->name('users.index');
        Route::put('users/{user}/ban', [UserController::class, 'toggleBan'])->name('users.ban');
    });
});
