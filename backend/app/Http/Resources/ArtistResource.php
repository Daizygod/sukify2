<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\Facades\Storage;

class ArtistResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'slug' => $this->slug,
            'bio' => $this->when($request->routeIs('artists.show'), $this->bio),
            'avatar_url' => $this->avatar_path ? Storage::disk('s3')->url($this->avatar_path) : null,
            'banner_url' => $this->banner_path ? Storage::disk('s3')->url($this->banner_path) : null,
            'colors' => [
                'background' => $this->dominant_color_hex,
                'text' => $this->text_color_hex,
            ],
            'monthly_listeners' => $this->monthly_listeners,
            'role' => $this->whenPivotLoaded('track_artist', fn () => $this->pivot->role),
            'is_followed' => $this->when(isset($this->is_followed), fn () => (bool) $this->is_followed),
            'releases' => ReleaseResource::collection($this->whenLoaded('releases')),
        ];
    }
}
