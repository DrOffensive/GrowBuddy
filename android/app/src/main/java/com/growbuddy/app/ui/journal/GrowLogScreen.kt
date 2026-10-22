package com.growbuddy.app.ui.journal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.LogEntry
import com.growbuddy.app.data.remote.LogPage
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.sync.CreateLogItem
import com.growbuddy.app.ui.common.EmptyState
import com.growbuddy.app.ui.common.ErrorBox
import com.growbuddy.app.ui.common.LoadingBox
import com.growbuddy.app.ui.common.Page
import com.growbuddy.app.ui.common.PageHeader
import com.growbuddy.app.ui.common.Pager
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** Log entries, newest first (grow log and plant detail). */
fun logPager(load: suspend (before: Long?) -> LogPage) = Pager<LogEntry>({ it.id }) { before -> load(before).let { Page(it.items, it.nextBefore) } }

class GrowLogViewModel(private val container: AppContainer) : ViewModel() {
    private val api get() = container.apiProvider.api()
    val pager = logPager { before -> api.logs(limit = 50, before = before) }
    var plantNames by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun refresh() {
        viewModelScope.launch {
            apiCall { api.plants(includeArchived = true) }.onSuccess { list -> plantNames = list.associate { it.id to it.nickname } }
            pager.refresh()
        }
    }

    fun loadMore() {
        viewModelScope.launch { pager.loadMore() }
    }

    fun photoUrl(id: String): String? = container.apiProvider.url("photos/$id?thumb=1")
}

@Composable
fun GrowLogScreen(onOpen: (String) -> Unit, onNewLog: () -> Unit, onSchedule: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { GrowLogViewModel(container) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val outbox by container.outbox.state.collectAsStateWithLifecycle()
            val queuedLogs = outbox.pending.count { it is CreateLogItem }
            PageHeader(stringResource(R.string.growlog_title), icon = R.drawable.ic_nav_growlog, actions = {
                IconButton(onClick = onSchedule) {
                    Image(painterResource(R.drawable.ic_calendar), stringResource(R.string.settings_schedule), Modifier.size(34.dp))
                }
            })
            if (queuedLogs > 0) {
                Text(
                    pluralStringResource(R.plurals.growlog_queued, queuedLogs, queuedLogs),
                    color = Palette.TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                        .background(Palette.Warning.copy(alpha = 0.25f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            val items = vm.pager.items
            when {
                items == null && vm.pager.error != null -> ErrorBox(vm.pager.error!!, onRetry = vm::refresh)
                items == null -> LoadingBox()
                items.isEmpty() -> EmptyState(R.drawable.ic_nav_growlog, appString(R.string.growlog_empty))
                else -> Timeline(items, vm, onOpen)
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewLog,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.wizard_title)) },
            containerColor = Palette.Button,
            contentColor = Palette.TextPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun Timeline(items: List<LogEntry>, vm: GrowLogViewModel, onOpen: (String) -> Unit) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }
    val byDay = items.groupBy { dayKey(it.ts) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        byDay.forEach { (_, entries) ->
            item(key = "day-${entries.first().ts}") {
                Text(
                    formatDay(entries.first().ts),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(entries, key = { it.id }) { e ->
                LogRow(e, vm.plantNames, vm::photoUrl, onClick = { onOpen(e.id) }, modifier = Modifier.widthIn(max = 720.dp))
            }
        }
        if (vm.pager.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    }
}

