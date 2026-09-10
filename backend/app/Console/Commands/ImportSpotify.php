<?php

namespace App\Console\Commands;

use App\Console\Commands\Concerns\ResolvesUser;
use App\Jobs\ImportSpotifyArchive;
use App\Models\SpotifyImport;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;

/**
 * Импорт архива Spotify из консоли — то же, что и страница /import, но без
 * загрузки через браузер. Удобно для больших архивов и для прода, где гонять
 * 400 МБ через HTTP незачем.
 */
class ImportSpotify extends Command
{
    use ResolvesUser;

    protected $signature = 'spotify:import
        {path : путь к ZIP из «Download your data» (или к отдельному JSON)}
        {--user= : email, @username или id пользователя}
        {--sync : разобрать сразу, не через очередь}';

    protected $description = 'Перенести библиотеку, плейлисты и историю из архива Spotify';

    public function handle(): int
    {
        $path = $this->argument('path');
        if (! is_file($path)) {
            $this->error("Файл не найден: {$path}");

            return self::FAILURE;
        }

        $user = $this->resolveUser((string) $this->option('user'));
        if (! $user) {
            $this->error('Не понял, в чей аккаунт импортировать. Укажи --user=email.');

            return self::FAILURE;
        }

        $key = 'tmp-uploads/spotify/'.uniqid('cli_', true).'.'.pathinfo($path, PATHINFO_EXTENSION);
        $stream = fopen($path, 'r');
        Storage::disk('s3')->writeStream($key, $stream);
        if (is_resource($stream)) {
            fclose($stream);
        }

        $import = SpotifyImport::create([
            'user_id' => $user->id,
            'original_name' => basename($path),
            'size_bytes' => filesize($path) ?: 0,
            'archive_path' => $key,
            'status' => 'pending',
            'stage' => 'В очереди',
        ]);

        $this->info("Импорт #{$import->id} для {$user->email} — ".basename($path));

        if ($this->option('sync')) {
            (new ImportSpotifyArchive($import->id))->handle();
            $import->refresh();
            $this->line('Статус: '.$import->status.' — '.$import->stage);
            $this->line(json_encode($import->summary, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
        } else {
            ImportSpotifyArchive::dispatch($import->id);
            $this->line('Отправлено в очередь. Прогресс — на /import.');
        }

        return self::SUCCESS;
    }
}
