package com.growbuddy.app.ui.journal

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.FeedingSchedule
import com.growbuddy.app.data.remote.FeedingWeek
import com.growbuddy.app.data.remote.LogCreate
import com.growbuddy.app.data.remote.NutrientLine
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.sync.CreateLogItem
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appPlural
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.bestLineFor
import com.growbuddy.app.ui.common.formatAge
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.common.productKey
import com.growbuddy.app.ui.schedule.ScheduleWeekOf
import com.growbuddy.app.ui.schedule.pickSchedule
import com.growbuddy.app.ui.schedule.scheduleWeekOf
import com.growbuddy.app.ui.schedule.schedulePhaseLabel
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// 0.1.6 flow: type, plants, data, comment, summary (+ photos, max 5).
private enum class Step { Type, Plants, Data, Comment, Summary }

const val MAX_LOG_PHOTOS = 5

class NutrientRow(product: String, amount: String = "") {
    var product by mutableStateOf(product)
    var amount by mutableStateOf(amount)
}

private fun String.toNumber(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

class LogWizardViewModel(private val container: AppContainer, private val preselectedPlant: String?) : ViewModel() {
    private val api get() = container.apiProvider.api()
    private val logId = UUID.randomUUID().toString() // fixed for this wizard, so a retried save never duplicates

    var plants by mutableStateOf<List<Plant>?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var lines by mutableStateOf<List<NutrientLine>>(emptyList())
        private set
    var schedule by mutableStateOf<FeedingSchedule?>(null)
        private set
    var sensors by mutableStateOf<List<Sensor>>(emptyList())
        private set

    var type by mutableStateOf<LogType?>(null)
    val selected = mutableStateListOf<String>().apply { preselectedPlant?.let(::add) }

    // Feeding
    var brand by mutableStateOf<String?>(null)
    val nutrients = mutableStateListOf<NutrientRow>()
    var ph by mutableStateOf("")
    var ec by mutableStateOf("")
    var volume by mutableStateOf("")

    // Light / humidity / temperature
    var value by mutableStateOf("")

    // Details
    val photoIds = mutableStateListOf<String>()
    private val localPreviews = mutableMapOf<String, Uri>() // queued photos have no server thumbnail yet
    var uploading by mutableIntStateOf(0)
        private set
    var comment by mutableStateOf("")
    var ts by mutableStateOf<Long?>(null) // null = now

    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var harvested by mutableStateOf<List<String>?>(null) // plants to offer moving after a Harvest
        private set
    var confirm by mutableStateOf<Confirmation?>(null) // destructive action waiting for OK

    /** Anything entered that closing the wizard would throw away. */
    val hasInput: Boolean
        get() = selected.toList() != listOfNotNull(preselectedPlant) || nutrients.isNotEmpty() ||
            listOf(ph, ec, volume, value, comment).any { it.isNotBlank() } || photoIds.isNotEmpty() || ts != null

    fun selectBrand(newBrand: String) {
        if (newBrand == brand) return
        if (nutrients.isEmpty()) {
            brand = newBrand
            return
        }
        confirm = Confirmation(appString(R.string.wizard_switch_title, newBrand), appPlural(R.plurals.wizard_switch_text, nutrients.size, nutrients.size), appString(R.string.wizard_switch)) {
            brand = newBrand
            nutrients.clear()
        }
    }

    fun removePhoto(id: String) {
        confirm = Confirmation(
            appString(R.string.wizard_remove_photo_title),
            appString(R.string.wizard_remove_photo_text),
            appString(R.string.common_remove),
        ) { photoIds.remove(id) }
    }

    init {
        viewModelScope.launch {
            apiCall { api.plants() }.onSuccess { list -> plants = list.filter { it.phase !in setOf("TerminatedEarly", "Complete") } }
                .onFailure { loadError = it.message }
            apiCall { api.nutrientLines() }.onSuccess {
                lines = it
                brand = it.firstOrNull { l -> l.brand.equals("CANNA", ignoreCase = true) }?.brand ?: it.firstOrNull()?.brand
            }
            apiCall { api.feedingSchedules() }.onSuccess { list ->
                // The schedule chosen on the schedule screen.
                pickSchedule(list, container.settings.scheduleId.first())?.let { s ->
                    apiCall { api.feedingSchedule(s.id) }.onSuccess { schedule = it }
                }
            }
            apiCall { api.sensors() }.onSuccess { sensors = it }
        }
    }

    val line: NutrientLine? get() = lines.firstOrNull { it.brand == brand }

    /** Schedule week of the first selected plant that is growing (docs/DOMAIN.md › Feeding schedules). */
    val scheduleWeek: ScheduleWeekOf?
        get() {
            val s = schedule ?: return null
            return plants?.filter { it.id in selected }?.firstNotNullOfOrNull { scheduleWeekOf(it, s) }
        }

    fun fillFromSchedule(week: FeedingWeek) {
        val target = lines.bestLineFor(week.nutrients.keys) ?: return
        brand = target.brand
        nutrients.clear()
        week.nutrients.filterValues { it > 0 }.forEach { (name, amount) ->
            val product = target.products.firstOrNull { it.name.productKey() == name.productKey() }?.name ?: name
            nutrients.add(NutrientRow(product, amount.toString()))
        }
        week.ph?.target?.let { ph = it.toString() }
        week.ec?.target?.let { ec = it.toString() }
    }

    val photoSlots: Int get() = MAX_LOG_PHOTOS - photoIds.size - uploading

    fun addPhoto(uri: Uri) {
        if (photoSlots <= 0) return // extra images from a multi-pick are ignored
        uploading++
        viewModelScope.launch {
            container.photos.upload(uri, allowQueue = true)
                .onSuccess { localPreviews[it.id] = it.localUri; photoIds.add(it.id) }
                .onFailure { error = it.message }
            uploading--
        }
    }

    fun photoModel(id: String): Any? = localPreviews[id] ?: container.apiProvider.url("photos/$id?thumb=1")

    val dataValid: Boolean
        get() = when (type?.data) {
            DataKind.Feeding -> nutrients.isNotEmpty() && nutrients.all { it.amount.toNumber() != null } &&
                listOf(ph, ec, volume).all { it.isBlank() || it.toNumber() != null }
            DataKind.LightHeight, DataKind.HumidityTarget, DataKind.TemperatureTarget -> value.toNumber() != null
            else -> true
        }

    private fun data(): JsonObject = buildJsonObject {
        when (type?.data) {
            DataKind.Feeding -> {
                ph.toNumber()?.let { put("ph", it) }
                ec.toNumber()?.let { put("ec", it) }
                volume.toNumber()?.let { put("volume_l", it) }
                putJsonArray("nutrients") {
                    val unit = line?.products?.firstOrNull()?.unit ?: "ml/L"
                    nutrients.forEach { n ->
                        addJsonObject {
                            put("brand", brand ?: "")
                            put("product", n.product)
                            put("amount", n.amount.toNumber() ?: 0.0)
                            put("unit", line?.products?.firstOrNull { it.name == n.product }?.unit ?: unit)
                        }
                    }
                }
            }
            DataKind.LightHeight -> put("light_height_cm", value.toNumber())
            DataKind.HumidityTarget -> put("humidity_target_pct", value.toNumber())
            DataKind.TemperatureTarget -> put("temperature_target_c", value.toNumber())
            else -> Unit
        }
    }

    fun save(onDone: () -> Unit) {
        val t = type ?: return
        saving = true
        error = null
        viewModelScope.launch {
            // "Now" is fixed at save time, so an entry synced later from the outbox keeps its real time.
            val body = LogCreate(logId, t.api, selected.toList(), ts ?: System.currentTimeMillis(), comment.trim().ifEmpty { null }, photoIds.toList(), data())
            apiCall { api.createLog(body) }.onSuccess {
                if (t == LogType.Harvest && sensors.isNotEmpty()) harvested = selected.toList() else onDone()
            }.onFailure { e ->
                if (e is ApiException && e.code == "network") {
                    container.queue(CreateLogItem(body)) // saved offline; synced when the server is reachable
                    onDone()
                } else {
                    error = e.message
                }
            }
            saving = false
        }
    }

    fun movePlants(sensorId: Long?, onDone: () -> Unit) {
        viewModelScope.launch {
            harvested.orEmpty().forEach { id ->
                apiCall { api.patchPlant(id, buildJsonObject { put("sensor_id", sensorId) }) }
            }
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogWizardScreen(preselectedPlant: String?, onClose: () -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = "wizard-$preselectedPlant") { LogWizardViewModel(container, preselectedPlant) }
    var step by remember { mutableStateOf(Step.Type) }
    val steps = Step.entries.filter { it != Step.Data || vm.type?.data != DataKind.None }

    fun back() {
        val i = steps.indexOf(step)
        when {
            i > 0 -> step = steps[i - 1]
            vm.hasInput -> vm.confirm = Confirmation(appString(R.string.wizard_discard_title), appString(R.string.wizard_discard_text), appString(R.string.common_discard), onClose)
            else -> onClose()
        }
    }
    BackHandler(onBack = ::back) // the system back gesture steps back too, instead of dropping the wizard
    fun next() {
        val i = steps.indexOf(step)
        if (i < steps.lastIndex) step = steps[i + 1]
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(vm.type?.let { stringResource(R.string.wizard_title_type, it.label) } ?: stringResource(R.string.wizard_title), onBack = ::back)
        val plants = vm.plants
        Box(Modifier.weight(1f)) {
            when {
                plants == null && vm.loadError != null -> ErrorBox(vm.loadError!!)
                plants == null -> LoadingBox()
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (step) {
                        Step.Type -> TypeStep { vm.type = it; next() }
                        Step.Plants -> PlantsStep(plants, vm)
                        Step.Data -> DataStep(vm)
                        Step.Comment -> CommentStep(vm)
                        Step.Summary -> SummaryStep(vm, plants)
                    }
                }
            }
        }
        if (step != Step.Type) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::back, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_back)) }
                val canNext = when (step) {
                    Step.Plants -> vm.selected.isNotEmpty()
                    Step.Data -> vm.dataValid
                    else -> true
                }
                if (step == Step.Summary) {
                    Button(onClick = { vm.save(onClose) }, enabled = !vm.saving && vm.uploading == 0, modifier = Modifier.weight(1f)) {
                        if (vm.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.common_save))
                    }
                } else {
                    Button(onClick = ::next, enabled = canNext, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_next)) }
                }
            }
        }
    }

    ConfirmDialog(vm.confirm) { vm.confirm = null }
    vm.harvested?.let {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.wizard_move_title)) },
            text = { Text(stringResource(R.string.wizard_move_text)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    vm.sensors.forEach { s -> TextButton(onClick = { vm.movePlants(s.id, onClose) }) { Text(stringResource(R.string.wizard_move_to, s.name)) } }
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.wizard_keep)) } },
        )
    }
}

