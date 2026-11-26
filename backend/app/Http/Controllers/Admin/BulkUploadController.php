<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Jobs\DiscardBulkUpload;
use App\Jobs\ImportBulkItem;
use App\Jobs\PrepareBulkUpload;
use App\Models\BulkUpload;
use App\Models\BulkUploadItem;
use App\Models\Track;
use App\Services\Bulk\ArchiveUnpacker;
use App\Services\Bulk\DiskSpace;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Inertia\Inertia;
use Inertia\Response;

/**
 * Массовая загрузка музыки: архив (докачивается кусками) или папка (файлы по
 * одному). Дальше всё делает очередь — {@see PrepareBulkUpload}.
 */
class BulkUploadController extends Controller
{
    /** Кусок архива за один запрос: меньше лимитов nginx и PHP (520 МБ) с запасом. */
    public const CHUNK_BYTES = 32 * 1024 * 1024;

    /** Один файл из папки — как обычная загрузка трека. */
    public const MAX_FILE_BYTES = 512 * 1024 * 1024;

    public function __construct(private readonly DiskSpace $disk)
    {
    }

    public function index(): Response
    {
        $uploads = BulkUpload::with('user:id,name')
            ->latest('id')
            ->limit(30)
            ->get()
            ->map(fn (BulkUpload $u) => $this->uploadCard($u) + ['counts' => $u->counts()]);

        return Inertia::render('Uploads/Index', [
            'uploads' => $uploads,
            'limits' => [
                'free_bytes' => max(0, $this->disk->free(storage_path('app')) - DiskSpace::RESERVE_BYTES),
                'chunk_bytes' => self::CHUNK_BYTES,
                'max_file_bytes' => self::MAX_FILE_BYTES,
            ],
            'defaults' => BulkUpload::DEFAULT_OPTIONS,
            'extensions' => [
                'archive' => ArchiveUnpacker::EXTENSIONS,
                'audio' => BulkUploadItem::AUDIO_EXT,
                'image' => BulkUploadItem::IMAGE_EXT,
            ],
        ]);
    }

