package com.growbuddy.app.data.remote

import kotlinx.serialization.Serializable

// Mirrors docs/API.md (/api/v1). Field names map to snake_case via the Json naming strategy.
// Timestamps are epoch milliseconds UTC; convert to local time only in the UI.

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String)

// ------------------------------------------------------------------ system

@Serializable
data class Health(val status: String, val version: String)

@Serializable
data class ServerStatus(
    val version: String,
    val uptimeS: Long,
    val schemaVersion: Int,
    val dbSizeBytes: Long,
    val diskFreeBytes: Long,
    val ble: BleStatus,
    val camera: CameraStatus,
    val alerts: AlertsStatus,
    val lastBackup: String? = null,
    val lastBackupTs: Long? = null,
)

@Serializable
data class BleStatus(
    val source: String,
    val state: String,
    val error: String? = null,
    val lastAdvertTs: Long? = null,
    val adopted: Int,
    val discovered: Int,
)

@Serializable
data class CameraStatus(
    val state: String,
    val error: String? = null,
    val viewers: Int,
    val lastSnapshotTs: Long? = null,
    val nextSnapshotTs: Long? = null,
)

@Serializable
data class AlertsStatus(val activeAlerts: Int, val outbox: Int, val lastNotifyError: String? = null)

// ----------------------------------------------------------------- sensors

@Serializable
data class Sensor(
    val id: Long,
    val mac: String,
    val name: String,
    val status: String,
    val alertsEnabled: Boolean,
    val adoptedTs: Long,
    val lastSeen: Long? = null,
    val batteryPct: Int? = null,
    val rssi: Int? = null,
    val rangeMode: String = "auto",
)

/** Auto range mode: the plant whose phase drives a sensor's ranges. */
@Serializable
data class RangeSource(val plantId: String, val nickname: String, val phase: String)

@Serializable
data class SensorDetail(
    val id: Long,
    val mac: String,
    val name: String,
    val status: String,
    val alertsEnabled: Boolean,
    val adoptedTs: Long,
    val lastSeen: Long? = null,
    val batteryPct: Int? = null,
    val rssi: Int? = null,
    val rangeMode: String = "auto",
    val ranges: SensorRanges,
    val rangeSource: RangeSource? = null,
)

@Serializable
data class MetricRange(
    val warnMin: Double? = null,
    val warnMax: Double? = null,
    val critMin: Double? = null,
    val critMax: Double? = null,
)

@Serializable
data class SensorRanges(
    val temperature: MetricRange = MetricRange(),
    val humidity: MetricRange = MetricRange(),
    val vpd: MetricRange = MetricRange(),
)

@Serializable
data class LiveValues(
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val batteryPct: Int? = null,
    val voltageV: Double? = null,
)

@Serializable
data class DiscoveredSensor(
    val mac: String,
    val name: String? = null,
    val rssi: Int? = null,
    val lastSeen: Long,
    val encrypted: Boolean,
    val values: LiveValues? = null,
)

@Serializable
data class AdoptRequest(val mac: String, val name: String)

@Serializable
data class SensorPatch(val name: String? = null, val alertsEnabled: Boolean? = null, val rangeMode: String? = null)

@Serializable
data class PhasePresetRequest(val phases: List<String>, val apply: Boolean)

@Serializable
data class PhaseRange(
    val phase: String,
    val enforceLimits: Boolean,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    val humidityMin: Double? = null,
    val humidityMax: Double? = null,
    val vpdMin: Double? = null,
    val vpdMax: Double? = null,
)

// ------------------------------------------------------------ environment

@Serializable
data class Age(val week: Int, val day: Int)

@Serializable
data class StrainSummary(
    val id: Long,
    val name: String,
    val vendor: String? = null,
    val category: String? = null,
    val floweringDays: String? = null,
)

