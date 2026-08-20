<?php

namespace App\Console\Commands;

use App\Jobs\AnalyzeTrackAudio;
use App\Models\Track;
use App\Services\Audio\AnalyzerClient;
use Illuminate\Console\Command;

class AnalyzeTracks extends Command
{
    protected $signature = 'tracks:analyze
        {--id=* : Конкретные треки (можно несколько)}
        {--all : Пересчитать даже те, что уже проанализированы}
        {--failed : Только упавшие}
        {--sync : Считать прямо здесь, а не через очередь}
        {--limit=0 : Ограничить число треков}';

    protected $description = 'Посчитать BPM, сетку долей, тональность и пики волны';

    public function handle(AnalyzerClient $analyzer): int
    {
        if (! $analyzer->available()) {
            $this->error('Анализатор недоступен: '.config('services.analyzer.url'));

            return self::FAILURE;
        }

        $query = Track::query()->whereNotNull('audio_stream_path');

        if ($ids = $this->option('id')) {
            $query->whereIn('id', $ids);
        } elseif ($this->option('failed')) {
            $query->where('analysis_status', 'failed');
        } elseif (! $this->option('all')) {
            $query->where(fn ($q) => $q->whereNull('analyzed_at')->orWhere('analysis_status', '!=', 'ready'));
        }

        if ($limit = (int) $this->option('limit')) {
            $query->limit($limit);
        }

        $tracks = $query->orderBy('id')->get(['id', 'title']);
        if ($tracks->isEmpty()) {
            $this->info('Нечего считать.');

            return self::SUCCESS;
        }

        $this->info("Треков в работе: {$tracks->count()}");
        $bar = $this->output->createProgressBar($tracks->count());
        $bar->start();

        foreach ($tracks as $track) {
            if ($this->option('sync')) {
                try {
                    AnalyzeTrackAudio::dispatchSync($track->id);
                } catch (\Throwable $e) {
                    $this->newLine();
                    $this->warn("#{$track->id} {$track->title}: {$e->getMessage()}");
                }
            } else {
                AnalyzeTrackAudio::dispatch($track->id);
            }
            $bar->advance();
        }

        $bar->finish();
        $this->newLine(2);
        $this->info($this->option('sync') ? 'Готово.' : 'Задачи поставлены в очередь.');

        return self::SUCCESS;
    }
}
