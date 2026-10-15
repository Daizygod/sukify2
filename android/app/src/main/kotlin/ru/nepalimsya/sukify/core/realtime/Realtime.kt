package ru.nepalimsya.sukify.core.realtime

import android.util.Log
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectedEvent
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.DisconnectedEvent
import io.github.centrifugal.centrifuge.ErrorEvent
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.SubscribedEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.SubscriptionOptions
import io.github.centrifugal.centrifuge.SubscriptionTokenEvent
import io.github.centrifugal.centrifuge.SubscriptionTokenGetter
import io.github.centrifugal.centrifuge.TokenCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Связь с Centrifugo: личный канал пользователя `user:playback#{id}` — тот
 * же, что у веба. Сейчас по нему приходят «медиатека изменилась» и «каталог
 * изменился» (по ним сверяются загрузки), протокол Connect и джем.
 */
@Singleton
class Realtime @Inject constructor(
    private val api: SukifyApi,
    private val http: OkHttpClient,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var client: Client? = null
    private var subscription: Subscription? = null

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)

    /** Сообщения личного канала (JSON как в devices.js: {t: …}). */
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _subscribed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Подписались на личный канал (и после каждого переподключения) — пора представиться. */
    val subscribed: SharedFlow<Unit> = _subscribed.asSharedFlow()

    /** Канал пользователя — для отправки своих сообщений (Connect). */
    var channel: String? = null
        private set

    suspend fun start() = lock.withLock {
        if (client != null) return@withLock
        val auth = apiCall { api.connectionToken() }.getOrElse {
            Log.w(TAG, "connection token: ${it.message}")
            return@withLock
        }
        channel = auth.playbackChannel
        val options = Options().apply {
            token = auth.token
            setOkHttpClient(http)
            tokenGetter = object : ConnectionTokenGetter() {
                override fun getConnectionToken(event: ConnectionTokenEvent?, cb: TokenCallback) {
                    scope.launch {
                        apiCall { api.connectionToken() }
                            .onSuccess { cb.Done(null, it.token) }
                            .onFailure { cb.Done(it, null) }
                    }
                }
            }
        }
        val created = Client(auth.wsUrl, options, Listener())
        val sub = created.newSubscription(auth.playbackChannel, SubListener())
        sub.subscribe()
        created.connect()
        client = created
        subscription = sub
        debug("connecting to ${auth.wsUrl}, channel ${auth.playbackChannel}")
    }

    suspend fun stop() = lock.withLock {
        subscription?.unsubscribe()
        client?.disconnect()
        client = null
        subscription = null
        channel = null
        _connected.value = false
    }

    /**
     * Подписаться на дополнительный канал: комната джема (с токеном подписки)
     * или активность друга (без токена). null — подключения нет (нет сети на
     * старте): попробуйте, когда [connected].
     */
    suspend fun openChannel(name: String, token: (suspend () -> String)? = null): RealtimeChannel? {
        start()
        return lock.withLock {
            val c = client ?: return@withLock null
            // Остатки прошлой подписки на этот же канал — убираем, иначе дубль.
            c.getSubscription(name)?.let { old ->
                old.unsubscribe()
                c.removeSubscription(old)
            }
            val channel = RealtimeChannel(name, json)
            val options = SubscriptionOptions()
            if (token != null) options.tokenGetter = object : SubscriptionTokenGetter() {
                override fun getSubscriptionToken(event: SubscriptionTokenEvent?, cb: TokenCallback) {
                    scope.launch {
                        runCatching { token() }
                            .onSuccess { cb.Done(null, it) }
                            .onFailure { cb.Done(it, null) }
                    }
                }
            }
            val sub = c.newSubscription(name, options, channel.listener)
            channel.subscription = sub
            sub.subscribe()
            debug("channel $name: subscribing")
            channel
        }
    }

    suspend fun closeChannel(channel: RealtimeChannel) = lock.withLock {
        val sub = channel.subscription ?: return@withLock
        channel.subscription = null
        sub.unsubscribe()
        client?.removeSubscription(sub)
    }

    /** Закрыть канал без ожидания — из onCleared и других не-suspend мест. */
    fun closeLater(channel: RealtimeChannel) {
        scope.launch { closeChannel(channel) }
    }

    /** Опубликовать в свой канал (Connect, джем). */
    fun publish(data: JsonObject) {
        subscription?.publish(json.encodeToString(JsonObject.serializer(), data).toByteArray()) { _, _ -> }
    }

    private inner class Listener : EventListener() {
        override fun onConnected(client: Client?, event: ConnectedEvent?) {
            _connected.value = true
            debug("connected")
        }

        override fun onDisconnected(client: Client?, event: DisconnectedEvent?) {
            _connected.value = false
            debug("disconnected: ${event?.reason}")
        }

        override fun onError(client: Client?, event: ErrorEvent?) {
            debug("error: ${event?.error?.message}")
        }
    }

    private inner class SubListener : SubscriptionEventListener() {
        override fun onSubscribed(sub: Subscription?, event: SubscribedEvent?) {
            debug("subscribed, recovered=${event?.recovered}")
            _subscribed.tryEmit(Unit)
        }

        override fun onPublication(sub: Subscription?, event: PublicationEvent) {
            val data = runCatching { json.parseToJsonElement(String(event.data)).jsonObject }.getOrNull() ?: return
            _events.tryEmit(data)
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SukifyRealtime"
    }
}
