package com.growbuddy.app

import com.growbuddy.app.data.remote.Age
import com.growbuddy.app.ui.common.MetricKind
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatAge
import com.growbuddy.app.ui.common.formatBand
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.formatPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.util.Locale

class FormatTest {
    init {
        TestStrings.install()
    }

    companion object {
        @JvmStatic @BeforeClass
        fun locale() = Locale.setDefault(Locale.US)
    }

    @Test
    fun phases() {
        assertEquals("Early Vegetation", formatPhase("EarlyVegetation"))
        assertEquals("Pre Germination", formatPhase("PreGermination"))
        assertEquals("Drying", formatPhase("Drying"))
    }

    @Test
    fun ageInDaysForShortPhasesOnly() {
        assertEquals("Day 1", formatAge(Age(1, 1), "Drying"))
        assertEquals("Day 10", formatAge(Age(2, 3), "Germinating"))
        assertEquals("Day 22", formatAge(Age(4, 1), "Curing"))
        assertEquals("Week 11, Day 1", formatAge(Age(11, 1), "MidFlower"))
        assertNull(formatAge(null, "Complete"))
    }

    @Test
    fun numbersAndBands() {
        assertEquals("25.4", formatNumber(25.43, 1))
        assertEquals("–", formatNumber(null, 1))
        assertEquals("22.0–25.0 °C", formatBand(22.0, 25.0, MetricKind.Temperature))
        assertEquals("≥ 0.80 kPa", formatBand(0.8, null, MetricKind.Vpd))
        assertNull(formatBand(null, null, MetricKind.Humidity))
    }

    @Test
    fun relativeTime() {
        val now = 1_000_000_000L
        assertEquals("never", formatAgo(null, now))
        assertEquals("just now", formatAgo(now - 3_000, now))
        assertEquals("42 s ago", formatAgo(now - 42_000, now))
        assertEquals("5 min ago", formatAgo(now - 300_000, now))
        assertEquals("3 h ago", formatAgo(now - 3 * 3_600_000, now))
    }
}
