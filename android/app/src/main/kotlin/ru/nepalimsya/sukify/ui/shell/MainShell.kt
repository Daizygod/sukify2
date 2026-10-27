package ru.nepalimsya.sukify.ui.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.artist.ArtistLikedScreen
import ru.nepalimsya.sukify.ui.artist.ArtistScreen
import ru.nepalimsya.sukify.ui.artist.DiscographyScreen
import ru.nepalimsya.sukify.ui.browse.GenreScreen
import ru.nepalimsya.sukify.ui.browse.ReleasesScreen
import ru.nepalimsya.sukify.ui.browse.SectionScreen
import ru.nepalimsya.sukify.ui.collections.LikedScreen
import ru.nepalimsya.sukify.ui.collections.MixScreen
import ru.nepalimsya.sukify.ui.components.ToastHost
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.downloads.DownloadsScreen
import ru.nepalimsya.sukify.ui.downloads.DownloadsUi
import ru.nepalimsya.sukify.ui.downloads.DownloadsViewModel
import ru.nepalimsya.sukify.ui.downloads.LocalDownloads
import ru.nepalimsya.sukify.ui.downloads.OfflineBanner
import ru.nepalimsya.sukify.ui.downloads.OfflineBannerHeight
import ru.nepalimsya.sukify.ui.home.HomeScreen
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.jam.JamBar
import ru.nepalimsya.sukify.ui.jam.JamBarHeight
import ru.nepalimsya.sukify.ui.jam.JamController
import ru.nepalimsya.sukify.ui.jam.JamSheet
import ru.nepalimsya.sukify.ui.jam.JamViewModel
import ru.nepalimsya.sukify.ui.jam.LocalJam
import ru.nepalimsya.sukify.ui.library.LibraryScreen
import ru.nepalimsya.sukify.ui.menus.MenuHost
import ru.nepalimsya.sukify.ui.mix.MixEditorScreen
import ru.nepalimsya.sukify.ui.mix.TransitionPickerScreen
import ru.nepalimsya.sukify.ui.player.MiniPlayerHeight
import ru.nepalimsya.sukify.ui.player.PlayerLayer
import ru.nepalimsya.sukify.ui.player.PlayerNavigation
import ru.nepalimsya.sukify.ui.player.PlayerViewModel
import ru.nepalimsya.sukify.ui.playlist.PlaylistAddScreen
import ru.nepalimsya.sukify.ui.playlist.PlaylistEditScreen
import ru.nepalimsya.sukify.ui.playlist.PlaylistScreen
import ru.nepalimsya.sukify.ui.profile.FriendsScreen
import ru.nepalimsya.sukify.ui.profile.HistoryScreen
import ru.nepalimsya.sukify.ui.profile.LinkDeviceScreen
import ru.nepalimsya.sukify.ui.profile.NotificationsScreen
import ru.nepalimsya.sukify.ui.profile.ProfileScreen
import ru.nepalimsya.sukify.ui.release.ReleaseScreen
import ru.nepalimsya.sukify.ui.search.SearchScreen
import ru.nepalimsya.sukify.ui.settings.EqualizerScreen
import ru.nepalimsya.sukify.ui.settings.ImportScreen
import ru.nepalimsya.sukify.ui.settings.SettingsScreen
import ru.nepalimsya.sukify.ui.settings.StatsScreen
import ru.nepalimsya.sukify.ui.settings.UpdateSheet
import ru.nepalimsya.sukify.ui.theme.SukifyMotion

/**
 * Каркас приложения: контент вкладки, мини-плеер и нижняя панель.
 *
 * У каждой вкладки свой NavController и свой стек экранов (как в Spotify):
 * ушёл с «Поиска» на «Главную» и вернулся — поиск там же, где был, вместе с
 * прокруткой. Неактивные вкладки не держим в композиции, а сохраняем
 * SaveableStateHolder'ом — память и кадры уходят только на видимое.
 */
