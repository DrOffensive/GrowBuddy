package com.growbuddy.app.data.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.growbuddy.app.MainActivity
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.ui.common.appString
import kotlinx.serialization.Serializable

/** An alert as the server publishes it to the app's push endpoint (server alerts/ntfy.py). */
@Serializable
data class PushAlert(
    val title: String,
    val message: String = "",
    val priority: Int = 3, // ntfy 1..5
    val tags: List<String> = emptyList(),
    val click: String? = null,
)

/** Shows pushed alerts as GrowBuddy notifications (cannabis-leaf status bar icon). */
object AlertNotifications {
    private const val CHANNEL_CRITICAL = "alerts_critical"
    private const val CHANNEL_ALERTS = "alerts"
    private const val BRAND_GREEN = 0xFF4BAF6F.toInt() // Palette.Green
    private val EMOJI = mapOf("rotating_light" to "🚨", "warning" to "⚠️", "white_check_mark" to "✅", "seedling" to "🌱")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CRITICAL, appString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = appString(R.string.channel_critical_desc) },
                NotificationChannel(CHANNEL_ALERTS, appString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = appString(R.string.channel_alerts_desc) },
            ),
        )
    }

    fun parse(content: ByteArray): PushAlert? = runCatching { ApiJson.decodeFromString<PushAlert>(content.decodeToString()) }.getOrNull()

    fun show(context: Context, alert: PushAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = Intent(Intent.ACTION_VIEW, (alert.click ?: "growbuddy://alerts").toUri(), context, MainActivity::class.java)
        val emoji = alert.tags.firstNotNullOfOrNull { EMOJI[it] }
        val notification = NotificationCompat.Builder(context, if (alert.priority >= 5) CHANNEL_CRITICAL else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_leaf)
            .setColor(BRAND_GREEN)
            .setContentTitle(listOfNotNull(emoji, alert.title).joinToString(" "))
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(if (alert.priority >= 5) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
