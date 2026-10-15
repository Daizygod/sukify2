package ru.nepalimsya.sukify.core.realtime

import io.github.centrifugal.centrifuge.HistoryOptions
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.SubscribedEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.UnsubscribedEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Дополнительный канал Centrifugo с токеном подписки — комната джема
 * `session:{id}`. Живёт поверх общего подключения [Realtime].
 */
class RealtimeChannel internal constructor(val name: String, private val json: Json) {
    internal var subscription: Subscription? = null

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val _subscribed = MutableStateFlow(false)

    /** Подписка жива — сообщения доходят. */
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    private val _joins = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Подписались (и после каждого переподключения). */
    val joins: SharedFlow<Unit> = _joins.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun publish(data: JsonObject) {
        subscription?.publish(json.encodeToString(JsonObject.serializer(), data).toByteArray()) { _, _ -> }
    }

    /** Последние сообщения канала, новые первыми; без истории — пусто. */
    suspend fun history(limit: Int): List<JsonObject> {
        val sub = subscription ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            val options = HistoryOptions.Builder().withLimit(limit).withReverse(true).build()
            sub.history(options) { error, result ->
                val list = if (error != null || result == null) {
                    emptyList()
                } else {
                    result.publications.mapNotNull { p -> runCatching { json.parseToJsonElement(String(p.data)).jsonObject }.getOrNull() }
                }
                if (cont.isActive) cont.resume(list)
            }
        }
    }

    internal val listener = object : SubscriptionEventListener() {
        override fun onSubscribed(sub: Subscription?, event: SubscribedEvent?) {
            _subscribed.value = true
            _joins.tryEmit(Unit)
        }

        override fun onUnsubscribed(sub: Subscription?, event: UnsubscribedEvent?) {
            _subscribed.value = false
        }

        override fun onError(sub: Subscription?, event: SubscriptionErrorEvent?) {
            _subscribed.value = false
            _errors.tryEmit(event?.error?.message.orEmpty())
        }

        override fun onPublication(sub: Subscription?, event: PublicationEvent) {
            runCatching { json.parseToJsonElement(String(event.data)).jsonObject }.getOrNull()?.let { _events.tryEmit(it) }
        }
    }
}
