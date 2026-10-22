package com.growbuddy.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.MetricRange
import com.growbuddy.app.data.remote.ReadingSeries
import com.growbuddy.app.data.remote.Sensor
import com.growbuddy.app.data.remote.SensorDetail
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.ChartMarker
import com.growbuddy.app.ui.common.ChartPoint
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.GbCard
import com.growbuddy.app.ui.common.MetricKind
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.TimeChart
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatNumber
import com.growbuddy.app.ui.common.formatPhase
import com.growbuddy.app.ui.journal.logTypeColor
import com.growbuddy.app.ui.journal.logTypeLabel
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Selectable time spans. */
enum class Span(private val labelRes: Int, val ms: Long) {
    Day(R.string.span_24h, 86_400_000L),
    ThreeDays(R.string.span_3d, 3 * 86_400_000L),
    Week(R.string.span_7d, 7 * 86_400_000L),
    Month(R.string.span_30d, 30 * 86_400_000L),
    Quarter(R.string.span_90d, 90 * 86_400_000L),
    ;

    val label: String get() = appString(labelRes)
}

class HistoryViewModel(private val container: AppContainer, initialSensor: Long) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var sensors by mutableStateOf<List<Sensor>>(emptyList())
        private set
    var sensorId by mutableLongStateOf(initialSensor)
        private set
    var span by mutableStateOf(Span.Day)
        private set
    var series by mutableStateOf<ReadingSeries?>(null)
        private set
    var detail by mutableStateOf<SensorDetail?>(null)
        private set
    var markers by mutableStateOf<List<ChartMarker>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    init {
        viewModelScope.launch { apiCall { api.sensors() }.onSuccess { sensors = it } }
        load()
    }

    fun select(sensor: Long = sensorId, newSpan: Span = span) {
        sensorId = sensor
        span = newSpan
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            val to = System.currentTimeMillis()
            apiCall { api.readings(sensorId, to - span.ms, to) }
                .onSuccess { series = it; error = null }
                .onFailure { error = it.message }
            apiCall { api.sensor(sensorId) }.onSuccess { detail = it }
            // Milestones of the plants located at this sensor, as chart markers.
            apiCall { api.plants() }.onSuccess { plants ->
                markers = plants.filter { it.sensorId == sensorId }.flatMap { p ->
                    p.milestones.map { (type, ts) -> ChartMarker(ts, logTypeColor(type), "${p.nickname}: ${logTypeLabel(type)}") }
                }
            }
            loading = false
        }
    }
}

@Composable
fun HistoryScreen(sensorId: Long, onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = "history") { HistoryViewModel(container, sensorId) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.history_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.sensors.size > 1) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.TabBar)) {
                    vm.sensors.forEach { s ->
                        val selected = s.id == vm.sensorId
                        Box(
                            Modifier.weight(1f).clickable { vm.select(sensor = s.id) }.background(if (selected) Palette.NavCenter else Palette.TabBar).padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                                color = if (selected) Palette.TextPrimary else Palette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Span.entries.forEach { s -> FilterChip(selected = vm.span == s, onClick = { vm.select(newSpan = s) }, label = { Text(s.label) }) }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val series = vm.series
            when {
                series == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
                series == null -> Unit
                else -> {
                    vm.detail?.let { d ->
                        val note = when {
                            d.rangeMode == "manual" -> appString(R.string.history_bands_manual)
                            d.rangeSource != null -> appString(R.string.history_bands_from, formatPhase(d.rangeSource.phase), d.rangeSource.nickname)
                            else -> appString(R.string.history_no_ranges)
                        }
                        Text(note + if (series.resolution == "hour") stringResource(R.string.history_hourly) else "", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    if (series.points.isEmpty()) Text(stringResource(R.string.history_no_readings), color = Palette.TextSecondary)
                    val ranges = vm.detail?.ranges
                    MetricChart(MetricKind.Temperature, series, ranges?.temperature, vm.markers) { p -> p.temperatureC?.let { ChartPoint(p.ts, it, p.tempMin, p.tempMax) } }
                    MetricChart(MetricKind.Humidity, series, ranges?.humidity, vm.markers) { p -> p.humidityPct?.let { ChartPoint(p.ts, it, p.humMin, p.humMax) } }
                    MetricChart(MetricKind.Vpd, series, ranges?.vpd, vm.markers) { p -> p.vpdKpa?.let { ChartPoint(p.ts, it) } }
                    if (vm.markers.any { it.ts in series.fromTs..series.toTs }) {
                        Text(stringResource(R.string.history_markers), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                }
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun MetricChart(
    kind: MetricKind,
    series: ReadingSeries,
    range: MetricRange?,
    markers: List<ChartMarker>,
    map: (com.growbuddy.app.data.remote.SeriesPoint) -> ChartPoint?,
) {
    val points = series.points.mapNotNull(map)
    GbCard(
        Modifier.fillMaxWidth(),
        title = kind.label,
        trailing = {
            points.lastOrNull()?.let {
                Text("${formatNumber(it.value, kind.decimals)} ${kind.unit}", style = MaterialTheme.typography.titleLarge.merge(NumericStyle), color = Palette.TextPrimary)
            }
        },
    ) {
        TimeChart(
            points = points,
            fromTs = series.fromTs,
            toTs = series.toTs,
            bucketMs = series.bucketMs,
            decimals = kind.decimals,
            unit = kind.unit,
            warnMin = range?.warnMin,
            warnMax = range?.warnMax,
            critMin = range?.critMin,
            critMax = range?.critMax,
            markers = markers,
            lineColor = when (kind) {
                MetricKind.Temperature -> Palette.Warning
                MetricKind.Humidity -> Palette.Teal
                MetricKind.Vpd -> Palette.NavSelected
            },
        )
    }
}
