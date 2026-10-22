package com.growbuddy.app.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.growbuddy.app.AlertCounts
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.EnvironmentCurrent
import com.growbuddy.app.data.remote.Plant
import com.growbuddy.app.data.remote.SensorEnvironment
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.BatteryIndicator
import com.growbuddy.app.ui.common.Dial
import com.growbuddy.app.ui.common.DialScale
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.GbCard
import com.growbuddy.app.ui.common.HALF_DIAL_SWEEP
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.RssiIcon
import com.growbuddy.app.ui.common.StatusDot
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appPlural
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatAge
import com.growbuddy.app.ui.common.formatAgo
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.common.levelColor
import com.growbuddy.app.ui.common.polling
import com.growbuddy.app.ui.common.refreshTrigger
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

data class DashboardState(
    val data: EnvironmentCurrent? = null,
    val error: String? = null,
    val loaded: Boolean = false,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val trigger = refreshTrigger()

    val state = polling(10_000, trigger) {
        val api = container.apiProvider.api()
        apiCall { api.environment() }
    }
        .runningFold(DashboardState()) { prev, env ->
            env.fold(
                onSuccess = { DashboardState(it, null, true) },
                onFailure = { prev.copy(error = it.message, loaded = true) }, // keep last data visible
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun refresh() {
        trigger.tryEmit(Unit)
        container.refreshAlerts()
    }

    val streamUrl: String? get() = container.apiProvider.url("camera/stream")

    fun photoUrl(photoId: String): String? = container.apiProvider.url("photos/$photoId?thumb=1")
}

private val WideLayout = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenPlant: (String) -> Unit,
    onOpenHistory: (Long) -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTimelapse: () -> Unit,
    onOpenLiveView: () -> Unit,
) {
    val container = appContainer()
    val vm = viewModel { DashboardViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var cameraLive by remember { mutableStateOf(true) }
    val offline by container.apiProvider.offline.collectAsStateWithLifecycle()
    LaunchedEffect(state) { refreshing = false }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val alerts by container.alertCounts.collectAsStateWithLifecycle()
            PageHeader(stringResource(R.string.dash_title), icon = R.drawable.ic_nav_home) {
                if (alerts.total > 0) AlertsButton(alerts, onOpenAlerts)
            }
            val data = state.data
            val connection = when {
                offline || (state.error != null && data != null) -> Connection.Off // cached data only
                data != null && !cameraLive -> Connection.Limited
                data != null -> Connection.On
                else -> Connection.Off
            }
            val camera: @Composable (Modifier) -> Unit = { m ->
                GrowcamCard(vm.streamUrl, container.mjpeg, connection, onLive = { cameraLive = it }, onTimelapse = onOpenTimelapse, onFullScreen = onOpenLiveView, modifier = m)
            }
            when {
                !state.loaded -> LoadingBox(Modifier.height(320.dp))
                data == null -> {
                    camera(Modifier.padding(horizontal = 16.dp))
                    ErrorBox(state.error ?: appString(R.string.dash_no_data), onRetry = vm::refresh)
                }
                else -> BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) {
                    if (maxWidth >= WideLayout) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            camera(Modifier.weight(1f))
                            Column(Modifier.weight(1f)) { SensorSection(data, offline, vm::photoUrl, onOpenPlant, onOpenHistory) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            camera(Modifier)
                            SensorSection(data, offline, vm::photoUrl, onOpenPlant, onOpenHistory)
                        }
                    }
                }
            }
        }
    }
}

private enum class Connection(val icon: Int, val tint: androidx.compose.ui.graphics.Color, private val labelRes: Int) {
    On(R.drawable.ic_connection_on, Palette.ConnectionOk, R.string.dash_connected),
    Limited(R.drawable.ic_connection_limited, Palette.Warning, R.string.dash_camera_unavailable),
    Off(R.drawable.ic_connection_off, Palette.Danger, R.string.dash_unreachable),
    ;

    val label: String get() = appString(labelRes)
}

