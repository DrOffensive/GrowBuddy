package com.growbuddy.app.ui.settings

import android.Manifest
import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.BuildConfig
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ConfigState
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.ServerStatus
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.data.widget.EnvironmentWidget
import com.growbuddy.app.data.widget.HomeWidgetProvider
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appPlural
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.common.polling
import com.growbuddy.app.ui.common.refreshTrigger
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val trigger = refreshTrigger()
    val status = polling(15_000, trigger) { apiCall { container.apiProvider.api().status() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var message by mutableStateOf<String?>(null)
        private set

    fun testNotification() {
        viewModelScope.launch {
            message = apiCall { container.apiProvider.api().testNotification().orThrow() }.fold(
                onSuccess = { appString(R.string.settings_test_sent) },
                onFailure = { it.message },
            )
        }
    }

    /** Server notifications follow the app language when the server has texts for it. */
    fun syncNotificationLanguage(code: String) {
        container.appScope.launch {
            val api = container.apiProvider.api()
            val known = apiCall { api.notificationLanguages() }.getOrNull()?.map { it.code } ?: return@launch
            val target = if (code in known) code else "en"
            apiCall { api.alertSettings() }.onSuccess { current ->
                if (current.language != target) apiCall { api.putAlertSettings(current.copy(language = target)) }
            }
        }
    }

    fun syncNow() {
        container.appScope.launch {
            message = if (container.sync.flush()) appString(R.string.settings_synced) else appString(R.string.settings_sync_retry)
        }
    }

    fun clearFailed() {
        viewModelScope.launch { container.outbox.clearFailed() }
    }

    fun disconnect() {
        viewModelScope.launch { container.settings.clear() }
    }
}

@Composable
fun SettingsScreen(onSensors: () -> Unit, onAlerts: () -> Unit, onSchedule: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { SettingsViewModel(container) }
    val status by vm.status.collectAsStateWithLifecycle()
    val config = (container.apiProvider.configState.collectAsStateWithLifecycle().value as? ConfigState.Ready)?.config
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(appString(R.string.settings_title), icon = R.drawable.ic_nav_settings)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_server))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row { Text(config?.baseUrl ?: "–", style = MaterialTheme.typography.titleMedium) }
                    status?.fold(
                        onSuccess = { StatusLines(it) },
                        onFailure = { Text(it.message ?: appString(R.string.settings_unreachable), color = MaterialTheme.colorScheme.error) },
                    ) ?: Text(stringResource(R.string.settings_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader(appString(R.string.settings_sensors))
            Card(Modifier.fillMaxWidth().clickable(onClick = onSensors)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_sensors), style = MaterialTheme.typography.titleMedium)
                        Text(
                            status?.getOrNull()?.let { stringResource(R.string.settings_sensors_count, it.ble.adopted) } ?: appString(R.string.settings_sensors_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            NavRow(appString(R.string.settings_alerts), status?.getOrNull()?.let { stringResource(R.string.settings_alerts_count, it.alerts.activeAlerts) } ?: appString(R.string.settings_alerts_hint), onAlerts)
            SectionHeader(appString(R.string.settings_grow))
            NavRow(appString(R.string.settings_schedule), appString(R.string.settings_schedule_hint), onSchedule)
            SectionHeader(appString(R.string.settings_notifications))
            AppNotificationsCard(onTurnOff = {
                confirm = Confirmation(
                    appString(R.string.settings_push_off_title),
                    appString(R.string.settings_push_off_text),
                    appString(R.string.settings_turn_off),
                    container.appPush::disable,
                )
            })
            WidgetCard()
            OutlinedButton(onClick = vm::testNotification, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_send_test))
            }
            vm.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            SectionHeader(appString(R.string.settings_sync))
            val outbox by container.outbox.state.collectAsStateWithLifecycle()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (outbox.pending.isEmpty()) appString(R.string.settings_all_synced)
                        else pluralStringResource(R.plurals.settings_pending, outbox.pending.size, outbox.pending.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    outbox.pending.take(5).forEach { Text("• ${it.label}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (outbox.pending.isNotEmpty()) OutlinedButton(onClick = vm::syncNow) { Text(stringResource(R.string.settings_sync_now)) }
                    if (outbox.failed.isNotEmpty()) {
                        Text(stringResource(R.string.settings_rejected), color = MaterialTheme.colorScheme.error)
                        outbox.failed.forEach { Text("• ${it.label}: ${it.error}", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = {
                            confirm = Confirmation(
                                appString(R.string.settings_dismiss_title),
                                appPlural(R.plurals.settings_dismiss_text, outbox.failed.size, outbox.failed.size),
                                appString(R.string.settings_dismiss),
                                vm::clearFailed,
                            )
                        }) { Text(stringResource(R.string.settings_dismiss)) }
                    }
                }
            }
            SectionHeader(stringResource(R.string.language_title))
            LanguagePicker(onChosen = vm::syncNotificationLanguage)
            SectionHeader(appString(R.string.settings_connection))
            TextButton(onClick = {
                confirm = Confirmation(
                    appString(R.string.settings_change_server_title),
                    appString(R.string.settings_change_server_text),
                    appString(R.string.settings_change_server),
                    vm::disconnect,
                )
            }) { Text(stringResource(R.string.settings_change_server_button)) }
            SectionHeader(appString(R.string.settings_about))
            AboutCard(serverVersion = status?.getOrNull()?.version)
        }
    }
    ConfirmDialog(confirm) { confirm = null }
}

