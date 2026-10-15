package ru.nepalimsya.sukify.util

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Форматирование чисел, длительностей и дат по-русски — порт frontend/src/lib/format.js. */
object Format {
    private val ru = Locale.forLanguageTag("ru-RU")

    /** 3:07 */
    fun duration(ms: Long?): String {
        if (ms == null || ms <= 0) return "0:00"
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** 3 109 (неразрывный пробел между разрядами, как Intl.NumberFormat('ru-RU')). */
    fun number(n: Long?): String = NumberFormat.getIntegerInstance(ru).format(n ?: 0)

    /** 1,2 млн / 3,4 тыс. */
    fun count(n: Long?): String {
        if (n == null) return ""
        fun short(v: Double) = "%.1f".format(ru, v).removeSuffix(",0")
        return when {
            n >= 1_000_000 -> "${short(n / 1_000_000.0)} млн"
            n >= 1_000 -> "${short(n / 1_000.0)} тыс."
            else -> n.toString()
        }
    }

    /** plural(3, "трек", "трека", "треков") → «трека». */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val abs = abs(n) % 100
        val d = abs % 10
        return when {
            abs in 11..19 -> many
            d in 2..4 -> few
            d == 1L -> one
            else -> many
        }
    }

    fun trackCount(n: Long): String = "${number(n)} ${plural(n, "трек", "трека", "треков")}"

    fun listeners(n: Long?): String {
        val v = n ?: 0
        return "${number(v)} ${plural(v, "слушатель", "слушателя", "слушателей")} за месяц"
    }

    /** «47 мин. 10 сек.» / «4 ч. 45 мин.» */
    fun totalDuration(ms: Long, approx: Boolean = false): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 && approx -> "примерно $h ч. $m мин."
            h > 0 -> "$h ч. $m мин."
            s > 0 -> "$m мин. $s сек."
            else -> "$m мин."
        }
    }

    /** «2 дня назад» — колонка «Дата добавления»; старше месяца — дата. */
    fun relativeDate(epochSeconds: Long?, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochSeconds == null) return ""
        val sec = max(0, nowMillis / 1000 - epochSeconds)
        return when {
            sec < 3600 -> {
                val m = max(1, sec / 60)
                "$m ${plural(m, "минуту", "минуты", "минут")} назад"
            }
            sec < 86400 -> {
                val h = sec / 3600
                "$h ${plural(h, "час", "часа", "часов")} назад"
            }
            sec < 30 * 86400 -> {
                val d = sec / 86400
                "$d ${plural(d, "день", "дня", "дней")} назад"
            }
            else -> DateTimeFormatter.ofPattern("d MMM yyyy 'г.'", ru)
                .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
        }
    }

    /** «2023-10-20» → «20 октября 2023 г.» (дата выхода под треклистом). */
    fun releaseDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val date = runCatching { java.time.LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
        return DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", ru).format(date)
    }

    /**
     * Время сервера → секунды эпохи. Laravel отдаёт и «…T08:45:26.000000Z»,
     * и «2026-10-14 08:45:26» (UTC без зоны).
     */
    fun epochSeconds(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        runCatching { return Instant.parse(iso).epochSecond }
        runCatching { return java.time.OffsetDateTime.parse(iso).toEpochSecond() }
        runCatching { return java.time.LocalDateTime.parse(iso.replace(' ', 'T')).toEpochSecond(java.time.ZoneOffset.UTC) }
        return null
    }

    /** «350 МБ», «1,2 ГБ» — место на диске. */
    fun bytes(n: Long): String {
        val mb = n / 1_048_576.0
        return when {
            mb >= 1024 -> "%.1f ГБ".format(ru, mb / 1024).replace(",0 ", " ")
            mb >= 10 -> "${mb.toLong()} МБ"
            mb >= 0.1 -> "%.1f МБ".format(ru, mb)
            else -> "${n / 1024} КБ"
        }
    }

    /** «14 октября» — заголовок дня в истории прослушивания. */
    fun day(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("d MMMM", ru).format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

    /** «12:30» */
    fun time(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", ru).format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
}