@Composable
private fun GrowcamCard(
    streamUrl: String?,
    mjpeg: com.growbuddy.app.data.remote.MjpegStream,
    connection: Connection,
    onLive: (Boolean) -> Unit,
    onTimelapse: () -> Unit,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GbCard(
        modifier.fillMaxWidth(),
        title = stringResource(R.string.dash_growcam),
        trailing = { androidx.compose.material3.TextButton(onClick = onTimelapse) { Text(stringResource(R.string.dash_timelapse), style = MaterialTheme.typography.titleMedium) } },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .border(4.dp, Palette.CardRaised, RoundedCornerShape(6.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(androidx.compose.ui.graphics.Color.Black)
                .clickable(onClickLabel = appString(R.string.dash_full_screen), onClick = onFullScreen),
        ) {
            LiveCamera(streamUrl, mjpeg, onLive, Modifier.fillMaxSize())
            Image(
                painterResource(connection.icon),
                contentDescription = connection.label,
                colorFilter = ColorFilter.tint(connection.tint),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp),
            )
        }
    }
}

@Composable
private fun SensorSection(
    data: EnvironmentCurrent,
    offline: Boolean,
    photoUrl: (String) -> String?,
    onOpenPlant: (String) -> Unit,
    onOpenHistory: (Long) -> Unit,
) {
    if (data.sensors.isEmpty()) {
        GbCard(Modifier.fillMaxWidth()) {
            EmptyState(R.drawable.ic_nav_settings, appString(R.string.dash_no_sensors))
        }
        return
    }
    var selectedId by rememberSaveable { mutableLongStateOf(data.sensors.first().sensor.id) }
    val env = data.sensors.firstOrNull { it.sensor.id == selectedId } ?: data.sensors.first()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.sensors.size > 1) SensorTabs(data.sensors, env.sensor.id, offline) { selectedId = it }
        Box(Modifier.clickable { onOpenHistory(env.sensor.id) }) { Gauges(env) } // tap the dials for history
        RangeNote(env, onHistory = { onOpenHistory(env.sensor.id) })
        ActivePlants(env.plants + if (data.sensors.size == 1) data.unlocatedPlants else emptyList(), photoUrl, onOpenPlant)
        if (data.sensors.size > 1 && data.unlocatedPlants.isNotEmpty()) {
            ActivePlants(data.unlocatedPlants, photoUrl, onOpenPlant, title = stringResource(R.string.dash_unlocated))
        }
    }
}

/** 0.1.6 tab style (Catalog / Active), one tab per sensor. */
@Composable
private fun SensorTabs(sensors: List<SensorEnvironment>, selected: Long, offline: Boolean, onSelect: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.TabBar)) {
        sensors.forEach { s ->
            val isSelected = s.sensor.id == selected
            Row(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(s.sensor.id) }
                    .background(if (isSelected) Palette.NavCenter else Palette.TabBar)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Offline the app only has saved data, so the sensor's state is unknown (grey).
                StatusDot(if (offline) Palette.TextSecondary else if (s.online) Palette.Green else Palette.Danger)
                Spacer(Modifier.width(8.dp))
                Text(
                    s.sensor.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                    color = if (isSelected) Palette.TextPrimary else Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Temperature + humidity dials with the VPD disc overlapping, flanked by RSSI and battery (0.1.6). */
@Composable
private fun Gauges(env: SensorEnvironment) {
    val r = env.reading
    val ranges = env.ranges
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DialCard(stringResource(R.string.metric_temperature), Modifier.weight(1f)) {
                Dial(
                    value = r?.temperatureC,
                    scale = DialScale.Temperature.including(ranges.temperature.warnMin, ranges.temperature.warnMax, r?.temperatureC),
                    bandMin = ranges.temperature.warnMin,
                    bandMax = ranges.temperature.warnMax,
                    unit = "°C",
                    decimals = 1,
                    valueColor = levelColor(env.levels.temperature ?: "normal"),
                )
            }
            DialCard(stringResource(R.string.metric_humidity), Modifier.weight(1f)) {
                Dial(
                    value = r?.humidityPct,
                    scale = DialScale.Humidity.including(ranges.humidity.warnMin, ranges.humidity.warnMax, r?.humidityPct),
                    bandMin = ranges.humidity.warnMin,
                    bandMax = ranges.humidity.warnMax,
                    unit = "%RH",
                    decimals = 0,
                    valueColor = levelColor(env.levels.humidity ?: "normal"),
                )
            }
        }
        Box(Modifier.fillMaxWidth().offset(y = (-26).dp).height(170.dp)) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .width(340.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Pill)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RssiIcon(env.sensor.rssi, env.online, Modifier.size(38.dp))
                Spacer(Modifier.weight(1f))
                BatteryIndicator(env.sensor.batteryPct)
            }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(170.dp)
                    .clip(CircleShape)
                    .background(Palette.Disc)
                    .border(5.dp, Palette.Card, CircleShape)
                    .padding(start = 6.dp, end = 6.dp, top = 11.dp), // centres dial + label vertically in the disc
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Dial(
                    value = r?.vpdKpa,
                    scale = DialScale.Vpd.including(ranges.vpd.warnMax, r?.vpdKpa),
                    bandMin = ranges.vpd.warnMin,
                    bandMax = ranges.vpd.warnMax,
                    unit = "kPa",
                    decimals = 2,
                    valueColor = levelColor(env.levels.vpd ?: "normal"),
                    labelDecimals = 0,
                    valueAlignment = Alignment.BottomCenter,
                    showBandLabels = false,
                    // 0.1.6 VPD dial: half circle, white end labels inside the arc.
                    sweep = HALF_DIAL_SWEEP,
                    labelsInside = true,
                    endLabelColor = Palette.TextPrimary,
                    radiusFactor = 0.43f, // fill the disc
                )
                Text("VPD", style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp), color = Palette.TextPrimary, modifier = Modifier.offset(y = (-4).dp))
            }
        }
    }
}

