package com.growbuddy.app.data.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.growbuddy.app.MainActivity
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.data.remote.ApiProvider
import com.growbuddy.app.data.remote.WidgetSensor
import com.growbuddy.app.data.remote.WidgetSnapshot
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.formatTime
import java.io.File

/**
 * The environment "widget": a silent, minimised notification (no status bar icon) that shows
 * the dashboard dials for one of the chosen sensors, with ‹ › to step between them. Values come
 * from server pushes (UnifiedPush, every ntfy.environment_push_s and on alert changes) and from
 * /environment/current when the app opens or an arrow is tapped. The same snapshot and sensor
 * choice feed the home screen widget (HomeWidgetProvider).
 */
object EnvironmentWidget {
    private const val CHANNEL = "environment_widget"
    private const val NOTIFICATION_ID = 2_000
    private const val PREFS = "environment_widget"
    private const val BRAND_GREEN = 0xFF4BAF6F.toInt() // Palette.Green
    const val ACTION_PREV = "com.growbuddy.app.widget.PREV"
    const val ACTION_NEXT = "com.growbuddy.app.widget.NEXT"
    const val ACTION_HOME_PREV = "com.growbuddy.app.widget.HOME_PREV"
    const val ACTION_HOME_NEXT = "com.growbuddy.app.widget.HOME_NEXT"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)

    /** Whether anything shows environment values: the server only pushes snapshots while this is true. */
    fun wanted(context: Context) = isEnabled(context) || HomeWidgetProvider.isPlaced(context)

    fun selectedSensors(context: Context): Set<Long> =
        prefs(context).getStringSet("sensors", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun configure(context: Context, enabled: Boolean, sensors: Set<Long>) {
        prefs(context).edit {
            putBoolean("enabled", enabled)
            putStringSet("sensors", sensors.map(Long::toString).toSet())
        }
        show(context)
        HomeWidgetProvider.updateAll(context)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, appString(R.string.channel_widget), NotificationManager.IMPORTANCE_MIN).apply {
            description = appString(R.string.channel_widget_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun snapshotFile(context: Context) = File(context.filesDir, "widget_snapshot.json")

    internal fun loadSnapshot(context: Context): WidgetSnapshot? =
        runCatching { ApiJson.decodeFromString<WidgetSnapshot>(snapshotFile(context).readText()) }.getOrNull()

    /** A pushed snapshot (PushService) or a fresh one from the API. */
    fun update(context: Context, snapshot: WidgetSnapshot) {
        val file = snapshotFile(context)
        File(file.path + ".tmp").apply { writeText(ApiJson.encodeToString(WidgetSnapshot.serializer(), snapshot)) }.renameTo(file)
        show(context)
        HomeWidgetProvider.updateAll(context)
    }

    /** Fetches /environment/current; used when the app opens, when an arrow is tapped and on the home widget's fallback update. */
    suspend fun refresh(context: Context, apiProvider: ApiProvider) {
        if (!wanted(context)) return
        apiCall { apiProvider.api().environment() }.onSuccess { update(context, WidgetSnapshot.of(it)) }
    }

    /** ‹ › on the notification (index) or the home widget (home_index); each keeps its own position. */
    fun step(context: Context, delta: Int, home: Boolean = false) {
        val key = if (home) "home_index" else "index"
        val prefs = prefs(context)
        prefs.edit { putInt(key, prefs.getInt(key, 0) + delta) }
        if (home) HomeWidgetProvider.updateAll(context) else show(context)
    }

    /** The home widget's sensors and the one it shows; with none chosen in Settings it cycles through all. */
    internal fun homeSensor(context: Context): Shown? {
        val all = loadSnapshot(context)?.sensors.orEmpty()
        val chosen = selectedSensors(context)
        val sensors = all.filter { it.id in chosen }.ifEmpty { all }
        if (sensors.isEmpty()) return null
        val index = Math.floorMod(prefs(context).getInt("home_index", 0), sensors.size)
        return Shown(sensors[index], index, sensors.size)
    }

    internal data class Shown(val sensor: WidgetSensor, val index: Int, val count: Int)

    /** Posts, updates or removes the notification from the stored settings and snapshot. */
    fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val snapshot = loadSnapshot(context)
        val chosen = selectedSensors(context)
        val sensors = snapshot?.sensors?.filter { it.id in chosen }.orEmpty()
        if (!isEnabled(context) || sensors.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val index = Math.floorMod(prefs(context).getInt("index", 0), sensors.size)
        val sensor = sensors[index]
        val multiple = sensors.size > 1

        fun views(layout: Int, dialWidthDp: Float, compact: Boolean) = RemoteViews(context.packageName, layout).apply {
            setImageViewBitmap(R.id.widget_dial_temperature, DialBitmaps.render(context, WidgetMetric.Temperature, sensor, dialWidthDp, compact))
            setImageViewBitmap(R.id.widget_dial_humidity, DialBitmaps.render(context, WidgetMetric.Humidity, sensor, dialWidthDp, compact))
            setImageViewBitmap(R.id.widget_dial_vpd, DialBitmaps.render(context, WidgetMetric.Vpd, sensor, dialWidthDp, compact))
            for (id in listOf(R.id.widget_prev, R.id.widget_next)) setViewVisibility(id, if (multiple) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.widget_prev, action(context, ACTION_PREV))
            setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT))
        }
        val collapsed = views(R.layout.widget_collapsed, 66f, compact = true)
        val expanded = views(R.layout.widget_expanded, 130f, compact = false).apply {
            setTextViewText(R.id.widget_title, sensor.name + if (multiple) "  ${index + 1}/${sensors.size}" else "")
            setTextViewText(
                R.id.widget_subtitle,
                if (!sensor.online) appString(R.string.widget_offline) else sensor.reading?.let { formatTime(it.ts) } ?: appString(R.string.widget_no_reading),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_leaf)
            .setColor(BRAND_GREEN)
            // Collapsed, One UI shows only the title of a custom-layout notification, so the values go there.
            .setContentTitle("${sensor.name} · ${summary(sensor)}")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun summary(sensor: WidgetSensor): String {
        val r = sensor.reading ?: return appString(R.string.widget_no_reading_yet)
        val values = "${formatNumber(r.temperatureC, 1)}° · ${formatNumber(r.humidityPct, 0)}% · ${formatNumber(r.vpdKpa, 2)} kPa"
        return if (sensor.online) values else appString(R.string.widget_values_offline, values)
    }

    private fun action(context: Context, action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, WidgetActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )
}
