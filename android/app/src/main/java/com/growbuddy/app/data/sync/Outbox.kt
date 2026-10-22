package com.growbuddy.app.data.sync

import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiJson
import com.growbuddy.app.data.remote.LogCreate
import com.growbuddy.app.data.remote.PlantCreate
import com.growbuddy.app.ui.common.appString
import com.growbuddy.app.ui.journal.logTypeLabel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A write made while the server was unreachable, replayed in order by [OutboxSync]. */
@Serializable
sealed interface OutboxItem {
    val id: String

    /** Short description for the UI ("Feeding log", "Plant Mary"). */
    val label: String
}

@Serializable
@SerialName("photo")
data class UploadPhotoItem(override val id: String) : OutboxItem {
    override val label get() = appString(R.string.outbox_photo)
}

@Serializable
@SerialName("log")
data class CreateLogItem(val body: LogCreate) : OutboxItem {
    override val id get() = body.id
    override val label get() = appString(R.string.outbox_log, logTypeLabel(body.type))
}

@Serializable
@SerialName("plant")
data class CreatePlantItem(val body: PlantCreate) : OutboxItem {
    override val id get() = body.id
    override val label get() = appString(R.string.outbox_plant, body.nickname)
}

/** An item the server rejected (not a network problem); kept so the user can see what was lost. */
@Serializable
data class FailedItem(val label: String, val error: String, val ts: Long)

@Serializable
data class OutboxState(val pending: List<OutboxItem> = emptyList(), val failed: List<FailedItem> = emptyList())

/**
 * File-backed FIFO of pending writes (`outbox.json`, written atomically) plus the image
 * files of queued photo uploads. Server-side idempotency (client UUIDs) makes replays safe.
 */
class Outbox(private val dir: File) {
    private val file = File(dir, "outbox.json")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<OutboxState> = _state

    fun photoFile(photoId: String): File = File(dir, "photos/$photoId.jpg")

    suspend fun addPhoto(photoId: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            photoFile(photoId).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        add(UploadPhotoItem(photoId))
    }

    suspend fun add(item: OutboxItem) = update { s ->
        if (s.pending.any { it.id == item.id && it::class == item::class }) s else s.copy(pending = s.pending + item)
    }

    suspend fun done(item: OutboxItem) {
        update { s -> s.copy(pending = s.pending.filterNot { it == item }) }
        if (item is UploadPhotoItem) withContext(Dispatchers.IO) { photoFile(item.id).delete() }
    }

    suspend fun reject(item: OutboxItem, error: String, now: Long) {
        update { s -> s.copy(pending = s.pending.filterNot { it == item }, failed = s.failed + FailedItem(item.label, error, now)) }
        if (item is UploadPhotoItem) withContext(Dispatchers.IO) { photoFile(item.id).delete() }
    }

    suspend fun clearFailed() = update { it.copy(failed = emptyList()) }

    private suspend fun update(change: (OutboxState) -> OutboxState) = mutex.withLock {
        val next = change(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { save(next) }
            _state.value = next
        }
    }

    private fun load(): OutboxState =
        runCatching { ApiJson.decodeFromString<OutboxState>(file.readText()) }.getOrDefault(OutboxState())

    private fun save(state: OutboxState) {
        dir.mkdirs()
        val tmp = File(dir, "outbox.json.tmp")
        tmp.writeText(ApiJson.encodeToString(OutboxState.serializer(), state))
        tmp.renameTo(file)
    }
}
