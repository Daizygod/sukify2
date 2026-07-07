<?php

return [

    /*
    | Cross-Origin Resource Sharing (CORS) — configured for the first-party
    | Vue SPA using Sanctum cookie auth (credentialed requests).
    */

    'paths' => ['api/*', 'sanctum/csrf-cookie', 'login', 'logout', 'register'],

    'allowed_methods' => ['*'],

    'allowed_origins' => [
        env('FRONTEND_URL', 'http://localhost:5173'),
        'http://localhost:5173',
    ],

    'allowed_origins_patterns' => [],

    'allowed_headers' => ['*'],

    'exposed_headers' => [],

    'max_age' => 0,

    // Required so the browser sends/receives the Sanctum session cookie.
    'supports_credentials' => true,

];
