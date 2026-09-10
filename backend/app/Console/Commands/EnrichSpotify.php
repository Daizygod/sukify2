<?php

namespace App\Console\Commands;

use App\Console\Commands\Concerns\ResolvesUser;
use App\Jobs\EnrichSpotifyTracks;
use App\Models\SpotifyImport;
use App\Models\Track;
use Illuminate\Console\Command;

/**
 * Догрузка обложек и превью отдельно от разбора архива.
 *
 * Нужна в двух случаях: догрузку остановили с /import и хотят продолжить, либо
 * поиск по Deezer стал умнее и «не найденные» треки стоит прогнать заново.
 */
class EnrichSpotify extends Command
{
    use ResolvesUser;

    protected $signature = 'spotify:enrich
        {--user= : email, @username или id пользователя (по умолчанию — последний импорт)}
        {--retry : заново прогнать треки, которые в прошлый раз не нашлись}';

    protected $description = 'Догрузить импортированным трекам обложки и превью из Deezer';

    public function handle(): int
    {
        $import = $this->resolveImport();
        if (! $import) {
            $this->error('Не нашёл ни одного импорта из Spotify.');

            return self::FAILURE;
        }

        if ($this->option('retry')) {
            // Локальные файлы («эксклюзивы») в Deezer искать бессмысленно.
            $retried = Track::where('enrich_status', 'notfound')
                ->where('unofficial', false)
                ->update(['enrich_status' => 'pending']);
            $this->info("Вернул в очередь: {$retried}");
        }

        $pending = Track::whereIn('enrich_status', ['pending', 'working'])->count();
        if ($pending === 0) {
            $this->info('Догружать нечего.');

            return self::SUCCESS;
        }

        $import->update([
            'status' => 'enriching',
            'stage' => 'Качаю обложки и превью из Deezer',
            'enrich_total' => $pending,
            'enrich_done' => 0,
        ]);
        EnrichSpotifyTracks::dispatch($import->id);

        $this->info("Отправил в очередь {$pending} треков (импорт #{$import->id}).");

        return self::SUCCESS;
    }

    private function resolveImport(): ?SpotifyImport
    {
        // Технический архив ничего не заводит — прогресс догрузки к нему не
        // относится, поэтому берём последний импорт с настоящей библиотекой.
        $query = SpotifyImport::query()
            ->whereIn('kind', ['account', 'extended', 'mixed'])
            ->latest('id');

        if ($raw = (string) $this->option('user')) {
            $user = $this->resolveUser($raw);
            if (! $user) {
                return null;
            }
            $query->where('user_id', $user->id);
        }

        return $query->first();
    }
}
