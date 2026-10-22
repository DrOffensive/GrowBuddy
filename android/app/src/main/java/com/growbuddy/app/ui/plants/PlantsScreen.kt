package com.growbuddy.app.ui.plants

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.data.remote.PlantCreate
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.Strain
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.sync.CreatePlantItem
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAge
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.common.polling
import com.growbuddy.app.ui.common.refreshTrigger
import com.growbuddy.app.ui.theme.Palette
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlantsViewModel(private val container: AppContainer) : ViewModel() {
    private val trigger = refreshTrigger()
    private val api get() = container.apiProvider.api()

    val plants = polling(15_000, trigger) { apiCall { api.plants() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var sensors by mutableStateOf<List<Sensor>>(emptyList())
        private set
    var strainResults by mutableStateOf<List<Strain>>(emptyList())
        private set
    var createError by mutableStateOf<String?>(null)
        private set

    fun loadSensors() {
        viewModelScope.launch { apiCall { api.sensors() }.onSuccess { sensors = it } }
    }

    suspend fun searchStrains(query: String) {
        strainResults = if (query.isBlank()) {
            emptyList()
        } else {
            apiCall { api.strains(query = query.trim(), limit = 8).items }.getOrDefault(emptyList())
        }
    }

    fun create(body: PlantCreate, onDone: () -> Unit) {
        createError = null
        viewModelScope.launch {
            apiCall { api.createPlant(body) }
                .onSuccess {
                    trigger.tryEmit(Unit)
                    onDone()
                }
                .onFailure { e ->
                    if (e is ApiException && e.code == "network") {
                        container.queue(CreatePlantItem(body)) // created when the server is reachable
                        onDone()
                    } else {
                        createError = e.message
                    }
                }
        }
    }

    fun photoUrl(photoId: String): String? = container.apiProvider.url("photos/$photoId?thumb=1")
}

@Composable
fun PlantsScreen(onOpen: (String) -> Unit) {
    val container = appContainer()
    val vm = viewModel { PlantsViewModel(container) }
    val plants by vm.plants.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.common_plants), icon = R.drawable.ic_nav_plants)
        Box(Modifier.weight(1f)) {
            val result = plants
            when {
                result == null -> LoadingBox()
                result.isFailure -> ErrorBox(result.exceptionOrNull()?.message ?: stringResource(R.string.events_error))
                result.getOrThrow().isEmpty() -> EmptyState(R.drawable.ic_nav_plants, appString(R.string.plants_empty))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(result.getOrThrow(), key = { it.id }) { PlantCard(it, vm::photoUrl, onClick = { onOpen(it.id) }) }
                }
            }
        }
        Button(
            onClick = { creating = true },
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Button, contentColor = Palette.TextPrimary),
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.plants_create), style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp))
        }
    }

    if (creating) CreatePlantSheet(vm, onDismiss = { creating = false })
}

/** 0.1.6 plant card: green border, Name/Strain/Age/Phase, photo or camera placeholder. */
@Composable
private fun PlantCard(plant: Plant, photoUrl: (String) -> String?, onClick: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.CardRaised)
            .border(2.dp, Palette.CardBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        // Narrow cards (folded phone) get a smaller photo and text so the fields fit, as in 0.1.6.
        val narrow = maxWidth < 400.dp
        val photo = if (narrow) 104.dp else 130.dp
        val textSize = if (narrow) 17.sp else 19.sp
        Row(Modifier.fillMaxWidth().heightIn(min = photo + 20.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 6.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field(stringResource(R.string.editor_name), plant.nickname, textSize)
                // Long strain names may wrap on narrow cards rather than being cut off.
                Field(stringResource(R.string.plant_strain), plant.strain?.name ?: "-", textSize, maxLines = if (narrow) 2 else 1)
                Field(stringResource(R.string.plant_age), formatAge(plant.age, plant.phase) ?: "-", textSize)
                Field(stringResource(R.string.plant_phase), formatPhase(plant.phase), textSize)
            }
            val thumb = plant.thumbnailPhotoId?.let(photoUrl)
            Box(
                Modifier.size(photo).clip(RoundedCornerShape(4.dp)).background(Palette.PhotoPlaceholder),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    AsyncImage(thumb, plant.nickname, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Image(painterResource(R.drawable.ic_camera), appString(R.string.plants_no_photo), Modifier.size(photo * 0.4f), colorFilter = ColorFilter.tint(Palette.TextPrimary))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, size: androidx.compose.ui.unit.TextUnit, maxLines: Int = 1) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label:  ") }
            append(value)
        },
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = size),
        color = Palette.TextPrimary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreatePlantSheet(vm: PlantsViewModel, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf("") }
    var strainQuery by remember { mutableStateOf("") }
    var strain by remember { mutableStateOf<Strain?>(null) }
    var autoflower by remember { mutableStateOf(false) }
    var sensorId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { vm.loadSensors() }
    LaunchedEffect(strainQuery) {
        delay(250) // debounce typing
        if (strain?.name != strainQuery) vm.searchStrains(strainQuery)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.plants_create), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.editor_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                strainQuery,
                {
                    strainQuery = it
                    if (strain?.name != it) strain = null
                },
                label = { Text(stringResource(R.string.plants_strain_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (strain == null) {
                vm.strainResults.forEach { s ->
                    Text(
                        listOfNotNull(s.name, s.vendor, s.category).joinToString("  ·  "),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                strain = s
                                strainQuery = s.name
                                autoflower = s.category.equals("Autoflower", ignoreCase = true)
                            }
                            .padding(vertical = 6.dp),
                        color = Palette.TextSecondary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cat_Autoflower), modifier = Modifier.weight(1f))
                Switch(autoflower, { autoflower = it })
            }
            Text(stringResource(R.string.plant_location), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sensorId == null, onClick = { sensorId = null }, label = { Text(stringResource(R.string.common_none)) })
                vm.sensors.forEach { s ->
                    FilterChip(selected = sensorId == s.id, onClick = { sensorId = s.id }, label = { Text(s.name) })
                }
            }
            vm.createError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = nickname.isNotBlank(),
                    onClick = {
                        vm.create(
                            PlantCreate(UUID.randomUUID().toString(), nickname.trim(), strain?.id, autoflower, sensorId),
                            onDone = onDismiss,
                        )
                    },
                ) { Text(stringResource(R.string.plants_create_button)) }
            }
        }
    }
}
