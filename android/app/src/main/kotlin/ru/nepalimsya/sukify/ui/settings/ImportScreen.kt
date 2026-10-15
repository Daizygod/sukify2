package ru.nepalimsya.sukify.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import ru.nepalimsya.sukify.data.importer.ImportManager
import ru.nepalimsya.sukify.data.importer.ImportUi
import ru.nepalimsya.sukify.data.model.ImportJobDto
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.PillButton
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LikedRoute
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.StatsRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class ImportViewModel @Inject constructor(private val manager: ImportManager) : ViewModel() {
    val ui: StateFlow<ImportUi> = manager.ui

    fun refresh() = manager.refresh()

    fun pick(uri: android.net.Uri) = manager.pick(uri)

    fun cancelEnrich() = manager.cancelEnrich()
}

/** Импорт из Spotify (ImportPage.vue): архив «Download your data» или CSV из Exportify. */
@Composable
fun ImportScreen(vm: ImportViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pick) }
    LaunchedEffect(Unit) { vm.refresh() }
    val busy = ui.upload != null || ui.csvBusy || ui.job?.running == true

    PageScaffold("Импорт из Spotify") {
        item {
            Text(
                "Выбери архив «Download your data» целиком — Sukify заведёт артистов, альбомы и треки, перенесёт «Любимые», плейлисты и все годы прослушиваний, а обложки и 30-секундные превью догрузит из Deezer.",
                style = SukifyType.BodySmall,
                color = SukifyColors.TextSubdued,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Выбор файла / ход загрузки.
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    ui.upload != null -> {
                        Text("Загружаю архив… ${((ui.upload ?: 0f) * 100).toInt()}%", style = SukifyType.BodyBold, color = SukifyColors.Text)
                        Spacer(Modifier.height(12.dp))
                        Progress(ui.upload ?: 0f)
                        Spacer(Modifier.height(8.dp))
                        Text("Можно уйти с экрана — загрузка продолжится.", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                    }
                    ui.csvBusy -> Text("Читаю CSV…", style = SukifyType.BodyBold, color = SukifyColors.Text)
                    else -> {
                        Icon(SukifyIcons.Install, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Выбрать файл",
                            style = SukifyType.BodyBold,
                            color = if (busy) SukifyColors.TextMuted else Color.Black,
                            modifier = Modifier
                                .bounceClick(enabled = !busy, pressed = 0.95f) {
                                    picker.launch(arrayOf("application/zip", "application/json", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
                                }
                                .background(if (busy) SukifyColors.Chip else SukifyColors.Accent, CircleShape)
                                .padding(horizontal = 22.dp, vertical = 11.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(".zip целиком, или отдельный YourLibrary.json / CSV из Exportify", style = SukifyType.Caption, color = SukifyColors.TextSubdued, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        ui.error?.let { error -> item { Text(error, style = SukifyType.BodySmall, color = Color(0xFFF15E6C), modifier = Modifier.padding(horizontal = 16.dp)) } }
        ui.csv?.let { r ->
            item {
                Panel {
                    Text("Лайки из CSV", style = SukifyType.BodyBold, color = SukifyColors.Text)
                    Text("Добавлено ${r.added} из ${r.total}, уже было ${r.already}. Остальных треков нет в каталоге.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                }
            }
        }
        ui.job?.let { job -> item { JobPanel(job, onLiked = { navigator.open(LikedRoute) }, onStats = { navigator.open(StatsRoute(spotify = true)) }, onCancel = vm::cancelEnrich) } }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Как получить архив", style = SukifyType.Title, color = SukifyColors.Text)
                Step(1, "Открой spotify.com/account/privacy.")
                Step(2, "Отметь «Данные аккаунта» — это библиотека, плейлисты и год истории.")
                Step(3, "Ниже отметь «Расширенная история прослушиваний», если хочешь всю статистику с самого начала.")
                Step(4, "Подтверди запрос письмом. Первый архив приходит за пару дней, расширенный — до месяца.")
                Step(5, "Пришедший ZIP выбери здесь — распаковывать не надо.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Аудио у импортированных треков — 30-секундное превью Deezer: полные версии Spotify не отдаёт никому. Нужны только лайки? Выгрузи «Liked Songs» на exportify.net и выбери CSV — найденное в каталоге сразу попадёт в «Любимые».",
                    style = SukifyType.Caption,
                    color = SukifyColors.TextSubdued,
                )
            }
        }
    }
}

@Composable
private fun JobPanel(job: ImportJobDto, onLiked: () -> Unit, onStats: () -> Unit, onCancel: () -> Unit) {
    Panel {
        when {
            job.running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = SukifyColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(job.stage ?: "Работаю…", style = SukifyType.BodyBold, color = SukifyColors.Text)
                }
                Spacer(Modifier.height(10.dp))
                Progress(job.progress / 100f)
                if (job.status == "enriching") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Библиотека и плейлисты уже на месте — можно слушать. Обложки и превью приезжают по ходу: ${Format.number(job.enrichDone)} из ${Format.number(job.enrichTotal)}.",
                        style = SukifyType.BodySmall,
                        color = SukifyColors.TextSubdued,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Открыть «Любимые»", onClick = onLiked)
                        PillButton("Остановить догрузку", onClick = onCancel)
                    }
                }
            }
            job.status == "failed" -> {
                Text("Импорт сорвался", style = SukifyType.BodyBold, color = Color(0xFFF15E6C))
                Text(job.error ?: "Не удалось разобрать архив.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
            }
            else -> {
                val lib = job.summary?.library
                val history = job.summary?.history
                Text(
                    if (job.status == "canceled") "Импорт остановлен" else if (lib != null || history != null) "Перенесено" else "Разобрал архив",
                    style = SukifyType.BodyBold,
                    color = SukifyColors.Text,
                )
                job.originalName?.let { Text(it, style = SukifyType.Caption, color = SukifyColors.TextSubdued) }
                job.summary?.note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                }
                if (lib != null) {
                    Spacer(Modifier.height(10.dp))
                    val stats = listOf(
                        lib.likedTracks to "в «Любимых»",
                        lib.playlists to Format.plural(lib.playlists, "плейлист", "плейлиста", "плейлистов"),
                        lib.tracksCreated to "новых треков",
                        lib.artistsCreated to Format.plural(lib.artistsCreated, "артист", "артиста", "артистов"),
                        lib.releasesCreated to Format.plural(lib.releasesCreated, "альбом", "альбома", "альбомов"),
                        lib.followedArtists to "подписок",
                    )
                    stats.chunked(3).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            row.forEachIndexed { i, (n, label) ->
                                Column(Modifier.weight(1f)) {
                                    // «Любимые» — главное, что переехало: зелёным, как в вебе.
                                    Text(Format.number(n), style = SukifyType.Title, color = if (r == 0 && i == 0) SukifyColors.Accent else SukifyColors.Text)
                                    Text(label, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                                }
                            }
                        }
                    }
                }
                if (history != null && history.plays > 0) {
                    Spacer(Modifier.height(10.dp))
                    val hours = history.ms / 3_600_000
                    val years = listOfNotNull(history.from?.take(4), history.to?.take(4)).distinct().joinToString("–")
                    Text(
                        "История: ${Format.number(history.plays)} ${Format.plural(history.plays, "прослушивание", "прослушивания", "прослушиваний")} за $years — ${Format.number(hours)} ${Format.plural(hours, "час", "часа", "часов")} музыки.",
                        style = SukifyType.BodySmall,
                        color = SukifyColors.Text,
                    )
                    Spacer(Modifier.height(8.dp))
                    PillButton("Открыть «Твой Spotify»", primary = true, onClick = onStats)
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(SukifyColors.Card, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun Progress(value: Float) {
    val shown by animateFloatAsState(value.coerceIn(0f, 1f), SukifyMotion.standard(), label = "imp")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(shown).height(6.dp).background(SukifyColors.Accent, CircleShape))
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Text("$n.", style = SukifyType.BodySmallBold, color = SukifyColors.TextSubdued, modifier = Modifier.width(22.dp))
        Text(text, style = SukifyType.BodySmall, color = SukifyColors.Text)
    }
}
