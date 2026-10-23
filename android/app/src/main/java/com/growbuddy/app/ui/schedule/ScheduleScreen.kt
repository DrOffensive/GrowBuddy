package com.growbuddy.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingScheduleSummary
import com.growbuddy.app.data.remote.FeedingWeek
import com.growbuddy.app.data.remote.MinMaxTarget
import com.growbuddy.app.data.remote.NutrientLine
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appPlural
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.bestLineFor
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.unitOf
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ScheduleViewModel(private val container: AppContainer) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var summaries by mutableStateOf<List<FeedingScheduleSummary>>(emptyList())
        private set
    var schedule by mutableStateOf<FeedingSchedule?>(null)
        private set
    var plants by mutableStateOf<List<Plant>>(emptyList())
        private set
    var lines by mutableStateOf<List<NutrientLine>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirm by mutableStateOf<Confirmation?>(null)

    /** Reloads the list (after the editor saved or a delete) and opens the chosen schedule. */
    fun refresh() {
        viewModelScope.launch {
            apiCall { api.feedingSchedules() }
                .onSuccess { list ->
                    summaries = list
                    pickSchedule(list, container.settings.scheduleId.first())?.let { open(it.id) } ?: run { schedule = null }
                }
                .onFailure { error = it.message }
            apiCall { api.plants() }.onSuccess { plants = it }
            apiCall { api.nutrientLines() }.onSuccess { lines = it }
        }
    }

    /** Opens a schedule and remembers it as the one to feed by (the log wizard uses it too). */
    fun choose(id: Long) {
        viewModelScope.launch { container.settings.saveScheduleId(id) }
        open(id)
    }

    private fun open(id: Long) {
        viewModelScope.launch {
            apiCall { api.feedingSchedule(id) }.onSuccess { schedule = it; error = null }.onFailure { error = it.message }
        }
    }

    fun delete(target: FeedingSchedule) {
        confirm = Confirmation(appString(R.string.sched_delete_title, target.displayName), appString(R.string.sched_delete_text), appString(R.string.common_delete)) {
            viewModelScope.launch {
                apiCall { api.deleteFeedingSchedule(target.id).orThrow() }.onSuccess { refresh() }.onFailure { error = it.message }
            }
        }
    }
}

/** [onEdit]: (schedule to edit, schedule to copy from); both null for a new, empty schedule. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit, onEdit: (editId: Long?, copyFromId: Long?) -> Unit) {
    val container = appContainer()
    val vm = viewModel { ScheduleViewModel(container) }
    LaunchedEffect(Unit) { vm.refresh() } // also when coming back from the editor
    val schedule = vm.schedule
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(appString(R.string.sched_title), onBack = onBack) {
            IconButton(onClick = { onEdit(null, null) }) { Icon(Icons.Outlined.Add, appString(R.string.sched_new), tint = Palette.TextPrimary) }
        }
        when {
            schedule == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::refresh)
            schedule == null -> LoadingBox()
            else -> {
                // Which plants are in which week right now: growing plants only, past the end on the last week.
                val labelsByWeek = vm.plants
                    .mapNotNull { p -> scheduleWeekOf(p, schedule)?.let { it to p } }
                    .groupBy({ it.first.week.week }) { (at, p) -> if (at.pastEnd) appString(R.string.sched_plant_past_end, p.nickname, at.plantWeek) else p.nickname }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActiveSchedule(schedule, canPick = vm.summaries.size > 1) { picking = true }
                            Text(stringResource(R.string.sched_disclaimer), style = MaterialTheme.typography.bodyMedium, color = Palette.Warning)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onEdit(null, schedule.id) }) { Text(stringResource(R.string.common_duplicate)) }
                                if (schedule.custom) {
                                    OutlinedButton(onClick = { onEdit(schedule.id, null) }) { Text(stringResource(R.string.common_edit)) }
                                    OutlinedButton(onClick = { vm.delete(schedule) }) { Text(stringResource(R.string.common_delete)) }
                                }
                            }
                            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    val line = vm.lines.bestLineFor(schedule.weeks.flatMap { it.nutrients.keys })
                    items(schedule.weeks, key = { it.week }) { week -> WeekCard(week, line, labelsByWeek[week.week].orEmpty()) }
                }
            }
        }
    }
    if (picking) {
        SchedulePicker(vm.summaries, schedule?.id, onDismiss = { picking = false }) {
            picking = false
            vm.choose(it)
        }
    }
    ConfirmDialog(vm.confirm) { vm.confirm = null }
}

private fun scheduleInfo(custom: Boolean, substrate: String?): String = listOfNotNull(
    appString(if (custom) R.string.sched_custom else R.string.sched_builtin),
    substrate?.let { appString(R.string.sched_substrate, it) },
).joinToString(" · ")

/** The schedule shown and fed by; tapping it opens [SchedulePicker]. */
@Composable
private fun ActiveSchedule(schedule: FeedingSchedule, canPick: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.CardRaised)
            .clickable(enabled = canPick, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sched_active), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
            Text(schedule.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(scheduleInfo(schedule.custom, schedule.substrate), color = Palette.TextSecondary)
        }
        if (canPick) Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.sched_choose), tint = Palette.TextPrimary)
    }
}

