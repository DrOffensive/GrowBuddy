package com.growbuddy.app.ui.plants

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.LogCreate
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.Strain
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.SectionHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAge
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.journal.LogRow
import com.growbuddy.app.ui.journal.TypeDot
import com.growbuddy.app.ui.journal.logPager
import com.growbuddy.app.ui.journal.logTypeLabel
import com.growbuddy.app.ui.journal.rememberPhotoPicker
import com.growbuddy.app.ui.theme.Palette
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PlantDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var plant by mutableStateOf<Plant?>(null)
        private set
    var sensors by mutableStateOf<List<Sensor>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var uploading by mutableStateOf(false)
        private set
    var strainResults by mutableStateOf<List<Strain>>(emptyList())
        private set
    val logs = logPager { before -> api.plantLogs(id, limit = 50, before = before) }

    fun refresh() {
        viewModelScope.launch {
            apiCall { api.plant(id) }.onSuccess { plant = it; error = null }.onFailure { error = it.message }
            apiCall { api.sensors() }.onSuccess { sensors = it }
            logs.refresh()
        }
    }

    fun patch(changes: JsonObject) {
        viewModelScope.launch {
            apiCall { api.patchPlant(id, changes) }.onSuccess { plant = it; error = null }.onFailure { error = it.message }
        }
    }

    fun setPhoto(uri: Uri) {
        uploading = true
        viewModelScope.launch {
            container.photos.upload(uri, allowQueue = false) // setting a thumbnail needs the server
                .onSuccess { photo -> patch(buildJsonObject { put("thumbnail_photo_id", photo.id) }) }
                .onFailure { error = it.message }
            uploading = false
        }
    }

    suspend fun searchStrains(query: String) {
        strainResults = if (query.isBlank()) emptyList() else apiCall { api.strains(query = query.trim(), limit = 8).items }.getOrDefault(emptyList())
    }

    /** Terminated early is a milestone log (phase becomes TerminatedEarly). */
    fun terminate() {
        viewModelScope.launch {
            apiCall { api.createLog(LogCreate(UUID.randomUUID().toString(), "TerminatedEarly", listOf(id))) }
                .onSuccess { refresh() }.onFailure { error = it.message }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { apiCall { api.deletePlant(id).orThrow() }.onSuccess { onDone() }.onFailure { error = it.message } }
    }

    fun photoUrl(photoId: String, thumb: Boolean = true): String? = container.apiProvider.url("photos/$photoId" + if (thumb) "?thumb=1" else "")
}