@Composable
fun MainShell() {
    // Сессия: медиатека, настройки, прошлая очередь, связь с сервисом воспроизведения.
    val session: SessionViewModel = hiltViewModel()
    val player: PlayerViewModel = hiltViewModel()
    val playerState = player.state.collectAsStateWithLifecycle()
    val user by session.user.collectAsStateWithLifecycle(null)
    val downloadsVm: DownloadsViewModel = hiltViewModel()
    val collectionsState = downloadsVm.collections.collectAsStateWithLifecycle()
    val downloadedState = downloadsVm.downloaded.collectAsStateWithLifecycle()
    val onlyDownloadedState = downloadsVm.onlyDownloaded.collectAsStateWithLifecycle()
    val downloadSettingsState = downloadsVm.settings.collectAsStateWithLifecycle()
    val downloads = remember(downloadsVm) {
        DownloadsUi(collectionsState, downloadedState, onlyDownloadedState, downloadSettingsState, downloadsVm.actions)
    }
    val hasNews by session.hasNews.collectAsStateWithLifecycle()
    val jamVm: JamViewModel = hiltViewModel()
    val jamUi by jamVm.ui.collectAsStateWithLifecycle()
    val jamBusy by jamVm.busy.collectAsStateWithLifecycle()
    val jam = remember { JamController() }

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val controllers = remember { mutableStateMapOf<Tab, NavHostController>() }
    val menus = remember { MenuController() }
    val chromeController = remember { ChromeController() }
    val chromeHidden = chromeController.hidden
    val playback = remember(session) { PlaybackUi(playerState, session.contexts) }
    val audible = remember(playerState) { derivedStateOf { playerState.value.playing } }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drawer = remember { DrawerController { scope.launch { drawerState.open() } } }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasPlayer = playerState.value.track != null
    // Нечего играть — плеер свёрнут. Иначе флаг «развёрнут» (он переживает и
    // перезапуск приложения) дожидался первого трека, и тап по треку в списке
    // сразу открывал «Сейчас играет».
    LaunchedEffect(hasPlayer) {
        if (!hasPlayer) playerExpanded = false
    }
    val offline = onlyDownloadedState.value
    val inJam = jamUi != null
    val chrome = if (chromeHidden) {
        navInset
    } else {
        BottomBarHeight + navInset + (if (hasPlayer) MiniPlayerHeight + 8.dp else 0.dp) + (if (offline) OfflineBannerHeight else 0.dp) + (if (inJam) JamBarHeight else 0.dp)
    }
    val currentNavigator = controllers[tab]?.let { Navigator(it) }

    /** Открыть экран в текущей вкладке, свернув плеер и боковое меню. */
    fun openInTab(route: Any) {
        playerExpanded = false
        scope.launch { drawerState.close() }
        controllers[tab]?.let { Navigator(it).open(route) }
    }

    // Ссылка на сайт: ждём, пока вкладка заведёт навигацию, и открываем экран.
    LaunchedEffect(Unit) {
        DeepLinks.pending.filterNotNull().collect { route ->
            snapshotFlow { controllers[tab] }.filterNotNull().first()
            openInTab(route)
            DeepLinks.consume()
        }
    }

    CompositionLocalProvider(
        LocalPlayback provides playback,
        LocalAudible provides audible,
        LocalMenus provides menus,
        LocalDrawer provides drawer,
        LocalDownloads provides downloads,
        LocalChrome provides chromeController,
        LocalJam provides jam,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            drawerContent = {
                ProfileDrawer(
                    user = user,
                    onProfile = { user?.let { openInTab(ProfileRoute(it.username ?: it.id.toString())) } },
                    entries = listOf(
                        DrawerEntry(SukifyIcons.Bell, "Что нового", dot = hasNews) { openInTab(NotificationsRoute) },
                        DrawerEntry(SukifyIcons.DownloadCircle, "Загрузки") { openInTab(DownloadsRoute) },
                        DrawerEntry(SukifyIcons.Friends, "Активность друзей") { openInTab(FriendsRoute) },
                        DrawerEntry(SukifyIcons.Clock, "История прослушивания") { openInTab(HistoryRoute) },
                        DrawerEntry(SukifyIcons.Devices, "Подключить компьютер") { openInTab(LinkDeviceRoute()) },
                        DrawerEntry(SukifyIcons.Stats, "Статистика") { openInTab(StatsRoute()) },
                        DrawerEntry(SukifyIcons.Settings, "Настройки") { openInTab(SettingsRoute) },
                        DrawerEntry(SukifyIcons.Close, "Выйти") {
                            scope.launch { drawerState.close() }
                            session.logout()
                        },
                    ),
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBottomChrome provides chrome) {
                    holder.SaveableStateProvider(tab.name) {
                        val nav = rememberNavController()
                        val current = tab
                        DisposableEffect(nav, current) {
                            controllers[current] = nav
                            onDispose { controllers.remove(current) }
                        }
                        CompositionLocalProvider(LocalNavigator provides remember(nav) { Navigator(nav) }) {
                            NavHost(
                                navController = nav,
                                startDestination = current.root,
                                enterTransition = { enter() },
                                exitTransition = { exit() },
                                popEnterTransition = { popEnter() },
                                popExitTransition = { popExit() },
                            ) {
                                sukifyGraph()
                            }
                        }
                    }

                    ToastHost(Modifier.align(Alignment.BottomCenter))
                }

                AnimatedVisibility(
                    visible = !playerExpanded && !chromeHidden,
                    enter = slideInVertically(SukifyMotion.sheet()) { it } + fadeIn(),
                    exit = slideOutVertically(SukifyMotion.sheet()) { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    BottomBar(
                        selected = tab,
                        onSelect = { selected ->
                            // Повторный тап по активной вкладке — к её началу.
                            if (selected == tab) controllers[selected]?.popBackStack(selected.root, inclusive = false)
                            else tab = selected
                        },
                        onCreate = { createOpen = true },
                    )
                }

                AnimatedVisibility(
                    visible = offline && !playerExpanded && !chromeHidden,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BottomBarHeight + navInset + if (hasPlayer) MiniPlayerHeight + 8.dp else 0.dp),
                ) {
                    OfflineBanner(offlineMode = downloadSettingsState.value.offlineMode, onClick = { openInTab(DownloadsRoute) })
                }

                // Джем — зелёная полоса над мини-плеером (и над плашкой «Нет сети»).
                AnimatedVisibility(
                    visible = inJam && !playerExpanded && !chromeHidden,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = BottomBarHeight + navInset + 4.dp +
                                (if (hasPlayer) MiniPlayerHeight + 4.dp else 0.dp) +
                                (if (offline) OfflineBannerHeight else 0.dp),
                        ),
                ) {
                    jamUi?.let { JamBar(it, onClick = { jam.open = true }) }
                }

                AnimatedVisibility(
                    visible = !chromeHidden || playerExpanded,
                    enter = slideInVertically(SukifyMotion.sheet()) { it } + fadeIn(),
                    exit = slideOutVertically(SukifyMotion.sheet()) { it } + fadeOut(),
                ) {
                    PlayerLayer(
                        vm = player,
                        expanded = playerExpanded,
                        onExpandedChange = { playerExpanded = it },
                        miniBottomPadding = BottomBarHeight + navInset + 4.dp,
                        navigation = object : PlayerNavigation {
                            override fun openArtist(slug: String) = openInTab(ArtistRoute(slug))
                            override fun openRelease(slug: String, trackId: Long) = openInTab(ReleaseRoute(slug, highlight = trackId))
                        },
                    )
                }

                if (createOpen) {
                    CreateSheet(
                        onDismiss = { createOpen = false },
                        onCreatePlaylist = { id ->
                            createOpen = false
                            openInTab(PlaylistRoute(id))
                        },
                        onJam = { jam.open = true },
                        onTransition = {
                            // Переход начинается с того, что играет; есть следующий — сразу редактор пары.
                            val s = playerState.value
                            val current = s.track
                            val next = s.queue.manual.firstOrNull()?.track ?: s.queue.upcoming.firstOrNull()
                            when {
                                current == null -> Toasts.show("Включи трек — переход начнётся с него")
                                next != null -> openInTab(MixEditorRoute(current.id, next.id))
                                else -> openInTab(TransitionPickerRoute(current.id))
                            }
                        },
                    )
                }
            }
        }

        val updatePrompt by session.updatePrompt.collectAsStateWithLifecycle()
        if (updatePrompt) {
            val updateState by session.updateState.collectAsStateWithLifecycle()
            UpdateSheet(
                state = updateState,
                currentVersion = session.currentVersion,
                onUpdate = session::startUpdate,
                onLater = session::skipUpdate,
                onDismiss = session::dismissUpdate,
            )
        }

        if (jam.open) {
            JamSheet(
                ui = jamUi,
                busy = jamBusy,
                onStart = jamVm::start,
                onJoin = jamVm::join,
                onLeave = {
                    jamVm.leave()
                    jam.open = false
                },
                onDismiss = { jam.open = false },
            )
        }

        MenuHost(menus, currentNavigator, onNavigate = {
            playerExpanded = false
        })
    }
}

