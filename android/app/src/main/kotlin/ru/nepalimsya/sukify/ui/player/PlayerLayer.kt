package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.jam.LocalJam
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.TrackMenuOptions
import ru.nepalimsya.sukify.ui.theme.SukifyMotion

/** Куда вести из плеера (к исполнителю, на альбом трека) — проставляет оболочка. */
interface PlayerNavigation {
    fun openArtist(slug: String)

    fun openRelease(slug: String, trackId: Long)
}

/**
 * Слой плеера поверх приложения: мини-плеер или «Сейчас играет». Обложка
 * перелетает между ними общим элементом, сам экран выезжает снизу.
 */
@Composable
fun PlayerLayer(
    vm: PlayerViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    miniBottomPadding: Dp,
    navigation: PlayerNavigation,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val likedIds by vm.likedIds.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val artist by vm.aboutArtist.collectAsStateWithLifecycle()
    val remoteDevice by vm.remoteDevice.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val track = state.track ?: return
    val position = rememberPlaybackPosition(state.playing) { vm.position() }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var devicesOpen by rememberSaveable { mutableStateOf(false) }

    val menus = LocalMenus.current
    val jam = LocalJam.current
    val actions = remember(vm, menus) {
        object : NowPlayingActions {
            override fun close() = onExpandedChange(false)
            override fun togglePlay() = vm.togglePlay()
            override fun next() = vm.next()
            override fun previous(force: Boolean) = vm.previous(force)
            override fun seek(ms: Long) = vm.seek(ms)
            override fun toggleShuffle() = vm.toggleShuffle()
            override fun cycleRepeat() = vm.cycleRepeat()
            override fun toggleLike(track: Track) = vm.toggleLike(track)
            override fun openQueue() {
                queueOpen = true
            }
            override fun openDevices() {
                devicesOpen = true
            }
            override fun openLyrics() {
                lyricsOpen = true
            }
            override fun openArtist(slug: String) {
                onExpandedChange(false)
                navigation.openArtist(slug)
            }
            override fun openRelease(slug: String, trackId: Long) {
                onExpandedChange(false)
                navigation.openRelease(slug, trackId)
            }
            override fun toggleFollow(artist: ArtistDto) = vm.toggleFollow(artist)
            override fun openMenu(track: Track) = menus.track(track, TrackMenuOptions(nowPlaying = true))
            override fun openSleepTimer() = menus.sleepTimer()
        }
    }

    SharedTransitionLayout(modifier) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                if (targetState) {
                    (slideInVertically(SukifyMotion.sheet()) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(220)) togetherWith (slideOutVertically(SukifyMotion.sheet()) { it / 3 } + fadeOut(tween(200)))
                }
            },
            label = "player",
        ) { open ->
            val cover = Modifier.sharedElement(rememberSharedContentState("cover"), animatedVisibilityScope = this@AnimatedContent)
            if (open) {
                NowPlayingScreen(
                    state = state,
                    liked = track.id in likedIds,
                    lyrics = lyrics,
                    artist = artist,
                    remoteDevice = remoteDevice,
                    position = { position.value },
                    actions = actions,
                    sleep = sleep,
                    sleepRemaining = vm::sleepRemaining,
                    modifier = Modifier.testTag("now_playing"),
                    coverModifier = cover,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        track = track,
                        previous = state.queue.previous,
                        next = state.queue.manual.firstOrNull()?.track ?: state.queue.upcoming.firstOrNull(),
                        playing = state.playing,
                        liked = track.id in likedIds,
                        duration = state.durationMs,
                        position = { position.value },
                        remoteDevice = remoteDevice,
                        onOpen = { onExpandedChange(true) },
                        onTogglePlay = vm::togglePlay,
                        onToggleLike = { vm.toggleLike(track) },
                        onNext = vm::next,
                        onPrevious = { vm.previous(force = true) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = miniBottomPadding),
                        coverModifier = cover,
                    )
                }
            }
        }
    }

    if (queueOpen) {
        QueueSheet(
            state = state,
            onDismiss = { queueOpen = false },
            onPlayManual = vm::playManual,
            onPlayUpcoming = vm::playUpcoming,
            onRemoveManual = vm::removeManual,
            onRemoveUpcoming = vm::removeUpcoming,
            onReorderManual = vm::reorderManual,
            onReorderUpcoming = vm::reorderUpcoming,
            onClearManual = vm::clearManual,
            remote = remoteDevice != null,
        )
    }

    if (devicesOpen) {
        val devices by vm.devices.collectAsStateWithLifecycle()
        val activeId by vm.activeDeviceId.collectAsStateWithLifecycle()
        val remoteVolume by vm.remoteVolume.collectAsStateWithLifecycle()
        DevicesSheet(
            devices = devices,
            activeId = activeId,
            remoteVolume = remoteVolume,
            onPick = { d ->
                if (d.id != activeId) vm.transferTo(d.id)
                devicesOpen = false
            },
            onVolume = vm::setRemoteVolume,
            onDismiss = { devicesOpen = false },
            onJam = {
                devicesOpen = false
                jam.open = true
            },
        )
    }

    val currentLyrics = lyrics
    if (lyricsOpen && currentLyrics != null && currentLyrics.hasAny) {
        LyricsScreen(
            track = track,
            lyrics = currentLyrics,
            playing = state.playing,
            duration = state.durationMs,
            position = { position.value },
            onSeek = vm::seek,
            onTogglePlay = vm::togglePlay,
            onClose = { lyricsOpen = false },
        )
    }
}
