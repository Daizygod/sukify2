package ru.nepalimsya.sukify.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.parseHexColor

/** Цвет обложки трека (из анализа обложки на сервере) или запасной. */
fun Track?.coverColor(fallback: Color = Color(0xFF3A3A3A)): Color =
    parseHexColor(this?.release?.color) ?: fallback

/** Мини-плеер: 60% цвета обложки поверх #181818, как color-mix в вебе. */
fun miniPlayerColor(cover: Color): Color = lerp(SukifyColors.Card, cover, 0.6f)

/** Средняя точка градиента «Сейчас играет»: 35% цвета обложки на #121212. */
fun nowPlayingMid(cover: Color): Color = lerp(SukifyColors.Elevated, cover, 0.35f)

/** Карточка «Текст»: цвет обложки, чуть высветленный. */
fun lyricsCardColor(cover: Color): Color = lerp(cover, Color.White, 0.06f)

/** Пульт, пока не знаем цвета обложки трека на другом устройстве (как в вебе). */
val RemoteColor = Color(0xFF503750)
