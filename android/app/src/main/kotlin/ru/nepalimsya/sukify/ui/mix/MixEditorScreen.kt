package ru.nepalimsya.sukify.ui.mix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.model.TransitionDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.mix.Camelot
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.ConfirmDialog
import ru.nepalimsya.sukify.ui.shell.HideChrome
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** «Изменение перехода» — порт MixEditor.vue под пальцы. */
@Composable
fun MixEditorScreen(vm: MixEditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val previewing by vm.preview.playing.collectAsStateWithLifecycle()
    val playhead by vm.preview.playhead.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var confirmLeave by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val leave = {
        vm.preview.stop()
        navigator.back()
    }
    BackHandler(enabled = ui.dirty) { confirmLeave = true }
    HideChrome()

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).bounceClick(pressed = 0.88f) { if (ui.dirty) confirmLeave = true else leave() },
                contentAlignment = Alignment.Center,
            ) { Icon(SukifyIcons.Close, "Закрыть", tint = SukifyColors.Text, modifier = Modifier.size(18.dp)) }
            Text("Изменение перехода", style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.padding(start = 4.dp))
            Text(
                "Бета",
                style = SukifyType.Tiny,
                color = SukifyColors.Elevated,
                modifier = Modifier.padding(start = 8.dp).background(SukifyColors.TextSubdued, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .bounceClick(enabled = !ui.saving && !ui.loading, pressed = 0.94f) { vm.save() }
                    .background(if (ui.saving) SukifyColors.Accent.copy(alpha = 0.5f) else SukifyColors.Accent, CircleShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(if (ui.saving) "Сохраняем…" else "Сохранить", style = SukifyType.BodySmallBold, color = Color.Black)
            }
        }

        when {
            ui.loading -> LoadingBlock()
            ui.error != null && ui.fromMix == null -> ErrorBlock(ui.error.orEmpty(), onRetry = vm::load)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > 700.dp
                if (wide) {
                    // Альбомная: волны слева, управление справа.
                    Row(Modifier.fillMaxSize().navigationBarsPadding()) {
                        Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Waves(ui, vm, zoom, { zoom = it }, previewing, playhead)
                        }
                        Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                            Controls(ui, vm)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Waves(ui, vm, zoom, { zoom = it }, previewing, playhead)
                        }
                        Controls(ui, vm)
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Отменить изменения?",
            text = "Если выйти сейчас, изменения перехода не сохранятся.",
            confirm = "Выйти",
            onConfirm = {
                confirmLeave = false
                leave()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** Две волны с обзорами, сдвиги, предпрослушка и такты. */
@Composable
private fun ColumnScope.Waves(
    ui: MixEditorUi,
    vm: MixEditorViewModel,
    zoom: Float,
    onZoom: (Float) -> Unit,
    previewing: Boolean,
    playhead: Float?,
) {
    val shapes = ui.shapes
    val volume = MixShapes.volume(shapes.volume)
    val eq = MixShapes.eq(shapes.eq)
    val filter = MixShapes.filter(shapes.filter)
    val d = ui.draft
    val changeZoom: (Float) -> Unit = { k -> onZoom((zoom * k).coerceIn(0.25f, 4f)) }

    TrackHeader(ui.from, ui.fromMix, ui.beatMatched)
    Spacer(Modifier.height(8.dp))
    MixOverview(ui.fromMix, d.outStartMs, d.lengthMs, onJump = { vm.setOutStart(it) })
    Spacer(Modifier.height(6.dp))
    MixPane(
        mix = ui.fromMix,
        startMs = d.outStartMs,
        lengthMs = d.lengthMs,
        zoom = zoom,
        curves = SideCurves(volume.out, eq.out.low ?: eq.out.mid ?: eq.out.high, filter.out),
        playhead = playhead,
        onMove = vm::setOutStart,
        onZoom = changeZoom,
    )
    Nudges(onNudge = { delta -> vm.setOutStart(d.outStartMs + delta) })

    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Такты — только когда темпы сходятся.
        if (ui.beatMatched) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MixShapes.BAR_OPTIONS, key = { it }) { bars ->
                    Text(
                        if (bars == 1) "1 bar" else "$bars bars",
                        style = SukifyType.LabelBold,
                        color = if (bars == d.bars) Color.Black else SukifyColors.Text,
                        modifier = Modifier
                            .bounceClick(pressed = 0.92f) { vm.setBars(bars) }
                            .background(if (bars == d.bars) SukifyColors.Text else SukifyColors.Chip, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        } else {
            Text(
                "${"%.1f".format(d.lengthMs / 1000.0)} с",
                style = SukifyType.LabelBold,
                color = SukifyColors.TextSubdued,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .size(52.dp)
                .bounceClick(pressed = 0.9f, haptic = true) { vm.togglePreview() }
                .background(SukifyColors.Text, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (previewing) SukifyIcons.Pause else SukifyIcons.Play, if (previewing) "Остановить" else "Послушать переход", tint = Color.Black, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
    }

    MixPane(
        mix = ui.toMix,
        startMs = d.inStartMs,
        lengthMs = d.lengthMs,
        zoom = zoom,
        curves = SideCurves(volume.inn, eq.inn.low ?: eq.inn.mid ?: eq.inn.high, filter.inn),
        playhead = playhead,
        onMove = vm::setInStart,
        onZoom = changeZoom,
    )
    Nudges(onNudge = { delta -> vm.setInStart(d.inStartMs + delta) })
    Spacer(Modifier.height(6.dp))
    MixOverview(ui.toMix, d.inStartMs, d.lengthMs, onJump = { vm.setInStart(it) })
    Spacer(Modifier.height(8.dp))
    TrackHeader(ui.to, ui.toMix, ui.beatMatched)
}

/** Трек над/под волной: обложка, название, темп и тональность. */
@Composable
private fun TrackHeader(track: Track?, mix: TrackMix?, beatMatched: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverImage(track?.cover, size = 40.dp, shape = RoundedCornerShape(3.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track?.title.orEmpty(), style = SukifyType.BodySmallBold, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artistNames.orEmpty(), style = SukifyType.Caption, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier
                .padding(start = 8.dp)
                .border(1.dp, if (beatMatched) SukifyColors.TextMuted else SukifyColors.Warning, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!beatMatched) {
                Icon(SukifyIcons.Warning, null, tint = SukifyColors.Warning, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(mix?.bpm?.let { "${Math.round(it)} BPM" } ?: "— BPM", style = SukifyType.Tiny, color = if (beatMatched) SukifyColors.TextSubdued else SukifyColors.Warning)
        }
        mix?.analysis?.camelot?.let { key ->
            Text(
                key,
                style = SukifyType.Tiny,
                color = Color(Camelot.TEXT),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(Color(0xFF000000 or (Camelot.color(key)?.toLong() ?: 0xB3B3B3L)), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** ±1 с и ±10 мс вместо клавиатуры; удержание — повтор. */
@Composable
private fun Nudges(onNudge: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, delta) in listOf("−1 с" to -1000L, "−10 мс" to -10L, "+10 мс" to 10L, "+1 с" to 1000L)) {
            RepeatButton(label, Modifier.weight(1f)) { onNudge(delta) }
        }
    }
}

@Composable
private fun RepeatButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .height(34.dp)
            .background(SukifyColors.Chip, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            onClick()
                            delay(70)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.LabelBold, color = SukifyColors.Text)
    }
}

/** Пресеты, списки эффектов, предупреждения и варианты сообщества. */
@Composable
private fun Controls(ui: MixEditorUi, vm: MixEditorViewModel) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val d = ui.draft
    val shapes = ui.shapes

    if (ui.keyMatched) {
        Text(
            "Тональности сочетаются — переход прозвучит чисто.",
            style = SukifyType.Caption,
            color = SukifyColors.Accent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    Text("Пресеты", style = SukifyType.BodySmallBold, color = SukifyColors.TextSubdued, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
    val presets = buildList {
        if (d.preset == "custom") add("custom")
        addAll(MixShapes.PRESET_ORDER)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = { it }) { key ->
            val on = key == d.preset
            Row(
                Modifier
                    .bounceClick(pressed = 0.94f) { vm.pickPreset(key) }
                    .background(if (on) SukifyColors.Text else SukifyColors.Chip, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (key == "auto") {
                    Icon(SukifyIcons.Sparkle, null, tint = if (on) Color.Black else SukifyColors.Text, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(MixShapes.PRESETS[key]?.title.orEmpty(), style = SukifyType.BodySmall, color = if (on) Color.Black else SukifyColors.Text)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    EffectRow("Громкость", MixShapes.volume(shapes.volume).title, MixShapes.COLOR_VOLUME) { sheet = "volume" }
    EffectRow("Эквалайзер", MixShapes.eq(shapes.eq).title, MixShapes.COLOR_EQ) { sheet = "eq" }
    EffectRow("Фильтр", MixShapes.filter(shapes.filter).title, MixShapes.COLOR_FILTER) { sheet = "filter" }

    if (!ui.beatMatched) {
        Text(
            "Ритмы треков не сходятся, поэтому длину нельзя задать в тактах — двигай волны вручную.",
            style = SukifyType.Caption,
            color = SukifyColors.TextSubdued,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }

    Text("Варианты для этой пары", style = SukifyType.BodySmallBold, color = SukifyColors.TextSubdued, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp))
    if (ui.variants.isEmpty()) {
        val hint = if (ui.defaultCrossfadeSeconds > 0) {
            "Пока для этой пары играет общий кроссфейд из настроек — ${ui.defaultCrossfadeSeconds} с."
        } else {
            "Пока треки идут подряд без перехода: общий кроссфейд в настройках выключен."
        }
        Text("$hint Сохрани свой переход — и для этой пары будет играть он.", style = SukifyType.Caption, color = SukifyColors.TextSubdued, modifier = Modifier.padding(horizontal = 16.dp))
    }
    ui.variants.forEachIndexed { i, t -> VariantRow(t, i == 0, ui.fromMix?.durationMs ?: 0, vm) }

    sheet?.let { which ->
        val (title, options, selected) = when (which) {
            "volume" -> Triple("Громкость", MixShapes.VOLUME.mapValues { it.value.title }, shapes.volume)
            "eq" -> Triple("Эквалайзер", MixShapes.EQ.mapValues { it.value.title }, shapes.eq)
            else -> Triple("Фильтр", MixShapes.FILTER.mapValues { it.value.title }, shapes.filter)
        }
        ShapeSheet(title, options, selected, onPick = { key ->
            when (which) {
                "volume" -> vm.pickVolume(key)
                "eq" -> vm.pickEq(key)
                else -> vm.pickFilter(key)
            }
            sheet = null
        }, onDismiss = { sheet = null })
    }
}

@Composable
private fun EffectRow(title: String, value: String, color: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(Color(color), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(title, style = SukifyType.Body, color = SukifyColors.Text)
        Spacer(Modifier.width(12.dp))
        Text(value, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(SukifyIcons.CaretDown, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun ShapeSheet(title: String, options: Map<String, String>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = SukifyColors.CardPressed) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, style = SukifyType.Title, color = SukifyColors.Text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            for ((key, label) in options) {
                Row(
                    Modifier.fillMaxWidth().bounceClick(pressed = 0.98f) { onPick(key) }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = SukifyType.Body, color = if (key == selected) SukifyColors.Accent else SukifyColors.Text, modifier = Modifier.weight(1f))
                    if (key == selected) Icon(SukifyIcons.Check, null, tint = SukifyColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Вариант сообщества: открыть, «использовать этот», голос, удалить свой. */
@Composable
private fun VariantRow(t: TransitionDto, first: Boolean, fromDurationMs: Long, vm: MixEditorViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val length = (t.fadeOutEndMs - t.fadeOutStartMs) / 1000.0
    val before = ((fromDurationMs - t.fadeOutStartMs).coerceAtLeast(0)) / 1000.0
    val description = listOfNotNull(
        "%.1f с".format(length),
        "за %.1f с до конца".format(before),
        t.bars?.let { "$it такт." },
    ).joinToString(" · ")
    val title = MixShapes.PRESETS[t.preset]?.title ?: MixShapes.VOLUME[t.volumeShape]?.title ?: "Свой вариант"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(if (t.isPreferred == true) SukifyColors.Accent.copy(alpha = 0.12f) else SukifyColors.Card, RoundedCornerShape(8.dp))
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).bounceClick(pressed = 0.98f) { vm.pickVariant(t) }.padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
                when {
                    t.isPreferred == true -> Badge("Твой выбор", SukifyColors.Accent)
                    first -> Badge("Основной", SukifyColors.TextMuted)
                }
            }
            Text(description, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
        }
        VariantButton(SukifyIcons.CheckCircle, if (t.isPreferred == true) SukifyColors.Accent else SukifyColors.TextSubdued) { vm.togglePrefer(t) }
        Row(
            Modifier.bounceClick(pressed = 0.9f) { vm.toggleLike(t) }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (t.isLiked == true) SukifyIcons.HeartFill else SukifyIcons.Heart, null, tint = if (t.isLiked == true) SukifyColors.Accent else SukifyColors.TextSubdued, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("${t.likesCount}", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
        }
        if (t.isMine == true) {
            VariantButton(SukifyIcons.Close, SukifyColors.TextSubdued) { confirmDelete = true }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить этот переход?",
            text = "Он пропадёт и для всех, кто его выбрал.",
            confirm = "Удалить",
            onConfirm = {
                confirmDelete = false
                vm.remove(t)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = SukifyType.Tiny,
        color = SukifyColors.Elevated,
        modifier = Modifier.padding(start = 6.dp).background(color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun VariantButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).bounceClick(pressed = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

