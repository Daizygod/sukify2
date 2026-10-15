package ru.nepalimsya.sukify.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.nepalimsya.sukify.R

/**
 * Шрифты веба: SpotifyMixUI для латиницы + CircularSp-Cyrl для кириллицы,
 * склеенные в один файл на начертание (android/tools/build_fonts.py).
 */
val SukifyTextFamily = FontFamily(
    Font(R.font.sukify_regular, FontWeight.Normal),
    Font(R.font.sukify_bold, FontWeight.Bold),
)

/** Заголовочный SpotifyMixUITitle (вес 700 и 900) — для больших тайтлов. */
val SukifyTitleFamily = FontFamily(
    Font(R.font.sukify_title_bold, FontWeight.Bold),
    Font(R.font.sukify_title_black, FontWeight.Black),
)

// Межстрочные отступы по центру строки и без шрифтовых полей сверху —
// иначе текст в строках списков «плывёт» на пару пикселей вниз.
private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
private val NoPadding = PlatformTextStyle(includeFontPadding = false)

private fun text(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.3f, family: FontFamily = SukifyTextFamily) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.em,
        lineHeightStyle = Tight,
        platformStyle = NoPadding,
        letterSpacing = 0.sp,
    )

/** Размеры сняты с мобильной вёрстки веба (components/mobile, MobileLibraryPage). */
@Immutable
object SukifyType {
    /** Огромный тайтл в шапке альбома/плейлиста. */
    val Hero = text(40, FontWeight.Black, 1.05f, SukifyTitleFamily)

    /** Заголовок страницы: «Моя медиатека», «Поиск». */
    val PageTitle = text(24, FontWeight.Bold, 1.2f)

    /** Заголовок полки на главной. */
    val SectionTitle = text(22, FontWeight.Bold, 1.2f)

    /** Название трека в «Сейчас играет». */
    val PlayerTitle = text(22, FontWeight.Bold, 1.2f)

    val Title = text(18, FontWeight.Bold, 1.25f)
    val Body = text(16)
    val BodyBold = text(16, FontWeight.Bold)
    val BodySmall = text(14)
    val BodySmallBold = text(14, FontWeight.Bold)
    val Caption = text(13)
    val Label = text(12)
    val LabelBold = text(12, FontWeight.Bold)
    val Tiny = text(11, FontWeight.Bold)
}
