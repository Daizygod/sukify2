<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class ListeningSessionResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'join_code' => $this->join_code,
            'is_active' => $this->is_active,
            'host_user_id' => $this->host_user_id,
            'is_host' => $request->user()?->id === $this->host_user_id,
            'channel' => "session:{$this->id}",
            'host' => new UserResource($this->whenLoaded('host')),
            'members' => UserResource::collection($this->whenLoaded('members')),
            'members_count' => $this->when(isset($this->members_count), fn () => $this->members_count),
            'created_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
