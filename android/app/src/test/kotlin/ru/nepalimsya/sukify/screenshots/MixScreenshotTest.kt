package ru.nepalimsya.sukify.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.nepalimsya.sukify.data.model.BandDto
import ru.nepalimsya.sukify.data.model.BandsDto
import ru.nepalimsya.sukify.data.model.TrackAnalysisDto
import ru.nepalimsya.sukify.data.model.WaveformDto
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.playback.mix.Waveform
import ru.nepalimsya.sukify.ui.mix.MixOverview
import ru.nepalimsya.sukify.ui.mix.MixPane
import ru.nepalimsya.sukify.ui.mix.SideCurves
import ru.nepalimsya.sukify.ui.mix.TrackMix
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTheme

/** Волны редактора переходов: обзор, две панели с перекрытием, долями и кривыми «Волны». */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class MixScreenshotTest {
    private val waveRef = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("mix_reference.json")!!.readText(),
    ).jsonObject.getValue("waveform").jsonObject

    private val decoder: (String?) -> IntArray = { b64 ->
        if (b64.isNullOrEmpty()) IntArray(0) else Base64.getDecoder().decode(b64).let { b -> IntArray(b.size) { b[it].toInt() and 0xFF } }
    }

    private fun str(obj: kotlinx.serialization.json.JsonObject, key: String) = obj.getValue(key).jsonPrimitive.content

    /** Темп 128: доля каждые ~469 мс от 120 мс. */
    private val beats = (0 until 380).map { 120L + it * 60_000L / 128 }

    private fun mix(waveform: WaveformDto): TrackMix {
        val analysis = TrackAnalysisDto(id = 1, durationMs = 180_000, bpm = 128.0, beats = beats, camelot = "8A", waveform = waveform)
        return TrackMix(analysis, Waveform.of(waveform, decoder))
    }

    @Test
    fun mixPanes() {
        val bands = waveRef.getValue("bands").jsonObject
        fun band(name: String) = bands.getValue(name).jsonObject.let { BandDto(rms = str(it, "rms"), peak = str(it, "peak")) }
        val legacy = waveRef.getValue("legacy").jsonObject
        val from = mix(WaveformDto(bands = BandsDto(band("lows"), band("mids"), band("highs"))))
        val to = mix(WaveformDto(full = str(legacy, "full"), bass = str(legacy, "bass")))
        val preset = MixShapes.PRESETS.getValue("wave")
        val volume = MixShapes.volume(preset.volume!!)
        val eq = MixShapes.eq(preset.eq!!)
        val filter = MixShapes.filter(preset.filter!!)

        captureRoboImage("screenshots/mix_panes.png") {
            SukifyTheme {
                Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).padding(16.dp)) {
                    MixOverview(from, startMs = 150_000, lengthMs = 8_000, onJump = {})
                    Spacer(Modifier.height(6.dp))
                    MixPane(
                        mix = from,
                        startMs = 150_000,
                        lengthMs = 8_000,
                        zoom = 1f,
                        curves = SideCurves(volume.out, eq.out.low ?: eq.out.mid ?: eq.out.high, filter.out),
                        playhead = 0.4f,
                        onMove = { it },
                        onZoom = {},
                    )
                    Spacer(Modifier.height(24.dp))
                    MixPane(
                        mix = to,
                        startMs = 0,
                        lengthMs = 8_000,
                        zoom = 1f,
                        curves = SideCurves(volume.inn, eq.inn.low ?: eq.inn.mid ?: eq.inn.high, filter.inn),
                        playhead = null,
                        onMove = { it },
                        onZoom = {},
                    )
                    Spacer(Modifier.height(6.dp))
                    MixOverview(to, startMs = 0, lengthMs = 8_000, onJump = {})
                }
            }
        }
    }
}
