package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * Короткие уведомления внизу экрана — как тосты веба (stores/toasts.js):
 * «Добавлено в очередь», «Играет на устройстве …».
 */
object Toasts {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    fun show(message: String) {
        _messages.tryEmit(message)
    }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Toasts.messages.collect { message ->
            current = message
            visible = true
            delay(2600)
            visible = false
            delay(250)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(SukifyMotion.standard()) + scaleIn(SukifyMotion.bouncy(), initialScale = 0.9f),
        exit = fadeOut(SukifyMotion.standard()) + scaleOut(targetScale = 0.95f),
        modifier = modifier.padding(bottom = bottomChromePadding(12.dp), start = 24.dp, end = 24.dp),
    ) {
        Text(
            text = current.orEmpty(),
            style = SukifyType.BodySmallBold,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}
