package ru.nepalimsya.sukify.ui.shell

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ссылки на сайт (App Links): «Поделиться» в вебе, приглашение в совместный
 * плейлист, код привязки компьютера — открываются нужным экраном приложения.
 */
object DeepLinks {
    const val HOST = "sukify.nepalimsya.ru"

    private val _pending = MutableStateFlow<Any?>(null)

    /** Экран, который надо открыть, когда оболочка готова. */
    val pending: StateFlow<Any?> = _pending.asStateFlow()

    fun offer(url: String?) {
        route(url)?.let { _pending.value = it }
    }

    fun consume() {
        _pending.value = null
    }

    /** Адрес сайта → маршрут приложения; null — не наш адрес или такого экрана нет. */
    fun route(url: String?): Any? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        if (uri.host != HOST) return null
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
        val id = parts.getOrNull(1)
        return when (parts.firstOrNull()) {
            "release" -> id?.let(::ReleaseRoute)
            "artist" -> id?.let { slug ->
                when (parts.getOrNull(2)) {
                    "discography" -> DiscographyRoute(slug)
                    "liked" -> ArtistLikedRoute(slug)
                    else -> ArtistRoute(slug)
                }
            }
            "playlist" -> id?.toLongOrNull()?.let { PlaylistRoute(it, join = query["join"]) }
            "user" -> id?.let(::ProfileRoute)
            "mix" -> id?.toIntOrNull()?.let(::MixRoute)
            "genre" -> id?.let(::GenreRoute)
            "liked" -> LikedRoute
            "link-device" -> LinkDeviceRoute(query["code"])
            else -> null
        }
    }
}
