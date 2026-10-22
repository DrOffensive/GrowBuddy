package com.growbuddy.app.ui.common

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/** A value at a time, with an optional min/max envelope (aggregated series). */
data class ChartPoint(val ts: Long, val value: Double, val min: Double? = null, val max: Double? = null)

/** Axis range with "nice" tick values (1, 2, 2.5, 5 × 10^n steps). */
data class Axis(val min: Double, val max: Double, val ticks: List<Double>, val step: Double) {
    /** Fewest decimals that show every tick exactly (step 2.5 -> 1, 0.25 -> 2, 5 -> 0). */
    val decimals: Int
        get() = (0..3).firstOrNull { d -> abs(step * 10.0.pow(d) - round(step * 10.0.pow(d))) < 1e-6 } ?: 3
}

fun niceAxis(dataMin: Double, dataMax: Double, targetTicks: Int = 5): Axis {
    var lo = dataMin
    var hi = dataMax
    if (hi - lo < 1e-9) {
        lo -= 1.0
        hi += 1.0
    }
    val rawStep = (hi - lo) / targetTicks.coerceAtLeast(1)
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rawStep }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val ticks = generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.map { round(it / step) * step }.toList()
    return Axis(start, end, ticks, step)
}

/**
 * Splits points into runs wherever consecutive timestamps are further apart than
 * [maxGapMs], so missing data is drawn as a gap rather than a straight line.
 */
fun splitAtGaps(points: List<ChartPoint>, maxGapMs: Long): List<List<ChartPoint>> {
    if (points.isEmpty()) return emptyList()
    val runs = mutableListOf(mutableListOf(points.first()))
    for (i in 1 until points.size) {
        if (points[i].ts - points[i - 1].ts > maxGapMs) runs.add(mutableListOf())
        runs.last().add(points[i])
    }
    return runs
}

/** Gap threshold: several typical intervals (median spacing), at least [floorMs]. */
fun gapThreshold(points: List<ChartPoint>, bucketMs: Long, floorMs: Long = 60_000): Long {
    if (bucketMs > 0) return maxOf(bucketMs * 3, floorMs)
    if (points.size < 3) return Long.MAX_VALUE
    val diffs = points.zipWithNext { a, b -> b.ts - a.ts }.sorted()
    return maxOf(diffs[diffs.size / 2] * 6, floorMs)
}

/** Index of the point nearest to [ts] (points sorted by ts). */
fun nearestIndex(points: List<ChartPoint>, ts: Long): Int {
    if (points.isEmpty()) return -1
    var lo = 0
    var hi = points.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].ts < ts) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && abs(points[lo - 1].ts - ts) <= abs(points[lo].ts - ts)) lo - 1 else lo
}