    /**
     * Начать загрузку — или продолжить недокачанную: тот же файл (имя и
     * размер) докачивается с того места, где оборвался.
     */
    public function store(Request $request): JsonResponse
    {
        $data = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'size' => ['required', 'integer', 'min:1'],
            'kind' => ['required', Rule::in([BulkUpload::KIND_ARCHIVE, BulkUpload::KIND_FOLDER])],
            'files' => ['nullable', 'integer', 'min:0', 'max:200000'],
            'options' => ['nullable', 'array'],
            'options.*' => ['boolean'],
        ]);

        $name = mb_substr(basename(str_replace('\\', '/', $data['name'])), 0, 255);
        if ($data['kind'] === BulkUpload::KIND_ARCHIVE
            && ! in_array(strtolower(pathinfo($name, PATHINFO_EXTENSION)), ArchiveUnpacker::EXTENSIONS, true)) {
            return response()->json(['message' => 'Это не архив: подойдут ZIP, RAR, 7z или TAR.'], 422);
        }

        $options = [];
        foreach (BulkUpload::DEFAULT_OPTIONS as $key => $default) {
            $options[$key] = (bool) ($data['options'][$key] ?? $default);
        }

        $existing = BulkUpload::where('user_id', $request->user()->id)
            ->where('kind', $data['kind'])
            ->where('name', $name)
            ->where('size_bytes', $data['size'])
            ->where('status', 'uploading')
            ->latest('id')
            ->first();
        if ($existing) {
            $existing->forceFill(['options' => $options])->save();

            return response()->json($this->uploadState($existing));
        }

        // «Пауза» оборвала только ожидание ответа: последний кусок мог дойти и
        // закончить загрузку. Тот же файл в ближайшие минуты — это она, а не
        // повод слать гигабайты заново.
        $finished = BulkUpload::where('user_id', $request->user()->id)
            ->where('kind', $data['kind'])
            ->where('name', $name)
            ->where('size_bytes', $data['size'])
            ->whereIn('status', BulkUpload::ACTIVE)
            ->where('updated_at', '>', now()->subMinutes(30))
            ->latest('id')
            ->first();
        if ($finished) {
            return response()->json(['received_bytes' => $finished->size_bytes] + $this->uploadState($finished));
        }

        // Архив лежит на диске целиком, пока распаковывается, — нужно вдвое.
        $need = $data['kind'] === BulkUpload::KIND_ARCHIVE ? $data['size'] * 2 : $data['size'];
        $free = $this->disk->free(storage_path('app')) - DiskSpace::RESERVE_BYTES;
        if ($need > $free) {
            return response()->json([
                'message' => sprintf(
                    'Не хватит места на сервере: нужно %s, свободно %s. Разбей музыку на несколько архивов поменьше.',
                    DiskSpace::human($need),
                    DiskSpace::human(max(0, $free)),
                ),
            ], 422);
        }

        $upload = BulkUpload::create([
            'user_id' => $request->user()->id,
            'name' => $name,
            'kind' => $data['kind'],
            'size_bytes' => $data['size'],
            'files_expected' => (int) ($data['files'] ?? 0),
            'status' => 'uploading',
            'stage' => 'Загружаю',
            'options' => $options,
        ]);
        @mkdir($upload->dir().'/files', 0777, true);
        @chmod($upload->dir(), 0777);
        @chmod($upload->dir().'/files', 0777);

        return response()->json($this->uploadState($upload), 201);
    }

    /**
     * Следующий кусок архива. Смещение — в заголовке X-Upload-Offset: если оно
     * не сходится с тем, что уже на диске, отвечаем 409 с правильным, и
     * браузер продолжает оттуда.
     */
    public function chunk(Request $request, BulkUpload $upload): JsonResponse
    {
        if ($upload->kind !== BulkUpload::KIND_ARCHIVE || $upload->status !== 'uploading') {
            return response()->json(['message' => 'Эта загрузка уже не принимает данные.', 'received_bytes' => $upload->received_bytes], 409);
        }

        $offset = (int) $request->header('X-Upload-Offset', '-1');
        $part = $upload->partPath();
        @mkdir(dirname($part), 0777, true);

        $out = fopen($part, 'c+b');
        if ($out === false || ! flock($out, LOCK_EX)) {
            return response()->json(['message' => 'Не получилось записать кусок на диск.'], 500);
        }

        try {
            $size = (int) fstat($out)['size'];
            if ($offset !== $size) {
                return response()->json(['message' => 'Смещение не сходится.', 'received_bytes' => $size], 409);
            }

            fseek($out, 0, SEEK_END);
            $in = $request->getContent(true);
            $written = is_resource($in) ? (int) stream_copy_to_stream($in, $out) : 0;
            fflush($out);
            $size += $written;

            if ($size > $upload->size_bytes) {
                ftruncate($out, $offset);

                return response()->json(['message' => 'Пришло больше, чем весит архив.', 'received_bytes' => $offset], 422);
            }
        } finally {
            flock($out, LOCK_UN);
            fclose($out);
        }

        $complete = $size === $upload->size_bytes;
        $upload->forceFill([
            'received_bytes' => $size,
            'progress' => (int) floor(100 * $size / max(1, $upload->size_bytes)),
            'stage' => 'Загружаю: '.DiskSpace::human($size).' из '.DiskSpace::human($upload->size_bytes),
        ])->save();

        if ($complete) {
            rename($part, $upload->archivePath());
            $upload->forceFill(['status' => 'queued', 'stage' => 'В очереди на распаковку', 'progress' => 0])->save();
            PrepareBulkUpload::dispatch($upload->id);
        }

        return response()->json(['received_bytes' => $size, 'complete' => $complete]);
    }

    /** Файл из папки (папку браузер отдаёт по одному файлу). */
    public function file(Request $request, BulkUpload $upload, ArchiveUnpacker $paths): JsonResponse
    {
        if ($upload->kind !== BulkUpload::KIND_FOLDER || $upload->status !== 'uploading') {
            return response()->json(['message' => 'Эта загрузка уже не принимает файлы.'], 409);
        }

        $request->validate([
            'file' => ['required', 'file', 'max:'.(int) (self::MAX_FILE_BYTES / 1024)],
            'path' => ['required', 'string', 'max:2000'],
        ]);

        $path = $paths->safePath((string) $request->input('path'));
        $ext = strtolower(pathinfo((string) $path, PATHINFO_EXTENSION));
        if ($path === null || (! BulkUploadItem::isAudioExt($ext) && ! BulkUploadItem::isImageExt($ext))) {
            return response()->json(['message' => 'Это не музыка и не обложка — пропускаю.'], 422);
        }

        // Повтор после обрыва — файл уже здесь.
        if ($upload->items()->where('path', $path)->exists()) {
            return response()->json(['received_bytes' => $upload->received_bytes, 'duplicate' => true]);
        }

        $local = 'files/'.Str::uuid().'.'.$ext;
        $request->file('file')->move($upload->dir().'/files', basename($local));
        $size = (int) filesize($upload->dir().'/'.$local);

        BulkUploadItem::create([
            'bulk_upload_id' => $upload->id,
            'path' => $path,
            'ext' => mb_substr($ext, 0, 8),
            'size_bytes' => $size,
            'local_path' => $local,
            'status' => BulkUploadItem::isImageExt($ext) ? 'asset' : 'pending',
        ]);
        $upload->increment('received_bytes', $size);
        $upload->forceFill([
            'progress' => (int) floor(100 * $upload->received_bytes / max(1, $upload->size_bytes)),
            'stage' => 'Загружаю: '.DiskSpace::human($upload->received_bytes).' из '.DiskSpace::human($upload->size_bytes),
        ])->save();

        return response()->json(['received_bytes' => $upload->received_bytes]);
    }

    /** Все файлы папки на месте — разбираем. */
    public function finish(BulkUpload $upload): JsonResponse
    {
        if ($upload->kind !== BulkUpload::KIND_FOLDER || $upload->status !== 'uploading') {
            return response()->json(['message' => 'Эта загрузка уже разбирается.'], 409);
        }
        if (! $upload->items()->where('status', '!=', 'asset')->exists()) {
            return response()->json(['message' => 'В папке не нашлось музыки.'], 422);
        }

        $upload->forceFill(['status' => 'queued', 'stage' => 'В очереди', 'progress' => 0])->save();
        PrepareBulkUpload::dispatch($upload->id);

        return response()->json(['ok' => true]);
    }

    public function show(Request $request, BulkUpload $upload): Response
    {
        $tab = (string) $request->query('tab', 'all');
        $q = trim((string) $request->query('q', ''));

        $items = $upload->items()
            ->where('status', '!=', 'asset')
            ->when($tab !== 'all', fn ($query) => match ($tab) {
                'importing' => $query->whereIn('status', ['queued', 'processing']),
                'suspect' => $query->whereIn('censor', ['suspect', 'differs']),
                default => $query->where('status', $tab),
            })
            ->when($q !== '', fn ($query) => $query->where('path', 'ilike', '%'.$this->like($q).'%'))
            ->orderByRaw("case status when 'review' then 0 when 'failed' then 1 when 'processing' then 2 when 'queued' then 3 else 4 end")
            ->orderBy('id')
            ->paginate(100)
            ->withQueryString();

        $ids = [];
        foreach ($items->items() as $item) {
            array_push($ids, ...$item->targetIds(), ...array_column($item->candidates ?? [], 'id'));
        }
        $tracks = $this->trackCards(array_unique($ids));

        return Inertia::render('Uploads/Show', [
            'upload' => $this->uploadCard($upload),
            'counts' => $upload->counts(),
            'items' => $items->through(fn (BulkUploadItem $i) => $this->itemCard($i, $tracks)),
            'filters' => ['tab' => $tab, 'q' => $q],
        ]);
    }

    /**
     * Решение по одному файлу: положить в выбранный трек, завести новый,
     * пропустить, заменить полную версию или повторить упавший.
     */
    public function resolve(Request $request, BulkUpload $upload, BulkUploadItem $item): RedirectResponse
    {
        abort_unless($item->bulk_upload_id === $upload->id, 404);

        $data = $request->validate([
            'action' => ['required', Rule::in(['attach', 'create', 'skip', 'replace', 'retry'])],
            'track_id' => ['required_if:action,attach', 'nullable', 'integer', 'exists:tracks,id'],
        ]);

        if ($data['action'] === 'skip') {
            $item->forceFill(['status' => 'skipped', 'reason' => 'manual'])->save();

            return back()->with('success', 'Skipped.');
        }

        $item->setRelation('upload', $upload);
        if (! $item->local_path || ! is_file((string) $item->absolutePath())) {
            return back()->with('error', 'The file is no longer on the server — upload it again.');
        }
        if (($item->tags['ok'] ?? false) !== true) {
            return back()->with('error', 'This file could not be read, so it cannot be imported.');
        }
        if ($data['action'] === 'create' && ! $this->creatable($item)) {
            return back()->with('error', 'The artist is not in the tags or the file name — pick an existing track for this file instead.');
        }

        $fields = match ($data['action']) {
            'attach' => ['action' => 'attach', 'reason' => 'manual', 'track_id' => (int) $data['track_id'], 'track_ids' => [(int) $data['track_id']]],
            // Трек заведёт задача импорта: там же замок на каталог.
            'create' => ['action' => 'create', 'reason' => 'manual', 'track_id' => null, 'track_ids' => null],
            'replace', 'retry' => $item->targetIds() || $item->action === 'create' ? [] : null,
        };
        if ($fields === null) {
            return back()->with('error', 'Pick a track for this file first.');
        }

        $item->forceFill($fields + ['status' => 'queued', 'error' => null, 'duplicate_of_id' => null])->save();
        $this->queue($upload, [$item->id]);

        return back()->with('success', 'Queued.');
    }

    /** Разом для всех, кто ждёт решения: завести новыми, взять лучшую догадку или пропустить. */
    public function resolveAll(Request $request, BulkUpload $upload): RedirectResponse
    {
        $data = $request->validate([
            'action' => ['required', Rule::in(['create', 'best', 'skip'])],
            'reason' => ['nullable', Rule::in(['ambiguous', 'no_match'])],
        ]);

        $items = $upload->items()
            ->where('status', 'review')
            ->when($data['reason'] ?? null, fn ($q, $reason) => $q->where('reason', $reason))
            ->get();

        $queued = [];
        $left = 0;
        foreach ($items as $item) {
            $tags = $item->tags ?? [];
            if ($data['action'] === 'skip') {
                $item->forceFill(['status' => 'skipped', 'reason' => 'manual'])->save();

                continue;
            }
            if ($data['action'] === 'best') {
                $best = $item->candidates[0]['id'] ?? null;
                if (! $best) {
                    $left++;

                    continue;
                }
                $item->forceFill(['status' => 'queued', 'action' => 'attach', 'reason' => 'manual', 'track_id' => $best, 'track_ids' => [$best]])->save();
            } else {
                if (! $this->creatable($item)) {
                    $left++;

                    continue;
                }
                $item->forceFill(['status' => 'queued', 'action' => 'create', 'reason' => 'manual', 'track_id' => null, 'track_ids' => null])->save();
            }
            $queued[] = $item->id;
        }

        $this->queue($upload, $queued);

        $message = count($queued).' queued.';
        if ($left) {
            $message .= " {$left} need a manual decision (no artist in the tags or no candidate).";
        }

        return back()->with('success', $message);
    }

    /** Упавшую подготовку — заново, загрузку на паузе — дальше. */
    public function retry(BulkUpload $upload): RedirectResponse
    {
        if ($upload->status === 'failed') {
            $upload->forceFill(['status' => 'queued', 'stage' => 'В очереди', 'error' => null])->save();
            PrepareBulkUpload::dispatch($upload->id);
        } elseif ($upload->status === 'paused') {
            $this->queue($upload, $upload->items()->where('status', 'queued')->pluck('id')->all());
        }

        return back();
    }

    public function destroy(BulkUpload $upload): RedirectResponse
    {
        $upload->forceFill(['status' => 'canceled', 'stage' => 'Удаляю файлы'])->save();
        DiscardBulkUpload::dispatch($upload->id);

        return redirect()->route('admin.uploads.index')->with('success', 'Upload removed. Tracks that were already imported stay in the catalog.');
    }

    /** Поиск трека для ручного выбора: каждое слово — в названии, артисте или альбоме. */
    public function tracks(Request $request): JsonResponse
    {
        $words = array_slice(preg_split('/\s+/u', trim((string) $request->query('q', ''))) ?: [], 0, 5);
        $words = array_values(array_filter($words, fn ($w) => mb_strlen($w) >= 2));
        if (! $words) {
            return response()->json(['data' => []]);
        }

        $ids = Track::query()
            ->leftJoin('releases', 'releases.id', '=', 'tracks.release_id')
            ->where(function ($query) use ($words) {
                foreach ($words as $word) {
                    $like = '%'.$this->like($word).'%';
                    $query->where(fn ($w) => $w->where('tracks.title', 'ilike', $like)
                        ->orWhere('releases.title', 'ilike', $like)
                        ->orWhereExists(fn ($e) => $e->selectRaw('1')
                            ->from('track_artist')
                            ->join('artists', 'artists.id', '=', 'track_artist.artist_id')
                            ->whereColumn('track_artist.track_id', 'tracks.id')
                            ->where('artists.name', 'ilike', $like)));
                }
            })
            ->orderBy('tracks.title')
            ->limit(20)
            ->pluck('tracks.id')
            ->all();

        $cards = $this->trackCards($ids);

        return response()->json(['data' => array_values(array_filter(array_map(fn ($id) => $cards[$id] ?? null, $ids)))]);
    }

    // -- Мелочи ---------------------------------------------------------------

    /** @param  list<int>  $ids */
    private function queue(BulkUpload $upload, array $ids): void
    {
        if (! $ids) {
            return;
        }

        // Загрузка могла уже закончиться — снова «ставлю», чтобы она закрылась
        // сама, когда эти файлы лягут.
        $upload->forceFill([
            'status' => 'importing',
            'finished_at' => null,
            'stage' => 'Ставлю полные версии',
        ])->save();

        foreach ($ids as $id) {
            ImportBulkItem::dispatch($id);
        }
    }

    private function uploadState(BulkUpload $upload): array
    {
        $received = $upload->kind === BulkUpload::KIND_ARCHIVE
            ? $this->partSize($upload)
            : (int) $upload->received_bytes;

        return [
            'id' => $upload->id,
            'received_bytes' => $received,
            'received_paths' => $upload->kind === BulkUpload::KIND_FOLDER
                ? $upload->items()->pluck('path')->all()
                : [],
            'chunk_bytes' => self::CHUNK_BYTES,
        ];
    }

    private function uploadCard(BulkUpload $upload): array
    {
        return [
            'id' => $upload->id,
            'name' => $upload->name,
            'kind' => $upload->kind,
            'status' => $upload->status,
            'stage' => $upload->stage,
            'progress' => $upload->progress,
            'size_bytes' => $upload->size_bytes,
            'received_bytes' => $upload->received_bytes,
            'options' => ($upload->options ?? []) + BulkUpload::DEFAULT_OPTIONS,
            'error' => $upload->error,
            'active' => $upload->isActive(),
            'user' => $upload->user?->name,
            'created_at' => $upload->created_at?->toIso8601String(),
            'finished_at' => $upload->finished_at?->toIso8601String(),
        ];
    }

    /**
     * @param  list<int>  $ids
     * @return array<int, array>
     */
    private function trackCards(array $ids): array
    {
        if (! $ids) {
            return [];
        }

        return Track::with(['release:id,title', 'artists:id,name'])
            ->whereIn('id', $ids)
            ->get()
            ->mapWithKeys(fn (Track $t) => [$t->id => [
                'id' => $t->id,
                'title' => $t->title,
                'artists' => $t->artists->pluck('name')->implode(', '),
                'release' => $t->release?->title,
                'release_id' => $t->release_id,
                'duration_ms' => $t->duration_ms,
                'full' => (bool) ($t->audio_stream_path && ! $t->preview_only && ! $t->demo_audio),
                'preview' => (bool) $t->preview_only,
            ]])
            ->all();
    }

    private function itemCard(BulkUploadItem $item, array $tracks): array
    {
        $tags = $item->tags ?? [];

        return [
            'id' => $item->id,
            'path' => $item->path,
            'ext' => $item->ext,
            'size' => $item->size_bytes,
            'status' => $item->status,
            'action' => $item->action,
            'reason' => $item->reason,
            'score' => $item->score,
            'has_file' => $item->local_path !== null,
            'creatable' => $this->creatable($item),
            'tags' => array_intersect_key($tags, array_flip([
                'title', 'artist', 'artist_from', 'album', 'track', 'duration_ms', 'codec', 'bit_rate',
                'sample_rate', 'bits', 'lossless',
            ])),
            'targets' => array_values(array_filter(array_map(fn ($id) => $tracks[$id] ?? null, $item->targetIds()))),
            'candidates' => array_values(array_filter(array_map(
                fn ($c) => isset($tracks[$c['id']]) ? $tracks[$c['id']] + ['score' => $c['score']] : null,
                $item->candidates ?? [],
            ))),
            'duplicate_of_id' => $item->duplicate_of_id,
            'censor' => $item->censor,
            'censor_info' => $item->censor_info,
            'error' => $item->error,
        ];
    }

    /**
     * Сколько архива уже на диске. Под общим замком: прерванный «Паузой» кусок
     * сервер может ещё дописывать — ждём его, а не отдаём размер на середине.
     */
    private function partSize(BulkUpload $upload): int
    {
        $handle = @fopen($upload->partPath(), 'rb');
        if ($handle === false) {
            return 0;
        }
        flock($handle, LOCK_SH);
        $size = (int) fstat($handle)['size'];
        flock($handle, LOCK_UN);
        fclose($handle);

        return $size;
    }

    /**
     * Завести новым можно, если артист из тегов или из имени файла «Артист -
     * Трек». Артист по имени папки («Моя музыка/Шум») — скорее всего не артист.
     */
    private function creatable(BulkUploadItem $item): bool
    {
        $t = $item->tags ?? [];

        return trim((string) ($t['title'] ?? '')) !== ''
            && trim((string) ($t['artist'] ?? '')) !== ''
            && in_array($t['artist_from'] ?? null, ['tags', 'filename'], true);
    }

    private function like(string $s): string
    {
        return str_replace(['\\', '%', '_'], ['\\\\', '\%', '\_'], $s);
    }
}