/** App information, laid out like the Server card. */
@Composable
private fun AboutCard(serverVersion: String?) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GrowBuddy", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = muted)
            Text(stringResource(R.string.settings_server_version, serverVersion ?: "–"), color = muted)
            installed?.let { Text(stringResource(R.string.settings_installed, formatDateTime(it)), color = muted) }
            Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", color = muted)
            Text(stringResource(R.string.settings_tagline), color = muted)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_developer), style = MaterialTheme.typography.titleSmall, color = Palette.Green)
        }
    }
}

@Composable
private fun StatusLines(s: ServerStatus) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(R.string.settings_status_version, s.version, (s.uptimeS / 3600).toInt(), ((s.uptimeS % 3600) / 60).toInt()), color = muted)
    Text(stringResource(R.string.settings_status_sensors, s.ble.adopted, s.ble.source, s.ble.state), color = muted)
    Text(stringResource(R.string.settings_status_camera, s.camera.state) + (s.camera.error?.let { " – $it" } ?: ""), color = muted)
    Text(stringResource(R.string.settings_status_alerts, s.alerts.activeAlerts), color = muted)
    Text(stringResource(R.string.settings_status_backup, s.lastBackup?.let { "$it (${formatAgo(s.lastBackupTs)})" } ?: stringResource(R.string.settings_no_backup)), color = muted)
    Text(stringResource(R.string.settings_status_db, s.dbSizeBytes / 1024, s.diskFreeBytes / 1_073_741_824), color = muted)
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** GrowBuddy's own alert notifications (leaf icon), delivered through the ntfy app via UnifiedPush. */
@Composable
private fun AppNotificationsCard(onTurnOff: () -> Unit) {
    val container = appContainer()
    val push by container.appPush.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && activity != null) container.appPush.enable(activity)
    }
    fun turnOn() {
        if (activity == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            container.appPush.enable(activity)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (push.enabled) appString(R.string.settings_push_on) else appString(R.string.settings_push_off), style = MaterialTheme.typography.titleMedium)
            Text(
                if (push.enabled) appString(R.string.settings_push_on_hint)
                else appString(R.string.settings_push_off_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            push.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                push.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                push.enabled -> TextButton(onClick = onTurnOff) { Text(stringResource(R.string.settings_turn_off)) }
                else -> OutlinedButton(onClick = ::turnOn) { Text(stringResource(R.string.settings_turn_on)) }
            }
        }
    }
}

/** The notification widget (on/off) and the home screen widget, and which sensors they cycle through. */
@Composable
private fun WidgetCard() {
    val container = appContainer()
    val context = LocalContext.current
    val push by container.appPush.state.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(EnvironmentWidget.isEnabled(context)) }
    var selected by remember { mutableStateOf(EnvironmentWidget.selectedSensors(context)) }
    var sensors by remember { mutableStateOf<List<Sensor>?>(null) }
    LaunchedEffect(Unit) { sensors = apiCall { container.apiProvider.api().sensors() }.getOrNull() }
    val homePlaced = HomeWidgetProvider.isPlaced(context)

    fun apply(newEnabled: Boolean, newSelected: Set<Long>) {
        val wasEnabled = enabled
        enabled = newEnabled
        selected = newSelected
        EnvironmentWidget.configure(context, newEnabled, newSelected)
        if (newEnabled != wasEnabled) container.appPush.updateRegistration() // server starts/stops the pushes
        container.appScope.launch { EnvironmentWidget.refresh(context, container.apiProvider) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_notification_widget), style = MaterialTheme.typography.titleMedium)
                    Text(
                        appString(R.string.settings_notification_widget_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        // Switching on with nothing chosen shows all sensors.
                        apply(on, if (on && selected.isEmpty()) sensors.orEmpty().map { it.id }.toSet() else selected)
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.Divider)
            Text(stringResource(R.string.settings_home_widget), style = MaterialTheme.typography.titleMedium)
            Text(
                if (homePlaced) {
                    appString(R.string.settings_home_widget_placed)
                } else {
                    appString(R.string.settings_home_widget_hint)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if ((enabled || homePlaced) && !push.enabled) {
                Text(stringResource(R.string.settings_widgets_need_push), color = Palette.Warning)
            }
            if (enabled || homePlaced) {
                Text(stringResource(R.string.settings_sensors), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                sensors?.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { apply(enabled, if (s.id in selected) selected - s.id else selected + s.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s.id in selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.name)
                    }
                }
                val cycled = if (selected.isEmpty()) sensors.orEmpty().size else selected.size
                if (cycled > 1) Text(stringResource(R.string.settings_widget_switch), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}



/**
 * App language: "System default" plus every language the APK has (res/values-<code>/, listed by
 * the generated locale config). Android 13+ switches per app and recreates the activity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePicker(onChosen: (String) -> Unit) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Text(stringResource(R.string.language_old_android), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val manager = context.getSystemService(LocaleManager::class.java)
    val supported = remember {
        val list = LocaleConfig(context).supportedLocales
        (0 until (list?.size() ?: 0)).map { list!![it] }.sortedBy { it.getDisplayName(it).lowercase() }
    }
    val current = manager.applicationLocales.takeIf { !it.isEmpty }?.get(0)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == null,
            onClick = {
                onChosen(manager.systemLocales[0].language)
                manager.applicationLocales = LocaleList.getEmptyLocaleList()
            },
            label = { Text(stringResource(R.string.language_system)) },
        )
        supported.forEach { locale ->
            FilterChip(
                selected = current?.language == locale.language,
                onClick = {
                    onChosen(locale.language)
                    manager.applicationLocales = LocaleList(locale)
                },
                // Each language in its own name ("Dansk", "English"), capitalised.
                label = { Text(locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }) },
            )
        }
    }
    Text(stringResource(R.string.language_notifications), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