@Serializable
data class Plant(
    val id: String,
    val nickname: String,
    val strainId: Long? = null,
    val strain: StrainSummary? = null,
    val isAutoflower: Boolean,
    val sensorId: Long? = null,
    val thumbnailPhotoId: String? = null,
    val archived: Boolean,
    val createdTs: Long,
    val milestones: Map<String, Long> = emptyMap(),
    val phase: String,
    val age: Age? = null,
)

@Serializable
data class CurrentReading(
    val ts: Long,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val vpdKpa: Double? = null,
)

@Serializable
data class Levels(val temperature: String? = null, val humidity: String? = null, val vpd: String? = null)

@Serializable
data class SensorEnvironment(
    val sensor: Sensor,
    val online: Boolean,
    val reading: CurrentReading? = null,
    val ranges: SensorRanges,
    val rangeSource: RangeSource? = null,
    val levels: Levels,
    val plants: List<Plant> = emptyList(),
)

@Serializable
data class EnvironmentCurrent(
    val ts: Long,
    val sensors: List<SensorEnvironment>,
    val unlocatedPlants: List<Plant> = emptyList(),
)

// ---------------------------------------------------------------- plants

@Serializable
data class PlantCreate(
    val id: String,
    val nickname: String,
    val strainId: Long? = null,
    val isAutoflower: Boolean = false,
    val sensorId: Long? = null,
)

// --------------------------------------------------------------- strains

@Serializable
data class Strain(
    val id: Long,
    val name: String,
    val vendor: String? = null,
    val category: String? = null,
    val shopCategory: String? = null,
    val thc: String? = null,
    val cbd: String? = null,
    val floweringDays: String? = null,
    val yieldIndoor: String? = null,
    val height: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val custom: Boolean = false, // added by the user: editable and deletable
)

/** POST /strains and PATCH /strains/{id} (all fields sent, so null clears). */
@Serializable
data class StrainBody(
    val name: String,
    val vendor: String? = null,
    val category: String? = null,
    val thc: String? = null,
    val cbd: String? = null,
    val floweringDays: String? = null,
    val yieldIndoor: String? = null,
    val height: String? = null,
    val description: String? = null,
)

@Serializable
data class StrainPage(val items: List<Strain>, val total: Int)

// ------------------------------------------------------------------ logs

@Serializable
data class SnapshotEntry(
    val sensorId: Long,
    val sensorName: String,
    val ts: Long,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val vpdKpa: Double? = null,
)

@Serializable
data class LogEntry(
    val id: String,
    val ts: Long,
    val type: String,
    val plantIds: List<String> = emptyList(),
    val photoIds: List<String> = emptyList(),
    val comment: String? = null,
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val sensorSnapshot: List<SnapshotEntry> = emptyList(),
    val createdTs: Long,
    val updatedTs: Long,
)

@Serializable
data class LogPage(val items: List<LogEntry>, val nextBefore: Long? = null)

