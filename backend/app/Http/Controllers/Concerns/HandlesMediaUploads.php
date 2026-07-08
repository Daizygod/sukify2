<?php

namespace App\Http\Controllers\Concerns;

use Illuminate\Http\UploadedFile;
use Illuminate\Support\Str;
use Illuminate\Support\Facades\Storage;

trait HandlesMediaUploads
{
    /**
     * Stash an upload under tmp-uploads/ (lifecycle-cleaned) and return its
     * S3 key + original extension for a processing job to pick up.
     *
     * @return array{0:string,1:string} [key, ext]
     */
    protected function stashUpload(UploadedFile $file): array
    {
        $ext = strtolower($file->getClientOriginalExtension() ?: $file->extension());
        $key = 'tmp-uploads/'.Str::uuid().'.'.$ext;

        Storage::disk('s3')->writeStream($key, fopen($file->getRealPath(), 'r'));

        return [$key, $ext];
    }
}
