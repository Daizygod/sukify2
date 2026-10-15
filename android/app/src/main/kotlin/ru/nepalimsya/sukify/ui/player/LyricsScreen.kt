package ru.nepalimsya.sukify.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.domain.Lyrics
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * Текст на весь экран (LyricsView.vue): крупные строки в цвет обложки,
 * звучащая — белая и держится в верхней трети, тап по строке — перемотка.
 */
@Composable
fun LyricsScreen(
    track: Track,
    lyrics: Lyrics,
    playing: Boolean,
    duration: Long,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val color = lyricsCardColor(track.coverColor())
    val active by remember(lyrics) { derivedStateOf { lyrics.activeIndex(position()) } }
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().background(color).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(track.cover, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = SukifyType.BodyBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistNames, style = SukifyType.BodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(40.dp).bounceClick(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.CaretDown, "Закрыть", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        ) {
            itemsIndexed(lyrics.displayLines, key = { i, _ -> i }) { i, line ->
                LyricLineText(
                    text = line,
                    big = true,
                    state = when {
                        lyrics.synced.isEmpty() -> LineState.Plain
                        i == active -> LineState.Active
                        i < active -> LineState.Past
                        else -> LineState.Upcoming
                    },
                    onClick = lyrics.synced.getOrNull(i)?.let { l -> { onSeek(l.ms) } },
                )
            }
            if (lyrics.synced.isEmpty()) {
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            SeekBar(position = position, duration = duration, onSeek = onSeek)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(56.dp).bounceClick(pressed = 0.92f, onClick = onTogglePlay).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) SukifyIcons.PauseBig else SukifyIcons.PlayBig, null, tint = Color.Black, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}
