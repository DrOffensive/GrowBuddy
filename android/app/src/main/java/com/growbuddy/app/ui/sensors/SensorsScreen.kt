package com.growbuddy.app.ui.sensors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.AdoptRequest
import com.growbuddy.app.data.remote.DiscoveredSensor
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.MetricKind
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.RssiIcon
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.polling
import com.growbuddy.app.ui.common.refreshTrigger
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SensorsViewModel(private val container: AppContainer) : ViewModel() {
    private val trigger = refreshTrigger()
    val sensors = polling(10_000, trigger) { apiCall { container.apiProvider.api().sensors() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var discovered by mutableStateOf<List<DiscoveredSensor>?>(null)
        private set
    var adoptError by mutableStateOf<String?>(null)
        private set

    suspend fun loadDiscovered() {
        apiCall { container.apiProvider.api().discoveredSensors() }.onSuccess { discovered = it }
    }

    fun adopt(mac: String, name: String, onDone: (Long) -> Unit) {
        adoptError = null
        viewModelScope.launch {
            apiCall { container.apiProvider.api().adoptSensor(AdoptRequest(mac, name.trim())) }
                .onSuccess {
                    trigger.tryEmit(Unit)
                    onDone(it.id)
                }
                .onFailure { adoptError = it.message }
        }
    }
}

@Composable
fun SensorsScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { SensorsViewModel(container) }
    val sensors by vm.sensors.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(stringResource(R.string.settings_sensors), onBack = onBack)
            val result = sensors
            when {
                result == null -> LoadingBox()
                result.isFailure -> ErrorBox(result.exceptionOrNull()?.message ?: stringResource(R.string.events_error))
                result.getOrThrow().isEmpty() -> EmptyState(
                    Icons.Outlined.Sensors,
                    appString(R.string.sensors_empty),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(result.getOrThrow(), key = { it.id }) { SensorRow(it, onClick = { onOpen(it.id) }) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { searching = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.sensors_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (searching) {
        DiscoverySheet(vm, onDismiss = { searching = false }, onAdopted = { id ->
            searching = false
            onOpen(id)
        })
    }
}

@Composable
private fun SensorRow(sensor: Sensor, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(sensor.name) },
            supportingContent = {
                Text(
                    stringResource(R.string.sensors_subtitle, sensor.mac, formatAgo(sensor.lastSeen), if (sensor.rangeMode == "manual") stringResource(R.string.sensor_manual) else stringResource(R.string.sensors_auto)) +
                        if (!sensor.alertsEnabled) stringResource(R.string.sensors_alerts_off) else "",
                    color = Palette.TextSecondary,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sensor.batteryPct?.let { Text("$it%", color = Palette.TextSecondary); Spacer(Modifier.width(10.dp)) }
                    RssiIcon(sensor.rssi, online = sensor.lastSeen != null, modifier = Modifier.size(26.dp))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverySheet(vm: SensorsViewModel, onDismiss: () -> Unit, onAdopted: (Long) -> Unit) {
    var naming by remember { mutableStateOf<DiscoveredSensor?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            vm.loadDiscovered()
            delay(3_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.sensors_nearby), style = MaterialTheme.typography.titleLarge)
            Text(
                appString(R.string.sensors_nearby_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            val found = vm.discovered
            when {
                found == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                found.isEmpty() -> Text(stringResource(R.string.sensors_searching), color = Palette.TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
                else -> found.forEach { device ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !device.encrypted) { naming = device },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(device.name ?: device.mac) },
                        supportingContent = {
                            val v = device.values
                            Column {
                                when {
                                    device.encrypted -> Text(stringResource(R.string.sensors_encrypted), color = Palette.Warning)
                                    v != null -> Text(
                                        // Just what helps tell identical sensors apart; battery shows once adopted.
                                        "${formatNumber(v.temperatureC, 1)} ${MetricKind.Temperature.unit}   " +
                                            "${formatNumber(v.humidityPct, 1)} ${MetricKind.Humidity.unit}",
                                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                                    )
                                }
                                Text(device.mac, style = MaterialTheme.typography.bodySmall.merge(NumericStyle), color = Palette.TextSecondary)
                            }
                        },
                        trailingContent = { RssiIcon(device.rssi, online = true, modifier = Modifier.size(26.dp)) },
                    )
                }
            }
        }
    }
    naming?.let { device ->
        var name by remember(device.mac) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(R.string.sensors_add)) },
            text = {
                Column {
                    Text(device.mac, style = NumericStyle, color = Palette.TextSecondary)
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sensors_name_hint)) }, singleLine = true, modifier = Modifier.padding(top = 12.dp))
                    vm.adoptError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.adopt(device.mac, name, onAdopted) }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
