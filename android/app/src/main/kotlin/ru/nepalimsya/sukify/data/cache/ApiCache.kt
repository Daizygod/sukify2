package ru.nepalimsya.sukify.data.cache

import android.content.Context
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.di.IoDispatcher

/**
 * Последний ответ API по ключу экрана: память (возврат «назад» — мгновенно)
 * + файл (холодный старт сразу показывает прошлое, пока идёт свежий запрос).
 * Это не офлайн-хранилище — им станет база загрузок; здесь просто «не
 * показывать пустой экран, если мы уже видели эти данные».
 */
@Singleton
class ApiCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    // Лениво: папку создаёт первое чтение (в IO), а не конструктор на главном потоке.
    private val dir by lazy { File(context.cacheDir, "api") }
    private val memory = LruCache<String, Any>(96)

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = memory.get(key) as T?

    suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        peek<T>(key)?.let { return it }
        return withContext(io) {
            val file = file(key)
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString(serializer, file.readText()) }
                .onSuccess { memory.put(key, it as Any) }
                .getOrNull()
        }
    }

    suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        memory.put(key, value as Any)
        withContext(io) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, file(key).name + ".tmp")
                tmp.writeText(json.encodeToString(serializer, value))
                tmp.renameTo(file(key))
            }
        }
    }

    fun forget(key: String) {
        memory.remove(key)
        runCatching { file(key).delete() }
    }

    /** Выход из аккаунта: чужие данные не должны мелькнуть следующему. */
    suspend fun clear() {
        memory.evictAll()
        withContext(io) { dir.deleteRecursively() }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    /**
     * Сначала то, что уже есть (если есть), затем свежее из сети. Ошибка сети
     * при наличии кэша не прячет данные — только помечает их.
     */
    fun <T> stream(
        key: String,
        serializer: KSerializer<T>,
        /** Без сети и без кэша — например, копия медиатеки из загрузок. */
        fallback: (suspend () -> T?)? = null,
        fetch: suspend () -> T,
    ): Flow<Loadable<T>> = flow {
        val cached = read(key, serializer)
        if (cached != null) emit(Loadable.Ready(cached, refreshing = true)) else emit(Loadable.Loading)
        try {
            val fresh = fetch()
            write(key, serializer, fresh)
            emit(Loadable.Ready(fresh))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = e.apiError.message
            val offline = if (cached == null) fallback?.invoke() else null
            when {
                cached != null -> emit(Loadable.Ready(cached, error = message))
                offline != null -> emit(Loadable.Ready(offline, error = message))
                else -> emit(Loadable.Failed(message))
            }
        }
    }
}

/** Состояние загрузки экрана. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    data class Ready<T>(val data: T, val refreshing: Boolean = false, val error: String? = null) : Loadable<T>

    data class Failed(val message: String) : Loadable<Nothing>

    val dataOrNull: T? get() = (this as? Ready<T>)?.data
}

inline fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    is Loadable.Ready -> Loadable.Ready(transform(data), refreshing, error)
    is Loadable.Failed -> this
    Loadable.Loading -> Loadable.Loading
}
