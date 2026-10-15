<?php

namespace Tests;

use Illuminate\Foundation\Testing\TestCase as BaseTestCase;
use Illuminate\Support\Facades\Storage;

abstract class TestCase extends BaseTestCase
{
    protected function setUp(): void
    {
        parent::setUp();

        // Тестовая база своя, а бакет S3 (MinIO) — общий с локальным стендом.
        // Очередь в тестах синхронная, и удаление трека с id 3 стирало бы
        // audio/3 настоящего демо-трека. Поэтому S3 в тестах всегда подделка.
        Storage::fake('s3');
    }
}
