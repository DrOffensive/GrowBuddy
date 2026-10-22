package com.growbuddy.app.ui.dashboard

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.growbuddy.app.R
import com.growbuddy.app.ui.common.appContainer

/** Full-screen landscape growcam stream (tap the dashboard feed); X returns to the dashboard. */
@Composable
fun LiveViewScreen(onClose: () -> Unit) {
    val container = appContainer()
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val wasPortrait = activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            // Go back to the orientation the dashboard had (auto-rotate alone keeps landscape
            // while the phone lies still), then hand control back to the sensor.
            activity?.requestedOrientation = if (wasPortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else before
            window?.decorView?.postDelayed({ activity.requestedOrientation = before }, RESTORE_DELAY_MS)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveCamera(
            container.apiProvider.url("camera/stream"),
            container.mjpeg,
            onLive = {},
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
        }
    }
}

private const val RESTORE_DELAY_MS = 1_500L
