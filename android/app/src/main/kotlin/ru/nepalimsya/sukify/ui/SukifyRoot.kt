package ru.nepalimsya.sukify.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nepalimsya.sukify.ui.auth.AuthScreen
import ru.nepalimsya.sukify.ui.shell.MainShell
import ru.nepalimsya.sukify.ui.theme.SukifyColors

@Composable
fun SukifyRoot(app: AppViewModel) {
    val state by app.state.collectAsStateWithLifecycle()

    // testTag → resource-id: по ним UiAutomator находит экраны в генераторе Baseline Profile.
    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(SukifyColors.Elevated)) {
        // Вход ↔ приложение — мягкий наплыв, без резкой подмены экрана.
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(200)) },
            label = "root",
        ) { current ->
            when (current) {
                AppState.Loading -> Unit // сплэш ещё на экране
                is AppState.LoggedOut -> AuthScreen(defaultServer = current.server)
                is AppState.LoggedIn -> MainShell()
            }
        }
    }
}
