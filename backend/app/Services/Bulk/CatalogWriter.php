<?php

namespace App\Services\Bulk;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Models\Artist;
use App\Models\Release;
use App\Models\Track;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

/**
 * Заводит в каталоге то, чего в нём нет: артиста, релиз и трек по тегам файла.
 *
 * Ищет по тем же нормализованным именам, что и матчер ({@see Text::norm}),
 * поэтому «AC/DC» и «AC DC» — один артист, а альбом второй раз не появится.
 */
class CatalogWriter
{
    /** @var array<string,int> norm(имя) => artists.id */
    private array $artists = [];

    /** @var array<string,true> */
    private array $artistSlugs = [];

    /** @var array<string,int> "artistId|norm(название)" => releases.id */
    private array $releases = [];

    /** @var array<string,true> */
    private array $releaseSlugs = [];

    /** @var array<int,true> релизы, которые завёл этот писатель */
    private array $created = [];

    public function __construct()
    {
        foreach (Artist::query()->select('id', 'name', 'slug')->orderBy('id')->cursor() as $a) {
            $this->artists[Text::norm($a->name)] ??= $a->id;
            $this->artistSlugs[$a->slug] = true;
        }
        foreach (Release::query()->select('id', 'artist_id', 'title', 'slug')->orderBy('id')->cursor() as $r) {
            $this->releases[$r->artist_id.'|'.Text::norm($r->title)] ??= $r->id;
            $this->releaseSlugs[$r->slug] = true;
        }
    }

    public function artistId(string $name): int
    {
        $name = mb_substr(trim($name), 0, 250);
        $key = Text::norm($name);
        if (isset($this->artists[$key])) {
            return $this->artists[$key];
        }

        $artist = Artist::create([
            'name' => $name,
            'slug' => $this->slug($name, $this->artistSlugs, 'artist'),
            'imported' => true,
        ]);

        return $this->artists[$key] = $artist->id;
    }

    /** @return array{0:int,1:bool} [id релиза, заведён ли только что] */
    public function release(int $artistId, string $artistName, string $title, ReleaseType $type, ?string $date): array
    {
        $title = mb_substr(trim($title), 0, 250);
        $key = $artistId.'|'.Text::norm($title);
        if (isset($this->releases[$key])) {
            return [$this->releases[$key], isset($this->created[$this->releases[$key]])];
        }

        $release = Release::create([
            'artist_id' => $artistId,
            'title' => $title,
            'slug' => $this->slug($artistName.' '.$title, $this->releaseSlugs, 'release'),
            'type' => $type,
            'release_date' => $date,
            'cover_status' => ProcessingStatus::Pending,
            'imported' => true,
        ]);
        DB::table('release_artists')->insertOrIgnore([
            'release_id' => $release->id, 'artist_id' => $artistId, 'position' => 0,
        ]);
        $this->created[$release->id] = true;

        return [$this->releases[$key] = $release->id, true];
    }

    /**
     * Трек в релизе. Если трек с таким названием там уже есть — вернём его:
     * второй такой же не нужен.
     *
     * @param  list<int>  $artistIds  первый — основной
     */
    public function track(int $releaseId, string $title, ?int $number, array $artistIds): int
    {
        $title = mb_substr(trim($title), 0, 255);
        $norm = Text::norm($title);
        foreach (Track::where('release_id', $releaseId)->get(['id', 'title']) as $existing) {
            if (Text::norm($existing->title) === $norm) {
                return $existing->id;
            }
        }

        $track = Track::create([
            'release_id' => $releaseId,
            'title' => $title,
            'track_number' => $number ?: ((int) Track::where('release_id', $releaseId)->max('track_number') + 1),
            'processing_status' => ProcessingStatus::Pending,
        ]);

        $links = [];
        foreach (array_values(array_unique($artistIds)) as $i => $artistId) {
            $links[$artistId] = ['role' => $i === 0 ? 'main' : 'featured', 'position' => $i];
        }
        $track->artists()->sync($links);

        return $track->id;
    }

    /** @param  array<string,true>  $used */
    private function slug(string $text, array &$used, string $fallback): string
    {
        $base = Str::limit(Str::slug($text), 180, '');
        if ($base === '') {
            $base = $fallback.'-'.substr(md5($text), 0, 8);
        }

        $slug = $base;
        $i = 2;
        // Кэш слагов мог устареть: соседняя загрузка или админ завели такой же.
        while (isset($used[$slug]) || $this->slugTaken($slug, $fallback)) {
            $slug = $base.'-'.$i++;
        }
        $used[$slug] = true;

        return $slug;
    }

    private function slugTaken(string $slug, string $kind): bool
    {
        return $kind === 'artist'
            ? Artist::where('slug', $slug)->exists()
            : Release::where('slug', $slug)->exists();
    }
}
