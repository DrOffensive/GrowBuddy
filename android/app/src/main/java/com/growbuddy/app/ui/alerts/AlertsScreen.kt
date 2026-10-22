package com.growbuddy.app.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.Alert
import com.growbuddy.app.data.remote.AlertSettings
import com.growbuddy.app.data.remote.Event
import com.growbuddy.app.data.remote.NotificationLanguage
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.MetricKind
import com.growbuddy.app.ui.common.Page
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.Pager
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatBand
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Human wording for alert metrics (docs/DOMAIN.md › Alerts). */
fun alertTitle(a: Alert): String {
    val what = when (a.metric) {
        "temperature" -> appString(R.string.metric_temperature)
        "humidity" -> appString(R.string.metric_humidity)
        "vpd" -> appString(R.string.metric_vpd)
        "sensor_offline" -> appString(R.string.alert_offline)
        "battery_low" -> appString(R.string.alert_battery_low)
        "camera" -> appString(R.string.alert_camera)
        else -> a.metric
    }
    return listOfNotNull(a.sensorName, what).joinToString(": ")
}

fun alertDetail(a: Alert): String? {
    val kind = when (a.metric) {
        "temperature" -> MetricKind.Temperature
        "humidity" -> MetricKind.Humidity
        "vpd" -> MetricKind.Vpd
        else -> null
    }
    return when {
        kind != null -> "${formatNumber(a.value, kind.decimals)} ${kind.unit}" + (formatBand(a.bandMin, a.bandMax, kind)?.let { appString(if (a.level == "critical") R.string.alert_range_critical else R.string.alert_range_warning, it) } ?: "")
        a.metric == "battery_low" -> a.value?.let { "${formatNumber(it, 0)} %" }
        else -> null
    }
}

fun alertColor(level: String): Color = if (level == "critical") Palette.Danger else Palette.Warning

class AlertsViewModel(private val container: AppContainer) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var active by mutableStateOf<List<Alert>?>(null)
        private set
    var activeError by mutableStateOf<String?>(null)
        private set
    val history = Pager<Alert>({ it.id }) { before -> api.alertHistory(before = before).let { Page(it.items, it.nextBefore) } }
    var eventLevel by mutableStateOf<String?>(null)
        private set
    val events = Pager<Event>({ it.id }) { before -> api.events(before = before, level = eventLevel).let { Page(it.items, it.nextBefore) } }
    var settings by mutableStateOf<AlertSettings?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun refresh() {
        viewModelScope.launch {
            apiCall { api.activeAlerts() }.onSuccess { active = it; activeError = null }.onFailure { activeError = it.message }
            history.refresh()
            events.refresh()
        }
    }

    fun filterEvents(level: String?) {
        eventLevel = level
        viewModelScope.launch { events.refresh() }
    }

    fun more(tab: Int) {
        viewModelScope.launch { if (tab == 1) history.loadMore() else if (tab == 2) events.loadMore() }
    }

    var languages by mutableStateOf<List<NotificationLanguage>>(emptyList())
        private set

    fun loadSettings() {
        viewModelScope.launch {
            apiCall { api.alertSettings() }.onSuccess { settings = it }.onFailure { message = it.message }
            apiCall { api.notificationLanguages() }.onSuccess { languages = it }
        }
    }

    fun saveSettings(s: AlertSettings, onDone: () -> Unit) {
        viewModelScope.launch {
            apiCall { api.putAlertSettings(s) }.onSuccess { settings = it; message = appString(R.string.alert_saved); onDone() }.onFailure { message = it.message }
        }
    }

    fun test() {
        viewModelScope.launch {
            message = apiCall { api.testNotification().orThrow() }.fold(onSuccess = { appString(R.string.settings_test_sent) }, onFailure = { it.message })
        }
    }
}

private val tabs get() = listOf(appString(R.string.alerts_tab_active), appString(R.string.alerts_tab_history), appString(R.string.alerts_tab_events))

@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { AlertsViewModel(container) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() } }

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.alerts_title), onBack = onBack, actions = {
            IconButton(onClick = { vm.loadSettings(); editing = true }) { Icon(Icons.Outlined.Settings, appString(R.string.alerts_settings), tint = Palette.TextPrimary) }
        })
        Row(Modifier.fillMaxWidth().background(Palette.TabBar)) {
            tabs.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }.background(if (tab == i) Palette.NavCenter else Palette.TabBar).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val count = if (i == 0) vm.active?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: "" else ""
                    Text(label + count, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), color = if (tab == i) Palette.TextPrimary else Palette.TextSecondary)
                }
            }
        }
        vm.message?.let { Text(it, modifier = Modifier.padding(16.dp, 8.dp)) }
        when (tab) {
            0 -> ActiveTab(vm)
            1 -> PagedList(vm.history.items, vm.history.error, vm.history.loading, onMore = { vm.more(1) }, empty = appString(R.string.alerts_no_history)) { AlertRow(it, showEnd = true) }
            else -> Column {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to stringResource(R.string.events_all), "info" to stringResource(R.string.events_info), "warning" to stringResource(R.string.events_warning), "error" to stringResource(R.string.events_error)).forEach { (level, label) ->
                        FilterChip(selected = vm.eventLevel == level, onClick = { vm.filterEvents(level) }, label = { Text(label) })
                    }
                }
                PagedList(vm.events.items, vm.events.error, vm.events.loading, onMore = { vm.more(2) }, empty = appString(R.string.events_none)) { EventRow(it) }
            }
        }
    }
    if (editing) SettingsSheet(vm, onDismiss = { editing = false })
}

