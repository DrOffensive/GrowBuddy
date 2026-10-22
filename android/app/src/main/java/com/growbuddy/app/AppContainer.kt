package com.growbuddy.app

import android.content.Context
import com.growbuddy.app.data.push.AppPush
import com.growbuddy.app.data.widget.EnvironmentWidget
import com.growbuddy.app.data.remote.Alert
import com.growbuddy.app.data.remote.ApiProvider
import com.growbuddy.app.data.remote.MjpegStream
import com.growbuddy.app.data.remote.PhotoUploader
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.settings.SettingsStore
import com.growbuddy.app.data.sync.Outbox
import com.growbuddy.app.data.sync.OutboxItem
import com.growbuddy.app.data.sync.OutboxSync
import com.growbuddy.app.data.sync.SyncWorker
import com.growbuddy.app.ui.common.polling
import com.growbuddy.app.ui.common.refreshTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Manual dependency container (the app is small enough not to need a DI framework). */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val apiProvider = ApiProvider(settings.config, appScope, cacheDir = context.cacheDir)
    val mjpeg = MjpegStream(apiProvider.streamClient)
    val outbox = Outbox(File(context.filesDir, "outbox"))
    val sync = OutboxSync(outbox, api = { apiProvider.api() })
    val photos = PhotoUploader(context.contentResolver, apiProvider, outbox, onQueued = ::scheduleSync)
    val appPush = AppPush(context, apiProvider, appScope, environmentWanted = { EnvironmentWidget.wanted(context) })

    private val alertsTrigger = refreshTrigger()

    /** Active alerts by level, polled while something shows them (dashboard icon, home tab). */
    val alertCounts: StateFlow<AlertCounts> = polling(15_000, alertsTrigger) { apiCall { apiProvider.api().activeAlerts() } }
        .runningFold(AlertCounts()) { prev, result -> result.fold(onSuccess = AlertCounts::of, onFailure = { prev }) }
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), AlertCounts())

    fun refreshAlerts() {
        alertsTrigger.tryEmit(Unit)
    }

    fun scheduleSync() = SyncWorker.schedule(context)

    /** Queues a write that failed because the server was unreachable, and schedules a sync. */
    suspend fun queue(item: OutboxItem) {
        outbox.add(item)
        scheduleSync()
    }
}

/** Number of active alerts per level ("warning" | "critical"). */
data class AlertCounts(val warning: Int = 0, val critical: Int = 0) {
    val total: Int get() = warning + critical

    companion object {
        fun of(alerts: List<Alert>) = AlertCounts(alerts.count { it.level == "warning" }, alerts.count { it.level == "critical" })
    }
}

