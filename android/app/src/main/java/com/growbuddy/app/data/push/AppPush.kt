package com.growbuddy.app.data.push

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiProvider
import com.growbuddy.app.data.remote.PushEndpointBody
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.appString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/** State of app notifications for the Settings screen. */
data class PushState(val enabled: Boolean = false, val busy: Boolean = false, val error: String? = null)

/**
 * App notifications through UnifiedPush: the ntfy app keeps the connection to the Pi
 * and hands alerts to GrowBuddy, which posts them with its own icon. The endpoint it
 * gets is registered on the server (PUT /push-endpoint), which then publishes there.
 */
class AppPush(
    private val context: Context,
    private val apiProvider: ApiProvider,
    private val scope: CoroutineScope,
    /** Whether the server should also push environment snapshots (notification or home widget). */
    private val environmentWanted: () -> Boolean = { false },
) {
    private val prefs = context.getSharedPreferences("app_push", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PushState(enabled = UnifiedPush.getAckDistributor(context) != null))
    val state: StateFlow<PushState> = _state.asStateFlow()

    /** Re-registers on app start so a changed endpoint reaches the server. */
    fun refresh() {
        if (UnifiedPush.getAckDistributor(context) != null) UnifiedPush.register(context, messageForDistributor = MESSAGE)
    }

    fun enable(activity: Activity) {
        _state.value = PushState(busy = true)
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(context, messageForDistributor = MESSAGE)
            } else {
                _state.value = PushState(error = appString(R.string.push_no_distributor))
            }
        }
    }

    fun disable() {
        prefs.edit { remove("endpoint") }
        UnifiedPush.unregister(context)
        UnifiedPush.removeDistributor(context)
        _state.value = PushState()
        scope.launch { apiCall { apiProvider.api().deletePushEndpoint().orThrow() } }
    }

    /** Re-sends the registration, e.g. after the widget was switched on or off. */
    fun updateRegistration() {
        prefs.getString("endpoint", null)?.let(::onNewEndpoint)
    }

    internal fun onNewEndpoint(url: String) {
        prefs.edit { putString("endpoint", url) }
        scope.launch {
            _state.value = apiCall { apiProvider.api().putPushEndpoint(PushEndpointBody(url, environmentWanted())) }.fold(
                onSuccess = { PushState(enabled = true) },
                onFailure = { PushState(enabled = false, error = it.message) },
            )
        }
    }

    internal fun onFailed(message: String) {
        _state.value = PushState(error = message)
    }

    internal fun onUnregistered() {
        _state.value = PushState()
        scope.launch { apiCall { apiProvider.api().deletePushEndpoint().orThrow() } }
    }

    private companion object {
        const val MESSAGE = "GrowBuddy alerts"
    }
}
