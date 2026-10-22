package com.growbuddy.app.ui.journal

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.growbuddy.app.R
import com.growbuddy.app.ui.common.appString

/** What a log type records in `data` (docs/DOMAIN.md › Log entries). */
enum class DataKind { None, Feeding, LightHeight, HumidityTarget, TemperatureTarget }

/** Log types with 0.1.6 names, groups and colours (PlantHelper.GetHexColor). */
enum class LogType(val api: String, @StringRes private val labelRes: Int, val color: Color, val data: DataKind = DataKind.None) {
    Inspection("Inspection", R.string.log_Inspection, Color(0xFF90A4AE)),
    Cure("Cure", R.string.log_Cure, Color(0xFF4A5568)),
    Topping("Topping", R.string.log_Topping, Color(0xFFE9D8FD)),
    Lst("LST", R.string.log_LST, Color(0xFFD6BCFA)),
    Pruning("Pruning", R.string.log_Pruning, Color(0xFFB794F4)),
    Feeding("Feeding", R.string.log_Feeding, Color(0xFF805AD5), DataKind.Feeding),
    LightAdjust("LightAdjust", R.string.log_LightAdjust, Color(0xFF90CDF4), DataKind.LightHeight),
    HumidityAdjust("HumidityAdjust", R.string.log_HumidityAdjust, Color(0xFF63B3ED), DataKind.HumidityTarget),
    TemperatureAdjust("TemperatureAdjust", R.string.log_TemperatureAdjust, Color(0xFF3182CE), DataKind.TemperatureTarget),
    Germination("Germination", R.string.log_Germination, Color(0xFFFFD700)),
    Sprout("Sprout", R.string.log_Sprout, Color(0xFF00FF66)),
    FlowerFlip("FlowerFlip", R.string.log_FlowerFlip, Color(0xFFFF1493)),
    Harvest("Harvest", R.string.log_Harvest, Color(0xFFFF3333)),
    Trim("Trim", R.string.log_Trim, Color(0xFFFF6F00)),
    TerminatedEarly("TerminatedEarly", R.string.log_TerminatedEarly, Color(0xFF9E9E9E)),
    ;

    val label: String get() = appString(labelRes)

    companion object {
        fun of(api: String): LogType? = entries.firstOrNull { it.api == api }

        /** 0.1.6 wizard groups (TerminatedEarly is a plant action, not a wizard choice). */
        val groups: List<Pair<String?, List<LogType>>> get() = listOf(
            null to listOf(Inspection, Cure),
            appString(R.string.log_group_care) to listOf(Topping, Lst, Pruning, Feeding),
            appString(R.string.log_group_environment) to listOf(LightAdjust, HumidityAdjust, TemperatureAdjust),
            appString(R.string.log_group_milestones) to listOf(Germination, Sprout, FlowerFlip, Harvest, Trim),
        )
    }
}

fun logTypeLabel(api: String): String = LogType.of(api)?.label ?: api
fun logTypeColor(api: String): Color = LogType.of(api)?.color ?: Color.White
