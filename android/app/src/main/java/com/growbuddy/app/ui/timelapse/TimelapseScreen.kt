package com.growbuddy.app.ui.timelapse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.Snapshot
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.common.formatDateTime
import com.growbuddy.app.ui.theme.NumericStyle
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TimelapseSpan(private val labelRes: Int, val ms: Long?) {
    Day(R.string.span_24h, 86_400_000L),
    Week(R.string.span_7d, 7 * 86_400_000L),
    Month(R.string.span_30d, 30 * 86_400_000L),
    All(R.string.events_all, null),
    ;

    val label: String get() = appString(labelRes)
}

private const val MAX_FRAMES = 3_000 // ~4 months of hourly snapshots
private const val PRELOAD = 4

class TimelapseViewModel(private val container: AppContainer) : ViewModel() {
    private val api get() = container.apiProvider.api()
    var span by mutableStateOf(TimelapseSpan.Week)
        private set
    var frames by mutableStateOf<List<Snapshot>?>(null) // oldest first
        private set
    var index by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun select(newSpan: TimelapseSpan) {
        span = newSpan
        load()
    }

    /** Pages through /snapshots (newest first) and keeps them oldest first for playback. */
    fun load() {
        viewModelScope.launch {
            val from = span.ms?.let { System.currentTimeMillis() - it }
            val all = mutableListOf<Snapshot>()
            var before: Long? = null
            val result = apiCall {
                do {
                    val page = api.snapshots(from = from, before = before)
                    all += page.items
                    before = page.nextBefore
                } while (before != null && all.size < MAX_FRAMES)
            }
            result.onSuccess {
                frames = all.asReversed().toList()
                index = (frames!!.size - 1).coerceAtLeast(0) // start at the newest frame
                error = null
            }.onFailure { error = it.message }
        }
    }

    fun capture() {
        capturing = true
        viewModelScope.launch {
            apiCall { api.captureSnapshot() }.onSuccess { load() }.onFailure { error = it.message }
            capturing = false
        }
    }

    fun imageUrl(s: Snapshot, thumb: Boolean): String? =
        container.apiProvider.url("snapshots/${s.id}/image" + if (thumb) "?thumb=1" else "")
}

@Composable
fun TimelapseScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { TimelapseViewModel(container) }
    var playing by remember { mutableStateOf(false) }
    var fps by rememberSaveable { mutableIntStateOf(5) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.timelapse_title), onBack = onBack, actions = {
            IconButton(onClick = vm::capture, enabled = !vm.capturing) {
                if (vm.capturing) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(Icons.Outlined.PhotoCamera, appString(R.string.timelapse_capture), tint = Palette.TextPrimary)
            }
        })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimelapseSpan.entries.forEach { s -> FilterChip(selected = vm.span == s, onClick = { playing = false; vm.select(s) }, label = { Text(s.label) }) }
        }
        val frames = vm.frames
        when {
            frames == null && vm.error != null -> ErrorBox(vm.error!!, onRetry = vm::load)
            frames == null -> LoadingBox()
            frames.isEmpty() -> EmptyState(Icons.Outlined.PhotoCamera, appString(R.string.timelapse_empty))
            else -> Player(vm, frames, playing, { playing = it }, fps, { fps = it })
        }
    }
}

@Composable
private fun Player(
    vm: TimelapseViewModel,
    frames: List<Snapshot>,
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    fps: Int,
    onFps: (Int) -> Unit,
) {
    val context = LocalContext.current
    val index = vm.index.coerceIn(0, frames.lastIndex)
    val frame = frames[index]
    val filmstrip = rememberLazyListState()

    // Playback: advance at [fps]; stop at the end.
    LaunchedEffect(playing, fps) {
        while (playing) {
            delay(1000L / fps)
            if (vm.index >= frames.lastIndex) onPlaying(false) else vm.index++
        }
    }
    // Preload the next frames so playback doesn't wait on the network.
    LaunchedEffect(index) {
        val loader = SingletonImageLoader.get(context)
        for (i in index + 1..minOf(index + PRELOAD, frames.lastIndex)) {
            vm.imageUrl(frames[i], thumb = false)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
        }
        filmstrip.animateScrollToItem((index - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val previous = frames.getOrNull(index - 1)?.let { vm.imageUrl(it, thumb = false) }
            Box(
                Modifier
                    .heightIn(max = maxWidth * 0.75f)
                    .aspectRatio(frame.width.toFloat() / frame.height.coerceAtLeast(1))
                    .border(4.dp, Palette.CardRaised, RoundedCornerShape(6.dp))
                    .padding(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(vm.imageUrl(frame, thumb = false))
                        // Keep showing the previous frame while this one loads (no flicker).
                        .placeholderMemoryCacheKey(previous)
                        .build(),
                    contentDescription = stringResource(R.string.timelapse_snapshot, formatDateTime(frame.ts)),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    formatDateTime(frame.ts) + if (frame.kind == "manual") stringResource(R.string.timelapse_manual) else "",
                    style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (!playing && index >= frames.lastIndex) vm.index = 0 // replay from the start
                onPlaying(!playing)
            }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) stringResource(R.string.timelapse_pause) else stringResource(R.string.timelapse_play), tint = Palette.TextPrimary, modifier = Modifier.size(36.dp))
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { onPlaying(false); vm.index = it.toInt() },
                valueRange = 0f..frames.lastIndex.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text("${index + 1}/${frames.size}", style = NumericStyle, color = Palette.TextSecondary, modifier = Modifier.padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10).forEach { f -> FilterChip(selected = fps == f, onClick = { onFps(f) }, label = { Text("$f fps") }) }
        }
        LazyRow(state = filmstrip, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(frames, key = { _, s -> s.id }) { i, s ->
                AsyncImage(
                    vm.imageUrl(s, thumb = true),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 96.dp, height = 72.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(3.dp, if (i == index) Palette.CardBorder else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { onPlaying(false); vm.index = i },
                )
            }
        }
    }
}
