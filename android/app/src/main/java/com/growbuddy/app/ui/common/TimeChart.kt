package com.growbuddy.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.growbuddy.app.ui.theme.Mono
import com.growbuddy.app.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Vertical marker at a time (plant milestones). */
data class ChartMarker(val ts: Long, val color: Color, val label: String)

/**
 * Time-series chart in the 0.1.6 style: line, optional min/max envelope, shaded warning
 * band, dashed critical limits, milestone markers, and a drag-to-read crosshair.
 */
@Composable
fun TimeChart(
    points: List<ChartPoint>,
    fromTs: Long,
    toTs: Long,
    bucketMs: Long,
    decimals: Int,
    unit: String,
    modifier: Modifier = Modifier,
    lineColor: Color = Palette.TextPrimary,
    warnMin: Double? = null,
    warnMax: Double? = null,
    critMin: Double? = null,
    critMax: Double? = null,
    markers: List<ChartMarker> = emptyList(),
    height: Dp = 200.dp,
) {
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val labelStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = Palette.TextSecondary)
    val span = toTs - fromTs
    val xFormat = remember(span) {
        DateTimeFormatter.ofPattern(
            when {
                span <= 36 * 3_600_000L -> "HH:mm"
                span <= 10 * 86_400_000L -> "EEE d"
                else -> "d MMM"
            },
        )
    }
    val tipFormat = remember(span) { DateTimeFormatter.ofPattern(if (span <= 36 * 3_600_000L) "HH:mm" else "d MMM HH:mm") }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            // Horizontal drags only, so vertical scrolling of the page still works over a chart.
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { touchX = it.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                ) { change, _ -> touchX = change.position.x }
            }
            .pointerInput(points) { detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null }) },
    ) {
        val left = 52.dp.toPx()
        val bottom = 22.dp.toPx()
        val top = 6.dp.toPx()
        val plotW = size.width - left - 8.dp.toPx()
        val plotH = size.height - bottom - top
        if (plotW <= 0 || plotH <= 0) return@Canvas

        val values = points.flatMap { listOfNotNull(it.value, it.min, it.max) } + listOfNotNull(warnMin, warnMax)
        val axis = if (values.isEmpty()) niceAxis(0.0, 1.0) else niceAxis(values.min(), values.max())
        fun x(ts: Long) = left + (ts - fromTs).toFloat() / span.coerceAtLeast(1) * plotW
        fun y(v: Double) = top + plotH - ((v - axis.min) / (axis.max - axis.min)).toFloat() * plotH

        // Grid + y labels.
        axis.ticks.forEach { t ->
            val yy = y(t)
            drawLine(Palette.Divider, Offset(left, yy), Offset(left + plotW, yy), strokeWidth = 1f)
            val layout = measurer.measure(formatNumber(t, axis.decimals), labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), yy - layout.size.height / 2f))
        }
        // X labels (about 4).
        for (i in 0..3) {
            val ts = fromTs + span * i / 3
            val layout = measurer.measure(xFormat.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())), labelStyle)
            val xx = (x(ts) - layout.size.width / 2f).coerceIn(left, left + plotW - layout.size.width)
            drawText(layout, topLeft = Offset(xx, top + plotH + 4.dp.toPx()))
        }
        // Warning band.
        if (warnMin != null || warnMax != null) {
            val yTop = y(warnMax ?: axis.max)
            val yBottom = y(warnMin ?: axis.min)
            drawRect(Palette.Green.copy(alpha = 0.13f), Offset(left, yTop), Size(plotW, yBottom - yTop))
        }
        // Critical limits.
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        listOfNotNull(critMin, critMax).filter { it in axis.min..axis.max }.forEach { c ->
            drawLine(Palette.Danger, Offset(left, y(c)), Offset(left + plotW, y(c)), strokeWidth = 2f, pathEffect = dash)
        }
        // Milestone markers.
        markers.filter { it.ts in fromTs..toTs }.forEach { m ->
            drawLine(m.color, Offset(x(m.ts), top), Offset(x(m.ts), top + plotH), strokeWidth = 3f, pathEffect = dash)
        }
        // Envelope + line, broken at gaps.
        splitAtGaps(points, gapThreshold(points, bucketMs)).forEach { run ->
            if (run.any { it.min != null && it.max != null }) {
                val env = Path()
                run.forEachIndexed { i, p -> val yy = y(p.max ?: p.value); if (i == 0) env.moveTo(x(p.ts), yy) else env.lineTo(x(p.ts), yy) }
                run.asReversed().forEach { p -> env.lineTo(x(p.ts), y(p.min ?: p.value)) }
                env.close()
                drawPath(env, lineColor.copy(alpha = 0.18f))
            }
            if (run.size == 1) {
                drawCircle(lineColor, 3.dp.toPx(), Offset(x(run[0].ts), y(run[0].value)))
            } else {
                val line = Path()
                run.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.ts), y(p.value)) else line.lineTo(x(p.ts), y(p.value)) }
                drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Crosshair readout.
        val tx = touchX
        if (tx != null && points.isNotEmpty()) {
            val ts = fromTs + ((tx - left) / plotW * span).toLong()
            val p = points[nearestIndex(points, ts)]
            val px = x(p.ts)
            val py = y(p.value)
            drawLine(Palette.TextSecondary, Offset(px, top), Offset(px, top + plotH), strokeWidth = 1.5f)
            drawCircle(lineColor, 5.dp.toPx(), Offset(px, py))
            val text = "${formatNumber(p.value, decimals)} $unit · ${tipFormat.format(Instant.ofEpochMilli(p.ts).atZone(ZoneId.systemDefault()))}"
            val layout = measurer.measure(text, labelStyle.copy(color = Palette.TextPrimary, fontSize = 13.sp))
            val pad = 6.dp.toPx()
            val bx = (px - layout.size.width / 2f).coerceIn(left, left + plotW - layout.size.width - 2 * pad)
            drawRoundRect(Palette.Background.copy(alpha = 0.9f), Offset(bx, top), Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad), CornerRadius(6.dp.toPx()))
            drawText(layout, topLeft = Offset(bx + pad, top + pad))
        }
    }
}
