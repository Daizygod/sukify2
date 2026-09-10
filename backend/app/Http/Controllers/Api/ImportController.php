<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Jobs\ImportSpotifyArchive;
use App\Models\SpotifyImport;
use App\Models\Track;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;

/**
 * Импорт из Spotify.
 *
 * Основной путь — целиком архив «Download your data»: библиотека, плейлисты и
 * годы истории прослушиваний ({@see ImportSpotifyArchive}). Старый путь —
 * список треков, разобранный на клиенте из Exportify-CSV, — оставлен для тех,
 * кому нужны только лайки и кто не хочет ждать выгрузку от Spotify.
 */
class ImportController extends Controller
{
    /** Максимальный размер архива, МБ. Extended History весит ~25. */
    private const MAX_MB = 400;

    /** Загрузка архива: кладём в S3 и отдаём разбор в очередь. */
    public function upload(Request $request)
    {
        $request->validate([
            'archive' => ['required', 'file', 'max:'.(self::MAX_MB * 1024)],
        ], [], ['archive' => 'архив']);

        $file = $request->file('archive');
        $extension = Str::lower($file->getClientOriginalExtension());
        if (! in_array($extension, ['zip', 'json'], true)) {
            return response()->json([
                'message' => 'Нужен ZIP из «Download your data» или отдельный JSON из него.',
            ], 422);
        }

        $path = $file->store('tmp-uploads/spotify', 's3');

        $import = SpotifyImport::create([
            'user_id' => $request->user()->id,
            'original_name' => mb_substr($file->getClientOriginalName(), 0, 250),
            'size_bytes' => $file->getSize() ?: 0,
            'archive_path' => $path,
            'status' => 'pending',
            'stage' => 'В очереди',
        ]);

        ImportSpotifyArchive::dispatch($import->id);

        return response()->json($this->payload($import), 201);
    }

    /** Последний импорт пользователя — им живёт страница /import. */
    public function latest(Request $request)
    {
        $import = SpotifyImport::where('user_id', $request->user()->id)
            ->latest('id')
            ->first();

        return response()->json($import ? $this->payload($import) : null);
    }

    public function show(Request $request, SpotifyImport $import)
    {
        abort_unless($import->user_id === $request->user()->id, 404);

        return response()->json($this->payload($import));
    }

    /** Остановить догрузку обложек и превью, оставив уже перенесённое. */
    public function cancel(Request $request, SpotifyImport $import)
    {
        abort_unless($import->user_id === $request->user()->id, 404);

        if (in_array($import->status, ['pending', 'parsing', 'enriching'], true)) {
            $import->update(['status' => 'canceled', 'stage' => 'Остановлено']);
            Track::whereIn('enrich_status', ['pending', 'working'])->update(['enrich_status' => null]);
        }

        return response()->json($this->payload($import->fresh()));
    }

    private function payload(SpotifyImport $import): array
    {
        return [
            'id' => $import->id,
            'status' => $import->status,
            'kind' => $import->kind,
            'stage' => $import->stage,
            'progress' => $import->progress,
            'enrich_total' => $import->enrich_total,
            'enrich_done' => min($import->enrich_done, $import->enrich_total),
            'original_name' => $import->original_name,
            'size_bytes' => $import->size_bytes,
            'summary' => $import->summary,
            'error' => $import->error,
            'created_at' => $import->created_at?->toIso8601String(),
        ];
    }

    // -- Старый путь: список треков из Exportify-CSV -------------------------

    /**
     * Матчим по названию + исполнителю (+ длительность, когда она есть) и
     * лайкаем найденное. Ничего не создаёт: только то, что уже есть в каталоге.
     */
    public function likedTracks(Request $request)
    {
        $data = $request->validate([
            'items' => ['required', 'array', 'max:5000'],
            'items.*.title' => ['required', 'string', 'max:500'],
            'items.*.artists' => ['array'],
            'items.*.artists.*' => ['string', 'max:300'],
            'items.*.duration_ms' => ['nullable', 'integer'],
        ]);

        $user = $request->user();
        $alreadyLiked = $user->likedTracks()->pluck('tracks.id')->flip();

        // Один проход по каталогу: индекс нормализованное-название → треки.
        $catalog = Track::with('artists:id,name')->get(['id', 'title', 'duration_ms']);
        $index = [];
        foreach ($catalog as $track) {
            $index[$this->norm($track->title)][] = $track;
        }

        $matchedIds = [];
        $added = 0;
        $already = 0;
        $missing = [];

        foreach ($data['items'] as $item) {
            $track = $this->match($item, $index);
            if (! $track) {
                $missing[] = [
                    'title' => $item['title'],
                    'artists' => $item['artists'] ?? [],
                ];

                continue;
            }
            if (isset($alreadyLiked[$track->id]) || isset($matchedIds[$track->id])) {
                $already++;

                continue;
            }
            $matchedIds[$track->id] = true;
            $added++;
        }

        if ($matchedIds) {
            $user->likedTracks()->syncWithoutDetaching(array_keys($matchedIds));
            Track::whereIn('id', array_keys($matchedIds))->increment('likes_count');
        }

        return response()->json([
            'added' => $added,
            'already' => $already,
            'missing' => $missing,
            'total' => count($data['items']),
        ]);
    }

    private function match(array $item, array $index): ?Track
    {
        $title = $this->norm($item['title']);
        $candidates = $index[$title] ?? [];

        // Заголовок в каталоге может не содержать «(feat. …)» и наоборот.
        if (! $candidates) {
            $stripped = $this->norm(preg_replace('/[\(\[].*?[\)\]]/u', '', $item['title']));
            $candidates = $index[$stripped] ?? [];
        }
        if (! $candidates) {
            return null;
        }

        $wantArtists = array_map(fn ($a) => $this->norm($a), $item['artists'] ?? []);
        $wantDur = $item['duration_ms'] ?? null;

        foreach ($candidates as $track) {
            if ($wantArtists) {
                $trackArtists = $track->artists->map(fn ($a) => $this->norm($a->name))->all();
                $overlap = array_intersect($wantArtists, $trackArtists);
                if (! $overlap) {
                    continue;
                }
            }
            if ($wantDur && $track->duration_ms && abs($track->duration_ms - $wantDur) > 5000) {
                continue;
            }

            return $track;
        }

        // Без артистов в запросе — берём первый кандидат по названию.
        return $wantArtists ? null : $candidates[0];
    }

    private function norm(string $s): string
    {
        $s = Str::lower(trim($s));
        $s = preg_replace('/\s+/u', ' ', $s);

        return preg_replace('/[«»"\'\x{2019}`´]/u', '', $s);
    }
}
