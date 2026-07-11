<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['user_id', 'from_track_id', 'to_track_id', 'transition_id'])]
class TransitionPreference extends Model
{
}
