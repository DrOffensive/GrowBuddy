package com.growbuddy.app.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingScheduleBody
import com.growbuddy.app.data.remote.FeedingWeek
import com.growbuddy.app.data.remote.FeedingWeekBody
import com.growbuddy.app.data.remote.MinMaxTarget
import com.growbuddy.app.data.remote.NutrientLine
import com.growbuddy.app.data.remote.ScheduleEnvironment
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.bestLineFor
import com.growbuddy.app.ui.common.unitOf
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch

private val PHASES = listOf("Veg", "Flower", "Flush")

private fun String.toNumber(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/** Number as typed text: 2.0 -> "2", 6.2 -> "6.2", null -> "". */
private fun Double?.asText(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> toLong().toString()
    else -> toString()
}

class NutrientDraft(name: String, amount: String) {
    var name by mutableStateOf(name)
    var amount by mutableStateOf(amount)
}

/** One week as editable text; numbers are parsed on save. */
class WeekDraft(from: FeedingWeek?) {
    var phase by mutableStateOf(from?.phase?.takeIf { it in PHASES } ?: "Veg")
    val nutrients = mutableStateListOf<NutrientDraft>().apply {
        from?.nutrients?.forEach { (name, amount) -> add(NutrientDraft(name, amount.asText())) }
    }
    var phMin by mutableStateOf(from?.ph?.min.asText())
    var phMax by mutableStateOf(from?.ph?.max.asText())
    var phTarget by mutableStateOf(from?.ph?.target.asText())
    var ecMin by mutableStateOf(from?.ec?.min.asText())
    var ecMax by mutableStateOf(from?.ec?.max.asText())
    var ecTarget by mutableStateOf(from?.ec?.target.asText())
    var dayMin by mutableStateOf(from?.environment?.tempDay?.min.asText())
    var dayMax by mutableStateOf(from?.environment?.tempDay?.max.asText())
    var nightMin by mutableStateOf(from?.environment?.tempNight?.min.asText())
    var nightMax by mutableStateOf(from?.environment?.tempNight?.max.asText())
    var rhMin by mutableStateOf(from?.environment?.humidity?.min.asText())
    var rhMax by mutableStateOf(from?.environment?.humidity?.max.asText())
    var lightHours by mutableStateOf(from?.environment?.lightHours?.toString().orEmpty())
    var notes by mutableStateOf(from?.notes.orEmpty())

    /** A copy for "Add week" (the next week usually starts from the previous one). */
    fun copy(): WeekDraft = WeekDraft(null).also { c ->
        c.phase = phase
        nutrients.forEach { c.nutrients.add(NutrientDraft(it.name, it.amount)) }
        c.phMin = phMin; c.phMax = phMax; c.phTarget = phTarget
        c.ecMin = ecMin; c.ecMax = ecMax; c.ecTarget = ecTarget
        c.dayMin = dayMin; c.dayMax = dayMax; c.nightMin = nightMin; c.nightMax = nightMax
        c.rhMin = rhMin; c.rhMax = rhMax; c.lightHours = lightHours
        c.notes = ""
    }

    /** The API body, or the first problem as text. */
    fun toBody(number: Int): Result<FeedingWeekBody> {
        val problems = mutableListOf<String>()
        fun num(label: String, text: String): Double? {
            val v = text.toNumber()
            if (text.isNotBlank() && (v == null || v < 0)) problems += appString(R.string.editor_not_number, label)
            return v
        }
        fun target(label: String, min: String, max: String, target: String) =
            MinMaxTarget(num(appString(R.string.editor_label_min, label), min), num(appString(R.string.editor_label_max, label), max), num(label, target))
                .takeIf { it.min != null || it.max != null || it.target != null }
        fun range(label: String, min: String, max: String) =
            MinMaxTarget(num(appString(R.string.editor_label_min, label), min), num(appString(R.string.editor_label_max, label), max)).takeIf { it.min != null || it.max != null }

        val amounts = linkedMapOf<String, Double>()
        nutrients.filter { it.name.isNotBlank() }.forEach { amounts[it.name.trim()] = num(it.name.trim(), it.amount) ?: 0.0 }
        val light = lightHours.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (lightHours.isNotBlank() && (light == null || light !in 0..24)) problems += appString(R.string.editor_light_range)
        val env = ScheduleEnvironment(range(appString(R.string.editor_day_c), dayMin, dayMax), range(appString(R.string.editor_night_c), nightMin, nightMax), range(appString(R.string.editor_rh), rhMin, rhMax), light)
        val body = FeedingWeekBody(
            phase = phase,
            nutrients = amounts,
            ph = target("pH", phMin, phMax, phTarget),
            ec = target("EC", ecMin, ecMax, ecTarget),
            environment = env.takeIf { it != ScheduleEnvironment() },
            notes = notes.trim().ifEmpty { null },
        )
        return if (problems.isEmpty()) Result.success(body) else Result.failure(IllegalArgumentException(appString(R.string.editor_week_problem, number, problems.first())))
    }
}

/** [editId]: custom schedule to change; [copyFromId]: schedule to start from (Duplicate); neither: empty. */
class ScheduleEditorViewModel(private val container: AppContainer, private val editId: Long?, private val copyFromId: Long?) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var lines by mutableStateOf<List<NutrientLine>>(emptyList())
        private set
    var name by mutableStateOf("")
    var substrate by mutableStateOf("")
    val weeks = mutableStateListOf<WeekDraft>()
    var expanded by mutableIntStateOf(0) // index of the open week card, -1 = none
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirm by mutableStateOf<Confirmation?>(null)
    private var initial: FeedingScheduleBody? = null

    val isNew get() = editId == null

    init {
        viewModelScope.launch {
            apiCall { api.nutrientLines() }.onSuccess { lines = it }
            val sourceId = editId ?: copyFromId
            if (sourceId == null) {
                start(null)
            } else {
                apiCall { api.feedingSchedule(sourceId) }.onSuccess(::start).onFailure { loadError = it.message }
            }
        }
    }

    private fun start(source: FeedingSchedule?) {
        name = when {
            source == null -> ""
            editId != null -> source.displayName
            else -> appString(R.string.editor_copy_name, source.displayName)
        }
        substrate = source?.substrate.orEmpty()
        weeks.clear()
        source?.weeks?.forEach { weeks.add(WeekDraft(it)) }
        if (weeks.isEmpty()) weeks.add(WeekDraft(null))
        expanded = if (source == null) 0 else -1
        initial = body().getOrNull()
        loaded = true
    }

    private fun body(): Result<FeedingScheduleBody> = runCatching {
        FeedingScheduleBody(
            displayName = name.trim(),
            substrate = substrate.trim().ifEmpty { null },
            weeks = weeks.mapIndexed { i, w -> w.toBody(i + 1).getOrThrow() },
        )
    }

    /** Anything changed that leaving would throw away (unparseable input counts as changed). */
    val hasChanges: Boolean get() = loaded && body().getOrNull() != initial

    /** Product names of all nutrient lines, for the name suggestions. */
    val productNames: List<String> get() = lines.flatMap { l -> l.products.map { it.name } }.distinct()

    /** Unit of a nutrient: from the line that has most of the schedule's products. */
    fun unitOf(name: String): String = lines.bestLineFor(weeks.flatMap { w -> w.nutrients.map { it.name } }).unitOf(name)

    fun addWeek() {
        weeks.add(weeks.lastOrNull()?.copy() ?: WeekDraft(null))
        expanded = weeks.lastIndex
    }

    fun removeWeek(index: Int) {
        confirm = Confirmation(appString(R.string.editor_remove_week_title, index + 1), appString(R.string.editor_remove_week_text), appString(R.string.common_remove)) {
            weeks.removeAt(index)
            expanded = -1
        }
    }

    fun addLine(week: WeekDraft, line: NutrientLine) {
        val present = week.nutrients.map { it.name }.toSet()
        line.products.filter { it.name !in present }.forEach { week.nutrients.add(NutrientDraft(it.name, "")) }
    }

    fun save(onDone: () -> Unit) {
        if (name.isBlank()) {
            error = appString(R.string.editor_need_name)
            return
        }
        val body = body().getOrElse { error = it.message; return }
        saving = true
        error = null
        viewModelScope.launch {
            apiCall { if (editId == null) api.createFeedingSchedule(body) else api.putFeedingSchedule(editId, body) }
                .onSuccess { saved ->
                    container.settings.saveScheduleId(saved.id) // the schedule screen opens it
                    onDone()
                }
                .onFailure { error = it.message }
            saving = false
        }
    }
}

