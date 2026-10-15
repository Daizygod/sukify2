<?php

namespace Tests\Feature;

use App\Models\DesktopDevice;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\PersonalAccessToken;
use Tests\TestCase;

class MobileAuthTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
    }

    public function test_login_issues_token_and_registers_android_device(): void
    {
        $user = User::factory()->create(['email' => 'phone@sukify.test', 'username' => 'phone']);

        $response = $this->postJson('/api/mobile/login', [
            'email' => 'phone@sukify.test',
            'password' => 'password',
            'device_name' => 'Pixel 8',
        ]);

        $response->assertOk()
            ->assertJsonStructure(['token', 'device_id', 'user' => ['id', 'name', 'username', 'email', 'is_admin']])
            ->assertJsonPath('user.email', 'phone@sukify.test');

        $device = DesktopDevice::find($response->json('device_id'));
        $this->assertSame($user->id, $device->user_id);
        $this->assertSame('Android', $device->platform);
        $this->assertSame('Pixel 8', $device->name);
        $this->assertNotNull($device->approved_at);
        $this->assertNotNull($device->token_id);

        // Токен сразу открывает закрытое API.
        $this->withToken($response->json('token'))
            ->getJson('/api/me')
            ->assertOk()
            ->assertJsonPath('data.id', $user->id);
    }

    public function test_wrong_password_and_banned_user_are_rejected(): void
    {
        User::factory()->create(['email' => 'a@sukify.test', 'username' => 'a']);
        $banned = User::factory()->create(['email' => 'b@sukify.test', 'username' => 'b']);
        $banned->forceFill(['is_banned' => true])->save();

        $this->postJson('/api/mobile/login', ['email' => 'a@sukify.test', 'password' => 'nope'])
            ->assertStatus(422)
            ->assertJsonValidationErrors('email');

        $this->postJson('/api/mobile/login', ['email' => 'b@sukify.test', 'password' => 'password'])
            ->assertStatus(422);

        $this->assertSame(0, PersonalAccessToken::count());
    }

    public function test_register_returns_token(): void
    {
        $this->postJson('/api/mobile/register', [
            'name' => 'Новый слушатель',
            'email' => 'new@sukify.test',
            'password' => 'secret-password',
            'password_confirmation' => 'secret-password',
            'device_name' => 'Galaxy',
        ])
            ->assertCreated()
            ->assertJsonStructure(['token', 'device_id', 'user' => ['id', 'username']]);

        $this->assertDatabaseHas('users', ['email' => 'new@sukify.test']);
    }

    public function test_logout_revokes_only_this_phone(): void
    {
        $user = User::factory()->create(['email' => 'c@sukify.test', 'username' => 'c']);

        $first = $this->postJson('/api/mobile/login', ['email' => 'c@sukify.test', 'password' => 'password'])->json();
        $second = $this->postJson('/api/mobile/login', ['email' => 'c@sukify.test', 'password' => 'password'])->json();

        $this->withToken($first['token'])->postJson('/api/mobile/logout')->assertOk();

        $this->assertDatabaseMissing('desktop_devices', ['id' => $first['device_id']]);
        $this->assertDatabaseHas('desktop_devices', ['id' => $second['device_id']]);
        $this->assertSame(1, $user->tokens()->count());

        // Гард кэширует пользователя между запросами одного теста — сбрасываем.
        $this->app['auth']->forgetGuards();
        $this->withToken($first['token'])->getJson('/api/me')->assertUnauthorized();
    }

    public function test_ban_revokes_tokens(): void
    {
        $admin = User::factory()->create(['username' => 'boss']);
        $admin->forceFill(['is_admin' => true])->save();
        $user = User::factory()->create(['email' => 'd@sukify.test', 'username' => 'd']);

        $this->postJson('/api/mobile/login', ['email' => 'd@sukify.test', 'password' => 'password'])->assertOk();
        $this->assertSame(1, $user->tokens()->count());

        $this->actingAs($admin)->put("/admin/users/{$user->id}/ban")->assertRedirect();

        $this->assertSame(0, $user->tokens()->count());
        $this->assertSame(0, $user->desktopDevices()->count());
    }
}
