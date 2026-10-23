package com.growbuddy.app.ui.strains

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.Strain
import com.growbuddy.app.data.remote.StrainBody
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.orThrow
import com.growbuddy.app.ui.common.ConfirmDialog
import com.growbuddy.app.ui.common.Confirmation
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StrainsViewModel(private val container: AppContainer) : ViewModel() {
    var strains by mutableStateOf<List<Strain>?>(null)
        private set
    var activeIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var facets by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var editError by mutableStateOf<String?>(null) // shown in the add/edit sheet
    var saving by mutableStateOf(false)
        private set
    var confirm by mutableStateOf<Confirmation?>(null)

    init {
        load()
    }

    /** Creates a custom strain ([existing] null) or replaces the fields of [existing]. */
    fun save(body: StrainBody, existing: Strain?, onDone: (Strain) -> Unit) {
        saving = true
        editError = null
        viewModelScope.launch {
            val api = container.apiProvider.api()
            apiCall {
                if (existing == null) {
                    api.createStrain(body)
                } else {
                    api.patchStrain(
                        existing.id,
                        buildJsonObject {
                            put("name", body.name)
                            put("vendor", body.vendor)
                            put("category", body.category)
                            put("thc", body.thc)
                            put("cbd", body.cbd)
                            put("flowering_days", body.floweringDays)
                            put("yield_indoor", body.yieldIndoor)
                            put("height", body.height)
                            put("description", body.description)
                        },
                    )
                }
            }.onSuccess { saved ->
                strains = (strains.orEmpty().filter { it.id != saved.id } + saved).sortedBy { it.name.lowercase() }
                apiCall { api.strainFacets() }.onSuccess { facets = it }
                onDone(saved)
            }.onFailure { editError = it.message }
            saving = false
        }
    }

    fun delete(strain: Strain, onDone: () -> Unit) {
        confirm = Confirmation(appString(R.string.strains_delete_title, strain.name), appString(R.string.strains_delete_text), appString(R.string.common_delete)) {
            viewModelScope.launch {
                apiCall { container.apiProvider.api().deleteStrain(strain.id).orThrow() }
                    .onSuccess {
                        strains = strains?.filter { it.id != strain.id }
                        onDone()
                    }
                    .onFailure { error = it.message }
            }
        }
    }

    /** The catalog is small (~400): load once and filter locally for instant search. */
    fun load() {
        viewModelScope.launch {
            val api = container.apiProvider.api()
            apiCall { api.strains(limit = 1000).items }.onSuccess { strains = it; error = null }.onFailure { error = it.message }
            apiCall { api.strainFacets() }.onSuccess { facets = it }
            apiCall { api.plants() }.onSuccess { list -> activeIds = list.mapNotNull { it.strainId }.toSet() }
        }
    }
}

/** Catalog categories are stored in English; shown in the app language. */
private fun categoryLabel(category: String): String = when (category) {
    "Feminized" -> appString(R.string.cat_Feminized)
    "Autoflower" -> appString(R.string.cat_Autoflower)
    "Regular" -> appString(R.string.cat_Regular)
    else -> category
}

/** Vendor logos (white silhouettes); matching ignores case and punctuation ("Barney’s Farm" / "Barneys Farm"). */
private fun brandIcon(vendor: String?): Int? {
    val key = vendor?.lowercase()?.filter { it.isLetter() } ?: return null
    return when {
        "barneysfarm" in key -> R.drawable.brand_barneys_farm
        "fastbuds" in key -> R.drawable.brand_fastbuds
        "rqs" in key || "royalqueen" in key -> R.drawable.brand_royal_queen_seeds
        "anesia" in key -> R.drawable.brand_anesia_seeds
        "ripperseeds" in key -> R.drawable.brand_ripper_seeds
        "purplecity" in key -> R.drawable.brand_purple_city_genetics
        "thseeds" in key -> R.drawable.brand_th_seeds
        "kannabia" in key -> R.drawable.brand_kannabia
        "dnagenetics" in key -> R.drawable.brand_dna_genetics
        else -> null
    }
}

/**
 * Initials for a vendor without a logo: short all-caps words are kept whole, other words give their
 * first letter ("Green House Seeds" → GHS, "CPH Seeds" → CPH, "T.H. Seeds" → THS). At most three letters.
 */
