package com.growbuddy.app.data.remote

import android.content.ContentResolver
import android.net.Uri
import com.growbuddy.app.R
import com.growbuddy.app.data.sync.Outbox
import com.growbuddy.app.ui.common.appString
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A photo the server has, or one queued in the outbox ([queued]) until it is reachable. */
data class UploadedPhoto(val id: String, val queued: Boolean, val localUri: Uri)

/** Uploads a picked or captured image (POST /photos). The server downscales and strips EXIF. */
class PhotoUploader(
    private val resolver: ContentResolver,
    private val provider: ApiProvider,
    private val outbox: Outbox,
    private val onQueued: () -> Unit,
) {
    /** With [allowQueue], a network failure queues the photo instead of failing. */
    suspend fun upload(uri: Uri, allowQueue: Boolean): Result<UploadedPhoto> {
        val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            ?: return Result.failure(ApiException("read_failed", appString(R.string.photo_read_failed)))
        val id = UUID.randomUUID().toString()
        val result = apiCall {
            val part = MultipartBody.Part.createFormData("file", "$id.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            provider.api().uploadPhoto(id.toRequestBody("text/plain".toMediaType()), part)
        }
        val error = result.exceptionOrNull()
        return when {
            error == null -> Result.success(UploadedPhoto(id, queued = false, localUri = uri))
            allowQueue && error is ApiException && error.code == "network" -> {
                outbox.addPhoto(id, bytes)
                onQueued()
                Result.success(UploadedPhoto(id, queued = true, localUri = uri))
            }
            else -> Result.failure(error)
        }
    }
}
