<?php

namespace Tests\Feature;

use App\Models\TrackTransition;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Http;
use Laravel\Sanctum\Sanctum;
use Tests\Concerns\BuildsCatalog;
use Tests\TestCase;

class TransitionsForContextTest extends TestCase
{
    use BuildsCatalog, RefreshDatabase;

    public function test_full_flag_returns_whole_transition(): void
    {
        Http::fake();
        $user = User::factory()->create(['username' => 'me']);
        $a = $this->makeTrack(null, 'A');
        $b = $this->makeTrack(null, 'B');
        $c = $this->makeTrack(null, 'C');

        TrackTransition::create([
            'from_track_id' => $a->id,
            'to_track_id' => $b->id,
            'fade_out_start_ms' => 170_000,
            'fade_out_end_ms' => 176_000,
            'fade_in_start_ms' => 1_000,
            'fade_in_full_volume_ms' => 7_000,
            'curve_type' => 'equal_power',
            'preset' => 'blend',
            'volume_shape' => 'crossfade',
            'eq_shape' => 'bass_swap_center',
            'filter_shape' => 'none',
            'bars' => 4,
            'created_by_user_id' => $user->id,
        ]);
        Sanctum::actingAs($user, ['mobile']);

        $pairs = "{$a->id}:{$b->id},{$b->id}:{$c->id}";

        $summary = $this->getJson("/api/transitions/for-context?pairs={$pairs}")->assertOk()->json('data');
        $this->assertSame('blend', $summary["{$a->id}:{$b->id}"]['preset']);
        $this->assertArrayNotHasKey('fade_out_start_ms', $summary["{$a->id}:{$b->id}"]);
        $this->assertNull($summary["{$b->id}:{$c->id}"]);

        $full = $this->getJson("/api/transitions/for-context?pairs={$pairs}&full=1")->assertOk()->json('data');
        $t = $full["{$a->id}:{$b->id}"];
        $this->assertSame(170_000, $t['fade_out_start_ms']);
        $this->assertSame(7_000, $t['fade_in_full_volume_ms']);
        $this->assertSame('bass_swap_center', $t['eq_shape']);
        $this->assertSame('equal_power', $t['curve_type']);
        $this->assertSame(1, $t['count']);
        $this->assertNull($full["{$b->id}:{$c->id}"]);
    }
}
