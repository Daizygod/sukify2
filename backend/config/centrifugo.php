<?php

return [

    // Server-to-server HTTP API (inside the docker network).
    'api_endpoint' => env('CENTRIFUGO_API_ENDPOINT', 'http://centrifugo:8000/api'),
    'api_key' => env('CENTRIFUGO_API_KEY', ''),

    // Must match token_hmac_secret_key in docker/centrifugo/config.json.
    'token_secret' => env('CENTRIFUGO_TOKEN_HMAC_SECRET', ''),

    // Browser-facing websocket URL (exposed to the SPA).
    'ws_url' => env('CENTRIFUGO_WS_URL', 'ws://localhost:8000/connection/websocket'),

    // Token lifetimes (seconds).
    'connection_ttl' => 60 * 60 * 24,      // 24h
    'subscription_ttl' => 60 * 60 * 12,    // 12h

];
