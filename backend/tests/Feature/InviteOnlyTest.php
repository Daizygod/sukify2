<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Http;
use Inertia\Testing\AssertableInertia;
use Tests\TestCase;

/** Регистрации нет: аккаунты заводит админ и сразу видит пароль. */
class InviteOnlyTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
        $this->withoutVite();
    }

    public function test_public_registration_is_closed(): void
    {
        foreach (['/api/register', '/api/mobile/register'] as $url) {
            $this->postJson($url, [
                'name' => 'Чужой',
                'email' => 'stranger@sukify.test',
                'password' => 'secret-password',
                'password_confirmation' => 'secret-password',
                'device_name' => 'Galaxy',
            ])
                ->assertForbidden()
                ->assertJsonPath('message', 'Регистрация только по приглашению — попроси аккаунт у администратора.');
        }

        $this->assertDatabaseCount('users', 0);
        $this->assertGuest();
    }

    public function test_admin_creates_account_and_sees_password_once(): void
    {
        $this->actingAs($this->admin());

        $issued = $this->issue('/admin/users', ['email' => ' Friend@Mail.RU ', 'name' => 'Вася Пупкин'], 'post');

        $user = User::where('email', 'friend@mail.ru')->firstOrFail();
        $this->assertSame('Вася Пупкин', $user->name);
        $this->assertSame('vasia_pupkin', $user->username);
        $this->assertFalse($user->is_admin);
        $this->assertNotNull($user->playbackSettings);

        $this->assertSame(
            ['name' => 'Вася Пупкин', 'username' => 'vasia_pupkin', 'email' => 'friend@mail.ru'],
            array_diff_key($issued, ['password' => 0]),
        );
        // Без похожих знаков: пароль перепечатывают с экрана.
        $this->assertMatchesRegularExpression('/^([a-hjkmnp-z2-9]{4}-){2}[a-hjkmnp-z2-9]{4}$/', $issued['password']);

        // В базе — только хеш.
        $this->assertNotSame($issued['password'], $user->password);
        $this->assertTrue(Hash::check($issued['password'], $user->password));

        // Страница получает пароль ровно один раз, и не пропсом.
        $this->get('/admin/users')->assertInertia(fn (AssertableInertia $page) => $page
            ->component('Users/Index')
            ->hasFlash('issued.password', $issued['password'])
            ->missing('issued'));
        $this->get('/admin/users')->assertInertia(fn (AssertableInertia $page) => $page->missingFlash('issued'));
    }

    public function test_issued_password_opens_site_and_app(): void
    {
        $this->actingAs($this->admin());
        $issued = $this->issue('/admin/users', ['email' => 'friend@mail.ru', 'name' => 'Friend'], 'post');

        $this->postJson('/api/mobile/login', [
            'email' => 'friend@mail.ru',
            'password' => $issued['password'],
            'device_name' => 'Pixel 8',
        ])->assertOk()->assertJsonPath('user.username', 'friend');

        // Сайт входит по cookie-сессии — Sanctum заводит её только «своим» доменам.
        $this->app['auth']->forgetGuards();
        config(['sanctum.stateful' => ['sukify.test']]);
        $this->withHeader('referer', 'http://sukify.test/')
            ->postJson('/api/login', ['email' => 'friend@mail.ru', 'password' => $issued['password']])
            ->assertOk()
            ->assertJsonPath('data.username', 'friend');
    }

    public function test_same_nick_gets_its_own_handle(): void
    {
        $this->actingAs($this->admin());

        $this->issue('/admin/users', ['email' => 'one@mail.ru', 'name' => 'Max'], 'post');
        $second = $this->issue('/admin/users', ['email' => 'two@mail.ru', 'name' => 'Max'], 'post');

        $this->assertSame('max_2', $second['username']);
    }

    public function test_bad_input_creates_nothing(): void
    {
        $this->actingAs($this->admin());
        User::factory()->create(['email' => 'taken@mail.ru', 'username' => 'taken']);

        // Та же почта другими буквами — тот же ящик.
        $this->post('/admin/users', ['email' => 'TAKEN@mail.ru', 'name' => 'X'])->assertSessionHasErrors('email');
        $this->post('/admin/users', ['email' => 'not-an-email', 'name' => ' '])->assertSessionHasErrors(['email', 'name']);

        $this->assertDatabaseCount('users', 2);
        $this->assertNull(session('inertia.flash_data'));
    }

    public function test_only_admins_issue_accounts(): void
    {
        $user = User::factory()->create(['username' => 'me']);
        $victim = User::factory()->create(['username' => 'victim']);

        $this->post('/admin/users', ['email' => 'x@mail.ru', 'name' => 'X'])->assertRedirect('/admin/login');
        $this->put("/admin/users/{$victim->id}/password")->assertRedirect('/admin/login');

        $this->actingAs($user);
        $this->post('/admin/users', ['email' => 'x@mail.ru', 'name' => 'X'])->assertForbidden();
        $this->put("/admin/users/{$victim->id}/password")->assertForbidden();

        $this->assertDatabaseMissing('users', ['email' => 'x@mail.ru']);
        $this->assertTrue(Hash::check('password', $victim->fresh()->password));
    }

    public function test_new_password_replaces_lost_one(): void
    {
        $this->actingAs($this->admin());
        $user = User::factory()->create(['email' => 'friend@mail.ru', 'username' => 'friend']);

        $issued = $this->issue("/admin/users/{$user->id}/password", [], 'put');

        $this->assertSame('friend@mail.ru', $issued['email']);
        $this->assertTrue(Hash::check($issued['password'], $user->fresh()->password));
        $this->assertFalse(Hash::check('password', $user->fresh()->password));
    }

    public function test_admin_passwords_are_not_reset_from_the_list(): void
    {
        $admin = $this->admin();
        $other = $this->admin('second');
        $this->actingAs($admin);

        foreach ([$admin, $other] as $target) {
            $this->put("/admin/users/{$target->id}/password")->assertSessionHas('error');
            $this->assertTrue(Hash::check('password', $target->fresh()->password));
        }
        $this->assertNull(session('inertia.flash_data'));
    }

    private function admin(string $username = 'boss'): User
    {
        $admin = User::factory()->create(['username' => $username]);
        $admin->forceFill(['is_admin' => true])->save();

        return $admin;
    }

    /** Отправляет форму и отдаёт то, что админ увидит на странице. */
    private function issue(string $url, array $data, string $method): array
    {
        $this->from('/admin/users')->{$method}($url, $data)
            ->assertRedirect('/admin/users')
            ->assertSessionHasNoErrors();

        return session('inertia.flash_data')['issued'];
    }
}
