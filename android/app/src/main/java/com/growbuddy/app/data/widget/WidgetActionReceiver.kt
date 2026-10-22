package com.growbuddy.app.data.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.growbuddy.app.GrowBuddyApp
import kotlinx.coroutines.launch

/** ‹ › on the notification or home widget: show the previous/next sensor, then fetch fresh values. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            EnvironmentWidget.ACTION_PREV -> EnvironmentWidget.step(context, -1)
            EnvironmentWidget.ACTION_NEXT -> EnvironmentWidget.step(context, +1)
            EnvironmentWidget.ACTION_HOME_PREV -> EnvironmentWidget.step(context, -1, home = true)
            EnvironmentWidget.ACTION_HOME_NEXT -> EnvironmentWidget.step(context, +1, home = true)
            else -> return
        }
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
}
