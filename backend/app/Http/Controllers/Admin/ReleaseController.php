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
            'release' => array_merge($release->load('artist')->toArray(), [
                'cover_url' => $release->coverUrl(300),
                'artist_ids' => $release->artists()->pluck('artists.id')->all() ?: [$release->artist_id],
            ]),
            'artists' => Artist::orderBy('name')->get(['id', 'name']),
        ]);
    }

    public function show(Release $release)
    {
        return Inertia::render('Releases/Show', [
            'release' => array_merge($release->load('artist')->toArray(), [
                'cover_url' => $release->coverUrl(300),
                'cover_status' => $release->cover_status->value,
            ]),
            'tracks' => $release->tracks()->with('artists:id,name')->orderBy('track_number')->get()->map(fn ($t) => [
                'id' => $t->id,
                'title' => $t->title,
                'track_number' => $t->track_number,
                'duration_ms' => $t->duration_ms,
                'loudness_lufs' => $t->loudness_lufs,
                'processing_status' => $t->processing_status->value,
                'processing_error' => $t->processing_error,
                'unofficial' => (bool) $t->unofficial,
                'artist_ids' => $t->artists->pluck('id')->all(),
            ]),
            'allArtists' => Artist::orderBy('name')->get(['id', 'name']),
        ]);
    }

    public function store(Request $request)
    {
        $data = $this->validateData($request);
        $mainArtist = (int) $data['artist_ids'][0];

        $release = Release::create([
            'artist_id' => $mainArtist,
            'title' => $data['title'],
            'slug' => $this->uniqueSlug($data['title'], $mainArtist),
            'type' => $data['type'],
            'release_date' => $data['release_date'] ?? null,
        ]);
        $this->syncArtists($release, $data['artist_ids']);
        $this->handleCover($request, $release);

        return redirect()->route('admin.releases.show', $release)->with('success', 'Release created.');
    }

    public function update(Request $request, Release $release)
    {
        $data = $this->validateData($request);
        $release->update([
            'artist_id' => (int) $data['artist_ids'][0],
            'title' => $data['title'],
            'type' => $data['type'],
            'release_date' => $data['release_date'] ?? null,
        ]);
        $this->syncArtists($release, $data['artist_ids']);
        $this->handleCover($request, $release);

        return redirect()->route('admin.releases.show', $release)->with('success', 'Release updated.');
    }

    /** Соавторы релиза в заданном порядке (первый — основной). */
    private function syncArtists(Release $release, array $artistIds): void
    {
        $release->artists()->sync(
            collect($artistIds)->values()->mapWithKeys(
                fn ($id, $i) => [(int) $id => ['position' => $i]]
            )->all()
        );
    }

    public function destroy(Release $release)
    {
        $release->delete();

        return redirect()->route('admin.releases.index')->with('success', 'Release deleted.');
    }

    private function validateData(Request $request): array
    {
        return $request->validate([
            'artist_ids' => ['required', 'array', 'min:1'],
            'artist_ids.*' => ['exists:artists,id'],
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
