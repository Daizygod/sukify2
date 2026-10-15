package ru.nepalimsya.sukify.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import ru.nepalimsya.sukify.playback.mix.FilterType
import ru.nepalimsya.sukify.playback.mix.MixShapes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Огибающая по медиавремени деки: таблица значений на отрезке
 * [startUs, startUs + durationUs]; до начала — первое значение, после — последнее.
 */
class Envelope(
    val startUs: Long,
    val durationUs: Long,
    private val table: FloatArray,
) {
    fun valueAt(timeUs: Long): Float {
        if (timeUs <= startUs || durationUs <= 0) return table.first()
        if (timeUs >= startUs + durationUs) return table.last()
        val pos = (timeUs - startUs).toFloat() / durationUs * (table.size - 1)
        val i = pos.toInt()
        val f = pos - i
        val a = table[i]
        val b = table[min(i + 1, table.size - 1)]
        return a + (b - a) * f
    }

    val endUs: Long get() = startUs + durationUs

    /** Та же форма, сдвинутая во времени (переход начался позже, чем планировали). */
    fun shiftedBy(deltaUs: Long): Envelope = Envelope(startUs + deltaUs, durationUs, table)

    companion object {
        val Unity = Envelope(0, 0, floatArrayOf(1f))

        /** Из функции 0..1 → значение, выбранной [steps] точками. */
        fun sampled(startUs: Long, durationUs: Long, steps: Int = 256, f: (Float) -> Float): Envelope =
            Envelope(startUs, durationUs, FloatArray(steps) { f(it / (steps - 1f)) })
    }
}

/**
 * Обработка звука одной деки — цепочка веба (lib/mix/engine.js):
 *
 *   фильтр НЧ/ВЧ → низкая полка 200 Гц → колокол 1 кГц → высокая полка 4 кГц
 *   → громкость (нормализация × огибающая) → + шум → мягкий лимитер
 *
 * Почему всё считается здесь, по медиавремени каждого сэмпла, а не задаётся
 * снаружи «прямо сейчас»: ExoPlayer декодирует звук на ~¼ секунды вперёд.
 * Команда «тише» из главного потока прозвучала бы с этой задержкой, а начало
 * следующего трека обработалось бы ещё со старыми настройками. Здесь же
 * каждый сэмпл знает своё место в треке — переход звучит ровно там, где
 * задуман, хоть на экране, хоть с выключенным экраном.
 *
 * Медиавремя: позиция последнего сброса (её до перемотки сообщает дека) плюс
 * число обработанных кадров. Параметры приходят из главного потока через
 * @Volatile-поля; коэффициенты фильтров пересчитываются каждые 32 кадра.
 */
class DeckProcessor : BaseAudioProcessor() {

    /** Множитель нормализации трека (PlaybackSettings.gainFor). */
    @Volatile var normGain: Float = 1f

    /** Громкость, эквалайзер, фильтр и шум на время перехода. */
    @Volatile var automation: DeckAutomation = DeckAutomation.Neutral

    /** Простой кроссфейд — только громкость. */
    var envelope: Envelope
        get() = automation.gain
        set(value) {
            automation = DeckAutomation(gain = value)
        }

    /** Позиция, с которой дека начнёт играть после ближайшего сброса, мкс. */
    @Volatile var pendingFlushPositionUs: Long = 0

    /** Медиавремя последнего обработанного сэмпла — для отладки и тестов. */
    @Volatile var processedPositionUs: Long = 0
        private set

    private var sampleRate = 44_100
    private var channels = 2
    private var basePositionUs = 0L
    private var frames = 0L
    private var smoothedNorm = 1f
    private var smoothing = 1f

    private val filter = Biquad()
    private val low = Biquad()
    private val mid = Biquad()
    private val high = Biquad()
    private val noiseTone = Biquad()
    private var noiseState = 0x2545F491

