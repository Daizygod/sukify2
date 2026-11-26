<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** Один файл из массовой загрузки. */
#[Fillable([
    'bulk_upload_id', 'path', 'ext', 'size_bytes', 'local_path', 'status', 'action',
    'reason', 'tags', 'track_id', 'track_ids', 'score', 'candidates',
    'duplicate_of_id', 'censor', 'censor_info', 'error',
])]
class BulkUploadItem extends Model
{
    /** Что берём из архива как музыку. Видео (mp4/mkv) не трогаем. */
    public const AUDIO_EXT = [
        'mp3', 'flac', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wav', 'aif', 'aiff',
        'ape', 'wma', 'wv', 'mka',
    ];

    /** Картинки из папки альбома — обложка для новых релизов. */
    public const IMAGE_EXT = ['jpg', 'jpeg', 'png', 'webp'];

    /** Без потерь — такая копия лучше любой mp3. */
    public const LOSSLESS_CODECS = [
        'flac', 'alac', 'ape', 'wavpack', 'tta', 'mlp', 'truehd',
        'pcm_s16le', 'pcm_s24le', 'pcm_s32le', 'pcm_s16be', 'pcm_s24be', 'pcm_s32be',
        'pcm_f32le', 'pcm_f64le', 'pcm_u8',
    ];

    protected function casts(): array
    {
        return [
            'tags' => 'array',
            'track_ids' => 'array',
            'candidates' => 'array',
            'censor_info' => 'array',
            'size_bytes' => 'integer',
            'score' => 'integer',
        ];
    }

    public function upload(): BelongsTo
    {
        return $this->belongsTo(BulkUpload::class, 'bulk_upload_id');
    }

    public function track(): BelongsTo
    {
        return $this->belongsTo(Track::class);
    }

    public function absolutePath(): ?string
    {
        return $this->local_path ? $this->upload->dir().'/'.$this->local_path : null;
    }

    /** Треки, которым достаётся этот файл. */
    public function targetIds(): array
    {
        $ids = $this->track_ids ?: ($this->track_id ? [$this->track_id] : []);

        return array_values(array_unique(array_map('intval', $ids)));
    }

    public static function isAudioExt(string $ext): bool
    {
        return in_array(strtolower($ext), self::AUDIO_EXT, true);
    }

    public static function isImageExt(string $ext): bool
    {
        return in_array(strtolower($ext), self::IMAGE_EXT, true);
    }

    /**
     * Чем больше, тем копия лучше: без потерь > битрейт > частота.
     * Из нескольких файлов одного трека берём лучший.
     */
    public function quality(): int
    {
        $t = $this->tags ?? [];
        $lossless = ! empty($t['lossless']) ? 1 : 0;
        $kbps = min(9999, (int) round(((int) ($t['bit_rate'] ?? 0)) / 1000));
        $khz = min(999, (int) round(((int) ($t['sample_rate'] ?? 0)) / 1000));
        $hiRes = (int) ($t['bits'] ?? 0) > 16 ? 1 : 0;

        return $lossless * 100_000_000 + $kbps * 10_000 + $khz * 10 + $hiRes;
    }
}