/** 0.1.6 type grid: grouped, coloured buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeStep(onPick: (LogType) -> Unit) {
    LogType.groups.forEach { (title, types) ->
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            types.forEach { t ->
                Row(
                    Modifier
                        .widthIn(min = 150.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.CardRaised)
                        .clickable { onPick(t) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(8.dp).fillMaxHeight().background(t.color))
                    Text(t.label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun PlantsStep(plants: List<Plant>, vm: LogWizardViewModel) {
    Text(stringResource(R.string.wizard_which_plants), style = MaterialTheme.typography.titleLarge)
    if (plants.isEmpty()) Text(stringResource(R.string.wizard_no_plants), color = Palette.TextSecondary)
    if (plants.size > 1) {
        TextButton(onClick = {
            if (vm.selected.size == plants.size) vm.selected.clear() else { vm.selected.clear(); vm.selected.addAll(plants.map { it.id }) }
        }) { Text(if (vm.selected.size == plants.size) stringResource(R.string.wizard_select_none) else stringResource(R.string.wizard_select_all)) }
    }
    plants.forEach { p ->
        val checked = p.id in vm.selected
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.CardRaised)
                .border(2.dp, if (checked) Palette.CardBorder else Palette.CardRaised, RoundedCornerShape(6.dp))
                .clickable { if (checked) vm.selected.remove(p.id) else vm.selected.add(p.id) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, { if (checked) vm.selected.remove(p.id) else vm.selected.add(p.id) })
            Column {
                Text(p.nickname, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(p.strain?.name, formatPhase(p.phase), formatAge(p.age, p.phase)).joinToString(" · "),
                    color = Palette.TextSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataStep(vm: LogWizardViewModel) {
    when (vm.type?.data) {
        DataKind.Feeding -> FeedingForm(vm)
        DataKind.LightHeight -> ValueField(stringResource(R.string.wizard_light_height), "cm", vm.value) { vm.value = it }
        DataKind.HumidityTarget -> ValueField(stringResource(R.string.wizard_humidity_target), "%RH", vm.value) { vm.value = it }
        DataKind.TemperatureTarget -> ValueField(stringResource(R.string.wizard_temperature_target), "°C", vm.value) { vm.value = it }
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedingForm(vm: LogWizardViewModel) {
    vm.scheduleWeek?.let { at ->
        val week = at.week
        OutlinedButton(onClick = { vm.fillFromSchedule(week) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.wizard_fill, vm.schedule?.displayName.orEmpty(), week.week, schedulePhaseLabel(week.phase)))
        }
        if (at.pastEnd) {
            Text(stringResource(R.string.wizard_past_end, at.plantWeek), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        week.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
    }
    SectionHeader(stringResource(R.string.wizard_nutrient_line))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.lines.forEach { l -> FilterChip(selected = vm.brand == l.brand, onClick = { vm.selectBrand(l.brand) }, label = { Text(l.brand) }) }
    }
    SectionHeader(stringResource(R.string.wizard_nutrients))
    vm.nutrients.forEachIndexed { i, row ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductPicker(vm.line?.products?.map { it.name }.orEmpty(), row.product, { row.product = it }, Modifier.weight(1f))
            OutlinedTextField(
                row.amount,
                { row.amount = it },
                label = { Text(vm.line?.products?.firstOrNull { it.name == row.product }?.unit ?: "ml/L") },
                singleLine = true,
                textStyle = NumericStyle,
                isError = row.amount.isNotBlank() && row.amount.toNumber() == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(110.dp),
            )
            IconButton(onClick = { vm.nutrients.removeAt(i) }) { Icon(Icons.Outlined.Close, stringResource(R.string.common_remove)) }
        }
    }
    OutlinedButton(onClick = { vm.line?.products?.firstOrNull()?.let { vm.nutrients.add(NutrientRow(it.name)) } }, enabled = vm.line != null) {
        Text(stringResource(R.string.wizard_add_nutrient))
    }
    SectionHeader(stringResource(R.string.wizard_solution))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("pH", vm.ph, { vm.ph = it }, Modifier.weight(1f))
        NumberField("EC", vm.ec, { vm.ec = it }, Modifier.weight(1f))
        NumberField(stringResource(R.string.wizard_volume_l), vm.volume, { vm.volume = it }, Modifier.weight(1f))
    }
}

@Composable
private fun ProductPicker(options: List<String>, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(value, maxLines = 1) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); open = false }) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, textStyle = NumericStyle,
        isError = value.isNotBlank() && value.toNumber() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
private fun ValueField(label: String, unit: String, value: String, onChange: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleLarge)
    NumberField(unit, value, onChange, Modifier.fillMaxWidth())
}

@Composable
private fun CommentStep(vm: LogWizardViewModel) {
    Text(stringResource(R.string.common_comments), style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        vm.comment,
        { vm.comment = it },
        placeholder = { Text(stringResource(R.string.wizard_comment_hint)) },
        minLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Date then time; returns epoch ms (local time zone). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePicker(initial: Long, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial).atZone(zone)
    // DatePicker works in UTC midnight millis.
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
        },
    )
    val timeState = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    if (date == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    date = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start.toLocalDate()
                }) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    onPicked(date!!.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant().toEpochMilli())
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(timeState) },
        )
    }
}

/** 0.1.6 confirm page (type, plants, date, details, comments) plus photo upload. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStep(vm: LogWizardViewModel, plants: List<Plant>) {
    val type = vm.type ?: return
    var picking by remember { mutableStateOf(false) }
    val picker = rememberPhotoPicker(galleryMax = MAX_LOG_PHOTOS, onPicked = vm::addPhoto)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.CardRaised).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeDot(type.api, 22)
            Spacer(Modifier.width(12.dp))
            Text(type.label, style = MaterialTheme.typography.headlineSmall)
        }
        SummaryLabel(stringResource(R.string.common_plants))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            plants.filter { it.id in vm.selected }.forEach { p ->
                Text(p.nickname, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.NavCenter).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        SummaryLabel(stringResource(R.string.wizard_date_time))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.ts?.let(::formatDateTime) ?: stringResource(R.string.common_now), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vm.ts != null) TextButton(onClick = { vm.ts = null }) { Text(stringResource(R.string.common_now)) }
            OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.wizard_select_date)) }
        }
        if (type.data != DataKind.None) {
            SummaryLabel(stringResource(R.string.wizard_details))
            when (type.data) {
                DataKind.Feeding -> {
                    vm.nutrients.forEach { n ->
                        DetailLine(n.product, "${n.amount} ${vm.line?.products?.firstOrNull { it.name == n.product }?.unit ?: "ml/L"}")
                    }
                    if (vm.ph.isNotBlank()) DetailLine("pH", vm.ph)
                    if (vm.ec.isNotBlank()) DetailLine("EC", vm.ec)
                    if (vm.volume.isNotBlank()) DetailLine(stringResource(R.string.wizard_volume), "${vm.volume} L")
                }
                DataKind.LightHeight -> DetailLine(stringResource(R.string.wizard_new_light), "${vm.value} cm")
                DataKind.HumidityTarget -> DetailLine(stringResource(R.string.wizard_new_humidity), "${vm.value} %RH")
                DataKind.TemperatureTarget -> DetailLine(stringResource(R.string.wizard_new_temperature), "${vm.value} °C")
                DataKind.None -> Unit
            }
        }
        SummaryLabel(stringResource(R.string.common_comments))
        Text(
            vm.comment.ifBlank { stringResource(R.string.common_none) },
            fontStyle = if (vm.comment.isBlank()) androidx.compose.ui.text.font.FontStyle.Italic else null,
            color = if (vm.comment.isBlank()) Palette.TextSecondary else Palette.TextPrimary,
        )
        SummaryLabel(stringResource(R.string.wizard_photos, vm.photoIds.size, MAX_LOG_PHOTOS))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.photoIds.forEach { id ->
                Box {
                    AsyncImage(vm.photoModel(id), null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)))
                    IconButton(onClick = { vm.removePhoto(id) }, modifier = Modifier.align(Alignment.TopEnd).size(30.dp)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.wizard_remove_photo), tint = Palette.TextPrimary)
                    }
                }
            }
            repeat(vm.uploading) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(Palette.PhotoPlaceholder), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
        if (vm.photoSlots > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = picker.takePhoto) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_camera)) }
                OutlinedButton(onClick = picker.pickFromGallery) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_gallery)) }
            }
        }
        if (type.api in setOf("Germination", "Sprout", "FlowerFlip", "Harvest", "Trim")) {
            Text(stringResource(R.string.wizard_milestone), color = Palette.NavSelected)
        }
    }
    vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (picking) DateTimePicker(vm.ts ?: System.currentTimeMillis(), onDismiss = { picking = false }) { vm.ts = it; picking = false }
}

@Composable
private fun SummaryLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = NumericStyle)
    }
}
