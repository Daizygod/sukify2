package ru.nepalimsya.sukify.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.playback.PlaybackEngine

private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui")

/** Запись «Недавних запросов» поиска: карточка или трек. */
@Serializable
data class RecentSearch(
    val kind: String,
    val ref: String,
    val title: String,
    val subtitle: String,
    val image: String? = null,
    val round: Boolean = false,
    val track: TrackDto? = null,
) {
    val key: String get() = "$kind:$ref"
}

/**
 * Мелкие настройки интерфейса, которые живут только на телефоне: вид
 * медиатеки, недавние запросы, что уже видели в «Что нового», когда
 * последний раз играла подборка (сортировка «Недавние»).
 */
@Singleton
class UiPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val store = context.uiDataStore
    private var contextsJob: Job? = null

    val librarySort: StateFlow<String> = store.data.map { it[LIBRARY_SORT] ?: "recent" }
        .stateIn(scope, SharingStarted.Eagerly, "recent")

    val libraryGrid: StateFlow<Boolean> = store.data.map { it[LIBRARY_GRID] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val bellSeen: Flow<String> = store.data.map { it[BELL_SEEN].orEmpty() }

    val recentSearches: StateFlow<List<RecentSearch>> = store.data
        .map { prefs -> prefs[RECENT_SEARCHES]?.let { runCatching { json.decodeFromString(recentSerializer, it) }.getOrNull() }.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Ключ подборки → когда она последний раз начинала играть. */
    val recentContexts: StateFlow<Map<String, Long>> = store.data
        .map { prefs -> prefs[RECENT_CONTEXTS]?.let { runCatching { json.decodeFromString(contextsSerializer, it) }.getOrNull() }.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun setLibrarySort(value: String) = edit { it[LIBRARY_SORT] = value }

    fun setLibraryGrid(value: Boolean) = edit { it[LIBRARY_GRID] = value }

    fun markBellSeen(date: String) = edit { it[BELL_SEEN] = date }

    fun addRecentSearch(entry: RecentSearch) = edit { prefs ->
        val list = (listOf(entry) + recentSearches.value.filterNot { it.key == entry.key }).take(20)
        prefs[RECENT_SEARCHES] = json.encodeToString(recentSerializer, list)
    }

    fun removeRecentSearch(key: String) = edit { prefs ->
        prefs[RECENT_SEARCHES] = json.encodeToString(recentSerializer, recentSearches.value.filterNot { it.key == key })
    }

    fun clearRecentSearches() = edit { it.remove(RECENT_SEARCHES) }

    /** Следим за плеером: новая подборка — отметка времени для «Недавних». */
    fun trackContexts(engine: PlaybackEngine) {
        if (contextsJob?.isActive == true) return
        contextsJob = scope.launch {
            engine.localState.map { it.queue.contextKey }.distinctUntilChanged().drop(1).collect { key ->
                if (key.isEmpty()) return@collect
                edit { prefs ->
                    val map = (recentContexts.value + (key to System.currentTimeMillis()))
                        .entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
                    prefs[RECENT_CONTEXTS] = json.encodeToString(contextsSerializer, map)
                }
            }
        }
    }

    /** Выход из аккаунта: чужие запросы и подборки не нужны следующему. */
    fun clearPersonal() = edit {
        it.remove(RECENT_SEARCHES)
        it.remove(RECENT_CONTEXTS)
        it.remove(BELL_SEEN)
    }

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { store.edit { block(it) } }
    }

    private companion object {
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_GRID = booleanPreferencesKey("library_grid")
        val BELL_SEEN = stringPreferencesKey("bell_seen")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        val RECENT_CONTEXTS = stringPreferencesKey("recent_contexts")
        val recentSerializer = ListSerializer(RecentSearch.serializer())
        val contextsSerializer = MapSerializer(String.serializer(), Long.serializer())
    }
}
