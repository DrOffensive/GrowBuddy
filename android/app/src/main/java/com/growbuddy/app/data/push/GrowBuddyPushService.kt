package com.growbuddy.app.data.push

import com.growbuddy.app.GrowBuddyApp
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.data.remote.WidgetSnapshot
import com.growbuddy.app.data.widget.EnvironmentWidget
import com.growbuddy.app.ui.common.appString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives UnifiedPush events from the distributor (the ntfy app). */
class GrowBuddyPushService : PushService() {
    private val appPush get() = (application as GrowBuddyApp).container.appPush

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = appPush.onNewEndpoint(endpoint.url)

    override fun onMessage(message: PushMessage, instance: String) {
        // The server publishes plain JSON, so decrypted is false and content is the raw body.
        val body = message.content.decodeToString()
        val type = runCatching { ApiJson.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        if (type == "environment") {
            runCatching { ApiJson.decodeFromString<WidgetSnapshot>(body) }.getOrNull()?.let { EnvironmentWidget.update(this, it) }
        } else {
            AlertNotifications.parse(message.content)?.let { AlertNotifications.show(this, it) }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = appPush.onFailed(appString(R.string.push_failed, reason.toString()))

    override fun onUnregistered(instance: String) = appPush.onUnregistered()
}
