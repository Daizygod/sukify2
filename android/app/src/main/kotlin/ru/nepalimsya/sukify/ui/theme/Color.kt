package ru.nepalimsya.sukify.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Палитра веба (frontend/src/styles/main.css) — один в один. */
@Immutable
object SukifyColors {
    val Base = Color(0xFF000000)
    val Elevated = Color(0xFF121212)
    val Card = Color(0xFF181818)
    val CardPressed = Color(0xFF282828)
    val Highlight = Color(0xFF1A1A1A)
    val Chip = Color(0xFF2A2A2A)
    val Divider = Color(0xFF292929)

    val Text = Color(0xFFFFFFFF)
    val TextSubdued = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF6A6A6A)

    val Accent = Color(0xFF1ED760)
    val AccentPressed = Color(0xFF3BE477)
    val Error = Color(0xFFF15E6C)
    val Warning = Color(0xFFF5A524)

    /** Плитка «Любимые треки»: фиолетовый градиент, как в вебе и в Spotify. */
    val LikedStart = Color(0xFF450AF5)
    val LikedEnd = Color(0xFF8E8EE5)

    /** Запасной фон «Сейчас играет», пока не знаем цвет обложки. */
    val NowPlayingFallback = Color(0xFF3B3054)
}

/** «#5a1e1e» из API → Color; кривая или пустая строка → null. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.removePrefix("#")
    val value = clean.toLongOrNull(16) ?: return null
    return when (clean.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> null
    }
}
