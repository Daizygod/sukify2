<?php

namespace App\Http\Resources;

use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

class TransitionResource extends JsonResource
{
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'from_track_id' => $this->from_track_id,
            'to_track_id' => $this->to_track_id,
            'fade_out_start_ms' => $this->fade_out_start_ms,
            'fade_out_end_ms' => $this->fade_out_end_ms,
            'fade_in_start_ms' => $this->fade_in_start_ms,
            'fade_in_full_volume_ms' => $this->fade_in_full_volume_ms,
            'curve_type' => $this->curve_type->value,
            'likes_count' => $this->likes_count,
            'created_by_user_id' => $this->created_by_user_id,
            'is_liked' => $this->when(isset($this->is_liked), fn () => (bool) $this->is_liked),
        ];
    }
}