@Serializable
data class LogCreate(
    val id: String,
    val type: String,
    val plantIds: List<String>,
    val ts: Long? = null,
    val comment: String? = null,
    val photoIds: List<String> = emptyList(),
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class Photo(val id: String, val ts: Long, val width: Int, val height: Int, val sizeBytes: Long)

// ------------------------------------------------------ nutrients/feeding

@Serializable
data class NutrientProduct(val id: Long, val name: String, val description: String? = null, val unit: String)

@Serializable
data class NutrientLine(val id: Long, val brand: String, val description: String? = null, val products: List<NutrientProduct>)

@Serializable
data class FeedingScheduleSummary(
    val id: Long,
    val profileKey: String,
    val displayName: String,
    val substrate: String? = null,
    val custom: Boolean = false,
    val weeks: Int,
)

@Serializable
data class MinMaxTarget(val min: Double? = null, val max: Double? = null, val target: Double? = null)

@Serializable
data class ScheduleEnvironment(
    val tempDay: MinMaxTarget? = null,
    val tempNight: MinMaxTarget? = null,
    val humidity: MinMaxTarget? = null,
    val lightHours: Int? = null,
)

@Serializable
data class FeedingWeek(
    val week: Int,
    val phase: String,
    val nutrients: Map<String, Double> = emptyMap(),
    val ph: MinMaxTarget? = null,
    val ec: MinMaxTarget? = null,
    val environment: ScheduleEnvironment? = null,
    val notes: String? = null,
)

@Serializable
data class FeedingSchedule(
    val id: Long,
    val profileKey: String,
    val displayName: String,
    val substrate: String? = null,
    val custom: Boolean = false,
    val weeks: List<FeedingWeek>,
)

/** POST /feeding-schedules and PUT /feeding-schedules/{id}: weeks are numbered by position. */
@Serializable
data class FeedingScheduleBody(val displayName: String, val substrate: String? = null, val weeks: List<FeedingWeekBody>)

@Serializable
data class FeedingWeekBody(
    val phase: String,
    val nutrients: Map<String, Double> = emptyMap(),
    val ph: MinMaxTarget? = null,
    val ec: MinMaxTarget? = null,
    val environment: ScheduleEnvironment? = null,
    val notes: String? = null,
)

// -------------------------------------------------------------- readings

@Serializable
data class SeriesPoint(
    val ts: Long,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    val vpdKpa: Double? = null,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    val humMin: Double? = null,
    val humMax: Double? = null,
    val samples: Int = 1,
)

@Serializable
data class ReadingSeries(
    val sensorId: Long,
    val resolution: String,
    val bucketMs: Long,
    val fromTs: Long,
    val toTs: Long,
    val points: List<SeriesPoint>,
)

// ---------------------------------------------------------------- alerts

@Serializable
data class Alert(
    val id: Long,
    val sensorId: Long? = null,
    val sensorName: String? = null,
    val metric: String,
    val level: String,
    val startedTs: Long,
    val endedTs: Long? = null,
    val value: Double? = null,
    val bandMin: Double? = null,
    val bandMax: Double? = null,
    val notifiedTs: Long? = null,
)

@Serializable
data class AlertPage(val items: List<Alert>, val nextBefore: Long? = null)

@Serializable
data class AlertSettings(
    val debounceS: Int,
    val warningDebounceS: Int = 1800,
    val language: String = "en", // notification texts (GET /notification-languages)
    val repeatAfterS: Int,
    val offlineAfterS: Int,
    val batteryLowPct: Int,
    val notifyWarning: Boolean,
    val notifyCritical: Boolean,
    val notifyResolved: Boolean,
)

@Serializable
data class NotificationLanguage(val code: String, val name: String)

@Serializable
data class Event(
    val id: Long,
    val ts: Long,
    val level: String,
    val source: String,
    val code: String,
    val message: String,
)

@Serializable
data class EventPage(val items: List<Event>, val nextBefore: Long? = null)

// ------------------------------------------------------------- snapshots

@Serializable
data class Snapshot(val id: Long, val ts: Long, val kind: String, val width: Int, val height: Int, val sizeBytes: Long)

@Serializable
data class SnapshotPage(val items: List<Snapshot>, val nextBefore: Long? = null)

/** The app's UnifiedPush endpoint as stored on the server (null = not registered). */
@Serializable
data class PushEndpointBody(val endpoint: String? = null, val environment: Boolean = false)

/** Environment snapshot for the notification widget: pushed by the server (services/environment_push.py) or built from /environment/current. */
@Serializable
data class WidgetSnapshot(val ts: Long, val sensors: List<WidgetSensor>) {
    companion object {
        fun of(env: EnvironmentCurrent) = WidgetSnapshot(
            env.ts,
            env.sensors.map { WidgetSensor(it.sensor.id, it.sensor.name, it.online, it.reading, it.ranges, it.levels) },
        )
    }
}

@Serializable
data class WidgetSensor(
    val id: Long,
    val name: String,
    val online: Boolean,
    val reading: CurrentReading? = null,
    val ranges: SensorRanges = SensorRanges(),
    val levels: Levels = Levels(),
)

