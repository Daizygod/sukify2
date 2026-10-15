package ru.nepalimsya.sukify.ui.mix

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.model.TrackAnalysisDto
import ru.nepalimsya.sukify.data.model.TransitionDto
import ru.nepalimsya.sukify.data.model.TransitionRequest
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.playback.MediaSources
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.PlaybackSettings
import ru.nepalimsya.sukify.playback.TransitionPlan
import ru.nepalimsya.sukify.playback.mix.Camelot
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.playback.mix.Shapes
import ru.nepalimsya.sukify.playback.mix.TransitionPreview
import ru.nepalimsya.sukify.playback.mix.Waveform
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.shell.MixEditorRoute

/** Анализ трека, разобранный для редактора (волна — один раз). */
@Immutable
data class TrackMix(
    val analysis: TrackAnalysisDto,
    val waveform: Waveform?,
) {
    val durationMs: Long get() = analysis.durationMs ?: 0
    val beats: List<Long> get() = analysis.beats.orEmpty()
    val bpm: Double? get() = analysis.bpm
}

/** Состояние перехода в редакторе (MixEditor.vue). */
@Immutable
data class MixDraft(
    val preset: String = "auto",
    val volume: String = "crossfade",
    val eq: String = "none",
    val filter: String = "none",
    val bars: Int = 4,
    val outStartMs: Long = 0,
    val inStartMs: Long = 0,
    val lengthMs: Long = 6_000,
)

