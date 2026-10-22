package com.growbuddy.app.ui.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.MetricRange
import com.growbuddy.app.data.remote.PhasePresetRequest
import com.growbuddy.app.data.remote.PhaseRange
import com.growbuddy.app.data.remote.SensorDetail
import com.growbuddy.app.data.remote.SensorPatch
import com.growbuddy.app.data.remote.SensorRanges
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.MetricKind
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Editable text state for one metric's four bounds. */
class RangeFields(range: MetricRange) {
    var warnMin by mutableStateOf(range.warnMin.toField())
    var warnMax by mutableStateOf(range.warnMax.toField())
    var critMin by mutableStateOf(range.critMin.toField())
    var critMax by mutableStateOf(range.critMax.toField())

    fun set(range: MetricRange) {
        warnMin = range.warnMin.toField(); warnMax = range.warnMax.toField()
        critMin = range.critMin.toField(); critMax = range.critMax.toField()
    }

    fun toRange() = MetricRange(warnMin.toBound(), warnMax.toBound(), critMin.toBound(), critMax.toBound())

    val invalid: Boolean get() = listOf(warnMin, warnMax, critMin, critMax).any { it.isNotBlank() && it.toBound() == null }
}

private fun Double?.toField() = this?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: ""
private fun String.toBound(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()

class SensorDetailViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {
    var sensor by mutableStateOf<SensorDetail?>(null)
        private set
    var phases by mutableStateOf<List<PhaseRange>>(emptyList())
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var name by mutableStateOf("")
    val fields = mapOf(
        MetricKind.Temperature to RangeFields(MetricRange()),
        MetricKind.Humidity to RangeFields(MetricRange()),
        MetricKind.Vpd to RangeFields(MetricRange()),
    )

    private val api get() = container.apiProvider.api()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            apiCall { api.sensor(id) }
                .onSuccess {
                    sensor = it
                    name = it.name
                    showRanges(it.ranges)
                    loadError = null
                }
                .onFailure { loadError = it.message }
            apiCall { api.phaseRanges() }.onSuccess { list -> phases = list.filter { it.enforceLimits } }
        }
    }

    private fun showRanges(r: SensorRanges) {
        fields.getValue(MetricKind.Temperature).set(r.temperature)
        fields.getValue(MetricKind.Humidity).set(r.humidity)
        fields.getValue(MetricKind.Vpd).set(r.vpd)
    }

    private fun currentRanges() = SensorRanges(
        temperature = fields.getValue(MetricKind.Temperature).toRange(),
        humidity = fields.getValue(MetricKind.Humidity).toRange(),
        vpd = fields.getValue(MetricKind.Vpd).toRange(),
    )

    fun saveRanges() {
        viewModelScope.launch {
            message = apiCall { api.putRanges(id, currentRanges()) }
                .fold(onSuccess = { showRanges(it); appString(R.string.sensor_ranges_saved) }, onFailure = { it.message })
            apiCall { api.sensor(id) }.onSuccess { sensor = it }
        }
    }

    /** Fills the editor from phase templates; the user still has to save. */
    fun previewPreset(selected: List<String>) {
        viewModelScope.launch {
            message = apiCall { api.rangesFromPhases(id, PhasePresetRequest(selected, apply = false)) }
                .fold(
                    onSuccess = { showRanges(it); appString(R.string.sensor_filled, selected.joinToString { p -> formatPhase(p) }) },
                    onFailure = { it.message },
                )
        }
    }

    fun patch(body: SensorPatch) {
        viewModelScope.launch {
            apiCall { api.patchSensor(id, body) }
                .onSuccess {
                    sensor = it
                    showRanges(it.ranges) // switching mode changes the effective ranges
                    message = appString(R.string.sensor_saved)
                }
                .onFailure { message = it.message }
        }
    }

    fun retire(onDone: () -> Unit) {
        viewModelScope.launch {
            apiCall { api.retireSensor(id).orThrow() }.onSuccess { onDone() }.onFailure { message = it.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailScreen(id: Long, onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = "sensor-$id") { SensorDetailViewModel(container, id) }
    var presetOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    val sensor = vm.sensor

    Column(Modifier.fillMaxSize()) {
        PageHeader(sensor?.name ?: stringResource(R.string.sensor_title), onBack = onBack)
        when {
            sensor == null && vm.loadError != null -> ErrorBox(vm.loadError!!, onRetry = vm::load)
            sensor == null -> LoadingBox()
            else -> Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.sensor_subtitle, sensor.mac, formatAgo(sensor.lastSeen)) + (sensor.batteryPct?.let { stringResource(R.string.sensor_battery_suffix, it) } ?: ""),
                    style = NumericStyle,
                    color = Palette.TextSecondary,
                )
                SectionHeader(stringResource(R.string.sensor_general))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vm.name, { vm.name = it }, label = { Text(stringResource(R.string.editor_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = vm.name.isNotBlank() && vm.name != sensor.name, onClick = { vm.patch(SensorPatch(name = vm.name.trim())) }) {
                        Text(stringResource(R.string.sensor_rename))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sensor_alerts), modifier = Modifier.weight(1f))
                    Switch(checked = sensor.alertsEnabled, onCheckedChange = { vm.patch(SensorPatch(alertsEnabled = it)) })
                }

                SectionHeader(stringResource(R.string.sensor_ranges))
                val auto = sensor.rangeMode == "auto"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = auto, onClick = { if (!auto) vm.patch(SensorPatch(rangeMode = "auto")) }, label = { Text(stringResource(R.string.sensor_follow)) })
                    FilterChip(selected = !auto, onClick = { if (auto) vm.patch(SensorPatch(rangeMode = "manual")) }, label = { Text(stringResource(R.string.sensor_manual)) })
                }
                Text(
                    when {
                        !auto -> appString(R.string.sensor_manual_hint)
                        sensor.rangeSource != null -> stringResource(R.string.sensor_auto_hint, formatPhase(sensor.rangeSource.phase), sensor.rangeSource.nickname)
                        else -> appString(R.string.sensor_no_plant)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                vm.fields.forEach { (kind, f) -> RangeEditor(kind, f, enabled = !auto) }
                if (!auto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { presetOpen = true }, enabled = vm.phases.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sensor_fill_phases))
                        }
                        Button(onClick = vm::saveRanges, enabled = vm.fields.values.none { it.invalid }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sensor_save_ranges))
                        }
                    }
                }
                vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                SectionHeader(stringResource(R.string.sensor_danger))
                TextButton(onClick = {
                    confirm = Confirmation(
                        appString(R.string.sensor_remove_title),
                        appString(R.string.sensor_remove_text),
                        appString(R.string.common_remove),
                    ) { vm.retire(onBack) }
                }) { Text(stringResource(R.string.sensor_remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (presetOpen) PresetDialog(vm.phases, onDismiss = { presetOpen = false }, onApply = { presetOpen = false; vm.previewPreset(it) })
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun RangeEditor(kind: MetricKind, f: RangeFields, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${kind.label} (${kind.unit})", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoundField(appString(R.string.sensor_warn_min), f.warnMin, { f.warnMin = it }, enabled, Modifier.weight(1f))
                BoundField(appString(R.string.sensor_warn_max), f.warnMax, { f.warnMax = it }, enabled, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoundField(appString(R.string.sensor_crit_min), f.critMin, { f.critMin = it }, enabled, Modifier.weight(1f))
                BoundField(appString(R.string.sensor_crit_max), f.critMax, { f.critMax = it }, enabled, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BoundField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotBlank() && value.toBound() == null,
        textStyle = NumericStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetDialog(phases: List<PhaseRange>, onDismiss: () -> Unit, onApply: (List<String>) -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { selected.clear() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sensor_fill_phases)) },
        text = {
            Column {
                Text(
                    appString(R.string.sensor_phases_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    phases.forEach { p ->
                        FilterChip(
                            selected = p.phase in selected,
                            onClick = { if (p.phase in selected) selected.remove(p.phase) else selected.add(p.phase) },
                            label = { Text(formatPhase(p.phase)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onApply(selected.toList()) }) { Text(stringResource(R.string.sensor_fill)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
