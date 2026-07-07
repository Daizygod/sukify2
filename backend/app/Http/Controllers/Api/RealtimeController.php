<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\ListeningSession;
use App\Services\Realtime\CentrifugoService;
use Illuminate\Http\Request;

class RealtimeController extends Controller
{
    public function __construct(private CentrifugoService $centrifugo)
    {
    }

    /** Connection token + everything the SPA needs to open a Centrifugo socket. */
    public function connectionToken(Request $request)
    {
        $user = $request->user();

        return response()->json([
            'token' => $this->centrifugo->connectionToken($user),
            'ws_url' => config('centrifugo.ws_url'),
            'playback_channel' => $this->centrifugo->playbackChannel($user->id),
        ]);
    }

    /**
     * Subscription token for channels that need explicit authorization.
     * Device-sync channels are user-limited (no token needed); only Jam
     * session channels are gated here, after a membership check.
     */
    public function subscriptionToken(Request $request)
    {
        $data = $request->validate([
            'channel' => ['required', 'string'],
        ]);

        $channel = $data['channel'];
        $user = $request->user();

        if (! str_starts_with($channel, 'session:')) {
            abort(403, 'Channel not authorizable via token.');
        }

        $sessionId = (int) str_replace('session:', '', $channel);
        $session = ListeningSession::where('id', $sessionId)->where('is_active', true)->first();

        if (! $session || ! $session->members()->whereKey($user->id)->exists()) {
            abort(403, 'Not a member of this session.');
        }

        return response()->json([
            'token' => $this->centrifugo->subscriptionToken($user, $channel),
        ]);
    }
}
