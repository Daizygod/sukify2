package ru.nepalimsya.sukify.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.domain.Lyrics
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.SleepState
import ru.nepalimsya.sukify.ui.components.FillCover
import ru.nepalimsya.sukify.ui.components.LikeButton
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format
import kotlin.coroutines.cancellation.CancellationException

/** Действия экрана «Сейчас играет» — чтобы экран рисовался и без ViewModel. */
interface NowPlayingActions {
    fun close()
    fun togglePlay()
    fun next()
    fun previous(force: Boolean)
    fun seek(ms: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun toggleLike(track: Track)
    fun openQueue()
    fun openDevices()

    fun openSleepTimer() {}
    fun openLyrics()
    fun openArtist(slug: String)
    fun toggleFollow(artist: ArtistDto)
    fun openMenu(track: Track)
}

/**
 * «Сейчас играет» — порт MobileNowPlaying.vue: фон из цвета обложки,
 * карусель обложек со свайпом, живая строка текста, перемотка, кнопки,
 * карточки «Текст» и «Об исполнителе». Тянется вниз пальцем и
 * сворачивается предиктивным «назад».
 */
@Composable
fun NowPlayingScreen(
    state: PlayerState,
    liked: Boolean,
    lyrics: Lyrics?,
    artist: ArtistDto?,
    remoteDevice: String?,
    position: () -> Long,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    sleep: SleepState = SleepState.Off,
    sleepRemaining: () -> Long? = { null },
) {
    val track = state.track ?: return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val dragY = remember { Animatable(0f) }
    var backProgress by remember { mutableStateOf(0f) }

    // Предиктивный «назад»: экран чуть уменьшается за пальцем, отпустил — сворачиваемся.
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backProgress = it.progress }
            actions.close()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val color by animateColorAsState(
        // На пульте — цвет обложки, когда полный трек уже подтянули; до тех пор — как в вебе.
        track.coverColor(if (remoteDevice != null) RemoteColor else SukifyColors.NowPlayingFallback),
        SukifyMotion.colorFade(),
        label = "npColor",
    )

    val dismissDrag = rememberDraggableState { delta ->
        scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragY.value
                val s = 1f - backProgress * 0.08f
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape((backProgress * 32).dp)
                clip = backProgress > 0f
            }
            .background(
                Brush.verticalGradient(
                    0f to color,
                    0.38f to nowPlayingMid(color),
                    0.72f to SukifyColors.Elevated,
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Шапка тянет экран вниз — как ручка шита.
            Row(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = dismissDrag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dragY.value > screenHeight * 0.18f || velocity > 1800f) {
                                actions.close()
                            } else {
                                dragY.animateTo(0f, SukifyMotion.sheet(), initialVelocity = velocity)
                            }
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderButton(SukifyIcons.CaretDown, "Свернуть") { actions.close() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (remoteDevice != null) "ИГРАЕТ НА УСТРОЙСТВЕ" else "ИГРАЕТ ИЗ",
                        style = SukifyType.Tiny.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Normal),
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        remoteDevice ?: state.queue.contextName.ifBlank { "Трек" },
                        style = SukifyType.LabelBold.copy(fontSize = 13.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                HeaderButton(SukifyIcons.More, "Меню") { actions.openMenu(track) }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = min(maxWidth, LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                    val px = with(density) { side.roundToPx() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .draggable(
                                state = dismissDrag,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (dragY.value > screenHeight * 0.18f || velocity > 1800f) {
                                        actions.close()
                                    } else {
                                        dragY.animateTo(0f, SukifyMotion.sheet(), initialVelocity = velocity)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        SwipeStrip(
                            previous = state.queue.previous,
                            current = track,
                            next = state.queue.manual.firstOrNull()?.track ?: state.queue.upcoming.firstOrNull(),
                            trackKey = track.id,
                            onNext = actions::next,
                            onPrevious = { actions.previous(force = true) },
                        ) { t ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                FillCover(
                                    cover = t?.cover,
                                    pxHint = px,
                                    modifier = Modifier
                                        .width(side)
                                        .then(if (t?.id == track.id) coverModifier else Modifier)
                                        .shadow(24.dp, RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    }
                }

                LiveLyricLine(lyrics, position, Modifier.padding(top = 18.dp))
                Spacer(Modifier.height(if (lyrics?.synced?.isNotEmpty() == true) 8.dp else 28.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = SukifyType.PlayerTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Row {
                            track.artists.forEachIndexed { i, a ->
                                Text(
                                    a.name + if (i < track.artists.lastIndex) ", " else "",
                                    style = SukifyType.Body,
                                    color = SukifyColors.TextSubdued,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.bounceClick(pressed = 0.97f) { actions.openArtist(a.slug) },
                                )
                            }
                        }
                    }
                    if (remoteDevice == null) {
                        LikeButton(liked = liked, onToggle = { actions.toggleLike(track) }, size = 26.dp, tint = SukifyColors.TextSubdued)
                    }
                }

                SeekBar(position = position, duration = state.durationMs, onSeek = actions::seek, modifier = Modifier.padding(top = 12.dp))

                PlayerControls(
                    playing = state.playing,
                    buffering = state.buffering,
                    shuffle = state.queue.shuffle,
                    repeat = state.queue.repeat,
                    onShuffle = actions::toggleShuffle,
                    onPrevious = { actions.previous(force = false) },
                    onPlay = actions::togglePlay,
                    onNext = actions::next,
                    onRepeat = actions::cycleRepeat,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.bounceClick(onClick = actions::openDevices).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(SukifyIcons.Devices, "Устройства", tint = if (remoteDevice != null) SukifyColors.Accent else SukifyColors.TextSubdued, modifier = Modifier.size(18.dp))
                        if (remoteDevice != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(remoteDevice, style = SukifyType.LabelBold, color = SukifyColors.Accent)
                        }
                    }
                    if (sleep != SleepState.Off) SleepBadge(sleep, sleepRemaining, onClick = actions::openSleepTimer)
                    Box(Modifier.bounceClick(onClick = actions::openQueue).padding(8.dp)) {
                        Icon(SukifyIcons.Queue, "Очередь", tint = SukifyColors.TextSubdued, modifier = Modifier.size(18.dp))
                    }
                }

                if (lyrics?.hasAny == true) {
                    LyricsCard(
                        lyrics = lyrics,
                        color = color,
                        position = position,
                        onSeek = actions::seek,
                        onExpand = actions::openLyrics,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                if (artist != null) {
                    AboutArtistCard(
                        artist = artist,
                        onOpen = { actions.openArtist(artist.slug) },
                        onToggleFollow = { actions.toggleFollow(artist) },
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).bounceClick(pressed = 0.85f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Таймер сна идёт: луна и обратный отсчёт (или «до конца трека»); тап — изменить. */
@Composable
private fun SleepBadge(sleep: SleepState, remaining: () -> Long?, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(remaining() ?: 0L) }
    LaunchedEffect(sleep) {
        while (true) {
            now = remaining() ?: 0L
            delay(1_000)
        }
    }
    Row(
        Modifier.bounceClick(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SukifyIcons.Moon, "Таймер сна", tint = SukifyColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (sleep == SleepState.EndOfTrack) "до конца трека" else Format.duration(now),
            style = SukifyType.LabelBold,
            color = SukifyColors.Accent,
        )
    }
}
