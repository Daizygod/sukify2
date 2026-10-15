package ru.nepalimsya.sukify.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import ru.nepalimsya.sukify.playback.audio.EqSettings
import ru.nepalimsya.sukify.playback.audio.EqualizerStore
import ru.nepalimsya.sukify.playback.audio.UserEq
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.PillButton
import ru.nepalimsya.sukify.ui.components.SettingSwitchRow
import ru.nepalimsya.sukify.ui.components.ThinSlider
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

@HiltViewModel
class EqualizerViewModel @Inject constructor(private val store: EqualizerStore) : ViewModel() {
    val settings: StateFlow<EqSettings> = store.settings

    fun setEnabled(on: Boolean) = store.update(settings.value.copy(enabled = on))

    fun applyPreset(id: String) {
        val gains = UserEq.PRESETS[id]?.second ?: return
        store.update(settings.value.copy(enabled = true, preset = id, gainsDb = gains))
    }

    /** Тянут точку полосы — пресет становится «Своим». */
    fun setGain(band: Int, db: Float) {
        val s = settings.value
        val gains = s.gainsDb.toMutableList().also { it[band] = db.coerceIn(-UserEq.MAX_DB, UserEq.MAX_DB) }
        store.update(s.copy(enabled = true, preset = "custom", gainsDb = gains))
    }

    fun setPreamp(db: Float) = store.update(settings.value.copy(preampDb = db))

    fun reset() = store.update(EqSettings(enabled = settings.value.enabled))
}

/** Эквалайзер (сверх веба): 10 полос, пресеты и кривая под палец. */
@Composable
fun EqualizerScreen(vm: EqualizerViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()

    PageScaffold("Эквалайзер") {
        item { SettingSwitchRow("Эквалайзер", "Настраивается под твои наушники — хранится на этом телефоне", s.enabled, onChange = vm::setEnabled) }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(UserEq.PRESETS.entries.toList(), key = { it.key }) { (id, preset) ->
                    PresetChip(preset.first, on = s.enabled && s.preset == id) { vm.applyPreset(id) }
                }
                if (s.preset == "custom") item { PresetChip("Свой", on = s.enabled) {} }
            }
        }
        item {
            EqCurve(
                settings = s,
                onGain = vm::setGain,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Предусиление", style = SukifyType.Body, color = SukifyColors.Text)
                        Text("Убавь, если с подъёмом полос звук начинает хрипеть", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                    }
                    Text(db(s.preampDb), style = SukifyType.BodyBold, color = SukifyColors.Text)
                }
                ThinSlider(
                    value = s.preampDb,
                    onChange = { vm.setPreamp((it * 2).roundToInt() / 2f) },
                    range = -UserEq.MAX_DB..UserEq.MAX_DB,
                    color = if (s.enabled) SukifyColors.Accent else SukifyColors.TextMuted,
                )
            }
        }
        item {
            Row(Modifier.padding(16.dp)) { PillButton("Сбросить", onClick = vm::reset) }
        }
    }
}

