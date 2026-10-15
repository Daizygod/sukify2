package ru.nepalimsya.sukify.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.playback.PlaybackEngine

/**
 * Будит виджет, когда сменились трек, пауза или лайк. Пока сессия виджета
 * жива, он и сам следит за плеером; это — чтобы её поднять заново.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: PlaybackEngine,
    private val library: LibraryState,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(engine.localState, library.likedIds) { s, liked ->
                val track = s.track
                Triple(track?.id, s.playing, track != null && track.id in liked)
            }
                .distinctUntilChanged()
                .debounce(250)
                .collect { runCatching { SukifyWidget().updateAll(context) } }
        }
    }
}
