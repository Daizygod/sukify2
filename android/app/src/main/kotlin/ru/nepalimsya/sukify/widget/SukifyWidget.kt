package ru.nepalimsya.sukify.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.nepalimsya.sukify.MainActivity
import ru.nepalimsya.sukify.R
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlaybackConnection
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.ui.theme.parseHexColor

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun engine(): PlaybackEngine
    fun library(): LibraryState
    fun imageLoader(): ImageLoader
    fun connection(): PlaybackConnection
}

/**
 * Виджет плеера (сверх веба): обложка, трек, ⏮ ▶/⏸ ⏭ и ♥ на фоне цвета
 * обложки. Три раскладки по размеру: 2×2 — обложка во всю ширину,
 * 4×1 — строка, 4×2 — обложка слева и кнопки справа. Данные берёт прямо
 * у плеера и следит за ними, пока живёт сессия виджета.
 */
class SukifyWidget : GlanceAppWidget() {
    // Точный размер: обложка в 4×2 и больше растёт вместе с виджетом.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val engine = entry.engine()
        val library = entry.library()
        // Процесс подняли ради виджета — лайки с диска, пока не пришли с сервера.
        if (!library.ready.value) runCatching { library.restoreCached() }
        if (engine.localState.value.track == null) withContext(Dispatchers.Main) { runCatching { engine.restoreSession() } }
        provideContent {
            val state by engine.localState.collectAsState()
            val liked by library.likedIds.collectAsState()
            val track = state.track
            val cover by produceState<Bitmap?>(null, track?.id) {
                value = track?.cover?.url(300)?.let { loadBitmap(context, entry.imageLoader(), it) }
            }
            Content(track, state.playing, track != null && track.id in liked, cover)
        }
    }

    private suspend fun loadBitmap(context: Context, loader: ImageLoader, url: String): Bitmap? = runCatching {
        val result = loader.execute(ImageRequest.Builder(context).data(url).size(300).allowHardware(false).build())
        (result as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}

class SukifyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SukifyWidget()
}

/** Кнопки виджета: своему плееру; процесс могли поднять ради нажатия — тогда поднимаем и сессию. */
class WidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[KEY] ?: return
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        withContext(Dispatchers.Main) {
            val engine = entry.engine()
            // Сервис воспроизведения — чтобы музыка жила в фоне без открытого приложения.
            entry.connection().connect()
            if (engine.localState.value.track == null) runCatching { engine.restoreSession() }
            when (action) {
                TOGGLE -> engine.local { togglePlay() }
                NEXT -> engine.local { next() }
                PREV -> engine.local { previous() }
                LIKE -> engine.toggleLikeCurrent()
            }
        }
    }

    companion object {
        val KEY = ActionParameters.Key<String>("action")
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val LIKE = "like"
    }
}

private fun action(name: String) = actionRunCallback<WidgetAction>(actionParametersOf(WidgetAction.KEY to name))

private val White = ColorProvider(Color.White)
private val Subdued = ColorProvider(Color(0xFFB3B3B3))

@Composable
private fun Content(track: Track?, playing: Boolean, liked: Boolean, cover: Bitmap?) {
    val size = LocalSize.current
    // Как мини-плеер: 60% цвета обложки поверх #181818.
    val bg = ColorProvider(lerp(Color(0xFF181818), parseHexColor(track?.release?.color) ?: Color(0xFF3A3A3A), 0.6f))
    val open = actionStartActivity<MainActivity>()
    if (track == null) {
        Box(
            GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF181818))).cornerRadius(16.dp).clickable(open).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sukify — включи что-нибудь", style = TextStyle(color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 2)
        }
        return
    }
    val root = GlanceModifier.fillMaxSize().background(bg).cornerRadius(16.dp)
    when {
        size.height < 90.dp -> Row(root.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, 44, GlanceModifier.clickable(open))
            Spacer(GlanceModifier.width(10.dp))
            Titles(track, GlanceModifier.defaultWeight().clickable(open))
            Controls(playing, small = true)
        }
        size.width < 200.dp -> Column(root.padding(10.dp).clickable(open)) {
            Cover(cover, 56)
            Spacer(GlanceModifier.height(8.dp))
            Titles(track, GlanceModifier.fillMaxWidth())
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Like(liked)
                Spacer(GlanceModifier.defaultWeight())
                PlayButton(playing)
            }
        }
        else -> Row(root.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Обложка — во всю высоту виджета (но не шире трети).
            val coverDp = (size.height.value - 20f).coerceIn(56f, minOf(160f, size.width.value / 3f)).toInt()
            Cover(cover, coverDp, GlanceModifier.clickable(open))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Titles(track, GlanceModifier.defaultWeight().clickable(open))
                    Like(liked)
                }
                Spacer(GlanceModifier.height(8.dp))
                Controls(playing, small = false)
            }
        }
    }
}

@Composable
private fun Cover(cover: Bitmap?, sizeDp: Int, modifier: GlanceModifier = GlanceModifier) {
    val provider = cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.widget_play)
    Image(
        provider = provider,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(sizeDp.dp).cornerRadius(6.dp).background(ColorProvider(Color(0xFF282828))),
    )
}

@Composable
private fun Titles(track: Track, modifier: GlanceModifier) {
    Column(modifier) {
        Text(track.title, style = TextStyle(color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(track.artistNames, style = TextStyle(color = Subdued, fontSize = 12.sp), maxLines = 1)
    }
}

@Composable
private fun Controls(playing: Boolean, small: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(R.drawable.widget_prev, "Предыдущий", WidgetAction.PREV, if (small) 34 else 40)
        PlayButton(playing)
        IconButton(R.drawable.widget_next, "Следующий", WidgetAction.NEXT, if (small) 34 else 40)
    }
}

@Composable
private fun PlayButton(playing: Boolean) {
    Box(
        GlanceModifier.size(40.dp).background(White).cornerRadius(20.dp).clickable(action(WidgetAction.TOGGLE)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (playing) R.drawable.widget_pause else R.drawable.widget_play),
            contentDescription = if (playing) "Пауза" else "Играть",
            modifier = GlanceModifier.size(20.dp),
        )
    }
}

@Composable
private fun Like(liked: Boolean) {
    IconButton(if (liked) R.drawable.widget_heart_fill else R.drawable.widget_heart, if (liked) "Убрать из любимых" else "В любимые", WidgetAction.LIKE, 36)
}

@Composable
private fun IconButton(icon: Int, description: String, name: String, boxDp: Int) {
    Box(GlanceModifier.size(boxDp.dp).clickable(action(name)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), contentDescription = description, modifier = GlanceModifier.size(18.dp))
    }
}
