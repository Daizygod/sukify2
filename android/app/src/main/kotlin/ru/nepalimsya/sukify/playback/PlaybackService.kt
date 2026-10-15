package ru.nepalimsya.sukify.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.MainActivity
import ru.nepalimsya.sukify.R
import ru.nepalimsya.sukify.playback.auto.AutoLibrary

/**
 * Сервис воспроизведения: держит MediaSession — по ней система рисует
 * уведомление и экран блокировки, гарнитура и машина шлют команды, а сам
 * сервис живёт в foreground, пока играет музыка (Media3 делает это сам).
 * Он же отдаёт Android Auto дерево медиатеки и поиск.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var engine: PlaybackEngine

    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var autoLibrary: AutoLibrary

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bitmaps: CoilBitmapLoader? = null

    private var session: MediaLibrarySession? = null
    private lateinit var player: SukifyPlayer
    private var shownLiked: Boolean? = null

    /** Результаты поиска в машине: Auto сначала ищет, потом забирает страницы. */
    private val searches = HashMap<String, List<MediaItem>>()

    private val onChange: () -> Unit = { updateLikeButton() }

    override fun onCreate() {
        super.onCreate()
        player = SukifyPlayer(engine)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        val loader = CoilBitmapLoader(this, imageLoader).also { bitmaps = it }
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(loader))
            .setMediaButtonPreferences(listOf(likeButton(false)))
            .build()
        engine.addChangeListener(onChange)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Смахнули приложение из недавних: играет — живём дальше, нет — уходим. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!engine.localState.value.playing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        engine.removeChangeListener(onChange)
        scope.cancel()
        session?.release()
        player.release()
        bitmaps?.release()
        session = null
        super.onDestroy()
    }

    /** ♥ в уведомлении — горит, если трек в «Любимых». */
    private fun updateLikeButton() {
        val track = engine.localState.value.track ?: return
        val liked = engine.isLiked(track.id)
        if (liked == shownLiked) return
        shownLiked = liked
        session?.setMediaButtonPreferences(listOf(likeButton(liked)))
    }

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Убрать из любимых" else "Добавить в любимые")
            .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build()

    /** Media3 ждёт ответы будущими значениями — считаем их корутиной на главном потоке. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
            } catch (e: Throwable) {
                result.setException(e)
            }
        }
        return result
    }

    private fun page(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
        return ImmutableList.copyOf(items.subList(from, to))
    }

    /** Подборки — плитками, треки — списком (вкладки корня Auto наследуют это). */
    private fun rootParams(params: LibraryParams?): LibraryParams = LibraryParams.Builder()
        .setOffline(params?.isOffline == true)
        .setExtras(
            Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            },
        )
        .build()

    private fun notSignedIn() = SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Войдите в Sukify на телефоне")

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_LIKE) {
                engine.toggleLikeCurrent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // --- Android Auto: дерево, поиск, выбор -----------------------------------

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(autoLibrary.root(), rootParams(params)))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            if (!autoLibrary.signedIn()) return@future LibraryResult.ofError(notSignedIn())
            LibraryResult.ofItemList(page(autoLibrary.children(parentId), page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            autoLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = autoLibrary.search(query)
                searches[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val results = searches[query] ?: autoLibrary.search(query).also { searches[query] = it }
            LibraryResult.ofItemList(page(results, page, pageSize), params)
        }

        /**
         * Выбор трека в машине («t:{id}|{подборка}») или «Включи …» голосом
         * (пустой id и поисковый запрос). Находим подборку здесь, а плеер
         * включит её, когда сессия передаст ему этот элемент.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.first()
            val id = requested.mediaId
            val query = requested.requestMetadata.searchQuery
            val selection = when {
                id == AutoLibrary.RESUME -> return@future resume(true)
                id.startsWith(AutoLibrary.TRACK) -> autoLibrary.resolve(id)
                !query.isNullOrBlank() -> autoLibrary.resolveSearch(query)
                // «Включи музыку» без названия — продолжаем прошлое, а нет его — любимые.
                query != null -> resumeOrNull(true)?.let { return@future it } ?: autoLibrary.resolveContext(AutoLibrary.LIKED)
                else -> null
            } ?: throw IllegalArgumentException("нечего включить: $id")
            val item = autoLibrary.playable(selection.start, selection.key)
            player.stage(item.mediaId) { engine.local { playContext(selection.tracks, selection.start, selection.name, selection.key) } }
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0)
        }

        /**
         * ▶ с гарнитуры или руля после перезапуска, а также системное
         * «Продолжить» (тогда [isForPlayback] = false — нужны только данные).
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future { resume(isForPlayback) }

        private suspend fun resume(forPlayback: Boolean): MediaSession.MediaItemsWithStartPosition =
            resumeOrNull(forPlayback) ?: throw IllegalStateException("нечего продолжать")

        /** Прошлая сессия: для игры — поднимаем её в плеер на паузе, ▶ сессия нажмёт сама. */
        private suspend fun resumeOrNull(forPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? {
            if (forPlayback) engine.restoreSession()
            val (track, positionMs) = engine.lastSessionTrack() ?: return null
            if (forPlayback) player.stage(AutoLibrary.RESUME) { }
            return MediaSession.MediaItemsWithStartPosition(listOf(autoLibrary.resumeItem(track)), 0, positionMs)
        }
    }

    companion object {
        const val ACTION_LIKE = "ru.nepalimsya.sukify.LIKE"
    }
}