/** Все экраны приложения — одинаковые для каждой вкладки. */
private fun NavGraphBuilder.sukifyGraph() {
    composable<HomeRoute> { HomeScreen() }
    composable<SearchRoute> { SearchScreen() }
    composable<LibraryRoute> { LibraryScreen() }
    composable<ReleaseRoute> { ReleaseScreen() }
    composable<PlaylistRoute> { PlaylistScreen() }
    composable<PlaylistEditRoute> { PlaylistEditScreen() }
    composable<PlaylistAddRoute> { PlaylistAddScreen() }
    composable<ArtistRoute> { ArtistScreen() }
    composable<DiscographyRoute> { DiscographyScreen() }
    composable<ArtistLikedRoute> { ArtistLikedScreen() }
    composable<LikedRoute> { LikedScreen() }
    composable<SectionRoute> { SectionScreen() }
    composable<ReleasesRoute> { ReleasesScreen() }
    composable<GenreRoute> { GenreScreen() }
    composable<MixRoute> { MixScreen() }
    composable<HistoryRoute> { HistoryScreen() }
    composable<FriendsRoute> { FriendsScreen() }
    composable<LinkDeviceRoute> { LinkDeviceScreen() }
    composable<SettingsRoute> { SettingsScreen() }
    composable<StatsRoute> { StatsScreen() }
    composable<ImportRoute> { ImportScreen() }
    composable<EqualizerRoute> { EqualizerScreen() }
    composable<NotificationsRoute> { NotificationsScreen() }
    composable<ProfileRoute> { ProfileScreen() }
    composable<DownloadsRoute> { DownloadsScreen() }
    composable<MixEditorRoute> { MixEditorScreen() }
    composable<TransitionPickerRoute> { TransitionPickerScreen() }
}

// Переходы между экранами: короткий сдвиг и наплыв — быстро, без «киношной» паузы.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    slideInHorizontally(tween(SukifyMotion.NAV_MS)) { it / 10 } + fadeIn(tween(SukifyMotion.NAV_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    fadeOut(tween(SukifyMotion.NAV_MS - 60))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    fadeIn(tween(SukifyMotion.NAV_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutHorizontally(tween(SukifyMotion.NAV_MS)) { it / 10 } + fadeOut(tween(SukifyMotion.NAV_MS - 40))
