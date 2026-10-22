package com.growbuddy.app.data.sync

import com.growbuddy.app.R
import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.GrowBuddyApi
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.ui.common.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Replays [Outbox] items in order. Photos go first by construction (queued before their log). */
class OutboxSync(private val outbox: Outbox, private val api: () -> GrowBuddyApi, private val now: () -> Long = System::currentTimeMillis) {
    /** Returns true when the outbox is empty, false when the server is still unreachable (retry later). */
    suspend fun flush(): Boolean {
        while (true) {
            val item = outbox.state.value.pending.firstOrNull() ?: return true
            val result = apiCall { send(item) }
            val error = result.exceptionOrNull()
            when {
                error == null -> outbox.done(item)
                error is ApiException && (error.code == "network" || (error.httpStatus ?: 0) >= 500) -> return false
                else -> outbox.reject(item, error.message ?: appString(R.string.outbox_rejected), now())
            }
        }
    }

    private suspend fun send(item: OutboxItem) {
        when (item) {
            is UploadPhotoItem -> {
                val bytes = withContext(Dispatchers.IO) { outbox.photoFile(item.id).takeIf { it.exists() }?.readBytes() }
                    ?: throw ApiException("missing_file", appString(R.string.outbox_missing_file))
                val part = MultipartBody.Part.createFormData("file", "${item.id}.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                api().uploadPhoto(item.id.toRequestBody("text/plain".toMediaType()), part)
            }
            is CreateLogItem -> api().createLog(item.body)
            is CreatePlantItem -> api().createPlant(item.body)
        }
    }
}
