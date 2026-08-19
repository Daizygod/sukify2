<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\Facades\Storage;

class UserResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        $isSelf = $request->user()?->id === $this->id;

        return [
            'id' => $this->id,
            'name' => $this->name,
            'username' => $this->username,
            // ?v= — путь аватарки постоянный, без версии браузер держит старую.
            'avatar_url' => $this->avatar_path
                ? Storage::disk('s3')->url($this->avatar_path).'?v='.$this->updated_at?->timestamp
                : null,
            // Only expose these to the account owner.
            'email' => $this->when($isSelf, $this->email),
            'is_admin' => $this->when($isSelf, fn () => (bool) $this->is_admin),
        ];
    }
}
