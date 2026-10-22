package com.growbuddy.app.data.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.growbuddy.app.data.remote.WidgetSensor
import com.growbuddy.app.ui.common.DialScale
import com.growbuddy.app.ui.common.HALF_DIAL_SWEEP
import com.growbuddy.app.ui.common.drawDialFace
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.levelColor
import com.growbuddy.app.ui.theme.Condensed
import com.growbuddy.app.ui.theme.Mono
import com.growbuddy.app.ui.theme.Palette
import kotlin.math.roundToInt

enum class WidgetMetric { Temperature, Humidity, Vpd }

/** The dashboard dials (ui/common/Dial.kt) rendered to bitmaps for the widgets, on dark cards so they read on any background. */
object DialBitmaps {
    private data class Spec(val value: Double?, val bandMin: Double?, val bandMax: Double?, val level: String?, val scale: DialScale, val unit: String, val decimals: Int)

    private fun spec(metric: WidgetMetric, sensor: WidgetSensor): Spec {
        val r = sensor.reading
        val ranges = sensor.ranges
        val levels = sensor.levels
        return when (metric) {
            WidgetMetric.Temperature -> Spec(
                r?.temperatureC, ranges.temperature.warnMin, ranges.temperature.warnMax, levels.temperature,
                DialScale.Temperature.including(ranges.temperature.warnMin, ranges.temperature.warnMax, r?.temperatureC), "°C", 1,
            )
            WidgetMetric.Humidity -> Spec(
                r?.humidityPct, ranges.humidity.warnMin, ranges.humidity.warnMax, levels.humidity,
                DialScale.Humidity.including(ranges.humidity.warnMin, ranges.humidity.warnMax, r?.humidityPct), "%RH", 0,
            )
            WidgetMetric.Vpd -> Spec(
                r?.vpdKpa, ranges.vpd.warnMin, ranges.vpd.warnMax, levels.vpd,
                DialScale.Vpd.including(ranges.vpd.warnMax, r?.vpdKpa), "kPa", 2,
            )
        }
    }

    private fun valueText(spec: Spec, online: Boolean) = buildAnnotatedString {
        withStyle(SpanStyle(color = if (online) levelColor(spec.level ?: "normal") else Palette.TextSecondary)) {
            append(formatNumber(spec.value, spec.decimals))
        }
        withStyle(SpanStyle(color = Palette.TextSecondary)) { append(spec.unit) }
    }

