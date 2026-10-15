package ru.nepalimsya.sukify.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.model.SpotifyStatsDto
import ru.nepalimsya.sukify.data.model.StatBucketDto
import ru.nepalimsya.sukify.data.model.StatsDto
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.ArtistRoute
import ru.nepalimsya.sukify.ui.shell.ImportRoute
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.StatsRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class StatsViewModel @Inject constructor(savedState: SavedStateHandle, private val api: SukifyApi) : ViewModel() {
    private val _spotifyTab = MutableStateFlow(savedState.toRoute<StatsRoute>().spotify)
    val spotifyTab: StateFlow<Boolean> = _spotifyTab.asStateFlow()

    private val _sukify = MutableStateFlow<Loadable<StatsDto>>(Loadable.Loading)
    val sukify: StateFlow<Loadable<StatsDto>> = _sukify.asStateFlow()

    private val _spotify = MutableStateFlow<Loadable<SpotifyStatsDto>>(Loadable.Loading)
    val spotify: StateFlow<Loadable<SpotifyStatsDto>> = _spotify.asStateFlow()

    private val _year = MutableStateFlow<Int?>(null)
    val year: StateFlow<Int?> = _year.asStateFlow()

    /** Часы суток считаются в местном поясе — на сервере, как в вебе. */
    val timezone: String = TimeZone.getDefault().id

    init {
        loadSukify()
        loadSpotify()
    }

    fun showSpotify(on: Boolean) {
        _spotifyTab.value = on
    }

    fun setYear(year: Int?) {
        if (_year.value == year) return
        _year.value = year
        loadSpotify()
    }

    fun loadSukify() {
        viewModelScope.launch {
            apiCall { api.stats() }
                .onSuccess { _sukify.value = Loadable.Ready(it) }
                .onFailure { _sukify.value = Loadable.Failed(it.message ?: "Не удалось загрузить") }
        }
    }

    fun loadSpotify() {
        viewModelScope.launch {
            apiCall { api.spotifyStats(timezone, _year.value) }
                .onSuccess { _spotify.value = Loadable.Ready(it) }
                .onFailure { if (_spotify.value !is Loadable.Ready) _spotify.value = Loadable.Failed(it.message ?: "Не удалось загрузить") }
        }
    }
}

