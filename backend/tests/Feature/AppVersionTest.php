<?php

namespace Tests\Feature;

use App\Http\Controllers\Api\AppVersionController;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Storage;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class AppVersionTest extends TestCase
{
    use RefreshDatabase;

    public function test_returns_latest_published_build(): void
    {
        Storage::fake('s3');
        Storage::disk('s3')->put(AppVersionController::LATEST, json_encode([
            'version_code' => 12,
            'version_name' => '1.0.12',
            'apk' => 'apps/android/sukify-1.0.12.apk',
            'sha256' => str_repeat('a', 64),
            'size' => 12_345_678,
            'notes' => 'Загрузки и Android Auto',
        ]));
        Sanctum::actingAs(User::factory()->create(['username' => 'me']), ['mobile']);

        $this->getJson('/api/app/android')
            ->assertOk()
            ->assertJsonPath('data.version_code', 12)
            ->assertJsonPath('data.version_name', '1.0.12')
            ->assertJsonPath('data.size', 12_345_678);

        $this->assertStringEndsWith('apps/android/sukify-1.0.12.apk', $this->getJson('/api/app/android')->json('data.apk_url'));
    }

    public function test_asset_links_use_fingerprint_from_published_build(): void
    {
        Storage::fake('s3');
        $cert = implode(':', array_fill(0, 32, 'AB'));
        Storage::disk('s3')->put(AppVersionController::LATEST, json_encode([
            'version_code' => 12,
            'apk' => 'apps/android/sukify-1.0.12.apk',
            'cert_sha256' => $cert,
        ]));

        $this->get('/.well-known/assetlinks.json')
            ->assertOk()
            ->assertJsonPath('0.relation.0', 'delegate_permission/common.handle_all_urls')
            ->assertJsonPath('0.target.package_name', 'ru.nepalimsya.sukify')
            ->assertJsonPath('0.target.sha256_cert_fingerprints', [$cert]);

        // В ответе API версии отпечаток не нужен.
        Sanctum::actingAs(User::factory()->create(['username' => 'me']), ['mobile']);
        $this->getJson('/api/app/android')->assertOk()->assertJsonMissingPath('data.cert_sha256');
    }

    public function test_asset_links_empty_until_first_release(): void
    {
        Storage::fake('s3');

        $this->get('/.well-known/assetlinks.json')->assertOk()->assertExactJson([]);
    }

    public function test_nothing_published_yet(): void
    {
        Storage::fake('s3');
        Sanctum::actingAs(User::factory()->create(['username' => 'me']), ['mobile']);

        $this->getJson('/api/app/android')->assertOk()->assertJsonPath('data', null);
    }
}
