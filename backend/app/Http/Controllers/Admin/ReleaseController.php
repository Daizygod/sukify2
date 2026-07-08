<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Concerns\HandlesMediaUploads;
use App\Http\Controllers\Controller;
use App\Jobs\ProcessReleaseCover;
use App\Models\Artist;
use App\Models\Release;
use Illuminate\Http\Request;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Inertia\Inertia;

class ReleaseController extends Controller
{
    use HandlesMediaUploads;

    public function index(Request $request)
    {
        $releases = Release::query()
            ->with('artist')
            ->withCount('tracks')
            ->when($request->search, fn ($q, $s) => $q->where('title', 'ilike', "%{$s}%"))
            ->latest()
            ->paginate(20)
            ->withQueryString();

        return Inertia::render('Releases/Index', [
            'releases' => $releases,
            'filters' => $request->only('search'),
        ]);
    }

    public function create()
    {
        return Inertia::render('Releases/Form', [
            'release' => null,
            'artists' => Artist::orderBy('name')->get(['id', 'name']),
        ]);
    }

    public function edit(Release $release)
    {
        return Inertia::render('Releases/Form', [
            'release' => $release->load('artist'),
            'artists' => Artist::orderBy('name')->get(['id', 'name']),
        ]);
    }

    public function show(Release $release)
    {
        return Inertia::render('Releases/Show', [
            'release' => $release->load('artist'),
            'tracks' => $release->tracks()->orderBy('track_number')->get()->map(fn ($t) => [
                'id' => $t->id,
                'title' => $t->title,
                'track_number' => $t->track_number,
                'duration_ms' => $t->duration_ms,
                'loudness_lufs' => $t->loudness_lufs,
                'processing_status' => $t->processing_status->value,
                'processing_error' => $t->processing_error,
            ]),
        ]);
    }

    public function store(Request $request)
    {
        $data = $this->validateData($request);

        $release = Release::create([
            'artist_id' => $data['artist_id'],
            'title' => $data['title'],
            'slug' => $this->uniqueSlug($data['title'], $data['artist_id']),
            'type' => $data['type'],
            'release_date' => $data['release_date'] ?? null,
        ]);

        $this->handleCover($request, $release);

        return redirect()->route('admin.releases.show', $release)->with('success', 'Release created.');
    }

    public function update(Request $request, Release $release)
    {
        $data = $this->validateData($request);
        $release->update([
            'artist_id' => $data['artist_id'],
            'title' => $data['title'],
            'type' => $data['type'],
            'release_date' => $data['release_date'] ?? null,
        ]);
        $this->handleCover($request, $release);

        return redirect()->route('admin.releases.show', $release)->with('success', 'Release updated.');
    }

    public function destroy(Release $release)
    {
        $release->delete();

        return redirect()->route('admin.releases.index')->with('success', 'Release deleted.');
    }

    private function validateData(Request $request): array
    {
        return $request->validate([
            'artist_id' => ['required', 'exists:artists,id'],
            'title' => ['required', 'string', 'max:255'],
            'type' => ['required', Rule::in(['album', 'single', 'compilation'])],
            'release_date' => ['nullable', 'date'],
            'cover' => ['nullable', 'image', 'max:12288'],
        ]);
    }

    private function handleCover(Request $request, Release $release): void
    {
        if ($request->hasFile('cover')) {
            [$key, $ext] = $this->stashUpload($request->file('cover'));
            ProcessReleaseCover::dispatch($release->id, $key, $ext);
        }
    }

    private function uniqueSlug(string $title, int $artistId): string
    {
        $artist = Artist::find($artistId);
        $base = Str::slug(($artist?->name ?? '').' '.$title);
        $slug = $base;
        $i = 1;
        while (Release::where('slug', $slug)->exists()) {
            $slug = "{$base}-".$i++;
        }

        return $slug;
    }
}