@Composable
fun ScheduleEditorScreen(editId: Long?, copyFromId: Long?, onClose: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { ScheduleEditorViewModel(container, editId, copyFromId) }
    val leave = {
        if (vm.hasChanges) {
            vm.confirm = Confirmation(appString(R.string.editor_discard_title), appString(R.string.editor_discard_text), appString(R.string.common_discard), onClose)
        } else {
            onClose()
        }
    }
    BackHandler(onBack = leave)
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (vm.isNew) stringResource(R.string.editor_new) else stringResource(R.string.editor_edit), onBack = leave) {
            Button(onClick = { vm.save(onClose) }, enabled = vm.loaded && !vm.saving) {
                if (vm.saving) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.common_save))
            }
        }
        when {
            vm.loadError != null -> ErrorBox(vm.loadError!!)
            !vm.loaded -> LoadingBox()
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(vm.name, { vm.name = it }, label = { Text(stringResource(R.string.editor_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            vm.substrate, { vm.substrate = it }, label = { Text(stringResource(R.string.editor_substrate)) },
                            placeholder = { Text(stringResource(R.string.editor_substrate_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.editor_week1_hint), color = Palette.TextSecondary)
                        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                itemsIndexed(vm.weeks) { i, week ->
                    WeekEditor(vm, i, week)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = vm::addWeek, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.weeks.isEmpty()) stringResource(R.string.editor_add_week) else stringResource(R.string.editor_add_week_copy, vm.weeks.size + 1, vm.weeks.size))
                    }
                }
            }
        }
    }
    ConfirmDialog(vm.confirm) { vm.confirm = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekEditor(vm: ScheduleEditorViewModel, index: Int, week: WeekDraft) {
    val open = vm.expanded == index
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.CardRaised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { vm.expanded = if (open) -1 else index }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sched_week, index + 1), style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), modifier = Modifier.weight(1f))
            Text(schedulePhaseLabel(week.phase), style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) stringResource(R.string.editor_collapse) else stringResource(R.string.editor_expand))
        }
        if (!open) {
            val summary = week.nutrients.filter { it.name.isNotBlank() && (it.amount.toNumber() ?: 0.0) > 0 }
                .joinToString(" · ") { "${it.name} ${it.amount}" }
            Text(summary.ifEmpty { appString(R.string.editor_no_nutrients) }, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PHASES.forEach { p -> FilterChip(selected = week.phase == p, onClick = { week.phase = p }, label = { Text(schedulePhaseLabel(p)) }) }
        }
        SectionHeader(stringResource(R.string.editor_nutrients))
        week.nutrients.forEachIndexed { n, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductField(vm.productNames, row.name, { row.name = it }, Modifier.weight(1f))
                Number(vm.unitOf(row.name), row.amount, { row.amount = it }, Modifier.weight(0.45f))
                IconButton(onClick = { week.nutrients.removeAt(n) }) { Icon(Icons.Outlined.Close, appString(R.string.editor_remove_nutrient)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { week.nutrients.add(NutrientDraft("", "")) }) { Text(stringResource(R.string.wizard_add_nutrient)) }
            vm.lines.forEach { line -> TextButton(onClick = { vm.addLine(week, line) }) { Text(stringResource(R.string.editor_add_all, line.brand)) } }
        }
        SectionHeader("pH")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(stringResource(R.string.editor_target), week.phTarget, { week.phTarget = it }, Modifier.weight(1f))
            Number(stringResource(R.string.editor_min), week.phMin, { week.phMin = it }, Modifier.weight(1f))
            Number(stringResource(R.string.editor_max), week.phMax, { week.phMax = it }, Modifier.weight(1f))
        }
        SectionHeader("EC")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(stringResource(R.string.editor_target), week.ecTarget, { week.ecTarget = it }, Modifier.weight(1f))
            Number(stringResource(R.string.editor_min), week.ecMin, { week.ecMin = it }, Modifier.weight(1f))
            Number(stringResource(R.string.editor_max), week.ecMax, { week.ecMax = it }, Modifier.weight(1f))
        }
        SectionHeader(stringResource(R.string.editor_environment))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(appString(R.string.editor_day_min), week.dayMin, { week.dayMin = it }, Modifier.weight(1f))
            Number(appString(R.string.editor_day_max), week.dayMax, { week.dayMax = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(appString(R.string.editor_night_min), week.nightMin, { week.nightMin = it }, Modifier.weight(1f))
            Number(appString(R.string.editor_night_max), week.nightMax, { week.nightMax = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(appString(R.string.editor_rh_min), week.rhMin, { week.rhMin = it }, Modifier.weight(1f))
            Number(appString(R.string.editor_rh_max), week.rhMax, { week.rhMax = it }, Modifier.weight(1f))
        }
        Number(appString(R.string.editor_light_hours), week.lightHours, { week.lightHours = it }, Modifier.fillMaxWidth(), whole = true)
        OutlinedTextField(week.notes, { week.notes = it }, label = { Text(stringResource(R.string.editor_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (vm.weeks.size > 1) {
            TextButton(onClick = { vm.removeWeek(index) }) { Text(stringResource(R.string.editor_remove_week, index + 1), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Free text with the nutrient lines' product names as suggestions. */
@Composable
private fun ProductField(options: List<String>, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value, onChange, label = { Text(stringResource(R.string.editor_product)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (options.isNotEmpty()) IconButton(onClick = { open = true }) { Icon(Icons.Outlined.ArrowDropDown, appString(R.string.editor_pick_product)) }
            },
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); open = false }) }
        }
    }
}

@Composable
private fun Number(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, whole: Boolean = false) {
    val parsed = if (whole) value.trim().toIntOrNull()?.toDouble() else value.toNumber()
    OutlinedTextField(
        value, onChange, label = { Text(label, maxLines = 1) }, singleLine = true, textStyle = NumericStyle,
        isError = value.isNotBlank() && (parsed == null || parsed < 0),
        keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}
