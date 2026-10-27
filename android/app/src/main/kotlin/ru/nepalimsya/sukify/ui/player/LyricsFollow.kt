package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * Автопрокрутка текста за песней — как в Spotify (и useLyricsFollow в вебе).
 * Пока текст никто не трогает, звучащая строка держится у верха окна. Стоило
 * потянуть список пальцем — следование выключается, и текст остаётся там,
 * куда его увели: вперёд можно читать сколько угодно, назад не «телепортирует».
 * Вернуться — кнопкой «Синхронизация» или тапом по строке.
 */
@Stable
class LyricsFollow {
    var following by mutableStateOf(true)
        internal set

    fun resync() {
        following = true
    }

    internal fun release() {
        following = false
    }
}

/**
 * [active] — звучащая строка, [lead] — сколько строк оставить над ней.
 * [key] — смена трека/текста: следим заново.
 */
@Composable
fun rememberLyricsFollow(listState: LazyListState, active: Int, key: Any?, lead: Int = 2): LyricsFollow {
    val follow = remember(key) { LyricsFollow() }
    // Свою прокрутку отличаем от пальца по намерению: перетаскивание списка
    // даёт DragInteraction, а animateScrollToItem — нет.
    LaunchedEffect(listState, follow) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow.release() }
    }
    LaunchedEffect(active, follow.following) {
        if (follow.following && active >= 0) listState.animateScrollToItem((active - lead).coerceAtLeast(0))
    }
    return follow
}

/** Белая «пилюля» «Синхронизация» — видна, пока текст уведён руками. */
@Composable
fun SyncPill(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(320)) { it / 2 } + scaleIn(tween(320), initialScale = 0.9f),
        exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 } + scaleOut(tween(220), targetScale = 0.9f),
    ) {
        Row(
            Modifier
                .padding(bottom = if (compact) 10.dp else 16.dp)
                .shadow(10.dp, CircleShape)
                .bounceClick(pressed = 0.94f, onClick = onClick)
                .background(Color.White, CircleShape)
                .height(if (compact) 34.dp else 42.dp)
                .padding(horizontal = if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SukifyIcons.Sync, null, tint = Color.Black, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Синхронизация",
                style = SukifyType.BodyBold.copy(fontSize = if (compact) 13.sp else 14.sp),
                color = Color.Black,
            )
        }
    }
}