    // Эквалайзер пользователя (UserEq): свои полосы у каждой деки, настройки общие.
    private val eqBands = Array(UserEq.FREQS.size) { Biquad() }
    private var eqVersion = -1
    private var eqRate = 0
    private var eqOn = false
    private var eqPre = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        smoothing = 1f - exp(-1f / (SMOOTHING_SECONDS * sampleRate))
        // Шум режем сверху, иначе он звенит поверх музыки (как в вебе: НЧ 6 кГц).
        noiseTone.passFilter(FilterType.Lowpass, 6000f, sampleRate, qDb = 1f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val auto = automation
        val env = auto.gain
        val fx = auto.hasFx
        val noise = auto.noise
        val target = normGain
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = channels * if (isFloat) 4 else 2
        val frameCount = size / bytesPerFrame
        val chMax = min(channels, 8)
        if (UserEq.version != eqVersion || eqRate != sampleRate) updateEq()
        val eq = eqOn

        for (frame in 0 until frameCount) {
            val timeUs = basePositionUs + (frames * 1_000_000L) / sampleRate
            if (fx && frames % BLOCK == 0L) updateFx(auto, timeUs)
            smoothedNorm += (target - smoothedNorm) * smoothing
            val gain = smoothedNorm * env.valueAt(timeUs)
            val n = if (noise != null) noiseTone.process(white(), 0) * noise.valueAt(timeUs) else 0f
            for (ch in 0 until channels) {
                var x = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                if (fx && ch < chMax) {
                    if (!filter.bypass) x = filter.process(x, ch)
                    if (!low.bypass) x = low.process(x, ch)
                    if (!mid.bypass) x = mid.process(x, ch)
                    if (!high.bypass) x = high.process(x, ch)
                }
                if (eq && ch < chMax) {
                    x *= eqPre
                    for (band in eqBands) if (!band.bypass) x = band.process(x, ch)
                }
                val y = limit(x * gain + n)
                if (isFloat) out.putFloat(y) else out.putShort((y * 32767f).toInt().toShort())
            }
            frames++
        }
        // Обрывок кадра (не бывает, но буфер нужно выбрать до конца).
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        processedPositionUs = basePositionUs + (frames * 1_000_000L) / sampleRate
        out.flip()
    }

    /** Коэффициенты эквалайзера и фильтра для текущего места перехода. */
    private fun updateFx(auto: DeckAutomation, timeUs: Long) {
        val type = auto.filterType
        val freq = auto.filter
        if (type != null && freq != null) {
            filter.passFilter(type, freq.valueAt(timeUs), sampleRate)
        } else {
            filter.passFilter(FilterType.Lowpass, MixShapes.OPEN_LP, sampleRate)
        }
        low.lowShelf(200f, auto.low?.valueAt(timeUs) ?: 0f, sampleRate)
        mid.peaking(1000f, auto.mid?.valueAt(timeUs) ?: 0f, 0.8f, sampleRate)
        high.highShelf(4000f, auto.high?.valueAt(timeUs) ?: 0f, sampleRate)
    }

    /** Настройки эквалайзера сменились (или частота дискретизации) — пересчитать полосы. */
    private fun updateEq() {
        val s = UserEq.settings
        eqVersion = UserEq.version
        eqRate = sampleRate
        eqOn = s.enabled
        eqPre = 10f.pow(s.preampDb / 20f)
        for ((i, band) in eqBands.withIndex()) {
            band.peaking(UserEq.FREQS[i].coerceAtMost(sampleRate / 2f - 100f), s.gainsDb.getOrElse(i) { 0f }, UserEq.Q, sampleRate)
        }
    }

    /** Белый шум −1..1 (xorshift — без аллокаций и блокировок). */
    private fun white(): Float {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        noiseState = x
        return (x.toFloat() / Int.MAX_VALUE).coerceIn(-1f, 1f)
    }

    override fun onFlush() = resetTimeline()

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = resetTimeline()

    private fun resetTimeline() {
        basePositionUs = pendingFlushPositionUs
        frames = 0
        smoothedNorm = normGain
        filter.reset()
        low.reset()
        mid.reset()
        high.reset()
        noiseTone.reset()
        eqBands.forEach { it.reset() }
    }

    override fun onReset() {
        frames = 0
        basePositionUs = 0
        smoothedNorm = 1f
    }

    companion object {
        private const val SMOOTHING_SECONDS = 0.008f

        /** Каждые столько кадров пересчитываются коэффициенты фильтров (~0,7 мс). */
        private const val BLOCK = 32L

        /** До −1 дБFS — без изменений, выше — мягкий загиб к 0 дБFS. */
        private const val KNEE = 0.891f

        /**
         * Мягкий лимитер: при усилении тихих треков (нормализация до ×4)
         * вершины не обрезаются «в квадрат», а плавно прижимаются.
         */
        fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val over = (a - KNEE) / (1f - KNEE)
            val y = KNEE + (1f - KNEE) * (over / (1f + over))
            return if (x < 0) -y else y
        }
    }
}