/** Bottom sheet listing every schedule: the user's own first, then the built-in ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePicker(summaries: List<FeedingScheduleSummary>, selectedId: Long?, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val (custom, builtin) = summaries.partition { it.custom }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item { Text(stringResource(R.string.sched_choose), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp)) }
            listOf(R.string.sched_section_custom to custom, R.string.sched_section_builtin to builtin).forEach { (title, list) ->
                if (list.isEmpty()) return@forEach
                item { Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(list.size, key = { list[it].id }) { i ->
                    val s = list[i]
                    val selected = s.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Palette.NavCenter else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onChoose(s.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(s.substrate?.let { appString(R.string.sched_substrate, it) }, appPlural(R.plurals.sched_weeks, s.weeks, s.weeks)).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextSecondary,
                            )
                        }
                        if (selected) Icon(Icons.Outlined.Check, null, tint = Palette.NavSelected)
                    }
                }
            }
        }
    }
}

private fun phaseColor(phase: String) = when (phase.lowercase()) {
    "veg" -> Palette.Green
    "flower" -> androidx.compose.ui.graphics.Color(0xFFFF1493) // Flower Flip colour (0.1.6)
    "flush" -> Palette.Teal
    else -> Palette.TextSecondary
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekCard(week: FeedingWeek, line: NutrientLine?, plantsHere: List<String>) {
    val current = plantsHere.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.CardRaised)
            .border(2.dp, if (current) Palette.CardBorder else Palette.CardRaised, RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(stringResource(R.string.sched_week, week.week), style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), modifier = Modifier.weight(1f))
            Text(schedulePhaseLabel(week.phase), style = MaterialTheme.typography.titleMedium, color = phaseColor(week.phase))
        }
        if (current) Text(stringResource(R.string.sched_now, plantsHere.joinToString()), color = Palette.NavSelected)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            week.nutrients.filterValues { it > 0 }.forEach { (name, amount) ->
                Text(
                    "$name ${formatNumber(amount, 1)} ${line.unitOf(name)}",
                    style = NumericStyle,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.NavCenter).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Target("pH", week.ph, 1)
            Target("EC", week.ec, 1)
        }
        week.environment?.let { env ->
            fun band(t: MinMaxTarget?, unit: String) = t?.takeIf { it.min != null && it.max != null }?.let { "${formatNumber(it.min, 0)}–${formatNumber(it.max, 0)} $unit" }
            listOfNotNull(
                band(env.tempDay, "°C")?.let { stringResource(R.string.sched_day, it) },
                band(env.tempNight, "°C")?.let { stringResource(R.string.sched_night, it) },
                band(env.humidity, "%RH")?.let { stringResource(R.string.sched_rh, it) },
                env.lightHours?.let { stringResource(R.string.sched_light, it) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) }
        }
        week.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
    }
}

@Composable
private fun Target(label: String, t: MinMaxTarget?, decimals: Int) {
    if (t == null) return
    Text(
        "$label ${formatNumber(t.target, decimals)}" + if (t.min != null && t.max != null) " (${formatNumber(t.min, decimals)}–${formatNumber(t.max, decimals)})" else "",
        style = NumericStyle,
    )
}

