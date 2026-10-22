package com.growbuddy.app.ui.common

import androidx.annotation.StringRes
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.Age
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

// All formatting of server values happens here: timestamps are epoch ms UTC on the wire.

enum class MetricKind(@StringRes private val labelRes: Int, val unit: String, val decimals: Int) {
    Temperature(R.string.metric_temperature, "°C", 1),
    Humidity(R.string.metric_humidity, "%", 1),
    Vpd(R.string.metric_vpd, "kPa", 2),
    ;

    val label: String get() = appString(labelRes)
}

fun formatNumber(value: Double?, decimals: Int): String =
    if (value == null) "–" else String.format(Locale.getDefault(), "%.${decimals}f", value)

fun formatBand(min: Double?, max: Double?, kind: MetricKind): String? = when {
    min != null && max != null -> "${formatNumber(min, kind.decimals)}–${formatNumber(max, kind.decimals)} ${kind.unit}"
    min != null -> "≥ ${formatNumber(min, kind.decimals)} ${kind.unit}"
    max != null -> "≤ ${formatNumber(max, kind.decimals)} ${kind.unit}"
    else -> null
}

/** "just now", "42 s ago", "5 min ago", "3 h ago", or a date. */
fun formatAgo(ts: Long?, now: Long = System.currentTimeMillis()): String {
    if (ts == null) return appString(R.string.ago_never)
    val seconds = (now - ts) / 1000
    return when {
        abs(seconds) < 10 -> appString(R.string.ago_now)
        seconds < 60 -> appString(R.string.ago_seconds, seconds.toInt())
        seconds < 3600 -> appString(R.string.ago_minutes, (seconds / 60).toInt())
        seconds < 86_400 -> appString(R.string.ago_hours, (seconds / 3600).toInt())
        else -> formatDateTime(ts)
    }
}

// Formatters are made per call: the locale changes when the app language does.
fun formatDateTime(ts: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

/** Local time of day only, e.g. "20.15". */
fun formatTime(ts: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

/** Server phase name in the app language ("EarlyVegetation" -> "Early Vegetation"). */
fun formatPhase(phase: String): String = when (phase) {
    "PreGermination" -> appString(R.string.phase_PreGermination)
    "Germinating" -> appString(R.string.phase_Germinating)
    "Seedling" -> appString(R.string.phase_Seedling)
    "EarlyVegetation" -> appString(R.string.phase_EarlyVegetation)
    "LateVegetation" -> appString(R.string.phase_LateVegetation)
    "EarlyFlower" -> appString(R.string.phase_EarlyFlower)
    "MidFlower" -> appString(R.string.phase_MidFlower)
    "LateFlower" -> appString(R.string.phase_LateFlower)
    "Drying" -> appString(R.string.phase_Drying)
    "Curing" -> appString(R.string.phase_Curing)
    "Complete" -> appString(R.string.phase_Complete)
    "TerminatedEarly" -> appString(R.string.phase_TerminatedEarly)
    else -> phase.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") // a phase newer than the app
}

/** Phases short enough to count in days ("Day 10") rather than "Week 2, Day 3". */
private val DAY_COUNTED_PHASES = setOf("Germinating", "Drying", "Curing")

/** Server-derived age for display; null when the plant has no age. */
fun formatAge(age: Age?, phase: String): String? = age?.let {
    if (phase in DAY_COUNTED_PHASES) appString(R.string.age_day, (it.week - 1) * 7 + it.day) else appString(R.string.age_week_day, it.week, it.day)
}
