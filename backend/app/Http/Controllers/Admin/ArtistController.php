<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Concerns\HandlesMediaUploads;
use App\Http\Controllers\Controller;
use App\Models\Artist;
use App\Services\Images\CoverProcessor;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;
use Inertia\Inertia;

class ArtistController extends Controller
{
    use HandlesMediaUploads;

    public function index(Request $request)
    {
        $artists = Artist::query()
            ->when($request->search, fn ($q, $s) => $q->where('name', 'ilike', "%{$s}%"))
            ->withCount('releases')
            ->orderBy('name')
            ->paginate(20)
            ->withQueryString();

        return Inertia::render('Artists/Index', [
            'artists' => $artists,
            'filters' => $request->only('search'),
        ]);
    }

    public function create()
    {
        return Inertia::render('Artists/Form', ['artist' => null]);
    }

    public function edit(Artist $artist)
    {
        return Inertia::render('Artists/Form', ['artist' => $artist]);
    }

    public function store(Request $request)
    {
        $data = $this->validateData($request);
        $artist = new Artist(['name' => $data['name'], 'bio' => $data['bio'] ?? null]);
        $artist->slug = $this->uniqueSlug($data['name']);
        $artist->save();

        $this->handleImages($request, $artist);

        return redirect()->route('admin.artists.index')->with('success', 'Artist created.');
    }

    public function update(Request $request, Artist $artist)
    {
        $data = $this->validateData($request);
        $artist->update(['name' => $data['name'], 'bio' => $data['bio'] ?? null]);
        $this->handleImages($request, $artist);

        return redirect()->route('admin.artists.index')->with('success', 'Artist updated.');
    }

    public function destroy(Artist $artist)
    {
        $artist->delete();

        return redirect()->route('admin.artists.index')->with('success', 'Artist deleted.');
    }

    private function validateData(Request $request): array
    {
        return $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'bio' => ['nullable', 'string', 'max:5000'],
            'avatar' => ['nullable', 'image', 'max:8192'],
            'banner' => ['nullable', 'image', 'max:12288'],
        ]);
    }

    private function handleImages(Request $request, Artist $artist): void
    {
        $disk = Storage::disk('s3');

        if ($request->hasFile('avatar')) {
            $ext = strtolower($request->file('avatar')->getClientOriginalExtension() ?: 'jpg');
            $key = "artists/{$artist->id}/avatar.{$ext}";
            $disk->writeStream($key, fopen($request->file('avatar')->getRealPath(), 'r'));
            $artist->avatar_path = $key;
        }

        if ($request->hasFile('banner')) {
            $file = $request->file('banner');
            $ext = strtolower($file->getClientOriginalExtension() ?: 'jpg');
            $key = "artists/{$artist->id}/banner.{$ext}";
            $disk->writeStream($key, fopen($file->getRealPath(), 'r'));
            $artist->banner_path = $key;

            // Pull the gradient colours straight from the banner.
            [$dominant, $text] = app(CoverProcessor::class)->extractColors($file->getRealPath());
            $artist->dominant_color_hex = $dominant;
            $artist->text_color_hex = $text;
        }

        $artist->save();
    }

    private function uniqueSlug(string $name): string
    {
        $base = Str::slug($name);
        $slug = $base;
        $i = 1;
        while (Artist::where('slug', $slug)->exists()) {
            $slug = "{$base}-".$i++;
        }

        return $slug;
    }
}
