<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class TrackResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'track_number' => $this->track_number,
            'duration_ms' => $this->duration_ms,
            'loudness_lufs' => $this->loudness_lufs,
            'processing_status' => $this->processing_status->value,
            'stream_url' => $this->streamUrl(),
            'cover' => $this->coverUrls(),
            'plays_count' => $this->plays_count,
            'likes_count' => $this->likes_count,
            'artists' => ArtistResource::collection($this->whenLoaded('artists')),
            'release' => new ReleaseResource($this->whenLoaded('release')),
            // Position within a playlist, when loaded through the pivot.
            'playlist_position' => $this->whenPivotLoaded('playlist_track', fn () => $this->pivot->position),
            'playlist_item_id' => $this->whenPivotLoaded('playlist_track', fn () => $this->pivot->id),
            'added_at' => $this->whenPivotLoaded('playlist_track', fn () => $this->pivot->added_at),
            'liked_at' => $this->whenPivotLoaded('liked_tracks', fn () => $this->pivot->created_at),
            'is_liked' => $this->when(isset($this->is_liked), fn () => (bool) $this->is_liked),
        ];
    }
}
