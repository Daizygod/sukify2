<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class ReleaseResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'slug' => $this->slug,
            'type' => $this->type->value,
            'release_date' => $this->release_date?->toDateString(),
            'year' => $this->release_date?->year,
            'cover' => $this->coverUrls(),
            'cover_original' => $this->originalCoverUrl(),
            'cover_status' => $this->cover_status->value,
            'colors' => [
                'background' => $this->dominant_color_hex,
                'text' => $this->text_color_hex,
            ],
            'artist' => new ArtistResource($this->whenLoaded('artist')),
            'tracks' => TrackResource::collection($this->whenLoaded('tracks')),
            'tracks_count' => $this->when(isset($this->tracks_count), fn () => $this->tracks_count),
            'is_liked' => $this->when(isset($this->is_liked), fn () => (bool) $this->is_liked),
        ];
    }
}
