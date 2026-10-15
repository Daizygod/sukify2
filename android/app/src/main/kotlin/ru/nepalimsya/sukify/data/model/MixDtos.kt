package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Редактор переходов: анализ треков (темп, доли, тональность, волна) и
 * переходы сообщества. Формат — TrackAnalysisController и TransitionResource.
 */

@Serializable
data class BandDto(val rms: String? = null, val peak: String? = null)

@Serializable
data class BandsDto(val lows: BandDto? = null, val mids: BandDto? = null, val highs: BandDto? = null)

@Serializable
data class WaveformDto(
    val buckets: Int? = null,
    /** Старый формат: вся полоса и бас, base64 байт 0..255. */
    val full: String? = null,
    val bass: String? = null,
    /** Новый формат: три полосы, у каждой тело (RMS) и гребень (пик). */
    val bands: BandsDto? = null,
)

@Serializable
data class TrackAnalysisDto(
    val id: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val bpm: Double? = null,
    @SerialName("beat_offset_ms") val beatOffsetMs: Long? = null,
    val beats: List<Long>? = null,
    @SerialName("musical_key") val musicalKey: String? = null,
    @SerialName("musical_scale") val musicalScale: String? = null,
    val camelot: String? = null,
    val waveform: WaveformDto? = null,
    val status: String? = null,
)

/** GET /analysis/pair?from&to */
@Serializable
data class AnalysisPairDto(val from: TrackAnalysisDto? = null, val to: TrackAnalysisDto? = null)

/** POST /transitions */
@Serializable
data class TransitionRequest(
    @SerialName("from_track_id") val fromTrackId: Long,
    @SerialName("to_track_id") val toTrackId: Long,
    @SerialName("fade_out_start_ms") val fadeOutStartMs: Long,
    @SerialName("fade_out_end_ms") val fadeOutEndMs: Long,
    @SerialName("fade_in_start_ms") val fadeInStartMs: Long,
    @SerialName("fade_in_full_volume_ms") val fadeInFullVolumeMs: Long,
    // Без значения по умолчанию: такие поля kotlinx.serialization не отправляет, а сервер требует.
    @SerialName("curve_type") val curveType: String,
    val preset: String,
    @SerialName("volume_shape") val volumeShape: String,
    @SerialName("eq_shape") val eqShape: String,
    @SerialName("filter_shape") val filterShape: String,
    val bars: Int? = null,
)

/** Ответ на голос за переход. */
@Serializable
data class LikesDto(@SerialName("likes_count") val likesCount: Int = 0)

/** Сводка перехода пары для чипа между строками (for-context без full). */
@Serializable
data class TransitionSummaryDto(
    val id: Long,
    val preset: String? = null,
    val bars: Int? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("is_preferred") val isPreferred: Boolean = false,
    val count: Int = 0,
)

/**
 * Ответы for-context: пара без перехода приходит null. Обёртки — конкретные
 * классы, а не DataEnvelope<Map<…, X?>>: Retrofit берёт тип ответа из
 * Java-рефлексии, где «?» у аргументов типа нет, и любой null ронял весь ответ.
 */
@Serializable
data class TransitionSummaries(val data: Map<String, TransitionSummaryDto?> = emptyMap())

@Serializable
data class FullTransitions(val data: Map<String, TransitionDto?> = emptyMap())
