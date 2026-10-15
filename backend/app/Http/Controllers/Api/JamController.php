<?php

namespace App\Http\Controllers\Api;

use App\Enums\SessionRole;
use App\Http\Controllers\Controller;
use App\Http\Resources\ListeningSessionResource;
use App\Models\ListeningSession;
use App\Services\Realtime\CentrifugoService;
use Illuminate\Http\Request;

class JamController extends Controller
{
    public function __construct(private CentrifugoService $centrifugo)
    {
    }

    /** Host starts a Jam. */
    public function store(Request $request)
    {
        $session = ListeningSession::create([
            'host_user_id' => $request->user()->id,
            'join_code' => $this->uniqueJoinCode(),
            'is_active' => true,
        ]);

        $session->members()->attach($request->user()->id, [
            'role' => SessionRole::Host->value,
            'joined_at' => now(),
        ]);

        return (new ListeningSessionResource($session->load(['host', 'members'])))
            ->response()->setStatusCode(201);
    }

    /** Guest joins by code. */
    public function join(Request $request)
    {
        $data = $request->validate([
            'join_code' => ['required', 'string'],
        ]);

        $session = ListeningSession::where('join_code', strtoupper($data['join_code']))
            ->where('is_active', true)
            ->firstOrFail();

        $session->members()->syncWithoutDetaching([
            $request->user()->id => [
                'role' => SessionRole::Guest->value,
                'joined_at' => now(),
            ],
        ]);

        // Tell the room someone joined so the host can push current state.
        $this->centrifugo->publish($this->centrifugo->sessionChannel($session->id), [
            'type' => 'member.joined',
            'user' => [
                'id' => $request->user()->id,
                'name' => $request->user()->name,
            ],
        ]);

        return new ListeningSessionResource($session->load(['host', 'members']));
    }

    public function show(Request $request, ListeningSession $session)
    {
        abort_unless(
            $session->members()->whereKey($request->user()->id)->exists(),
            403,
            'Not a member of this session.'
        );

        return new ListeningSessionResource($session->load(['host', 'members']));
    }

    public function leave(Request $request, ListeningSession $session)
    {
        $userId = $request->user()->id;

        // If the host leaves, the Jam ends for everyone.
        if ($session->host_user_id === $userId) {
            return $this->end($request, $session);
        }

        $session->members()->detach($userId);

        $this->centrifugo->publish($this->centrifugo->sessionChannel($session->id), [
            'type' => 'member.left',
            'user' => ['id' => $userId],
        ]);

        return response()->json(['message' => 'Left the session.']);
    }

    public function end(Request $request, ListeningSession $session)
    {
        abort_unless($session->host_user_id === $request->user()->id, 403, 'Only the host can end the Jam.');

        $session->update(['is_active' => false]);

        $this->centrifugo->publish($this->centrifugo->sessionChannel($session->id), [
            'type' => 'session.ended',
        ]);

        return response()->json(['message' => 'Session ended.']);
    }

    /**
     * Код диктуют голосом и переписывают с экрана другого телефона, поэтому в
     * нём нет похожих символов: 0/O, 1/I/L.
     */
    private function uniqueJoinCode(): string
    {
        $alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
        do {
            $code = '';
            for ($i = 0; $i < 6; $i++) {
                $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            }
        } while (ListeningSession::where('join_code', $code)->exists());

        return $code;
    }
}
