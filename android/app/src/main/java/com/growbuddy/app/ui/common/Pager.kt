package com.growbuddy.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.growbuddy.app.data.remote.apiCall

/** One page from a `{items, next_before}` endpoint. */
data class Page<T>(val items: List<T>, val nextBefore: Long?)

/** Cursor-paged list (newest first): [refresh] loads the first page, [loadMore] appends. */
class Pager<T>(private val id: (T) -> Any, private val load: suspend (before: Long?) -> Page<T>) {
    var items by mutableStateOf<List<T>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    private var nextBefore: Long? = null
    private var exhausted = false

    suspend fun refresh() {
        loading = true
        apiCall { load(null) }
            .onSuccess {
                items = it.items
                nextBefore = it.nextBefore
                exhausted = it.nextBefore == null
                error = null
            }
            .onFailure { error = it.message }
        loading = false
    }

    suspend fun loadMore() {
        if (loading || exhausted) return
        loading = true
        apiCall { load(nextBefore) }.onSuccess { page ->
            val seen = items.orEmpty().map(id).toSet()
            items = items.orEmpty() + page.items.filter { id(it) !in seen }
            nextBefore = page.nextBefore
            exhausted = page.nextBefore == null
        }.onFailure { error = it.message }
        loading = false
    }
}
