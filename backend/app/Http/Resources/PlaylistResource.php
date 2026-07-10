<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\Facades\Storage;

class PlaylistResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'title' => $this->title,
            'description' => $this->description,
            'cover_url' => $this->cover_path ? Storage::disk('s3')->url($this->cover_path) : null,
            'is_public' => $this->is_public,
            'is_owner' => $request->user()?->id === $this->user_id,
            'is_collaborator' => $this->when(
                $request->user() !== null,
                fn () => $this->isCollaborator($request->user())
            ),
            'is_collaborative' => $this->invite_token !== null,
            'owner' => new UserResource($this->whenLoaded('owner')),
            'collaborators' => UserResource::collection($this->whenLoaded('collaborators')),
            'tracks_count' => $this->when(isset($this->tracks_count), fn () => $this->tracks_count),
            'tracks' => TrackResource::collection($this->whenLoaded('tracks')),
            'created_at' => $this->created_at?->toIso8601String(),
            'updated_at' => $this->updated_at?->toIso8601String(),
        ];
    }
}