@Composable
private fun PresetChip(text: String, on: Boolean, onClick: () -> Unit) {
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

private fun db(v: Float): String = when {
    abs(v) < 0.05f -> "0 дБ"
    v > 0 -> "+${"%.1f".format(v).removeSuffix(",0").removeSuffix(".0")} дБ"
    else -> "−${"%.1f".format(-v).removeSuffix(",0").removeSuffix(".0")} дБ"
}

/**
 * АЧХ эквалайзера: кривая — честный суммарный отклик полос (по тем же
 * формулам, что и звук), точки — центры полос. Тянем точку вверх-вниз,
 * шаг полдецибела, на нуле — щелчок хаптикой.
 */
@Composable
private fun EqCurve(settings: EqSettings, onGain: (Int, Float) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(settings)
    val dragging = remember { mutableIntStateOf(-1) }
    val accent = if (settings.enabled) SukifyColors.Accent else SukifyColors.TextMuted
    // Шкала — октавы от 22 Гц до 22,6 кГц: центры полос встают ровно над подписями.
    val minF = 31.25f / 1.4142135f
    val maxF = 16_000f * 1.4142135f
    fun xOf(freq: Float, width: Float) = (ln(freq / minF) / ln(maxF / minF)) * width
    fun freqAt(x: Float, width: Float) = minF * (maxF / minF).pow(x / width)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(SukifyColors.Card, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    fun bandAt(x: Float): Int {
                        var best = 0
                        var bestD = Float.MAX_VALUE
                        UserEq.FREQS.forEachIndexed { i, f ->
                            val d = abs(xOf(f, size.width.toFloat()) - x)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        return best
                    }
                    fun dbAt(y: Float): Float {
                        val pad = 18.dp.toPx()
                        val h = size.height - 2 * pad
                        val v = UserEq.MAX_DB - (y - pad) / h * 2 * UserEq.MAX_DB
                        return ((v * 2).roundToInt() / 2f).coerceIn(-UserEq.MAX_DB, UserEq.MAX_DB)
                    }
                    detectDragGestures(
                        onDragStart = { o -> dragging.intValue = bandAt(o.x) },
                        onDragEnd = { dragging.intValue = -1 },
                        onDragCancel = { dragging.intValue = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            val band = dragging.intValue.takeIf { it >= 0 } ?: return@detectDragGestures
                            val v = dbAt(change.position.y)
                            val before = current.gainsDb.getOrElse(band) { 0f }
                            if (v != before) {
                                if (v == 0f || (before < 0f) != (v < 0f)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onGain(band, v)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Двойной тап по точке — полосу в ноль.
                    detectTapGestures(onDoubleTap = { o ->
                        var best = 0
                        var bestD = Float.MAX_VALUE
                        UserEq.FREQS.forEachIndexed { i, f ->
                            val d = abs(xOf(f, size.width.toFloat()) - o.x)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        onGain(best, 0f)
                    })
                },
        ) {
            val pad = 18.dp.toPx()
            val h = size.height - 2 * pad
            fun yOf(dbValue: Float) = pad + (UserEq.MAX_DB - dbValue.coerceIn(-UserEq.MAX_DB, UserEq.MAX_DB)) / (2 * UserEq.MAX_DB) * h
            // Сетка: 0 и ±6/±12 дБ.
            for (line in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(
                    Color.White.copy(alpha = if (line == 0f) 0.22f else 0.07f),
                    Offset(0f, yOf(line)),
                    Offset(size.width, yOf(line)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // Кривая отклика — 160 точек по логарифмической шкале.
            val path = Path()
            val fill = Path()
            val steps = 160
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = yOf(UserEq.responseDb(settings, freqAt(x, size.width)))
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, yOf(0f))
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(size.width, yOf(0f))
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.04f))))
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            // Точки полос.
            UserEq.FREQS.forEachIndexed { i, f ->
                val x = xOf(f, size.width)
                val y = yOf(settings.gainsDb.getOrElse(i) { 0f })
                val active = dragging.intValue == i
                drawCircle(Color.Black.copy(alpha = 0.5f), radius = (if (active) 11 else 8).dp.toPx(), center = Offset(x, y))
                drawCircle(if (active) Color.White else accent, radius = (if (active) 9 else 6).dp.toPx(), center = Offset(x, y))
            }
        }
        // Подписи полос — под точками.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            UserEq.LABELS.forEachIndexed { i, label ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = SukifyType.Label.copy(fontSize = 10.sp), color = SukifyColors.TextSubdued, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        settings.gainsDb.getOrElse(i) { 0f }.let { if (abs(it) < 0.05f) "0" else "%+.0f".format(it).replace('-', '−') },
                        style = SukifyType.Label.copy(fontSize = 10.sp),
                        color = SukifyColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Тяни точки вверх и вниз; двойной тап — полоса в ноль.", style = SukifyType.Caption, color = SukifyColors.TextMuted)
    }
}