@Immutable
data class MixEditorUi(
    val from: Track? = null,
    val to: Track? = null,
    val fromMix: TrackMix? = null,
    val toMix: TrackMix? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val draft: MixDraft = MixDraft(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val variants: ImmutableList<TransitionDto> = persistentListOf(),
    val defaultCrossfadeSeconds: Int = 0,
) {
    val beatMatched: Boolean get() = MixShapes.beatsMatch(fromMix?.bpm, toMix?.bpm)

    val keyMatched: Boolean get() = Camelot.compatible(fromMix?.analysis?.camelot, toMix?.analysis?.camelot)

    val shapes: Shapes get() = MixShapes.resolve(draft.preset, draft.volume, draft.eq, draft.filter, beatMatched)
}

@HiltViewModel
class MixEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext context: Context,
    private val api: SukifyApi,
    private val engine: PlaybackEngine,
    private val settings: PlaybackSettings,
    sources: MediaSources,
) : ViewModel() {
    private val route = savedState.toRoute<MixEditorRoute>()

    private val _ui = MutableStateFlow(MixEditorUi(defaultCrossfadeSeconds = settings.current.crossfadeSeconds))
    val ui: StateFlow<MixEditorUi> = _ui.asStateFlow()

    val preview = TransitionPreview(context, sources) { settings.current.gainFor(it.loudnessLufs) }

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val tracks = apiCall { api.tracksBulk("${route.fromId},${route.toId}").data.map { it.toDomain() } }.getOrNull()
            val pair = apiCall { api.analysisPair(route.fromId, route.toId).data }
            val transition = apiCall { api.transition(route.fromId, route.toId).data }.getOrNull()
            pair.onFailure { e ->
                _ui.update { it.copy(loading = false, error = e.apiError.message) }
                return@launch
            }
            val analysis = pair.getOrNull()
            val fromMix = analysis?.from?.let { TrackMix(it, Waveform.of(it.waveform)) }
            val toMix = analysis?.to?.let { TrackMix(it, Waveform.of(it.waveform)) }
            _ui.update {
                it.copy(
                    from = tracks?.firstOrNull { t -> t.id == route.fromId },
                    to = tracks?.firstOrNull { t -> t.id == route.toId },
                    fromMix = fromMix,
                    toMix = toMix,
                    loading = false,
                )
            }
            apply(transition)
            _ui.update { it.copy(dirty = false) }
            loadVariants()
        }
    }

    /** Сохранённый переход → форма; нет перехода — умолчания как в вебе. */
    private fun apply(t: TransitionDto?) {
        val s = _ui.value
        if (t != null) {
            _ui.update {
                it.copy(
                    draft = MixDraft(
                        preset = t.preset ?: "custom",
                        volume = t.volumeShape ?: "crossfade",
                        eq = t.eqShape ?: "none",
                        filter = t.filterShape ?: "none",
                        bars = t.bars ?: 4,
                        outStartMs = t.fadeOutStartMs,
                        inStartMs = t.fadeInStartMs,
                        lengthMs = max(500, t.fadeOutEndMs - t.fadeOutStartMs),
                    ),
                )
            }
            return
        }
        val duration = s.fromMix?.durationMs ?: s.from?.durationMs ?: 0
        val auto = MixShapes.autoShapes(s.beatMatched)
        // Общий темп — четыре такта; иначе длина как у общего кроссфейда из настроек.
        val length = if (s.beatMatched && s.fromMix?.bpm != null) {
            (Waveform.barMs(s.fromMix.bpm) * 4).roundToLong()
        } else {
            max(1000L, (settings.current.crossfadeSeconds.takeIf { it > 0 } ?: 6) * 1000L)
        }
        _ui.update {
            it.copy(
                draft = MixDraft(
                    preset = "auto",
                    volume = auto.volume,
                    eq = auto.eq,
                    filter = auto.filter,
                    bars = 4,
                    lengthMs = length,
                    // Перекрытие — на самом хвосте уходящего и в начале нового.
                    outStartMs = max(0, duration - length),
                    inStartMs = s.toMix?.analysis?.beatOffsetMs ?: 0,
                ),
            )
        }
    }

    private fun edit(markCustom: Boolean = true, transform: (MixDraft) -> MixDraft) {
        _ui.update { s ->
            val next = transform(s.draft)
            s.copy(draft = if (markCustom) next.copy(preset = "custom") else next, dirty = true)
        }
    }

    fun pickPreset(key: String) {
        val p = MixShapes.PRESETS[key] ?: return
        val shapes = if (p.auto) MixShapes.autoShapes(_ui.value.beatMatched) else null
        edit(markCustom = false) { d ->
            d.copy(
                preset = key,
                volume = shapes?.volume ?: p.volume ?: d.volume,
                eq = shapes?.eq ?: p.eq ?: d.eq,
                filter = shapes?.filter ?: p.filter ?: d.filter,
            )
        }
    }

    fun pickVolume(key: String) = edit { it.copy(volume = key) }

    fun pickEq(key: String) = edit { it.copy(eq = key) }

    fun pickFilter(key: String) = edit { it.copy(filter = key) }

    /** Такты пересчитывают длину перекрытия по темпу уходящего трека. */
    fun setBars(bars: Int) {
        val bpm = _ui.value.fromMix?.bpm ?: return
        edit { d -> clamp(d.copy(bars = bars, lengthMs = (Waveform.barMs(bpm) * bars).roundToLong())) }
    }

    /** Границы прилипают к долям, когда темпы сходятся. Возвращает итоговое значение. */
    fun setOutStart(ms: Long): Long {
        val s = _ui.value
        val max = max(0, (s.fromMix?.durationMs ?: 0) - s.draft.lengthMs)
        val snapped = if (s.beatMatched) Waveform.snapToBeat(s.fromMix?.beats, ms) else ms
        val value = snapped.coerceIn(0, max)
        edit { it.copy(outStartMs = value) }
        return value
    }

    fun setInStart(ms: Long): Long {
        val s = _ui.value
        val max = max(0, (s.toMix?.durationMs ?: 0) - s.draft.lengthMs)
        val snapped = if (s.beatMatched) Waveform.snapToBeat(s.toMix?.beats, ms) else ms
        val value = snapped.coerceIn(0, max)
        edit { it.copy(inStartMs = value) }
        return value
    }

    private fun clamp(d: MixDraft): MixDraft {
        val s = _ui.value
        val outMax = max(0, (s.fromMix?.durationMs ?: 0) - d.lengthMs)
        val inMax = max(0, (s.toMix?.durationMs ?: 0) - d.lengthMs)
        return d.copy(outStartMs = d.outStartMs.coerceAtMost(outMax), inStartMs = d.inStartMs.coerceAtMost(inMax))
    }

    fun plan(): TransitionPlan {
        val s = _ui.value
        return TransitionPlan(
            outStartMs = s.draft.outStartMs,
            inStartMs = s.draft.inStartMs,
            lengthMs = s.draft.lengthMs,
            shapes = s.shapes,
        )
    }

    fun togglePreview() {
        if (preview.playing.value) {
            preview.stop()
            return
        }
        val s = _ui.value
        val from = s.from ?: return
        val to = s.to ?: return
        if (engine.localState.value.playing) engine.pauseLocal()
        preview.play(from, to, plan())
    }

    fun save(onDone: () -> Unit = {}) {
        val s = _ui.value
        if (s.saving) return
        val shapes = s.shapes
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val d = s.draft
            apiCall {
                api.saveTransition(
                    TransitionRequest(
                        fromTrackId = route.fromId,
                        toTrackId = route.toId,
                        fadeOutStartMs = d.outStartMs,
                        fadeOutEndMs = d.outStartMs + d.lengthMs,
                        fadeInStartMs = d.inStartMs,
                        fadeInFullVolumeMs = d.inStartMs + d.lengthMs,
                        curveType = "equal_power",
                        preset = d.preset,
                        volumeShape = shapes.volume,
                        eqShape = shapes.eq,
                        filterShape = shapes.filter,
                        bars = if (s.beatMatched) d.bars else null,
                    ),
                )
            }
                .onSuccess {
                    engine.transitionsChanged(route.fromId, route.toId)
                    Toasts.show("Переход сохранён")
                    _ui.update { it.copy(dirty = false) }
                    loadVariants()
                    onDone()
                }
                .onFailure { Toasts.show(it.apiError.message.ifBlank { "Не удалось сохранить переход" }) }
            _ui.update { it.copy(saving = false) }
        }
    }

    // --- Варианты сообщества ------------------------------------------------------

    private fun loadVariants() {
        viewModelScope.launch {
            apiCall { api.transitionsAll(route.fromId, route.toId).data }
                .onSuccess { list -> _ui.update { it.copy(variants = list.toImmutableList()) } }
        }
    }

    /** Открыть чужой вариант в редакторе: дальше его можно послушать и поправить. */
    fun pickVariant(t: TransitionDto) {
        apply(t)
        _ui.update { it.copy(dirty = true) }
    }

    fun togglePrefer(t: TransitionDto) {
        viewModelScope.launch {
            val prefer = t.isPreferred != true
            apiCall { if (prefer) api.preferTransition(t.id) else api.unpreferTransition(t.id) }
                .onSuccess {
                    _ui.update { s -> s.copy(variants = s.variants.map { v -> v.copy(isPreferred = prefer && v.id == t.id) }.toImmutableList()) }
                    engine.transitionsChanged(route.fromId, route.toId)
                    Toasts.show(if (prefer) "Теперь для тебя играет этот переход" else "Снова играет переход сообщества")
                }
                .onFailure { Toasts.show("Не получилось сохранить выбор") }
        }
    }

    fun toggleLike(t: TransitionDto) {
        viewModelScope.launch {
            val like = t.isLiked != true
            apiCall { if (like) api.likeTransition(t.id) else api.unlikeTransition(t.id) }
                .onSuccess { r ->
                    _ui.update { s ->
                        s.copy(variants = s.variants.map { v -> if (v.id == t.id) v.copy(isLiked = like, likesCount = r.likesCount) else v }.toImmutableList())
                    }
                    engine.transitionsChanged(route.fromId, route.toId)
                }
                .onFailure { Toasts.show("Не получилось проголосовать") }
        }
    }

    fun remove(t: TransitionDto) {
        viewModelScope.launch {
            apiCall { api.deleteTransition(t.id) }
                .onSuccess {
                    _ui.update { s -> s.copy(variants = s.variants.filterNot { it.id == t.id }.toImmutableList()) }
                    engine.transitionsChanged(route.fromId, route.toId)
                    Toasts.show("Переход удалён")
                }
                .onFailure { Toasts.show("Удалять можно только свои переходы") }
        }
    }

    override fun onCleared() {
        preview.release()
    }
}
