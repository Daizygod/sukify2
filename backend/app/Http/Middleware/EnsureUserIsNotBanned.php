<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class EnsureUserIsNotBanned
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();

        if ($user && $user->is_banned) {
            // Kill the session so a banned user can't keep acting.
            if ($request->hasSession()) {
                auth()->guard('web')->logout();
            }

            abort(403, 'Your account has been suspended.');
        }

        return $next($request);
    }
}
