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
            // ?v= — обложка всегда лежит по одному пути, без версии браузер
            // продолжал бы показывать прежнюю картинку из кэша.
            'cover_url' => $this->cover_path
                ? Storage::disk('s3')->url($this->cover_path).'?v='.$this->updated_at?->timestamp
                : null,
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
            // Режим микса: колонки BPM/тональности и чипы переходов между строками.
            'mix_enabled' => (bool) $this->mix_enabled,
            'created_at' => $this->created_at?->toIso8601String(),
            'updated_at' => $this->updated_at?->toIso8601String(),
        ];
    }
}
