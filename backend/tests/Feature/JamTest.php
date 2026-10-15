<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class JamTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        Http::fake();
    }

    public function test_join_codes_have_no_lookalike_characters(): void
    {
        Sanctum::actingAs(User::factory()->create(), ['mobile']);

        // Код переписывают с чужого экрана — 0/O и 1/I/L там путаются.
        for ($i = 0; $i < 25; $i++) {
            $code = $this->postJson('/api/jam/sessions')->assertCreated()->json('data.join_code');
            $this->assertMatchesRegularExpression('/^[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}$/', $code);
        }
    }

    public function test_guest_joins_by_code_typed_in_lowercase(): void
    {
        $host = User::factory()->create();
        Sanctum::actingAs($host, ['mobile']);
        $session = $this->postJson('/api/jam/sessions')->json('data');

        $guest = User::factory()->create();
        Sanctum::actingAs($guest, ['mobile']);
        $this->postJson('/api/jam/sessions/join', ['join_code' => strtolower($session['join_code'])])
            ->assertOk()
            ->assertJsonPath('data.id', $session['id'])
            ->assertJsonPath('data.is_host', false)
            ->assertJsonCount(2, 'data.members');
    }
}