@Composable
fun PlantDetailScreen(id: String, onBack: () -> Unit, onAddLog: (String) -> Unit, onOpenLog: (String) -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = "plant-$id") { PlantDetailViewModel(container, id) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() } }
    val plant = vm.plant

    Column(Modifier.fillMaxSize()) {
        PageHeader(plant?.nickname ?: stringResource(R.string.plant_title), onBack = onBack)
        when {
            plant == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::refresh)
            plant == null -> LoadingBox()
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                val info: @Composable () -> Unit = { PlantInfo(plant, vm, onAddLog, onBack) }
                val log: @Composable () -> Unit = { PlantLog(vm, plant, onOpenLog) }
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { info() }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { log() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        info()
                        log()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlantInfo(plant: Plant, vm: PlantDetailViewModel, onAddLog: (String) -> Unit, onBack: () -> Unit) {
    val picker = rememberPhotoPicker(onPicked = vm::setPhoto)
    var photoMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.PhotoPlaceholder)
                .border(2.dp, Palette.CardBorder, RoundedCornerShape(6.dp))
                .clickable { photoMenu = true },
            contentAlignment = Alignment.Center,
        ) {
            val thumb = plant.thumbnailPhotoId?.let { vm.photoUrl(it, thumb = false) }
            if (thumb != null) {
                AsyncImage(thumb, plant.nickname, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Image(painterResource(R.drawable.ic_camera), appString(R.string.plant_add_photo), Modifier.size(72.dp), colorFilter = ColorFilter.tint(Palette.TextPrimary))
            }
            if (vm.uploading) CircularProgressIndicator()
            DropdownMenu(photoMenu, onDismissRequest = { photoMenu = false }) {
                // A new photo replaces the current one, so ask first when there is one.
                fun replacing(pick: () -> Unit) {
                    photoMenu = false
                    if (plant.thumbnailPhotoId == null) pick()
                    else confirm = Confirmation(appString(R.string.plant_replace_title), appString(R.string.plant_replace_text, plant.nickname), appString(R.string.plant_replace), pick)
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.plant_take_photo)) }, onClick = { replacing(picker.takePhoto) })
                DropdownMenuItem(text = { Text(stringResource(R.string.plant_choose_gallery)) }, onClick = { replacing(picker.pickFromGallery) })
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.CardRaised).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InfoLine(stringResource(R.string.plant_strain), plant.strain?.let { listOfNotNull(it.name, it.vendor).joinToString(" · ") } ?: "-")
            InfoLine(stringResource(R.string.plant_type), if (plant.isAutoflower) stringResource(R.string.cat_Autoflower) else stringResource(R.string.plant_photoperiod))
            InfoLine(stringResource(R.string.plant_age), formatAge(plant.age, plant.phase) ?: "-")
            InfoLine(stringResource(R.string.plant_phase), formatPhase(plant.phase))
            if (plant.archived) Text(stringResource(R.string.plant_archived), color = Palette.Warning)
        }
        SectionHeader(stringResource(R.string.plant_location))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(plant.sensorId == null, { vm.patch(buildJsonObject { put("sensor_id", null as Long?) }) }, label = { Text(stringResource(R.string.common_none)) })
            vm.sensors.forEach { s -> FilterChip(plant.sensorId == s.id, { vm.patch(buildJsonObject { put("sensor_id", s.id) }) }, label = { Text(s.name) }) }
        }
        if (plant.milestones.isNotEmpty()) {
            SectionHeader(stringResource(R.string.plant_milestones))
            plant.milestones.forEach { (type, ts) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeDot(type)
                    Spacer(Modifier.width(10.dp))
                    Text(logTypeLabel(type), modifier = Modifier.weight(1f))
                    Text(formatDateTime(ts), color = Palette.TextSecondary)
                }
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onAddLog(plant.id) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Button, contentColor = Palette.TextPrimary),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.plant_add_log), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.common_edit)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, appString(R.string.plant_more)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (plant.archived) stringResource(R.string.plant_unarchive) else stringResource(R.string.plant_archive)) },
                        onClick = { menu = false; vm.patch(buildJsonObject { put("archived", !plant.archived) }) },
                    )
                    if (plant.phase != "TerminatedEarly") {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plant_terminate_menu)) }, onClick = {
                            menu = false
                            confirm = Confirmation(
                                appString(R.string.plant_terminate_title, plant.nickname),
                                appString(R.string.plant_terminate_text),
                                appString(R.string.plant_terminate),
                                vm::terminate,
                            )
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        confirm = Confirmation(
                            appString(R.string.plant_delete_title, plant.nickname),
                            appString(R.string.plant_delete_text),
                            appString(R.string.common_delete),
                        ) { vm.delete(onBack) }
                    })
                }
            }
        }
    }

    if (editing) EditPlantSheet(plant, vm, onDismiss = { editing = false })
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text("$label:", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp), modifier = Modifier.width(90.dp), color = Palette.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp))
    }
}

@Composable
private fun PlantLog(vm: PlantDetailViewModel, plant: Plant, onOpenLog: (String) -> Unit) {
    SectionHeader(stringResource(R.string.plant_grow_log))
    val items = vm.logs.items
    when {
        items == null -> LoadingBox(Modifier.size(80.dp))
        items.isEmpty() -> Text(stringResource(R.string.plant_no_entries), color = Palette.TextSecondary)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            items.forEach { e -> LogRow(e, mapOf(plant.id to plant.nickname), { vm.photoUrl(it) }, onClick = { onOpenLog(e.id) }) }
            if (vm.logs.loading) CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPlantSheet(plant: Plant, vm: PlantDetailViewModel, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf(plant.nickname) }
    var autoflower by remember { mutableStateOf(plant.isAutoflower) }
    var strainQuery by remember { mutableStateOf(plant.strain?.name.orEmpty()) }
    var strainId by remember { mutableStateOf(plant.strainId) }
    LaunchedEffect(strainQuery) {
        delay(250)
        if (strainQuery != plant.strain?.name) vm.searchStrains(strainQuery)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.plant_edit), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.editor_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                strainQuery,
                { strainQuery = it; if (it.isBlank()) strainId = null },
                label = { Text(stringResource(R.string.plant_strain)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            vm.strainResults.forEach { s ->
                Text(
                    listOfNotNull(s.name, s.vendor).joinToString("  ·  "),
                    color = if (s.id == strainId) Palette.NavSelected else Palette.TextSecondary,
                    modifier = Modifier.fillMaxWidth().clickable { strainId = s.id; strainQuery = s.name }.padding(vertical = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cat_Autoflower), modifier = Modifier.weight(1f))
                Switch(autoflower, { autoflower = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(enabled = nickname.isNotBlank(), onClick = {
                    vm.patch(buildJsonObject {
                        put("nickname", nickname.trim())
                        put("is_autoflower", autoflower)
                        put("strain_id", strainId)
                    })
                    onDismiss()
                }) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}
