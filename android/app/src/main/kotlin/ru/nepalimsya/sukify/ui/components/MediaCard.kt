package ru.nepalimsya.sukify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.theme.Depth
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor

/** Ширина карточки в горизонтальной полке: 42% экрана, как .grid-cards в вебе. */
@Composable
fun shelfCardWidth(): Dp {
    val screen = LocalConfiguration.current.screenWidthDp
    return (screen * 0.42f).dp.coerceIn(136.dp, 190.dp)
}

/**
 * Карточка (MediaCard.vue): обложка, название, подпись. Исполнители и люди —
 * в круге. Играет её подборка — название зелёное.
 */
@Composable
fun MediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val playing = LocalPlayback.current.isContext(item.contextKey)
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .pressScale(interaction, 0.96f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
    ) {
        if (item.kind == MediaKind.Mix) {
            MixArt(item)
        } else {
            FillCover(
                item.cover,
                pxHint = 300,
                modifier = Modifier.fillMaxWidth(),
                shape = if (item.round) CircleShape else RoundedCornerShape(6.dp),
                fallbackIcon = if (item.round) SukifyIcons.Person else SukifyIcons.Album,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            style = SukifyType.BodySmallBold,
            color = if (playing) SukifyColors.Accent else SukifyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                item.subtitle,
                style = SukifyType.Caption,
                color = SukifyColors.TextSubdued,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Обложка «Микса дня»: цветной квадрат с эквалайзером и жанром (mixcard в вебе). */
@Composable
private fun MixArt(item: MediaItem) {
    val color = parseHexColor(item.color) ?: SukifyColors.CardPressed
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .background(Depth.topLight)
            .background(Depth.bottomShade)
            .background(Depth.grain),
        contentAlignment = Alignment.Center,
    ) {
        Icon(SukifyIcons.Nowplaying, null, tint = SukifyColors.Text.copy(alpha = 0.9f), modifier = Modifier.size(40.dp))
        if (!item.tag.isNullOrEmpty()) {
            Text(
                item.tag,
                style = SukifyType.LabelBold,
                color = SukifyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(SukifyColors.Base.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Полка главной/исполнителя: заголовок (+ надзаголовок), «Показать все» и
 * горизонтальная лента карточек.
 */
@Composable
fun Shelf(
    title: String,
    items: List<MediaItem>,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    onShowAll: (() -> Unit)? = null,
    onLongPress: ((MediaItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val width = shelfCardWidth()
    Column(modifier.fillMaxWidth()) {
        ShelfHeader(title, eyebrow, onShowAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.key }) { item ->
                MediaCard(
                    item = item,
                    width = width,
                    onClick = { onOpen(item) },
                    onLongClick = onLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

@Composable
fun ShelfHeader(title: String, eyebrow: String? = null, onShowAll: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = SukifyType.SectionTitle, color = SukifyColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onShowAll != null) {
            Text(
                "Показать все",
                style = SukifyType.BodySmallBold,
                color = SukifyColors.TextSubdued,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .bounceClick(pressed = 0.94f, onClick = onShowAll)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** Сетка карточек в две колонки (Секция, Жанр, Все релизы) — строка из двух. */
@Composable
fun CardPair(
    left: MediaItem,
    right: MediaItem?,
    onOpen: (MediaItem) -> Unit,
    onLongPress: ((MediaItem) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.weight(1f)) {
            MediaCard(left, onClick = { onOpen(left) }, onLongClick = onLongPress?.let { { it(left) } })
        }
        Box(Modifier.weight(1f)) {
            if (right != null) MediaCard(right, onClick = { onOpen(right) }, onLongClick = onLongPress?.let { { it(right) } })
            else Spacer(Modifier.fillMaxSize())
        }
    }
}
