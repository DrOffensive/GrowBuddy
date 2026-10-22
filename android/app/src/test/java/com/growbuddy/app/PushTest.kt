package com.growbuddy.app

import com.growbuddy.app.data.push.AlertNotifications
import com.growbuddy.app.data.push.PushAlert
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.data.remote.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTest {
    init {
        TestStrings.install()
    }

    @Test
    fun parsesTheServerPayload() {
        // Body the server publishes to the app's push topic (server alerts/ntfy.py _app_payload).
        val body = """{"title": "Grow Tent: humidity low", "message": "Humidity 37.1% is outside the warning range.",
            "priority": 4, "tags": ["warning"], "click": "growbuddy://alerts"}"""
        assertEquals(
            PushAlert("Grow Tent: humidity low", "Humidity 37.1% is outside the warning range.", 4, listOf("warning"), "growbuddy://alerts"),
            AlertNotifications.parse(body.toByteArray()),
        )
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(AlertNotifications.parse("not json".toByteArray()))
        assertNull(AlertNotifications.parse("""{"message": "no title"}""".toByteArray()))
    }

    @Test
    fun parsesTheEnvironmentSnapshot() {
        // Body of services/environment_push.py environment_snapshot().
        val body = """{"type": "environment", "ts": 1792346630000, "sensors": [{"id": 1, "name": "Grow Tent", "online": true,
            "reading": {"ts": 1792346625000, "temperature_c": 23.5, "humidity_pct": 55.0, "vpd_kpa": 1.29},
            "ranges": {"temperature": {"warn_min": 20, "warn_max": 26, "crit_min": 18, "crit_max": 28},
                       "humidity": {"warn_min": 40, "warn_max": 50, "crit_min": 35, "crit_max": 60},
                       "vpd": {"warn_min": null, "warn_max": null, "crit_min": null, "crit_max": null}},
            "levels": {"temperature": "normal", "humidity": "warning", "vpd": null}}]}"""
        val sensor = ApiJson.decodeFromString<WidgetSnapshot>(body).sensors.single()
        assertEquals("Grow Tent", sensor.name)
        assertEquals(55.0, sensor.reading!!.humidityPct!!, 0.0)
        assertEquals(40.0, sensor.ranges.humidity.warnMin!!, 0.0)
        assertEquals("warning", sensor.levels.humidity)
        assertNull(sensor.levels.vpd)
    }
}
