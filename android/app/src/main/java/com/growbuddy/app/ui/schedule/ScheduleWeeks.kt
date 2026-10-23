package com.growbuddy.app.ui.schedule

import com.growbuddy.app.R
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingScheduleSummary
import com.growbuddy.app.data.remote.FeedingWeek
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.ui.common.appString

/**
 * Phases in which a plant follows a feeding schedule (docs/DOMAIN.md › Feeding schedules).
 * Only then does its age count from Sprout; drying or germinating plants count from
 * another milestone, so their age week says nothing about the schedule.
 */
private val GROWING_PHASES = setOf("Seedling", "EarlyVegetation", "LateVegetation", "EarlyFlower", "MidFlower", "LateFlower")

/** The plant's place in a schedule: the week to show, and the plant's own week (larger when past the end). */
data class ScheduleWeekOf(val week: FeedingWeek, val plantWeek: Int) {
    val pastEnd: Boolean get() = plantWeek > week.week
}

/** Where [plant] is in [schedule], or null when it isn't growing (or the schedule is empty). */
fun scheduleWeekOf(plant: Plant, schedule: FeedingSchedule): ScheduleWeekOf? {
    if (plant.phase !in GROWING_PHASES) return null
    val plantWeek = plant.age?.week ?: return null
    val week = schedule.weeks.firstOrNull { it.week == plantWeek } ?: schedule.weeks.lastOrNull()?.takeIf { plantWeek > it.week }
    return week?.let { ScheduleWeekOf(it, plantWeek) }
}

/**
 * The schedule to use: the one chosen before if it still exists, else the oldest (the list is
 * sorted by name, so taking the first would change whenever a new built-in schedule is seeded).
 */
fun pickSchedule(summaries: List<FeedingScheduleSummary>, chosenId: Long?): FeedingScheduleSummary? =
    summaries.firstOrNull { it.id == chosenId } ?: summaries.minByOrNull { it.id }

/** A schedule week's phase (Veg / Flower / Flush) in the app language. */
fun schedulePhaseLabel(phase: String): String = when (phase) {
    "Veg" -> appString(R.string.sched_phase_Veg)
    "Flower" -> appString(R.string.sched_phase_Flower)
    "Flush" -> appString(R.string.sched_phase_Flush)
    else -> phase
}