@Composable
private fun DialCard(title: String, modifier: Modifier = Modifier, dial: @Composable () -> Unit) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Card)) {
        // Shrink the title on narrow cards (two-column layout) instead of wrapping it.
        val titleSize = minOf(28f, maxWidth.value / 7f).sp
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 34.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = titleSize), color = Palette.TextPrimary, maxLines = 1)
            dial()
        }
    }
}

/** Where the dial bands come from (auto: oldest plant's phase). */
@Composable
private fun RangeNote(env: SensorEnvironment, onHistory: () -> Unit) {
    val source = env.rangeSource
    val text = when {
        env.sensor.rangeMode == "manual" -> appString(R.string.dash_ranges_manual)
        source != null -> appString(R.string.dash_ranges_from, formatPhase(source.phase), source.nickname)
        else -> appString(R.string.dash_no_ranges)
    }
    val updated = env.reading?.let { if (env.online) appString(R.string.dash_updated, formatAgo(it.ts)) else appString(R.string.dash_offline, formatAgo(env.sensor.lastSeen)) }
    Row(Modifier.offset(y = (-26).dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(text, updated).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (env.online) Palette.TextSecondary else Palette.Danger,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onHistory) { Text(stringResource(R.string.dash_history), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ActivePlants(plants: List<Plant>, photoUrl: (String) -> String?, onOpen: (String) -> Unit, title: String? = null) {
    GbCard(
        Modifier.fillMaxWidth().offset(y = (-20).dp),
        title = title ?: stringResource(R.string.dash_active_plants_title),
        trailing = { Image(painterResource(R.drawable.ic_nav_plants), null, Modifier.size(36.dp), colorFilter = ColorFilter.tint(Palette.TextPrimary)) },
    ) {
        if (plants.isEmpty()) {
            Text(stringResource(R.string.dash_no_plants_here), color = Palette.TextSecondary)
        }
        plants.forEach { plant ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.CardRaised).clickable { onOpen(plant.id) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumb = plant.thumbnailPhotoId?.let(photoUrl)
                if (thumb != null) {
                    AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.TextPrimary))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            append(plant.nickname)
                            plant.strain?.let { withStyle(SpanStyle(color = Palette.TextSecondary)) { append("  (${it.name})") } }
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatPhase(plant.phase) + (formatAge(plant.age, plant.phase)?.let { " - ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                        color = Palette.TextSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Header button: warning icon (error icon if any alert is critical) with the alert count; opens the alerts screen. */
@Composable
private fun AlertsButton(alerts: AlertCounts, onClick: () -> Unit) {
    val critical = alerts.critical > 0
    val color = if (critical) Palette.Danger else Palette.Warning
    val label = appPlural(R.plurals.dash_alerts, alerts.total, alerts.total)
    // Only the ripple is round; clipping the whole box would cut off the count badge.
    // The box keeps the 48 dp touch target; the icon itself is smaller.
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().clip(CircleShape).clickable(onClickLabel = label, onClick = onClick))
        Icon(if (critical) Icons.Filled.Error else Icons.Filled.Warning, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
        Box(
            Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp).size(16.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (alerts.total > 99) "99+" else alerts.total.toString(),
                color = Palette.Background,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp),
            )
        }
    }
}
