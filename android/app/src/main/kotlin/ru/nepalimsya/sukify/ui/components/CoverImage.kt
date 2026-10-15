package ru.nepalimsya.sukify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors

/**
 * Обложка: берём рендишн под реальный размер на экране (64…1000 px), пока
 * грузится — подложка цветом обложки, чтобы ничего не «вспыхивало».
 */
@Composable
fun CoverImage(
    cover: Cover?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholder: Color = SukifyColors.CardPressed,
    fallbackIcon: ImageVector = SukifyIcons.Album,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val url = cover?.url(px)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .size(px)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(fallbackIcon, contentDescription = null, tint = SukifyColors.TextMuted, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** Квадратная обложка на всю ширину (карусель «Сейчас играет», шапки). */
@Composable
fun FillCover(
    cover: Cover?,
    pxHint: Int,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    fallbackIcon: ImageVector = SukifyIcons.Album,
) {
    val url = cover?.url(pxHint)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(SukifyColors.CardPressed),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).size(pxHint).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(fallbackIcon, contentDescription = null, tint = SukifyColors.TextMuted, modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

/** Круглая картинка артиста/профиля. */
@Composable
fun AvatarImage(url: String?, size: Dp, modifier: Modifier = Modifier) {
    CoverImage(
        cover = Cover.single(url),
        size = size,
        modifier = modifier,
        shape = CircleShape,
        fallbackIcon = SukifyIcons.Person,
    )
}
