package ru.nepalimsya.sukify.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.components.Toasts

/**
 * Единая логика кнопок «играть подборку» (usePlayContext.js): подборка
 * опознаётся по ключу (`release:slug`, `liked`, …), который плеер хранит как
 * contextKey. Своя подборка — ▶/⏸ просто ставит паузу или продолжает;
 * чужая — грузим треки (если их ещё нет на экране) и включаем с начала.
 */
/** Что умеют кнопки «играть подборку» — экраны знают только это. */
interface ContextPlayer {
    fun toggle(key: String, name: String, tracks: List<Track>? = null, shuffle: Boolean = false)

    fun playFrom(tracks: List<Track>, start: Track, name: String, key: String)

    fun enqueue(key: String)

    fun enqueueTracks(tracks: List<Track>)
}

@Singleton
class PlayContexts @Inject constructor(
    private val engine: PlaybackEngine,
    private val catalog: CatalogRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : ContextPlayer {
    private var loading: Job? = null

    fun isCurrent(key: String): Boolean = key.isNotEmpty() && engine.state.value.queue.contextKey == key

    /**
     * ▶ на карточке/в шапке. [tracks] — если список уже на экране; иначе он
     * загрузится по ключу. [shuffle] — «В случайном порядке»: включает
     * перемешивание и стартует со случайного трека.
     */
    override fun toggle(key: String, name: String, tracks: List<Track>?, shuffle: Boolean) {
        if (!shuffle && isCurrent(key)) {
            engine.togglePlay()
            return
        }
        loading?.cancel()
        loading = scope.launch(Dispatchers.Main.immediate) {
            val list = tracks ?: apiCall { catalog.contextTracks(key) }
                .onFailure { Toasts.show("Не получилось включить — нет связи с сервером") }
                .getOrNull() ?: return@launch
            play(list, key, name, shuffle)
        }
    }

    /** Трек из списка: очередь продолжает остальной список. */
    override fun playFrom(tracks: List<Track>, start: Track, name: String, key: String) {
        val current = engine.state.value.track
        if (current?.id == start.id && engine.state.value.queue.contextKey == key) {
            engine.togglePlay()
            return
        }
        engine.playContext(tracks, start, name, key)
    }

    /** Подборку — в конец «Далее в очереди». */
    override fun enqueue(key: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val list = apiCall { catalog.contextTracks(key) }
                .onFailure { Toasts.show("Не получилось добавить в очередь") }
                .getOrNull() ?: return@launch
            enqueueTracks(list)
        }
    }

    override fun enqueueTracks(tracks: List<Track>) {
        var added = 0
        for (track in tracks) if (engine.addToQueue(track)) added++
        Toasts.show("В очередь добавлено: $added")
    }

    private fun play(list: List<Track>, key: String, name: String, shuffle: Boolean) {
        val playable = list.filter { it.playable }
        if (playable.isEmpty()) {
            Toasts.show("Здесь пока нечего слушать")
            return
        }
        if (shuffle) {
            engine.setShuffle(true)
            engine.playContext(list, playable.random(), name, key)
        } else {
            engine.playContext(list, playable.first(), name, key)
        }
    }
}