/** Статистика: «В Sukify» (StatsPage.vue) и «Из Spotify» (SpotifyStatsPage.vue). */
@Composable
fun StatsScreen(vm: StatsViewModel = hiltViewModel()) {
    val spotifyTab by vm.spotifyTab.collectAsStateWithLifecycle()
    val sukify by vm.sukify.collectAsStateWithLifecycle()
    val spotify by vm.spotify.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    PageScaffold("Статистика") {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab("В Sukify", !spotifyTab) { vm.showSpotify(false) }
                Tab("Из Spotify", spotifyTab) { vm.showSpotify(true) }
            }
        }
        if (!spotifyTab) {
            val data = sukify.dataOrNull
            when {
                data == null && sukify is Loadable.Failed -> item { ErrorBlock((sukify as Loadable.Failed).message, onRetry = vm::loadSukify) }
                data == null -> item { LoadingBlock() }
                else -> {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Твоя статистика", style = SukifyType.PageTitle, color = SukifyColors.Text)
                            Text("За последние 30 дней", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                        }
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Tile(Format.number(data.totalPlays), Format.plural(data.totalPlays, "прослушивание", "прослушивания", "прослушиваний"), Modifier.weight(1f))
                            Tile(Format.number(data.minutes), "${Format.plural(data.minutes, "минута", "минуты", "минут")} музыки", Modifier.weight(1f))
                        }
                    }
                    if (data.topTracks.isNotEmpty()) {
                        item { Heading("Топ треков") }
                        data.topTracks.forEachIndexed { i, t ->
                            item(key = "st$i") {
                                RankRow(
                                    rank = i + 1,
                                    cover = Cover.of(t.track.cover),
                                    title = t.track.title,
                                    subtitle = t.track.artists.orEmpty().joinToString(", ") { it.name },
                                    value = "${t.plays} ${Format.plural(t.plays, "раз", "раза", "раз")}",
                                )
                            }
                        }
                    }
                    if (data.topArtists.isNotEmpty()) {
                        item { Heading("Топ исполнителей") }
                        data.topArtists.forEachIndexed { i, a ->
                            item(key = "sa$i") {
                                RankRow(
                                    rank = i + 1,
                                    cover = Cover.single(a.artist.avatarUrl ?: a.artist.bannerUrl),
                                    round = true,
                                    title = a.artist.name,
                                    value = "${a.plays} ${Format.plural(a.plays, "раз", "раза", "раз")}",
                                    onClick = { navigator.open(ArtistRoute(a.artist.slug)) },
                                )
                            }
                        }
                    }
                    if (data.totalPlays == 0L) item { Note("Пока нечего показать — послушай что-нибудь!") }
                }
            }
        } else {
            val data = spotify.dataOrNull
            when {
                data == null && spotify is Loadable.Failed -> item { ErrorBlock((spotify as Loadable.Failed).message, onRetry = vm::loadSpotify) }
                data == null -> item { LoadingBlock() }
                !data.hasData -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Твой Spotify", style = SukifyType.PageTitle, color = SukifyColors.Text)
                        Spacer(Modifier.height(8.dp))
                        Note(
                            "История прослушиваний ещё не перенесена. Загрузи архив Spotify в импорте — и здесь появятся все твои годы: топы, часы и по каким устройствам ты слушал.",
                            padding = false,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Открыть импорт",
                            style = SukifyType.BodyBold,
                            color = Color.Black,
                            modifier = Modifier.bounceClick { navigator.open(ImportRoute) }.background(SukifyColors.Accent, CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
                else -> spotifyContent(data, year, vm, navigator)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.spotifyContent(
    data: SpotifyStatsDto,
    year: Int?,
    vm: StatsViewModel,
    navigator: ru.nepalimsya.sukify.ui.shell.Navigator,
) {
    val totals = data.totals ?: return
    val period = year?.toString() ?: data.years.let { ys ->
        if (ys.isEmpty()) "" else if (ys.first().year == ys.last().year) "${ys.first().year}" else "${ys.first().year}–${ys.last().year}"
    }
    item { Text("Твой Spotify", style = SukifyType.PageTitle, color = SukifyColors.Text, modifier = Modifier.padding(horizontal = 16.dp)) }
    item {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Tab("Все годы", year == null) { vm.setYear(null) } }
            items(data.years, key = { it.year }) { y -> Tab(y.year.toString(), year == y.year) { vm.setYear(y.year) } }
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val h = hours(totals.ms)
            Text(Format.number(h), style = SukifyType.Hero.copy(fontSize = 56.sp), color = SukifyColors.Accent)
            Text("${Format.plural(h, "час", "часа", "часов")} музыки за $period", style = SukifyType.Body, color = SukifyColors.Text)
        }
    }
    item {
        val tiles = listOf(
            "Прослушиваний" to Format.number(totals.plays),
            "Разных треков" to Format.number(totals.tracks),
            "Разных артистов" to Format.number(totals.artists),
            "Дослушано до конца" to "${percent(totals.fullPlays, totals.plays)}%",
            "Включено вперемешку" to "${percent(totals.shuffled, totals.plays)}%",
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, value) -> Tile(value, label, Modifier.weight(1f), small = true) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (data.topArtists.isNotEmpty()) {
        item { Heading("Топ артистов") }
        val max = data.topArtists.maxOf { it.ms }.coerceAtLeast(1)
        data.topArtists.forEachIndexed { i, a ->
            item(key = "spa$i") {
                RankRow(
                    rank = i + 1,
                    cover = Cover.single(a.imageUrl),
                    round = true,
                    title = a.name,
                    bar = a.ms.toFloat() / max,
                    value = listened(a.ms),
                    onClick = a.slug?.let { slug -> { navigator.open(ArtistRoute(slug)) } },
                )
            }
        }
    }
    if (data.topTracks.isNotEmpty()) {
        item { Heading("Топ треков") }
        data.topTracks.forEachIndexed { i, t ->
            item(key = "spt$i") {
                RankRow(
                    rank = i + 1,
                    cover = Cover.of(t.cover),
                    title = t.title,
                    subtitle = t.artist,
                    value = "${Format.number(t.plays)} ${Format.plural(t.plays, "раз", "раза", "раз")}",
                )
            }
        }
    }
    if (data.byHour.isNotEmpty()) {
        item { Heading("Когда ты слушаешь") }
        item {
            val peak = data.byHour.maxByOrNull { it.ms }
            Note("Часы по местному времени (${vm.timezone}).${peak?.let { " Пик — в ${"%02d".format(it.bucket)}:00." }.orEmpty()}")
            Bars(data.byHour, labels = data.byHour.map { if (it.bucket % 4 == 0) "%02d".format(it.bucket) else "" })
        }
    }
    if (data.byWeekday.isNotEmpty()) {
        item { Heading("По дням недели") }
        item {
            val peak = data.byWeekday.indices.maxByOrNull { data.byWeekday[it].ms }
            peak?.let { Note("Больше всего — в ${WEEKDAYS[it]}.") }
            Bars(data.byWeekday, labels = WEEKDAYS)
        }
    }
    val months = year != null
    val rows = if (months) data.byMonth else data.years.map { StatBucketDto(it.year, it.plays, it.ms) }
    if (rows.isNotEmpty()) {
        item { Heading(if (months) "По месяцам" else "По годам") }
        item {
            Note("Высота столбика — часы прослушивания.")
            Bars(rows, labels = if (months) MONTHS else rows.map { it.bucket.toString().takeLast(2).let { y -> "’$y" } })
        }
    }
    if (data.platforms.isNotEmpty()) {
        item { Heading("С чего слушал") }
        val max = data.platforms.maxOf { it.ms }.coerceAtLeast(1)
        data.platforms.forEach { p ->
            item(key = "pl${p.platform}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(PLATFORMS[p.platform] ?: p.platform, style = SukifyType.BodySmall, color = SukifyColors.Text, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f))) {
                        Box(Modifier.fillMaxWidth(p.ms.toFloat() / max).height(6.dp).background(SukifyColors.Accent, CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(listened(p.ms), style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                }
            }
        }
    }
    item {
        Note("Считается по истории из архива Spotify: ${Format.number(totals.plays)} ${Format.plural(totals.plays, "прослушивание", "прослушивания", "прослушиваний")} за $period.")
    }
}

private val WEEKDAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val MONTHS = listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")
private val PLATFORMS = mapOf(
    "windows" to "Windows",
    "android" to "Android",
    "ios" to "iPhone / iPad",
    "mac" to "Mac",
    "playstation" to "PlayStation",
    "xbox" to "Xbox",
    "web" to "Браузер",
    "cast" to "Chromecast",
    "linux" to "Linux",
    "partner" to "Партнёрские устройства",
    "other" to "Другое",
)

private fun hours(ms: Long): Long = (ms / 3_600_000.0).roundToLong()

/** «371 ч» либо «14 мин» — часы врут о мелочах, минуты о больших числах. */
private fun listened(ms: Long): String = if (hours(ms) >= 1) "${Format.number(hours(ms))} ч" else "${(ms / 60_000.0).roundToLong()} мин"

private fun percent(part: Long, total: Long): Long = if (total > 0) (part * 100.0 / total).roundToLong() else 0

@Composable
private fun Tab(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = SukifyType.BodySmallBold,
        color = if (on) Color.Black else SukifyColors.Text,
        modifier = Modifier
            .bounceClick(pressed = 0.94f, onClick = onClick)
            .background(if (on) SukifyColors.Accent else SukifyColors.Chip, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = SukifyType.SectionTitle, color = SukifyColors.Text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp))
}

@Composable
private fun Note(text: String, padding: Boolean = true) {
    Text(text, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = if (padding) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier)
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier.background(SukifyColors.Card, RoundedCornerShape(10.dp)).padding(14.dp)) {
        if (small) {
            Text(label, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
            Text(value, style = SukifyType.Title, color = SukifyColors.Text)
        } else {
            Text(value, style = SukifyType.Hero.copy(fontSize = 32.sp), color = SukifyColors.Text)
            Text(label, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
        }
    }
}

@Composable
private fun RankRow(
    rank: Int,
    cover: Cover?,
    title: String,
    value: String,
    subtitle: String? = null,
    round: Boolean = false,
    bar: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick(pressed = 0.98f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = SukifyType.BodyBold, color = SukifyColors.TextSubdued, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.width(8.dp))
        CoverImage(
            cover,
            size = 44.dp,
            shape = if (round) CircleShape else RoundedCornerShape(4.dp),
            fallbackIcon = if (round) SukifyIcons.Person else SukifyIcons.Album,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            bar?.let { f ->
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                    Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(4.dp).background(SukifyColors.Accent, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(value, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
    }
}

/** Столбики от общего максимума (иначе высота ничего не значит); пик — зелёный. */
@Composable
private fun Bars(rows: List<StatBucketDto>, labels: List<String>) {
    val max = rows.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    val peak = rows.indices.maxByOrNull { rows[it].ms } ?: -1
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / rows.size
            // Столбиков мало (два года) — не раздуваем их в плитки.
            val w = (slot * 0.68f).coerceIn(2f, 28.dp.toPx())
            val radius = (w / 4).coerceAtMost(4.dp.toPx())
            rows.forEachIndexed { i, r ->
                val f = r.ms.toFloat() / max
                val h = if (r.ms > 0) (size.height * f).coerceAtLeast(3f) else 0f
                drawRoundRect(
                    color = if (i == peak) SukifyColors.Accent else Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(radius),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.take(rows.size).forEach { label ->
                Text(label, style = SukifyType.Label.copy(fontSize = 10.sp), color = SukifyColors.TextSubdued, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
