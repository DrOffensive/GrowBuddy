package com.growbuddy.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.growbuddy.app.AppContainer
import com.growbuddy.app.GrowBuddyApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as GrowBuddyApp).container

/**
 * Emits [load] results every [intervalMs], or immediately when [trigger] fires.
 * Collect with `stateIn(WhileSubscribed)` so polling stops when the screen is not visible.
 */
fun <T> polling(intervalMs: Long, trigger: MutableSharedFlow<Unit>, load: suspend () -> T): Flow<T> = flow {
    while (true) {
        emit(load())
        withTimeoutOrNull(intervalMs) { trigger.first() }
    }
}

fun refreshTrigger() = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