internal fun vendorMonogram(vendor: String?): String? {
    val words = vendor.orEmpty().split(' ', '-').map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }
    val letters = words.joinToString("") { w -> if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.take(1).uppercase() }
    return letters.take(3).ifEmpty { null }
}

@Composable
fun StrainsScreen() {
    val container = appContainer()
    val vm = viewModel { StrainsViewModel(container) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val categories = remember { mutableStateListOf<String>() }
    val vendors = remember { mutableStateListOf<String>() }
    var filtering by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Strain?>(null) }
    var editing by remember { mutableStateOf<Strain?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.strains_title), icon = R.drawable.ic_nav_strains) {
            IconButton(onClick = { vm.editError = null; adding = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.strains_add), tint = Palette.TextPrimary) }
        }
        // Active / Catalog tabs (Active first and the default).
        Row(Modifier.fillMaxWidth().background(Palette.TabBar)) {
            listOf(stringResource(R.string.strains_active), stringResource(R.string.strains_catalog)).forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).clickable { tab = i }.background(if (tab == i) Palette.NavCenter else Palette.TabBar).padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.displaySmall.copy(fontSize = 32.sp), color = if (tab == i) Palette.TextPrimary else Palette.TextSecondary)
                }
            }
        }
        // Filter + search row.
        Row(Modifier.fillMaxWidth().background(Palette.TabBar).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { filtering = true }) {
                Image(
                    painterResource(R.drawable.ic_filter),
                    appString(R.string.strains_filter),
                    Modifier.size(34.dp),
                    colorFilter = ColorFilter.tint(if (categories.isEmpty() && vendors.isEmpty()) Palette.Green else Palette.Warning),
                )
            }
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary, fontSize = 22.sp),
                cursorBrush = SolidColor(Palette.TextPrimary),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(2.dp)).background(Palette.SearchField).padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.strains_search_hint), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp, fontStyle = FontStyle.Italic), color = Palette.TextSecondary)
                    }
                    inner()
                },
            )
        }
        val all = vm.strains
        when {
            all == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
            all == null -> LoadingBox()
            else -> {
                val q = query.trim().lowercase()
                val shown = all.filter { s ->
                    (tab == 1 || s.id in vm.activeIds) &&
                        (q.isEmpty() || q in s.name.lowercase() || q in (s.vendor ?: "").lowercase()) &&
                        (categories.isEmpty() || s.category in categories) &&
                        (vendors.isEmpty() || s.vendor in vendors)
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                if (shown.isEmpty()) {
                    EmptyState(R.drawable.ic_nav_strains, if (tab == 0) stringResource(R.string.strains_none_active) else stringResource(R.string.strains_no_match))
                } else {
                    LazyColumn(Modifier.fillMaxSize().background(Palette.CardRaised)) {
                        items(shown, key = { it.id }) { s -> StrainRow(s) { selected = s } }
                    }
                }
            }
        }
    }

    if (filtering) FilterSheet(vm.facets, categories, vendors, onDismiss = { filtering = false })
    selected?.let { strain ->
        StrainSheet(
            strain,
            onDismiss = { selected = null },
            onEdit = { vm.editError = null; editing = strain; selected = null },
            onDelete = { vm.delete(strain) { selected = null } },
        )
    }
    if (adding || editing != null) {
        StrainEditSheet(
            vm,
            existing = editing,
            categories = vm.facets["category"].orEmpty(),
            onDismiss = { adding = false; editing = null },
            onSaved = { saved -> adding = false; editing = null; tab = 1; selected = saved },
        )
    }
    ConfirmDialog(vm.confirm) { vm.confirm = null }
}

