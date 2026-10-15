package ru.nepalimsya.sukify.playback

import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.queue.RepeatMode

/**
 * Плеер для внешнего мира: уведомление, экран блокировки, гарнитура,
 * Bluetooth в машине, Android Auto. Сам ничего не играет — показывает
 * состояние PlaybackEngine и передаёт ему команды. Очередь видна как
 * «текущий + ручная очередь + дальше по контексту».
 */
class SukifyPlayer(private val engine: PlaybackEngine) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val onChange: () -> Unit = { invalidateState() }

    /**
     * Что включить по элементу из машины или голосовой команды: подборку
     * находит колбэк сессии (это сеть, асинхронно), а сюда приходит уже
     * готовое — setMediaItems только запускает.
     */
    private val staged = HashMap<String, () -> Unit>()

    fun stage(mediaId: String, start: () -> Unit) {
        staged[mediaId] = start
    }

    init {
        engine.addChangeListener(onChange)
    }

    override fun getState(): State {
        val s = engine.localState.value
        val current = s.track ?: return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaybackState(Player.STATE_IDLE)
            .build()

        val items = ArrayList<MediaItemData>(1 + s.queue.manual.size + UPCOMING_LIMIT)
        items += item("now:${current.id}", current, s.durationMs)
        s.queue.manual.forEach { items += item("m:${it.qid}", it.track) }
        s.queue.upcoming.take(UPCOMING_LIMIT).forEachIndexed { i, t -> items += item("u:$i:${t.id}", t) }

        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (s.buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setContentPositionMs { engine.localPositionMs() }
            .setRepeatMode(
                when (s.queue.repeat) {
                    RepeatMode.Off -> Player.REPEAT_MODE_OFF
                    RepeatMode.All -> Player.REPEAT_MODE_ALL
                    RepeatMode.One -> Player.REPEAT_MODE_ONE
                },
            )
            .setShuffleModeEnabled(s.queue.shuffle)
            .build()
    }

    private fun item(uid: String, track: Track, durationMs: Long = track.durationMs): MediaItemData =
        MediaItemData.Builder(uid)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setMediaMetadata(metadata(track))
                    .build(),
            )
            .setDurationUs(durationMs.coerceAtLeast(0) * 1000)
            .setIsSeekable(true)
            .build()

    private fun metadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artistNames)
        .setAlbumTitle(track.release?.title)
        // Большая обложка — для экрана блокировки и машины.
        .setArtworkUri(track.cover?.url(640)?.toUri())
        .setUserRating(HeartRating(engine.isLiked(track.id)))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val id = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
        staged.remove(id)?.invoke()
        staged.clear()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        engine.local { if (playWhenReady) play() else pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        engine.local {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> next()
                // «Назад» с гарнитуры — как кнопка ⏮: после 3 секунд в начало трека.
                Player.COMMAND_SEEK_TO_PREVIOUS -> previous()
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previous(force = true)
                Player.COMMAND_SEEK_TO_MEDIA_ITEM -> jumpTo(mediaItemIndex)
                else -> seekTo(positionMs.coerceAtLeast(0))
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Тап по треку в очереди Android Auto / системного плеера. */
    private fun jumpTo(index: Int) {
        val s = engine.localState.value
        val manual = s.queue.manual
        when {
            index <= 0 -> engine.seekTo(0)
            index - 1 < manual.size -> engine.playManual(manual[index - 1].qid)
            else -> engine.playUpcoming(index - 1 - manual.size)
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.All
            Player.REPEAT_MODE_ONE -> RepeatMode.One
            else -> RepeatMode.Off
        }
        engine.local { setRepeat(mode) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        engine.local { setShuffle(shuffleModeEnabled) }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pauseLocal()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        engine.removeChangeListener(onChange)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val UPCOMING_LIMIT = 50

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                // Выбор трека в Android Auto и «включи …» голосом.
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
