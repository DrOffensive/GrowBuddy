package com.growbuddy.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.growbuddy.app.ui.theme.Mono
import com.growbuddy.app.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.sin

/** Scale of a dial; widened automatically to include the band and the value. */
data class DialScale(val min: Float, val max: Float, val step: Float) {
    fun including(vararg values: Double?): DialScale {
        val present = values.filterNotNull().map { it.toFloat() }
        val lo = (present + min).min()
        val hi = (present + max).max()
        return copy(min = floor(lo / step) * step, max = ceil(hi / step) * step)
    }

    companion object {
        // 0.1.6 scales.
        val Temperature = DialScale(15f, 30f, 5f)
        val Humidity = DialScale(30f, 100f, 10f)
        val Vpd = DialScale(0f, 2f, 0.5f)
    }
}

/** 0.1.6 DialDisplay arc: from 9 o'clock clockwise over the top. Side dials 135°, VPD 180°. */
const val SIDE_DIAL_SWEEP = 135f
const val HALF_DIAL_SWEEP = 180f
private const val PINS = 5
private const val NEEDLE_LENGTH = 0.72f // of the arc radius; the tip stays inside the arc

/**
 * Port of 0.1.6's DialDisplay: white arc with 5 pins, red danger bands outside
 * [bandMin]..[bandMax], labels at the scale ends (muted) and band edges (red),
 * and a spring-animated needle.
 */
@Composable
fun Dial(
    value: Double?,
    scale: DialScale,
    bandMin: Double?,
    bandMax: Double?,
    unit: String,
    decimals: Int,
    valueColor: Color,
    modifier: Modifier = Modifier,
    labelDecimals: Int = 0,
    valueAlignment: Alignment = Alignment.BottomEnd,
    showBandLabels: Boolean = true,
    sweep: Float = SIDE_DIAL_SWEEP,
    labelsInside: Boolean = false,
    endLabelColor: Color = Palette.TextSecondary,
    radiusFactor: Float = 0.35f, // arc radius as a fraction of the dial width
) {
    val target = value?.toFloat()?.coerceIn(scale.min, scale.max) ?: scale.min
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow),
        label = "needle",
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1.35f)) {
        Canvas(Modifier.matchParentSize()) {
            drawDialFace(
                needle = if (value != null) animated else null,
                scale = scale,
                bandMin = bandMin,
                bandMax = bandMax,
                measurer = measurer,
                labelDecimals = labelDecimals,
                showBandLabels = showBandLabels,
                sweep = sweep,
                labelsInside = labelsInside,
                endLabelColor = endLabelColor,
                radiusFactor = radiusFactor,
            )
        }
        // Value readout bottom-right, number white (or level colour) and unit muted, as in 0.1.6.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = valueColor)) { append(formatNumber(value, decimals)) }
                withStyle(SpanStyle(color = Palette.TextSecondary)) { append(unit) }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mono, fontSize = (maxWidth.value / 8.5f).sp),
            modifier = Modifier.align(valueAlignment),
        )
    }
}

/**
 * Draws a dial face (arc, danger bands, pins, labels, needle at [needle] or none) into
 * this DrawScope. Shared by [Dial] and the notification widget, which renders it to a bitmap.
 */
fun DrawScope.drawDialFace(
    needle: Float?,
    scale: DialScale,
    bandMin: Double?,
    bandMax: Double?,
    measurer: TextMeasurer,
    labelDecimals: Int = 0,
    showBandLabels: Boolean = true,
    sweep: Float = SIDE_DIAL_SWEEP,
    labelsInside: Boolean = false,
    endLabelColor: Color = Palette.TextSecondary,
    radiusFactor: Float = 0.35f,
) {
    val radius = size.width * radiusFactor
    val arcWidth = radius * 0.12f
    // 135° arcs lean right, so shift those slightly; a 180° arc is symmetric and sits centred.
    val centerX = if (sweep >= HALF_DIAL_SWEEP) 0.5f else 0.52f
    val center = Offset(size.width * centerX, size.height * 0.64f)
    fun t(v: Float) = ((v - scale.min) / (scale.max - scale.min)).coerceIn(0f, 1f)
    fun angleOf(tv: Float) = 180f + tv * sweep // Compose: clockwise from 3 o'clock

    // Main white arc.
    drawArcBand(center, radius, arcWidth, angleOf(0f), sweep, Palette.TextPrimary)
    // Danger bands on the inside, outside the target band.
    val inner = radius - arcWidth * 1.0f
    bandMin?.let { lo -> t(lo.toFloat()).takeIf { it > 0f }?.let { drawArcBand(center, inner, arcWidth, angleOf(0f), it * sweep, Palette.Danger) } }
    bandMax?.let { hi -> t(hi.toFloat()).takeIf { it < 1f }?.let { drawArcBand(center, inner, arcWidth, angleOf(it), (1f - it) * sweep, Palette.Danger) } }
    // Pins.
    for (i in 0 until PINS) {
        val a = Math.toRadians(angleOf(i / (PINS - 1f)).toDouble())
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(
            Palette.TextPrimary,
            center + dir * (radius + arcWidth / 2),
            center + dir * (radius - arcWidth * 1.5f),
            strokeWidth = arcWidth * 0.9f,
        )
    }
    // Labels: scale ends muted, band edges red (0.1.6 behaviour).
    val labelStyle = TextStyle(fontFamily = Mono, fontSize = (radius / 3.9f).toSp())
    val labelRadius = if (labelsInside) radius - arcWidth * 3.4f else radius + arcWidth * 2.6f
    fun label(v: Double, color: Color) =
        drawLabel(measurer, formatNumber(v, labelDecimals), labelStyle.copy(color = color), center, labelRadius, angleOf(t(v.toFloat())))
    label(scale.min.toDouble(), endLabelColor)
    label(scale.max.toDouble(), endLabelColor)
    if (showBandLabels) {
        bandMin?.takeIf { it > scale.min && it < scale.max }?.let { label(it, Palette.Danger) }
        bandMax?.takeIf { it > scale.min && it < scale.max }?.let { label(it, Palette.Danger) }
    }
    // Needle (tapered, like dial_arrow.png) and hub.
    if (needle != null) {
        rotate(angleOf(t(needle)), pivot = center) {
            val len = radius * NEEDLE_LENGTH
            val w = arcWidth * 0.55f
            val path = Path().apply {
                moveTo(center.x, center.y - w)
                lineTo(center.x + len, center.y - w * 0.15f)
                lineTo(center.x + len, center.y + w * 0.15f)
                lineTo(center.x, center.y + w)
                close()
            }
            drawPath(path, Palette.TextPrimary)
        }
    }
    drawCircle(Palette.TextPrimary, radius = arcWidth * 0.95f, center = center)
    drawCircle(Palette.Card, radius = arcWidth * 0.4f, center = center)
}

private fun DrawScope.drawArcBand(center: Offset, radius: Float, width: Float, start: Float, sweep: Float, color: Color) {
    if (sweep <= 0f) return
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Butt),
    )
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, style: TextStyle, center: Offset, r: Float, angle: Float) {
    val layout = measurer.measure(text, style)
    val a = Math.toRadians(angle.toDouble())
    val p = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * r
    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
}
