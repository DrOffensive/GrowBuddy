package com.growbuddy.app

import com.growbuddy.app.ui.common.ChartPoint
import com.growbuddy.app.ui.common.gapThreshold
import com.growbuddy.app.ui.common.nearestIndex
import com.growbuddy.app.ui.common.niceAxis
import com.growbuddy.app.ui.common.splitAtGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    init {
        TestStrings.install()
    }

    @Test
    fun niceAxisRoundsToFriendlySteps() {
        val axis = niceAxis(18.3, 26.7)
        assertEquals(18.0, axis.min, 1e-9)
        assertEquals(28.0, axis.max, 1e-9)
        assertEquals(listOf(18.0, 20.0, 22.0, 24.0, 26.0, 28.0), axis.ticks)
        val vpd = niceAxis(0.62, 1.31)
        assertTrue(vpd.ticks.first() <= 0.62 && vpd.ticks.last() >= 1.31)
        assertTrue(vpd.ticks.zipWithNext { a, b -> b - a }.all { kotlin.math.abs(it - 0.2) < 1e-9 })
    }

    @Test
    fun labelDecimalsFollowTheStep() {
        assertEquals(0, niceAxis(18.3, 26.7).decimals) // step 2
        assertEquals(1, niceAxis(53.4, 64.2).decimals) // step 2.5 -> 57.5 must not show as 58
        assertEquals(1, niceAxis(0.62, 1.31).decimals) // step 0.2
    }

    @Test
    fun flatDataStillGetsARange() {
        val axis = niceAxis(21.0, 21.0)
        assertTrue(axis.min < 21.0 && axis.max > 21.0)
    }

    @Test
    fun gapsSplitRuns() {
        val pts = listOf(0L, 10_000, 20_000, 30_000, 900_000, 910_000).map { ChartPoint(it, 1.0) }
        val threshold = gapThreshold(pts, bucketMs = 0)
        assertEquals(60_000L, threshold) // 6 × 10 s median, floored at 1 min
        assertEquals(listOf(4, 2), splitAtGaps(pts, threshold).map { it.size })
        assertEquals(3 * 3_600_000L, gapThreshold(pts, bucketMs = 3_600_000))
    }

    @Test
    fun nearestPoint() {
        val pts = listOf(0L, 100, 200, 300).map { ChartPoint(it, 0.0) }
        assertEquals(0, nearestIndex(pts, -50))
        assertEquals(1, nearestIndex(pts, 140))
        assertEquals(2, nearestIndex(pts, 160))
        assertEquals(3, nearestIndex(pts, 1_000))
    }
}
