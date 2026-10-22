package com.growbuddy.app.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.growbuddy.app.GrowBuddyApp
import com.growbuddy.app.MainActivity
import com.growbuddy.app.R
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatTime
import kotlinx.coroutines.launch

/**
 * The 4×2 home screen widget: the dashboard's dial triangle for one sensor (‹ › between the sensors chosen
 * in Settings, all of them if none are), and a quick-log button that opens the log wizard.
 * It draws EnvironmentWidget's snapshot, so it updates with every server push; while one is
 * placed the server pushes snapshots even when the notification widget is off.
 */
class HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        // Placement and the 30-minute fallback: fetch fresh values in case pushes aren't arriving.
        val container = (context.applicationContext as GrowBuddyApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                EnvironmentWidget.refresh(context, container.apiProvider)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        render(context, manager, id)

    // First widget placed / last one removed: the server starts or stops the environment pushes.
    override fun onEnabled(context: Context) = registrationChanged(context)

    override fun onDisabled(context: Context) = registrationChanged(context)

    private fun registrationChanged(context: Context) =
        (context.applicationContext as GrowBuddyApp).container.appPush.updateRegistration()

    companion object {
        private const val PADDING_DP = 8f // widget_home.xml root padding, both sides together

        private fun ids(context: Context) =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))

        fun isPlaced(context: Context) = ids(context).isNotEmpty()

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            ids(context).forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            val shown = EnvironmentWidget.homeSensor(context)
            if (shown == null) {
                views.setTextViewText(R.id.home_subtitle, appString(R.string.widget_open_app))
                views.setImageViewBitmap(R.id.home_cluster, null)
            } else {
                val (sensor, index, count) = shown
                // Portrait size (One UI reports the same in both orientations); the bitmap matches it 1:1.
                val options = manager.getAppWidgetOptions(id)
                val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
                val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
                views.setImageViewBitmap(
                    R.id.home_cluster,
                    DialBitmaps.renderCluster(context, sensor, widthDp - PADDING_DP, heightDp - PADDING_DP),
                )
                views.setTextViewText(R.id.home_title, sensor.name + if (count > 1) "  ${index + 1}/$count" else "")
                views.setTextViewText(
                    R.id.home_subtitle,
                    if (!sensor.online) appString(R.string.widget_offline) else sensor.reading?.let { formatTime(it.ts) } ?: appString(R.string.widget_no_reading),
                )
            }
            val multiple = (shown?.count ?: 0) > 1
            for (arrow in listOf(R.id.home_prev, R.id.home_next)) views.setViewVisibility(arrow, if (multiple) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.home_prev, broadcast(context, EnvironmentWidget.ACTION_HOME_PREV))
            views.setOnClickPendingIntent(R.id.home_next, broadcast(context, EnvironmentWidget.ACTION_HOME_NEXT))
            views.setOnClickPendingIntent(R.id.home_cluster, activity(context, null))
            views.setOnClickPendingIntent(R.id.home_log, activity(context, "growbuddy://log"))
            manager.updateAppWidget(id, views)
        }

        private fun activity(context: Context, link: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            if (link != null) intent.setAction(Intent.ACTION_VIEW).setData(link.toUri())
            return PendingIntent.getActivity(
                context, link.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun broadcast(context: Context, action: String) = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, WidgetActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
