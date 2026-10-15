package ru.nepalimsya.sukify.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(name = "player")

/**
 * Последняя сессия плеера (как sukify.playerSession в вебе): после перезапуска
 * приложения тот же трек, очередь и позиция — на паузе.
 */
@Serializable
data class SavedSession(
    val queueIds: List<Long>,
    val manualIds: List<Long> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val name: String = "",
    val key: String = "",
    val shuffle: Boolean = false,
    val repeat: String = "off",
    /** Порядок до перемешивания — чтобы «вперемешку» выключалось и после перезапуска. */
    val sourceIds: List<Long> = emptyList(),
)

@Singleton
class PlayerSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    suspend fun save(session: SavedSession) {
        context.playerDataStore.edit { it[KEY] = json.encodeToString(SavedSession.serializer(), session) }
    }

    suspend fun load(): SavedSession? = context.playerDataStore.data.first()[KEY]?.let {
        runCatching { json.decodeFromString(SavedSession.serializer(), it) }.getOrNull()
    }

    suspend fun clear() {
        context.playerDataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("session")
    }
}
