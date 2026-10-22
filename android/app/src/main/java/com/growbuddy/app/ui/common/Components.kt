package com.growbuddy.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.growbuddy.app.R
import com.growbuddy.app.ui.theme.Palette

/** Colour for a server alarm level ("normal" | "warning" | "critical" | null). */
fun levelColor(level: String?): Color = when (level) {
    "normal" -> Palette.TextPrimary
    "warning" -> Palette.Warning
    "critical" -> Palette.Danger
    else -> Palette.TextSecondary
}

/** 0.1.6 page header: white icon (or back arrow) + large condensed title. */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = Palette.TextPrimary) }
            Spacer(Modifier.width(4.dp))
        } else if (icon != null) {
            Image(painterResource(icon), null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(Palette.TextPrimary))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp),
            color = Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Dark rounded panel used for dashboard sections, with an optional condensed title. */
@Composable
fun GbCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                    color = Palette.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
        }
        content()
    }
}

/** 0.1.6 RSSI sprite (dot + 0..3 arcs), tinted along red -> olive -> green. */
@Composable
fun RssiIcon(rssi: Int?, online: Boolean, modifier: Modifier = Modifier) {
    val level = when {
        rssi == null || !online -> 0
        rssi >= -65 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val res = listOf(R.drawable.ic_rssi_0, R.drawable.ic_rssi_1, R.drawable.ic_rssi_2, R.drawable.ic_rssi_3)[level]
    val tint = if (!online) Palette.Danger else listOf(Palette.Danger, Palette.Warning, Color(0xFF8C9A4B), Palette.Green)[level]
    Image(painterResource(res), stringResource(R.string.common_signal), modifier.size(32.dp), colorFilter = ColorFilter.tint(tint))
}

/** 0.1.6 battery frame sprite with a fill bar. */
@Composable
fun BatteryIndicator(pct: Int?, modifier: Modifier = Modifier) {
    val fraction = ((pct ?: 0) / 100f).coerceIn(0f, 1f)
    val color = when {
        pct == null -> Palette.TextSecondary
        pct <= 15 -> Palette.Danger
        pct <= 35 -> Palette.Warning
        else -> Color(0xFF458C48)
    }
    Box(modifier.size(width = 44.dp, height = 44.dp), contentAlignment = Alignment.CenterStart) {
        Image(painterResource(R.drawable.ic_battery_frame), if (pct != null) stringResource(R.string.common_battery, pct) else stringResource(R.string.common_battery_unknown), Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(color))
        // The frame's inner cavity spans about 12%..80% of the sprite width.
        Box(Modifier.padding(start = 7.dp).width((28 * fraction).dp).height(10.dp).background(color))
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    MessageBox(message, modifier, onRetry) { Icon(Icons.Outlined.CloudOff, null, tint = Palette.TextSecondary) }
}

/** Nothing to show yet (not an error). */
@Composable
fun EmptyState(icon: ImageVector, message: String, modifier: Modifier = Modifier) {
    MessageBox(message, modifier, null) { Icon(icon, null, tint = Palette.TextSecondary) }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, message: String, modifier: Modifier = Modifier) {
    MessageBox(message, modifier, null) {
        Image(painterResource(icon), null, Modifier.size(48.dp), colorFilter = ColorFilter.tint(Palette.TextSecondary))
    }
}

@Composable
private fun MessageBox(message: String, modifier: Modifier, onRetry: (() -> Unit)?, icon: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = Palette.TextSecondary)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

/** A destructive action waiting for the user's OK; shown by [ConfirmDialog]. */
data class Confirmation(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

/**
 * Every destructive action (deleting, overwriting saved data, throwing away
 * unsaved input, disconnecting) goes through this dialog first.
 */
@Composable
fun ConfirmDialog(confirmation: Confirmation?, onDismiss: () -> Unit) {
    confirmation ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirmation.title) },
        text = { Text(confirmation.text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); confirmation.onConfirm() }) {
                Text(confirmation.confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
