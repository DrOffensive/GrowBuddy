package com.growbuddy.app

import com.growbuddy.app.data.remote.Age
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingScheduleSummary
import com.growbuddy.app.data.remote.FeedingWeek
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.ui.schedule.pickSchedule
import com.growbuddy.app.ui.schedule.scheduleWeekOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWeeksTest {
    init {
        TestStrings.install()
    }

    private val schedule = FeedingSchedule(1, "s", "Ten weeks", weeks = (1..10).map { FeedingWeek(it, if (it <= 4) "Veg" else "Flower") })

    private fun plant(phase: String, week: Int?) =
        Plant(id = phase, nickname = phase, isAutoflower = true, archived = false, createdTs = 0, phase = phase, age = week?.let { Age(it, 1) })

    @Test
    fun growingPlantIsOnItsAgeWeek() {
        val at = scheduleWeekOf(plant("EarlyVegetation", 3), schedule)!!
        assertEquals(3, at.week.week)
        assertFalse(at.pastEnd)
    }

    @Test
    fun dryingPlantIsNotOnTheSchedule() {
        // A harvested plant's age restarts at the harvest: its week 1 is not schedule week 1.
        assertNull(scheduleWeekOf(plant("Drying", 1), schedule))
        assertNull(scheduleWeekOf(plant("Germinating", 1), schedule))
        assertNull(scheduleWeekOf(plant("Curing", 2), schedule))
    }

    @Test
    fun plantPastTheEndStaysOnTheLastWeek() {
        val at = scheduleWeekOf(plant("LateFlower", 11), schedule)!!
        assertEquals(10, at.week.week)
        assertEquals(11, at.plantWeek)
        assertTrue(at.pastEnd)
    }

    @Test
    fun chosenScheduleFallsBackToTheOldest() {
        val list = listOf(FeedingScheduleSummary(2, "b", "B", weeks = 1), FeedingScheduleSummary(1, "a", "A", weeks = 1))
        assertEquals(2L, pickSchedule(list, 2)?.id)
        assertEquals(1L, pickSchedule(list, 99)?.id)
        assertEquals(1L, pickSchedule(list, null)?.id)
        assertNull(pickSchedule(emptyList(), 1))
    }
}