    /**
     * The dashboard's triangle for the home widget, [widthDp] × [heightDp] on a transparent
     * background: temperature and humidity on cards side by side (value under the dial's min
     * label, so the cards stay tight), the VPD disc centred below, overlapping both. The cards
     * take about half the height (the widget layout's arrows line up with that), and the
     * cluster is centred horizontally.
     */
    fun renderCluster(context: Context, sensor: WidgetSensor, widthDp: Float, heightDp: Float): Bitmap {
        val density = Density(context)
        val px = density.density
        val image = ImageBitmap((widthDp * px).roundToInt(), (heightDp * px).roundToInt())
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val gap = 8 * px
        // Card geometry for a card width: the value sits just under the min label (left end of
        // the arc, level with the hub), and the disc overlaps the cards' bottom padding.
        class Geometry(val cardW: Float) {
            val pad = cardW * 0.05f
            val dialW = cardW - 2 * pad
            val dialH = dialW / 1.35f
            val valueStyle = TextStyle(fontFamily = Mono, fontSize = (dialW / px / 8.5f).sp)
            val valueTop = pad * 0.6f + dialH * 0.64f + dialW * 0.07f
            val cardH = valueTop + measurer.measure("0", valueStyle).size.height + pad
            val disc = cardW * DISC
            val discTop = cardH - pad * 1.2f
            val height = discTop + disc
        }
        // As wide as fits both cards side by side and, with the disc below, the height (everything scales with the width).
        val trial = Geometry(image.height / 2f)
        val g = Geometry(minOf((image.width - gap) / 2f, trial.cardW * image.height / trial.height))
        val cardW = g.cardW
        val disc = g.disc
        val left = (image.width - 2 * cardW - gap) / 2f
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
            fun sideCard(metric: WidgetMetric, x: Float) {
                val spec = spec(metric, sensor)
                drawRoundRect(Palette.Card, topLeft = Offset(x, 0f), size = Size(cardW, g.cardH), cornerRadius = CornerRadius(10 * px))
                inset(x + g.pad, g.pad * 0.6f, size.width - x - cardW + g.pad, size.height - g.pad * 0.6f - g.dialH) {
                    drawDialFace(spec.value?.toFloat()?.coerceIn(spec.scale.min, spec.scale.max), spec.scale, spec.bandMin, spec.bandMax, measurer)
                }
                drawText(measurer.measure(valueText(spec, sensor.online), g.valueStyle), topLeft = Offset(x + g.pad, g.valueTop))
            }
            sideCard(WidgetMetric.Temperature, left)
            sideCard(WidgetMetric.Humidity, left + cardW + gap)

            // VPD disc: half-circle dial filling the disc, value under the hub, "VPD" below (dashboard proportions).
            val spec = spec(WidgetMetric.Vpd, sensor)
            // Overlaps the cards' inner bottom corners, below their values.
            val center = Offset(size.width / 2f, g.discTop + disc / 2f)
            drawCircle(Palette.Card, radius = disc / 2f, center = center)
            drawCircle(Palette.Disc, radius = disc / 2f - disc * 0.03f, center = center)
            val discLeft = center.x - disc / 2f
            val discTop = center.y - disc / 2f
            val side = disc * 6f / 170f
            val dialW = disc - 2 * side
            val dialH = dialW / 1.35f
            val dialTop = discTop + disc * 11f / 170f
            inset(discLeft + side, dialTop, size.width - discLeft - disc + side, size.height - dialTop - dialH) {
                drawDialFace(
                    needle = spec.value?.toFloat()?.coerceIn(spec.scale.min, spec.scale.max),
                    scale = spec.scale,
                    bandMin = spec.bandMin,
                    bandMax = spec.bandMax,
                    measurer = measurer,
                    showBandLabels = false,
                    sweep = HALF_DIAL_SWEEP,
                    labelsInside = true,
                    endLabelColor = Palette.TextPrimary,
                    radiusFactor = 0.43f,
                )
            }
            val value = measurer.measure(valueText(spec, sensor.online), TextStyle(fontFamily = Mono, fontSize = (dialW / px / 8.5f).sp))
            drawText(value, topLeft = Offset(center.x - value.size.width / 2f, dialTop + dialH - value.size.height))
            val title = measurer.measure("VPD", TextStyle(color = Palette.TextPrimary, fontFamily = Condensed, fontSize = (disc / px * 22f / 170f).sp))
            drawText(title, topLeft = Offset(center.x - title.size.width / 2f, dialTop + dialH + disc * 1f / 170f))
        }
        return image.asAndroidBitmap()
    }

    private const val DISC = 0.88f // VPD disc diameter / card width: the VPD dial is the main one

    fun render(context: Context, metric: WidgetMetric, sensor: WidgetSensor, widthDp: Float, compact: Boolean): Bitmap {
        val density = Density(context)
        val w = (widthDp * density.density).roundToInt()
        val h = (w / 1.35f).roundToInt()
        val image = ImageBitmap(w, h)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val r = sensor.reading
        val (value, range, level) = when (metric) {
            WidgetMetric.Temperature -> Triple(r?.temperatureC, sensor.ranges.temperature, sensor.levels.temperature)
            WidgetMetric.Humidity -> Triple(r?.humidityPct, sensor.ranges.humidity, sensor.levels.humidity)
            WidgetMetric.Vpd -> Triple(r?.vpdKpa, sensor.ranges.vpd, sensor.levels.vpd)
        }
        val scale = when (metric) {
            WidgetMetric.Temperature -> DialScale.Temperature.including(range.warnMin, range.warnMax, value)
            WidgetMetric.Humidity -> DialScale.Humidity.including(range.warnMin, range.warnMax, value)
            WidgetMetric.Vpd -> DialScale.Vpd.including(range.warnMax, value)
        }
        val (unit, decimals) = when (metric) {
            WidgetMetric.Temperature -> "°C" to 1
            WidgetMetric.Humidity -> "%RH" to 0
            WidgetMetric.Vpd -> "kPa" to 2
        }
        val vpd = metric == WidgetMetric.Vpd
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
            drawRoundRect(Palette.Card, cornerRadius = CornerRadius(8 * density.density))
            drawDialFace(
                needle = value?.toFloat()?.coerceIn(scale.min, scale.max),
                scale = scale,
                bandMin = range.warnMin,
                bandMax = range.warnMax,
                measurer = measurer,
                showBandLabels = !compact && !vpd,
                sweep = if (vpd) HALF_DIAL_SWEEP else com.growbuddy.app.ui.common.SIDE_DIAL_SWEEP,
                labelsInside = vpd,
                endLabelColor = if (vpd) Palette.TextPrimary else Palette.TextSecondary,
            )
            // Value readout, as on the dashboard (larger when compact so it stays readable).
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = if (sensor.online) levelColor(level ?: "normal") else Palette.TextSecondary)) {
                    append(formatNumber(value, decimals))
                }
                withStyle(SpanStyle(color = Palette.TextSecondary)) { append(unit) }
            }
            val layout = measurer.measure(text, TextStyle(fontFamily = Mono, fontSize = (widthDp / if (compact) 6.5f else 8.5f).sp))
            val pad = 4 * density.density
            drawText(layout, topLeft = Offset(size.width - layout.size.width - pad, size.height - layout.size.height - pad / 2))
        }
        return image.asAndroidBitmap()
    }
}
