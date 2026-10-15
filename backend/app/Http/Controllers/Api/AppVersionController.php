<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Storage;
use Throwable;

/**
 * Свежая версия приложения для Android.
 *
 * CI кладёт в хранилище подписанный APK и рядом `apps/android/latest.json`
 * ({version_code, version_name, apk, sha256, size, notes, released_at}).
 * Приложение спрашивает здесь, не вышло ли новое, а настройки веба — что
 * отдать по кнопке «Скачать для Android».
 *
 * Отсюда же /.well-known/assetlinks.json: CI пишет в latest.json отпечаток
 * ключа подписи (cert_sha256), и ссылки на сайт открываются в приложении
 * без ручной настройки сервера.
 */
class AppVersionController extends Controller
{
    public const LATEST = 'apps/android/latest.json';

    public const PACKAGE = 'ru.nepalimsya.sukify';

    public function android()
    {
        $data = $this->latest();
        if ($data !== null) {
            unset($data['cert_sha256']);
        }

        return response()->json(['data' => $data]);
    }

    /** Digital Asset Links: каким приложениям сайт доверяет свои ссылки. */
    public function assetLinks()
    {
        $fingerprints = array_values(array_unique(array_filter(array_merge(
            array_map('trim', explode(',', (string) config('services.android.cert_sha256'))),
            [$this->latest()['cert_sha256'] ?? null],
        ))));

        $links = $fingerprints === [] ? [] : [[
            'relation' => ['delegate_permission/common.handle_all_urls'],
            'target' => [
                'namespace' => 'android_app',
                'package_name' => self::PACKAGE,
                'sha256_cert_fingerprints' => $fingerprints,
            ],
        ]];

        return response()->json($links)->header('Cache-Control', 'public, max-age=300');
    }

    private function latest(): ?array
    {
        return Cache::remember('app:android:latest', 300, function () {
            try {
                $disk = Storage::disk('s3');
                if (! $disk->exists(self::LATEST)) {
                    return null;
                }
                $json = json_decode((string) $disk->get(self::LATEST), true);
            } catch (Throwable) {
                return null;
            }

            if (! is_array($json) || empty($json['version_code']) || empty($json['apk'])) {
                return null;
            }

            return [
                'version_code' => (int) $json['version_code'],
                'version_name' => (string) ($json['version_name'] ?? ''),
                'apk_url' => $disk->url($json['apk']),
                'sha256' => $json['sha256'] ?? null,
                'size' => isset($json['size']) ? (int) $json['size'] : null,
                'notes' => $json['notes'] ?? null,
                'released_at' => $json['released_at'] ?? null,
                'cert_sha256' => $json['cert_sha256'] ?? null,
            ];
        });
    }
}
