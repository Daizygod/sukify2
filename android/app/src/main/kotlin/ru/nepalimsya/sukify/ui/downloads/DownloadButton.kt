package ru.nepalimsya.sukify.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.nepalimsya.sukify.data.offline.CollectionDownload
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.ExtraIcons
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.ConfirmDialog
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion

/**
 * ⬇ подборки, как в Spotify: контур — не скачано; кольцо — качается;
 * зелёный круг со стрелкой — всё на телефоне. Повторное нажатие спрашивает,
 * убрать ли из загрузок.
 */
@Composable
fun DownloadButton(key: String, title: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val downloads = LocalDownloads.current
    val status = downloads.collection(key)
    val haptics = LocalHapticFeedback.current
    val requestNotifications = rememberNotificationPermission()
    var confirm by remember { mutableStateOf(false) }

    Box(
        modifier
            .size(size + 16.dp)
            .bounceClick(pressed = 0.85f) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                if (status.active) {
                    confirm = true
                } else {
                    requestNotifications()
                    downloads.actions?.add(key, title)
                }
            }
            .semantics { contentDescription = if (status.active) "Удалить из загрузок" else "Скачать" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = status.state,
            transitionSpec = { (scaleIn(SukifyMotion.bouncy(), 0.7f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.7f) + fadeOut()) },
            label = "download",
        ) { state ->
            when (state) {
                CollectionDownload.State.Off ->
                    Icon(SukifyIcons.DownloadCircle, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(size))
                CollectionDownload.State.Waiting -> Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.fillMaxSize(), color = SukifyColors.Accent, strokeWidth = 2.dp, trackColor = SukifyColors.TextMuted.copy(alpha = 0.4f))
                    Icon(ExtraIcons.DownloadArrow, null, tint = SukifyColors.Accent, modifier = Modifier.size(size * 0.7f))
                }
                CollectionDownload.State.Downloading -> ProgressRing(status.fraction, size)
                CollectionDownload.State.Done -> Box(
                    Modifier.size(size).background(SukifyColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ExtraIcons.DownloadArrow, null, tint = Color.Black, modifier = Modifier.size(size * 0.78f))
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Удалить из загрузок?",
            text = "«$title» не будет доступен без сети. Слушать его онлайн можно по-прежнему.",
            confirm = "Удалить",
            onConfirm = {
                confirm = false
                downloads.actions?.remove(key, title)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Кольцо прогресса со стрелкой внутри: сколько треков подборки уже на телефоне. */
@Composable
private fun ProgressRing(fraction: Float, size: Dp) {
    val animated by animateFloatAsState(fraction, SukifyMotion.standard(), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(SukifyColors.TextMuted, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(SukifyColors.Accent, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
        Icon(ExtraIcons.DownloadArrow, null, tint = SukifyColors.Accent, modifier = Modifier.size(size * 0.7f))
    }
}

/**
 * Прогресс загрузок показывается уведомлением, а на Android 13+ для этого
 * нужно разрешение — спрашиваем при первом ⬇, а не при запуске.
 */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
