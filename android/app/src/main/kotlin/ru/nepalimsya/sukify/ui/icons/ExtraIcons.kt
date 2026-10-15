package ru.nepalimsya.sukify.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Иконки приложения, которых нет в Icon.vue веба (SukifyIcons генерируется
 * из него — руками туда не пишем).
 */
object ExtraIcons {
    /** Стрелка вниз из downloadCircle — для зелёной кнопки «скачано». */
    val DownloadArrow: ImageVector by lazy {
        svgIcon(
            "downloadArrow",
            "0 0 24 24",
            "M12 6.05a1 1 0 0 1 1 1v7.486l1.793-1.793a1 1 0 1 1 1.414 1.414L12 18.364l-4.207-4.207a1 1 0 1 1 1.414-1.414L11 14.536V7.05a1 1 0 0 1 1-1",
        )
    }

    /** Облако с косой чертой — «нет сети». */
    val Offline: ImageVector by lazy {
        svgIcon(
            "offline",
            "0 0 24 24",
            "M3.29 2.29a1 1 0 0 1 1.42 0l17 17a1 1 0 0 1-1.42 1.42l-2.3-2.3A5 5 0 0 1 17 19H7a5 5 0 0 1-1.65-9.72 7 7 0 0 1 1.1-2.86L3.3 3.7a1 1 0 0 1 0-1.42zM7.9 7.9a5 5 0 0 0-.86 2.4 1 1 0 0 1-.78.88A3 3 0 0 0 7 17h9.59zM12 4a7 7 0 0 1 6.65 4.8A5 5 0 0 1 21.5 16a1 1 0 1 1-1.73-1A3 3 0 0 0 17.86 11a1 1 0 0 1-.98-.82A5 5 0 0 0 10.5 6.2a1 1 0 0 1-.6-1.9A7 7 0 0 1 12 4z",
        )
    }
}
