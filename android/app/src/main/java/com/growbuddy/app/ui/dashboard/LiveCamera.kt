package com.growbuddy.app.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.MjpegStream
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.theme.Palette
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen

/**
 * MJPEG live view filling [modifier]. Streams only while the screen is started, so the
 * server can release the camera. [onLive] reports whether frames are arriving.
 */
@Composable
fun LiveCamera(
    streamUrl: String?,
    mjpeg: MjpegStream,
    onLive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(streamUrl) {
        if (streamUrl == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            mjpeg.frames(streamUrl)
                .retryWhen { cause, attempt ->
                    error = (cause as? ApiException)?.message ?: appString(R.string.dash_camera_unavailable)
                    frame = null
                    onLive(false)
                    delay(min(30_000L, 2_000L * (attempt + 1)))
                    true
                }
                .collect {
                    frame = it
                    error = null
                    onLive(true)
                }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val current = frame
        when {
            current != null -> Image(current, "Growcam", contentScale = contentScale, modifier = Modifier.fillMaxSize())
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.VideocamOff, contentDescription = null, tint = Palette.TextSecondary)
                Text(error!!, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            }
            else -> CircularProgressIndicator()
        }
    }
}
