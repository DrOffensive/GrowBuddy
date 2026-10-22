package com.growbuddy.app.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.LogEntry
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
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LogDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var entry by mutableStateOf<LogEntry?>(null)
        private set
    var plantNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var comment by mutableStateOf("")

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            apiCall { api.log(id) }.onSuccess { entry = it; comment = it.comment.orEmpty(); error = null }.onFailure { error = it.message }
            apiCall { api.plants(includeArchived = true) }.onSuccess { list -> plantNames = list.associate { it.id to it.nickname } }
        }
    }

    private fun patch(changes: JsonObject) {
        viewModelScope.launch {
            apiCall { api.patchLog(id, changes) }.onSuccess { entry = it; error = null }.onFailure { error = it.message }
        }
    }

    fun saveComment() = patch(buildJsonObject { put("comment", comment.trim().ifEmpty { null }) })

    fun setTime(ts: Long) = patch(buildJsonObject { put("ts", ts) })

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { apiCall { api.deleteLog(id).orThrow() }.onSuccess { onDone() }.onFailure { error = it.message } }
    }

    fun photoUrl(photoId: String, thumb: Boolean): String? = container.apiProvider.url("photos/$photoId" + if (thumb) "?thumb=1" else "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogDetailScreen(id: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = "log-$id") { LogDetailViewModel(container, id) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    val entry = vm.entry

    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader(entry?.let { logTypeLabel(it.type) } ?: stringResource(R.string.logd_title), onBack = onBack)
        when {
            entry == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
            entry == null -> LoadingBox()
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeDot(entry.type, 18)
                    Spacer(Modifier.width(10.dp))
                    Text(formatDateTime(entry.ts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.logd_change)) }
                }
                Text(entry.plantIds.mapNotNull { vm.plantNames[it] }.joinToString(", "), color = Palette.TextSecondary)
                LogData(entry)
                if (entry.sensorSnapshot.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.logd_conditions))
                    entry.sensorSnapshot.forEach { s ->
                        Text(
                            "${s.sensorName}: ${formatNumber(s.temperatureC, 1)} ${MetricKind.Temperature.unit} · " +
                                "${formatNumber(s.humidityPct, 0)} %RH · ${formatNumber(s.vpdKpa, 2)} kPa",
                            style = NumericStyle,
                        )
                    }
                }
                if (entry.photoIds.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.logd_photos))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.photoIds.forEach { p ->
                            AsyncImage(
                                vm.photoUrl(p, thumb = true), null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)).clickable { viewing = p },
                            )
                        }
                    }
                }
                SectionHeader(stringResource(R.string.logd_comment))
                OutlinedTextField(vm.comment, { vm.comment = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (vm.comment != entry.comment.orEmpty()) Button(onClick = vm::saveComment) { Text(stringResource(R.string.logd_save_comment)) }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.size(12.dp))
                OutlinedButton(onClick = {
                    confirm = Confirmation(
                        appString(R.string.logd_delete_title),
                        appString(R.string.logd_delete_text),
                        appString(R.string.common_delete),
                    ) { vm.delete(onBack) }
                }) { Text(stringResource(R.string.logd_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    viewing?.let { PhotoViewer(vm.photoUrl(it, thumb = false)) { viewing = null } }
    if (picking && entry != null) {
        DateTimePicker(entry.ts, onDismiss = { picking = false }) { ts ->
            picking = false
            if (ts != entry.ts) {
                confirm = Confirmation(
                    appString(R.string.logd_time_title),
                    appString(R.string.logd_time_text, formatDateTime(entry.ts), formatDateTime(ts)),
                    appString(R.string.logd_change),
                ) { vm.setTime(ts) }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun LogData(entry: LogEntry) {
    val d = entry.data
    fun text(key: String) = (d[key] as? JsonPrimitive)?.content
    when (LogType.of(entry.type)?.data) {
        DataKind.Feeding -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.CardRaised).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (d["nutrients"] as? JsonArray)?.forEach { n ->
                val o = n as? JsonObject ?: return@forEach
                fun f(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                Row {
                    Text("${f("brand")} ${f("product")}", modifier = Modifier.weight(1f))
                    Text("${f("amount")} ${f("unit")}", style = NumericStyle)
                }
            }
            listOfNotNull(text("ph")?.let { "pH $it" }, text("ec")?.let { "EC $it" }, text("volume_l")?.let { "$it L" })
                .joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = NumericStyle, color = Palette.TextSecondary) }
        }
        DataKind.None, null -> Unit
        else -> summary(entry)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    }
}