@Composable
private fun ActiveTab(vm: AlertsViewModel) {
    val active = vm.active
    when {
        active == null && vm.activeError != null -> ErrorBox(vm.activeError!!, onRetry = vm::refresh)
        active == null -> LoadingBox()
        active.isEmpty() -> EmptyState(Icons.Outlined.CheckCircle, appString(R.string.alerts_all_good))
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(active, key = { it.id }) { AlertRow(it, showEnd = false) }
        }
    }
}

@Composable
private fun <T> PagedList(
    items: List<T>?,
    error: String?,
    loading: Boolean,
    onMore: () -> Unit,
    empty: String,
    row: @Composable (T) -> Unit,
) {
    val state = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) onMore() }
    when {
        items == null && error != null -> ErrorBox(error)
        items == null -> LoadingBox()
        items.isEmpty() -> EmptyState(Icons.Outlined.NotificationsNone, empty)
        else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(items) { row(it) }
            if (loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun AlertRow(a: Alert, showEnd: Boolean) {
    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.CardRaised)) {
        Box(Modifier.width(8.dp).fillMaxHeight().background(alertColor(a.level)))
        Column(Modifier.padding(12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(alertTitle(a), style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
                Text(a.level.replaceFirstChar { it.uppercase() }, color = alertColor(a.level), style = MaterialTheme.typography.titleMedium)
            }
            alertDetail(a)?.let { Text(it, style = NumericStyle) }
            val time = when {
                !showEnd || a.endedTs == null -> stringResource(R.string.alerts_since, formatDateTime(a.startedTs), formatAgo(a.startedTs))
                else -> "${formatDateTime(a.startedTs)} – ${formatDateTime(a.endedTs)}"
            }
            Text(time, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun EventRow(e: Event) {
    val color = when (e.level) {
        "error" -> Palette.Danger
        "warning" -> Palette.Warning
        else -> Palette.TextSecondary
    }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.CardRaised).padding(12.dp)) {
        Row {
            Text(e.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
            Text(formatDateTime(e.ts), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Text(e.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(vm: AlertsViewModel, onDismiss: () -> Unit) {
    val current = vm.settings
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (current == null) {
            LoadingBox(Modifier.padding(32.dp))
            return@ModalBottomSheet
        }
        var warnDelay by remember(current) { mutableStateOf((current.warningDebounceS / 60).toString()) }
        var debounce by remember(current) { mutableStateOf((current.debounceS / 60).toString()) }
        var repeat by remember(current) { mutableStateOf((current.repeatAfterS / 60).toString()) }
        var offline by remember(current) { mutableStateOf((current.offlineAfterS / 60).toString()) }
        var battery by remember(current) { mutableStateOf(current.batteryLowPct.toString()) }
        var warn by remember(current) { mutableStateOf(current.notifyWarning) }
        var crit by remember(current) { mutableStateOf(current.notifyCritical) }
        var resolved by remember(current) { mutableStateOf(current.notifyResolved) }
        var language by remember(current) { mutableStateOf(current.language) }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.alerts_settings), style = MaterialTheme.typography.headlineMedium)
            Minutes(appString(R.string.alerts_warn_delay), warnDelay) { warnDelay = it }
            Minutes(appString(R.string.alerts_crit_delay), debounce) { debounce = it }
            Minutes(appString(R.string.alerts_repeat), repeat) { repeat = it }
            Minutes(appString(R.string.alerts_offline_after), offline) { offline = it }
            Minutes(appString(R.string.alerts_battery_at), battery) { battery = it }
            SectionHeader(stringResource(R.string.alerts_notify_about))
            Toggle(appString(R.string.alerts_warnings), warn) { warn = it }
            Toggle(appString(R.string.alerts_criticals), crit) { crit = it }
            Toggle(appString(R.string.alerts_resolved), resolved) { resolved = it }
            if (vm.languages.size > 1) {
                SectionHeader(stringResource(R.string.alerts_notification_language))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.languages.forEach { l -> FilterChip(selected = language == l.code, onClick = { language = l.code }, label = { Text(l.name) }) }
                }
            }
            val parsed = listOf(debounce, repeat, offline, battery, warnDelay).map { it.trim().toIntOrNull() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::test) { Text(stringResource(R.string.settings_send_test)) }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = parsed.all { it != null && it >= 0 },
                    onClick = {
                        vm.saveSettings(
                            AlertSettings(
                                debounceS = parsed[0]!! * 60,
                                warningDebounceS = parsed[4]!! * 60,
                                repeatAfterS = parsed[1]!! * 60,
                                offlineAfterS = parsed[2]!! * 60,
                                batteryLowPct = parsed[3]!!,
                                notifyWarning = warn,
                                notifyCritical = crit,
                                notifyResolved = resolved,
                                language = language,
                            ),
                            onDone = onDismiss,
                        )
                    },
                ) { Text(stringResource(R.string.common_save)) }
            }
            vm.message?.let { Text(it) }
        }
    }
}

@Composable
private fun Minutes(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, textStyle = NumericStyle,
        isError = value.trim().toIntOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}
