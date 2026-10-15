package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.domain.Lyrics
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

/**
 * Живая строка текста под обложкой: старая уезжает вверх и тает, новая
 * приходит снизу — как в приложении Spotify и в вебе.
 */
@Composable
fun LiveLyricLine(lyrics: Lyrics?, position: () -> Long, modifier: Modifier = Modifier) {
    if (lyrics == null || lyrics.synced.isEmpty()) return
    val index by remember(lyrics) { derivedStateOf { lyrics.activeIndex(position()) } }
    Box(modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                    (slideOutVertically(tween(320)) { -it / 2 } + fadeOut(tween(260)))
            },
            label = "liveLine",
        ) { i ->
            val text = lyrics.synced.getOrNull(i)?.text?.ifBlank { "♪" }.orEmpty()
            Text(
                text,
                style = SukifyType.BodyBold.copy(fontSize = SukifyType.Title.fontSize),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Карточка «Текст» под плеером: активная строка подсвечена и держится по центру. */
@Composable
fun LyricsCard(
    lyrics: Lyrics,
    color: Color,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by remember(lyrics) { derivedStateOf { lyrics.activeIndex(position()) } }
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    val lines = lyrics.displayLines
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(lyricsCardColor(color))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Текст", style = SukifyType.BodyBold, color = Color.White, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape).bounceClick(onClick = onExpand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SukifyIcons.Expand, "Развернуть", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            userScrollEnabled = true,
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                LyricLineText(
                    text = line,
                    state = when {
                        lyrics.synced.isEmpty() -> LineState.Plain
                        i == active -> LineState.Active
                        i < active -> LineState.Past
                        else -> LineState.Upcoming
                    },
                    onClick = lyrics.synced.getOrNull(i)?.let { line -> { onSeek(line.ms) } },
                )
            }
        }
    }
}

enum class LineState { Plain, Past, Active, Upcoming }

@Composable
fun LyricLineText(text: String, state: LineState, onClick: (() -> Unit)?, big: Boolean = false) {
    val color by animateColorAsState(
        when (state) {
            LineState.Active, LineState.Plain -> Color.White
            LineState.Past -> Color.Black.copy(alpha = 0.45f)
            LineState.Upcoming -> Color.White.copy(alpha = 0.55f)
        },
        tween(250),
        label = "lyricLine",
    )
    Text(
        text.ifBlank { " " },
        style = SukifyType.Title.copy(fontSize = if (big) SukifyType.Hero.fontSize * 0.7f else SukifyType.Title.fontSize * 1.1f),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = if (big) 8.dp else 4.dp),
    )
}

/** Карточка «Об исполнителе»: баннер, слушатели за месяц, подписка. */
@Composable
fun AboutArtistCard(artist: ArtistDto, onOpen: () -> Unit, onToggleFollow: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1F1F1F))
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            val image = artist.bannerUrl ?: artist.avatarUrl
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(SukifyColors.CardPressed), contentAlignment = Alignment.Center) {
                    Icon(SukifyIcons.Person, null, tint = SukifyColors.TextMuted, modifier = Modifier.size(48.dp))
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent)),
            )
            Text(
                "Об исполнителе",
                style = SukifyType.BodyBold,
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(artist.name, style = SukifyType.BodyBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(Format.listeners(artist.monthlyListeners), style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
            }
            val following = artist.isFollowed == true
            Text(
                if (following) "Уже подписаны" else "Подписаться",
                style = SukifyType.BodySmallBold,
                color = Color.White,
                modifier = Modifier
                    .bounceClick(onClick = onToggleFollow)
                    .border(1.dp, if (following) Color.White else SukifyColors.TextSubdued, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
