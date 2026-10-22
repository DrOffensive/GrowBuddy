package com.growbuddy.app

import com.growbuddy.app.data.remote.AlertSettings
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingScheduleSummary
import com.growbuddy.app.data.remote.NotificationLanguage
import com.growbuddy.app.data.remote.DiscoveredSensor
import com.growbuddy.app.data.remote.EnvironmentCurrent
import com.growbuddy.app.data.remote.PhaseRange
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.SensorDetail
import com.growbuddy.app.data.remote.ServerStatus
import com.growbuddy.app.data.remote.StrainPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses responses captured from the real server (simulated hardware), so a change in
 * docs/API.md that the app does not follow breaks here rather than at runtime.
 * Refresh fixtures from a running server when the contract changes.
 */
class ContractTest {
    init {
        TestStrings.install()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    private inline fun <reified T> parse(name: String): T = ApiJson.decodeFromString(fixture(name))

    @Test
    fun environmentCurrent() {
        val env = parse<EnvironmentCurrent>("environment_current.json")
        val tent = env.sensors.single()
        assertEquals("Grow tent", tent.sensor.name)
        assertTrue(tent.online)
        val reading = requireNotNull(tent.reading)
        assertNotNull(reading.temperatureC)
        assertNotNull(reading.vpdKpa)
        assertEquals("warning", tent.levels.temperature)
        assertNotNull(tent.ranges.temperature.warnMin)
        val plant = tent.plants.single()
        assertEquals("Dosi #1", plant.nickname)
        assertEquals("Seedling", plant.phase)
        assertEquals(1, plant.age?.week)
        assertNotNull(plant.strain?.name)
        assertTrue("Sprout" in plant.milestones)
        assertEquals("Clone", env.unlocatedPlants.single().nickname)
    }

    @Test
    fun sensors() {
        val list = parse<List<Sensor>>("sensors.json")
        assertEquals("02:00:00:00:00:01", list.single().mac)
        val detail = parse<SensorDetail>("sensor_detail.json")
        assertTrue(detail.alertsEnabled)
        assertNotNull(detail.ranges.humidity.critMax)
        val fresh = parse<SensorDetail>("sensor_detail_adopted.json")
        assertNull(fresh.ranges.temperature.warnMin)
    }

    @Test
    fun discovered() {
        val found = parse<List<DiscoveredSensor>>("sensors_discovered.json")
        assertEquals(2, found.size)
        assertTrue(found.all { !it.encrypted && it.values?.temperatureC != null })
        assertEquals(setOf("SIM tent", "SIM drying"), found.mapNotNull { it.name }.toSet())
    }

    @Test
    fun phaseRangesAndStatus() {
        val phases = parse<List<PhaseRange>>("phase_ranges.json")
        assertEquals("PreGermination", phases.first().phase)
        assertTrue(phases.first { it.phase == "Seedling" }.enforceLimits)
        val status = parse<ServerStatus>("status.json")
        assertEquals("simulated", status.ble.source)
        assertEquals(1, status.ble.adopted)
    }

    @Test
    fun feedingSchedules() {
        val list = parse<List<FeedingScheduleSummary>>("feeding_schedules.json")
        assertTrue(list.isNotEmpty() && list.none { it.custom })
        val builtin = parse<FeedingSchedule>("feeding_schedule.json")
        assertTrue(!builtin.custom)
        val week1 = builtin.weeks.first()
        assertEquals(1, week1.week)
        assertNotNull(week1.ph?.target)
        assertNotNull(week1.environment?.tempDay?.min)
        assertNotNull(week1.environment?.lightHours)

        val custom = parse<FeedingSchedule>("feeding_schedule_custom.json")
        assertTrue(custom.custom && custom.profileKey.startsWith("custom-"))
        assertEquals(listOf("Veg", "Flush"), custom.weeks.map { it.phase })
        assertEquals(2.0, custom.weeks[0].nutrients["Bio-Grow"])
        assertEquals(1.2, custom.weeks[0].ec?.target)
        assertNull(custom.weeks[0].ec?.min)
        assertEquals(18, custom.weeks[0].environment?.lightHours)
        assertNull(custom.weeks[1].environment)
    }

    @Test
    fun strainsCarryCustomFlag() {
        val page = parse<StrainPage>("strains.json")
        assertTrue(page.total > page.items.size)
        assertTrue(page.items.none { it.custom })
    }

    @Test
    fun alertSettings() {
        val settings = parse<AlertSettings>("alert_settings.json")
        assertEquals(600, settings.debounceS)
        assertEquals(1800, settings.warningDebounceS)
        assertEquals("en", settings.language)
    }

    @Test
    fun notificationLanguages() {
        val languages = parse<List<NotificationLanguage>>("notification_languages.json")
        assertTrue(languages.any { it.code == "da" && it.name == "Dansk" })
        assertTrue(languages.any { it.code == "en" })
    }
}
