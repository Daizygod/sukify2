<?php

namespace App\Http\Controllers\Admin;

use App\Enums\ProcessingStatus;
use App\Http\Controllers\Concerns\HandlesMediaUploads;
use App\Http\Controllers\Controller;
use App\Jobs\ProcessTrackAudio;
use App\Models\Release;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class TrackController extends Controller
{
    use HandlesMediaUploads;

    public function store(Request $request, Release $release)
    {
        $data = $request->validate([
            'title' => ['required', 'string', 'max:255'],
            'audio' => ['required', 'file', 'max:524288', 'mimes:mp3,flac,ape,wav,m4a,aac,mpga'],
        ]);

        $track = $release->tracks()->create([
            'title' => $data['title'],
            'track_number' => (int) $release->tracks()->max('track_number') + 1,
            'processing_status' => ProcessingStatus::Pending,
        ]);

        // Main artist inherits from the release by default.
        $track->artists()->syncWithoutDetaching([
            $release->artist_id => ['role' => 'main', 'position' => 0],
        ]);

        [$key, $ext] = $this->stashUpload($request->file('audio'));
        ProcessTrackAudio::dispatch($track->id, $key, $ext);

        return back()->with('success', 'Track uploaded — processing started.');
    }

    public function update(Request $request, Track $track)
    {
        $data = $request->validate([
            'title' => ['required', 'string', 'max:255'],
            'unofficial' => ['sometimes', 'boolean'],
            'artist_ids' => ['sometimes', 'array', 'min:1'],
            'artist_ids.*' => ['exists:artists,id'],
        ]);
        $track->fill(['title' => $data['title']]);
        if (array_key_exists('unofficial', $data)) {
            $track->unofficial = (bool) $data['unofficial'];
        }
        $track->save();

        // Исполнители трека (feat): первый — main, остальные — featured.
        if (! empty($data['artist_ids'])) {
            $track->artists()->sync(
                collect($data['artist_ids'])->values()->mapWithKeys(fn ($id, $i) => [
                    (int) $id => ['role' => $i === 0 ? 'main' : 'featured', 'position' => $i],
                ])->all()
            );
        }

        return back()->with('success', 'Track updated.');
    }

    public function destroy(Track $track)
    {
        $track->delete();

        return back()->with('success', 'Track deleted.');
    }

    public function reorder(Request $request, Release $release)
    {
        $data = $request->validate([
            'track_ids' => ['required', 'array'],
            'track_ids.*' => ['integer'],
        ]);

        DB::transaction(function () use ($data, $release) {
            foreach ($data['track_ids'] as $i => $id) {
                Release::query(); // keep scope
                $release->tracks()->whereKey($id)->update(['track_number' => $i + 1]);
            }
        });

        return back();
    }

    /** Lightweight JSON status endpoint the admin polls after upload. */
    public function status(Track $track)
    {
        return response()->json([
            'id' => $track->id,
            'processing_status' => $track->processing_status->value,
            'duration_ms' => $track->duration_ms,
            'loudness_lufs' => $track->loudness_lufs,
            'processing_error' => $track->processing_error,
        ]);
    }
}
