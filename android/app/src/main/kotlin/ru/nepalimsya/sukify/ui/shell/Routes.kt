package ru.nepalimsya.sukify.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.serialization.Serializable
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.ui.icons.SukifyIcons

// --- Корни вкладок -----------------------------------------------------------

@Serializable data object HomeRoute

@Serializable data object SearchRoute

@Serializable data object LibraryRoute

// --- Экраны внутри вкладок (как маршруты веба) ---------------------------------

@Serializable data class ReleaseRoute(val slug: String)

@Serializable data class PlaylistRoute(val id: Long, val join: String? = null)

/** Правка плейлиста: обложка, название, порядок и удаление треков. */
@Serializable data class PlaylistEditRoute(val id: Long)

/** «Добавить в этот плейлист»: любимые и поиск по каталогу. */
@Serializable data class PlaylistAddRoute(val id: Long)

@Serializable data class ArtistRoute(val slug: String)

@Serializable data class DiscographyRoute(val slug: String)

@Serializable data class ArtistLikedRoute(val slug: String)

@Serializable data object LikedRoute

/** Полка главной целиком: recent | popular | new. */
@Serializable data class SectionRoute(val key: String)

/** Весь каталог: sort = added | date | name. */
@Serializable data class ReleasesRoute(val sort: String = "added")

@Serializable data class GenreRoute(val name: String)

@Serializable data class MixRoute(val n: Int)

@Serializable data object HistoryRoute

@Serializable data object NotificationsRoute

@Serializable data object DownloadsRoute

/** Активность друзей (FriendsPanel.vue). */
@Serializable data object FriendsRoute

/** Настройки (SettingsPage.vue). */
@Serializable data object SettingsRoute

/** Статистика: в Sukify и по архиву Spotify. */
@Serializable data class StatsRoute(val spotify: Boolean = false)

/** Эквалайзер (сверх веба). */
@Serializable data object EqualizerRoute

/** Импорт из Spotify (ImportPage.vue). */
@Serializable data object ImportRoute

/** Подключить настольное приложение по коду (LinkDevicePage.vue); код может прийти из ссылки. */
@Serializable data class LinkDeviceRoute(val code: String? = null)

/** Редактор перехода пары треков. */
@Serializable data class MixEditorRoute(val fromId: Long, val toId: Long)

/** «Создать переход…»: выбрать второй трек. */
@Serializable data class TransitionPickerRoute(val fromId: Long)

@Serializable data class ProfileRoute(val username: String)

/** Вкладки нижней панели. «Создать» — не вкладка, а шит (как в Spotify). */
enum class Tab(val label: String, val root: Any) {
    Home("Главная", HomeRoute),
    Search("Поиск", SearchRoute),
    Library("Моя медиатека", LibraryRoute),
    ;

    val icon: ImageVector
        get() = when (this) {
            Home -> SukifyIcons.Home
            Search -> SukifyIcons.Search
            Library -> SukifyIcons.Library
        }

    val selectedIcon: ImageVector
        get() = when (this) {
            Home -> SukifyIcons.HomeFill
            Search -> SukifyIcons.Search
            Library -> SukifyIcons.Library
        }
}

/**
 * Навигация внутри текущей вкладки: у каждой вкладки свой стек, поэтому
 * переходы (к альбому, к исполнителю) идут в активный NavController.
 */
@Stable
class Navigator(private val controller: NavHostController) {
    fun open(route: Any) {
        controller.navigate(route) { launchSingleTop = true }
    }

    /** Открыть карточку: релиз, плейлист, исполнитель, микс или профиль. */
    fun open(item: MediaItem) {
        open(item.route())
    }

    fun back(): Boolean = controller.popBackStack()

    /** Открыть экран вместо текущего (выбор трека → редактор перехода). */
    fun replace(route: Any) {
        val current = controller.currentBackStackEntry?.destination?.id
        controller.navigate(route) {
            if (current != null) popUpTo(current) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun popToRoot(root: Any) {
        controller.popBackStack(root, inclusive = false)
    }
}

fun MediaItem.route(): Any = when (kind) {
    MediaKind.Release -> ReleaseRoute(ref)
    MediaKind.Playlist -> PlaylistRoute(ref.toLong())
    MediaKind.Artist -> ArtistRoute(ref)
    MediaKind.Mix -> MixRoute(ref.toInt())
    MediaKind.User -> ProfileRoute(ref)
    MediaKind.Liked -> LikedRoute
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator не предоставлен") }

/**
 * Сколько снизу занимают мини-плеер и нижняя панель: списки добавляют это
 * в contentPadding, чтобы последняя строка не пряталась под ними.
 */
val LocalBottomChrome = staticCompositionLocalOf<Dp> { 0.dp }

@Composable
fun bottomChromePadding(extra: Dp = 16.dp): Dp = LocalBottomChrome.current + extra