@Composable
private fun StrainRow(strain: Strain, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = brandIcon(strain.vendor)
        if (icon != null) {
            Image(painterResource(icon), strain.vendor, Modifier.size(40.dp), colorFilter = ColorFilter.tint(Palette.TextPrimary))
        } else {
            // No logo: the vendor's initials cut out of the white disc.
            Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.TextPrimary), contentAlignment = Alignment.Center) {
                vendorMonogram(strain.vendor)?.let { initials ->
                    Text(
                        initials, color = Palette.CardRaised, maxLines = 1,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = if (initials.length > 2) 14.sp else 18.sp),
                    )
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Text(
            strain.name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 24.sp), color = Palette.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (strain.custom) {
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.strains_mine), style = MaterialTheme.typography.labelMedium, color = Palette.Green)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    facets: Map<String, List<String>>,
    categories: MutableList<String>,
    vendors: MutableList<String>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.strains_filter), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.strains_category), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                facets["category"].orEmpty().forEach { c -> Toggle(categoryLabel(c), c in categories) { if (c in categories) categories.remove(c) else categories.add(c) } }
            }
            Text(stringResource(R.string.strains_vendor), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                facets["vendor"].orEmpty().forEach { v -> Toggle(v, v in vendors) { if (v in vendors) vendors.remove(v) else vendors.add(v) } }
            }
            TextButton(onClick = { categories.clear(); vendors.clear() }) { Text(stringResource(R.string.strains_clear_filters)) }
        }
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StrainSheet(strain: Strain, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            strain.imageUrl?.let {
                AsyncImage(it, strain.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).height(200.dp))
            }
            Text(strain.name, style = MaterialTheme.typography.headlineMedium)
            strain.vendor?.let { Text(it, color = Palette.TextSecondary) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    strain.category?.let(::categoryLabel),
                    strain.thc?.let { stringResource(R.string.strains_thc, it) },
                    strain.cbd?.let { stringResource(R.string.strains_cbd, it) },
                    strain.floweringDays?.let { stringResource(R.string.strains_flowering, it) },
                    strain.height?.let { stringResource(R.string.strains_height_value, it) },
                    strain.yieldIndoor?.let { stringResource(R.string.strains_yield_value, it) },
                ).forEach { AssistChip(onClick = {}, label = { Text(it) }) }
            }
            strain.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            strain.url?.let { url -> OutlinedButton(onClick = { uri.openUri(url) }) { Text(stringResource(R.string.strains_shop)) } }
            if (strain.custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}

/** Add a custom strain ([existing] null) or edit one. Only the name is required. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StrainEditSheet(
    vm: StrainsViewModel,
    existing: Strain?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSaved: (Strain) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var vendor by rememberSaveable { mutableStateOf(existing?.vendor.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(existing?.category) }
    var thc by rememberSaveable { mutableStateOf(existing?.thc.orEmpty()) }
    var cbd by rememberSaveable { mutableStateOf(existing?.cbd.orEmpty()) }
    var flowering by rememberSaveable { mutableStateOf(existing?.floweringDays.orEmpty()) }
    var height by rememberSaveable { mutableStateOf(existing?.height.orEmpty()) }
    var yieldIndoor by rememberSaveable { mutableStateOf(existing?.yieldIndoor.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    val changed = listOf(name, vendor, thc, cbd, flowering, height, yieldIndoor, description).any { it.isNotBlank() } || category != null
    val dismiss = {
        if (existing == null && changed) {
            vm.confirm = Confirmation(appString(R.string.strains_discard_title), appString(R.string.strains_discard_text), appString(R.string.common_discard), onDismiss)
        } else {
            onDismiss()
        }
    }
    // Swiping the sheet away would lose the input, so only an explicit Cancel/Save closes it.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !changed })
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (existing == null) stringResource(R.string.strains_add) else stringResource(R.string.strains_edit_title), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vendor, { vendor = it }, label = { Text(stringResource(R.string.strains_breeder)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.strains_category), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (categories + listOf("Feminized", "Autoflower", "Regular")).distinct().forEach { c ->
                    Toggle(categoryLabel(c), category == c) { category = if (category == c) null else c }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(thc, { thc = it }, label = { Text("THC") }, placeholder = { Text("20%") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(cbd, { cbd = it }, label = { Text("CBD") }, placeholder = { Text("<1%") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                flowering, { flowering = it }, label = { Text(stringResource(R.string.strains_flowering_days)) }, placeholder = { Text("60-65") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.strains_height)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(yieldIndoor, { yieldIndoor = it }, label = { Text(stringResource(R.string.strains_yield)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.editor_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            vm.editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = name.isNotBlank() && !vm.saving,
                    onClick = {
                        fun String.orNull() = trim().ifEmpty { null }
                        vm.save(
                            StrainBody(
                                name = name.trim(), vendor = vendor.orNull(), category = category, thc = thc.orNull(), cbd = cbd.orNull(),
                                floweringDays = flowering.orNull(), yieldIndoor = yieldIndoor.orNull(), height = height.orNull(),
                                description = description.orNull(),
                            ),
                            existing,
                            onSaved,
                        )
                    },
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}
