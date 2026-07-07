<?php

use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Schedule;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

// Reconcile S3 with the DB once a day (spec §3).
Schedule::command('storage:cleanup-orphans')->dailyAt('04:00')->withoutOverlapping();
