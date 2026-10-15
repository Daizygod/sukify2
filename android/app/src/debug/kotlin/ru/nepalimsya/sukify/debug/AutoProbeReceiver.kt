package ru.nepalimsya.sukify.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.util.Log
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.nepalimsya.sukify.playback.PlaybackService
import androidx.media3.session.MediaBrowser as Media3Browser

/**
 * Только для отладки: Android Auto без машины. Подключается к PlaybackService
 * так же, как машина, — старым MediaBrowser — и пишет в logcat (тег AutoProbe):
 *
 *   adb shell am broadcast -n <пакет>/ru.nepalimsya.sukify.debug.AutoProbeReceiver --es cmd tree
 *   … --es cmd children --es id home         — раздел
 *   … --es cmd play --es id "t:12|liked"     — выбор трека
 *   … --es cmd voice --es q "daft punk"      — «Включи …» голосом
 *   … --es cmd search --es q "daft punk"     — поиск в машине
 */
class AutoProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: "tree"
        val id = intent.getStringExtra("id").orEmpty()
        val query = intent.getStringExtra("q").orEmpty()
        CoroutineScope(Dispatchers.Main).launch {
            val browser = connect(app)
            try {
                if (browser == null) {
                    log("не подключились к сервису")
                    return@launch
                }
                log("корень: ${browser.root} extras=${browser.extras?.keySet()?.associateWith { browser.extras?.get(it) }}")
                when (cmd) {
                    "tree" -> {
                        val tabs = children(browser, browser.root)
                        for (tab in tabs) {
                            val items = children(browser, tab.mediaId!!)
                            log("[${tab.mediaId}] ${tab.description.title}: ${items.size} шт.")
                            items.take(4).forEach { log("    ${describe(it)}") }
                            items.firstOrNull { it.isBrowsable }?.let { first ->
                                val inner = children(browser, first.mediaId!!)
                                log("    [${first.mediaId}] внутри: ${inner.size} шт.")
                                inner.take(3).forEach { log("        ${describe(it)}") }
                            }
                        }
                    }
                    "children" -> children(browser, id).forEach { log(describe(it)) }
                    "search" -> search(app, query)
                    "play", "voice" -> {
                        val controller = MediaController(app, browser.sessionToken)
                        if (cmd == "play") controller.transportControls.playFromMediaId(id, Bundle()) else controller.transportControls.playFromSearch(query, Bundle())
                        delay(4000)
                        val m = controller.metadata
                        log("играет: «${m?.description?.title}» — ${m?.description?.subtitle}; состояние ${controller.playbackState?.state}, позиция ${controller.playbackState?.position}; очередь ${controller.queue?.size}")
                    }
                }
            } finally {
                browser?.disconnect()
                pending.finish()
            }
        }
    }

    private fun describe(item: MediaBrowser.MediaItem): String {
        val d = item.description
        val kind = if (item.isPlayable) "▶" else "▸"
        return "$kind ${item.mediaId} «${d.title}» ${d.subtitle ?: ""} icon=${d.iconUri}"
    }

    private suspend fun connect(context: Context): MediaBrowser? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { cont ->
            var browser: MediaBrowser? = null
            browser = MediaBrowser(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = cont.resume(browser)

                    override fun onConnectionFailed() = cont.resume(null)
                },
                null,
            )
            browser.connect()
        }
    }

    private suspend fun children(browser: MediaBrowser, parent: String): List<MediaBrowser.MediaItem> = withTimeoutOrNull(8000) {
        suspendCancellableCoroutine { cont ->
            browser.subscribe(
                parent,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                        browser.unsubscribe(parent)
                        if (cont.isActive) cont.resume(children)
                    }

                    override fun onError(parentId: String) {
                        log("ошибка раздела $parentId")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                },
            )
        }
    } ?: emptyList<MediaBrowser.MediaItem>().also { log("раздел $parent не ответил") }

    /** Поиск в старом MediaBrowser не доступен — спрашиваем через Media3, как новые машины. */
    private suspend fun search(context: Context, query: String) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val browser = await(Media3Browser.Builder(context, token).buildAsync())
        try {
            await(browser.search(query, null))
            val result = await(browser.getSearchResult(query, 0, 50, null))
            log("поиск «$query»: ${result.value?.size ?: result.resultCode}")
            result.value?.forEach { log("    ▶ ${it.mediaId} «${it.mediaMetadata.title}» ${it.mediaMetadata.artist} icon=${it.mediaMetadata.artworkUri}") }
        } finally {
            browser.release()
        }
    }

    private suspend fun <T> await(future: ListenableFuture<T>): T = suspendCancellableCoroutine { cont ->
        future.addListener({ cont.resume(future.get()) }, MoreExecutors.directExecutor())
    }

    private fun log(message: String) {
        Log.i("AutoProbe", message)
    }
}
