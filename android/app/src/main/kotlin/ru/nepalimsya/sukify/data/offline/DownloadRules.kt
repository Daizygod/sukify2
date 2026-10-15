package ru.nepalimsya.sukify.data.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.nepalimsya.sukify.di.ApplicationScope

private val Context.downloadsDataStore: DataStore<Preferences> by preferencesDataStore(name = "downloads")

/** Где хранить загрузки: внутренняя память или карта памяти (app-specific). */
enum class StorageVolume { Phone, SdCard }

/**
 * Что качать (Настройки → Загрузки).
 *
 * Итог = (автокатегории ∪ включённые вручную) − исключённые. По умолчанию
 * автоматически не качается ничего — только то, на чём нажали ⬇.
 */
data class DownloadSettings(
    val autoLiked: Boolean = false,
    val autoAlbums: Boolean = false,
    val autoPlaylists: Boolean = false,
    val autoArtists: Boolean = false,
    /** Ключи подборок, скачанных вручную: liked, playlist:12, release:5, artist:3, track:77. */
    val includes: Set<String> = emptySet(),
    /** Ключи подборок, исключённых из автокатегорий. */
    val excludes: Set<String> = emptySet(),
    val cellular: Boolean = false,
    val previews: Boolean = true,
    val offlineMode: Boolean = false,
    val volume: StorageVolume = StorageVolume.Phone,
) {
    val anyAuto: Boolean get() = autoLiked || autoAlbums || autoPlaylists || autoArtists
}

@Singleton
class DownloadRules @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = context.downloadsDataStore

    val settings: StateFlow<DownloadSettings> = store.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, DownloadSettings())

    suspend fun current(): DownloadSettings = store.data.first().toSettings()

    suspend fun update(transform: (DownloadSettings) -> DownloadSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    /** ⬇ на подборке: включить вручную (или снять исключение из автокатегории). */
    suspend fun include(key: String) = update { it.copy(includes = it.includes + key, excludes = it.excludes - key) }

    /**
     * «Удалить из загрузок»: снять ручное включение, а если подборка качается
     * по автокатегории — исключить её.
     */
    suspend fun exclude(key: String, coveredByAuto: Boolean) = update {
        it.copy(
            includes = it.includes - key,
            excludes = if (coveredByAuto) it.excludes + key else it.excludes - key,
        )
    }

    /** Подборка пропала на сервере — забываем про неё. */
    suspend fun forget(keys: Collection<String>) = update { it.copy(includes = it.includes - keys.toSet(), excludes = it.excludes - keys.toSet()) }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun Preferences.toSettings() = DownloadSettings(
        autoLiked = this[AUTO_LIKED] ?: false,
        autoAlbums = this[AUTO_ALBUMS] ?: false,
        autoPlaylists = this[AUTO_PLAYLISTS] ?: false,
        autoArtists = this[AUTO_ARTISTS] ?: false,
        includes = this[INCLUDES].orEmpty(),
        excludes = this[EXCLUDES].orEmpty(),
        cellular = this[CELLULAR] ?: false,
        previews = this[PREVIEWS] ?: true,
        offlineMode = this[OFFLINE_MODE] ?: false,
        volume = runCatching { StorageVolume.valueOf(this[VOLUME] ?: "") }.getOrDefault(StorageVolume.Phone),
    )

    private fun MutablePreferences.write(s: DownloadSettings) {
        this[AUTO_LIKED] = s.autoLiked
        this[AUTO_ALBUMS] = s.autoAlbums
        this[AUTO_PLAYLISTS] = s.autoPlaylists
        this[AUTO_ARTISTS] = s.autoArtists
        this[INCLUDES] = s.includes
        this[EXCLUDES] = s.excludes
        this[CELLULAR] = s.cellular
        this[PREVIEWS] = s.previews
        this[OFFLINE_MODE] = s.offlineMode
        this[VOLUME] = s.volume.name
    }

    private companion object {
        val AUTO_LIKED = booleanPreferencesKey("auto_liked")
        val AUTO_ALBUMS = booleanPreferencesKey("auto_albums")
        val AUTO_PLAYLISTS = booleanPreferencesKey("auto_playlists")
        val AUTO_ARTISTS = booleanPreferencesKey("auto_artists")
        val INCLUDES = stringSetPreferencesKey("includes")
        val EXCLUDES = stringSetPreferencesKey("excludes")
        val CELLULAR = booleanPreferencesKey("cellular")
        val PREVIEWS = booleanPreferencesKey("previews")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        val VOLUME = stringPreferencesKey("volume")
    }
}
