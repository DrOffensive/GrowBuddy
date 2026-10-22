package com.growbuddy.app.ui.journal

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.LogEntry
import com.growbuddy.app.ui.common.appPlural
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.theme.Palette
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** Camera + gallery pickers; [onPicked] gets a content Uri per image. */
class PhotoPicker(val takePhoto: () -> Unit, val pickFromGallery: () -> Unit)

/** [galleryMax] > 1 lets the gallery return several images at once. */
@Composable
fun rememberPhotoPicker(galleryMax: Int = 1, onPicked: (Uri) -> Unit): PhotoPicker {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<Uri>(1) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending[0]
        if (ok && uri != null) onPicked(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val galleryMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(galleryMax.coerceAtLeast(2))) { uris ->
        uris.forEach(onPicked)
    }
    return remember {
        PhotoPicker(
            takePhoto = {
                val uri = captureUri(context)
                pending[0] = uri
                camera.launch(uri)
            },
            pickFromGallery = {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (galleryMax > 1) galleryMany.launch(request) else gallery.launch(request)
            },
        )
    }
}

private fun captureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/** Coloured type dot (0.1.6 log dots). */
@Composable
fun TypeDot(type: String, size: Int = 14) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(logTypeColor(type)))
}

/** One journal entry: type, time, plants, comment and photo strip. */
@Composable
fun LogRow(
    entry: LogEntry,
    plantNames: Map<String, String>,
    photoUrl: (String) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.CardRaised)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeDot(entry.type)
            Spacer(Modifier.width(10.dp))
            Text(logTypeLabel(entry.type), style = MaterialTheme.typography.titleMedium.copy(fontSize = 21.sp), modifier = Modifier.weight(1f))
            Text(formatTime(entry.ts), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        val names = entry.plantIds.mapNotNull { plantNames[it] }
        if (names.isNotEmpty()) {
            Text(names.joinToString(", "), color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        summary(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        entry.comment?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (entry.photoIds.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.photoIds.forEach { id ->
                    AsyncImage(photoUrl(id), null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** One-line summary of a log's data (feeding: pH/EC/volume and nutrient count). */
fun summary(entry: LogEntry): String? {
    val d = entry.data
    fun num(key: String) = (d[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    return when (LogType.of(entry.type)?.data) {
        DataKind.Feeding -> listOfNotNull(
            num("ph")?.let { "pH $it" },
            num("ec")?.let { "EC $it" },
            num("volume_l")?.let { "$it L" },
            (d["nutrients"] as? kotlinx.serialization.json.JsonArray)?.size?.takeIf { it > 0 }?.let { appPlural(R.plurals.journal_nutrients, it, it) },
        ).joinToString(" · ").ifEmpty { null }
        DataKind.LightHeight -> num("light_height_cm")?.let { appString(R.string.journal_light, it) }
        DataKind.HumidityTarget -> num("humidity_target_pct")?.let { appString(R.string.journal_humidity, it) }
        DataKind.TemperatureTarget -> num("temperature_target_c")?.let { appString(R.string.journal_temperature, it) }
        else -> null
    }
}

private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

fun formatTime(ts: Long): String = timeFormatter.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
fun formatDay(ts: Long): String = dayFormatter.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
fun dayKey(ts: Long): Long = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** Full-screen photo viewer (tap to close). */
@Composable
fun PhotoViewer(url: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun DateTimeLabel(ts: Long) {
    Text(formatDateTime(ts), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
}

