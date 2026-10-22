package com.growbuddy.app.data.remote

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException

/**
 * Reads the server's `multipart/x-mixed-replace` MJPEG stream (GET /camera/stream).
 * Each part carries a Content-Length, so frames are read exactly without scanning
 * for boundaries. Cancelling the collector cancels the HTTP call, which frees the
 * server's viewer slot.
 */
class MjpegStream(private val client: OkHttpClient) {
    fun frames(url: String): Flow<ImageBitmap> = channelFlow {
        val call = client.newCall(Request.Builder().url(url).build())
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw parseHttpError(response.code, response.body.string())
                    }
                    val source = response.body.source()
                    while (true) {
                        val length = readPartHeaders(source)
                        val jpeg = source.readByteArray(length.toLong())
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { send(it.asImageBitmap()) }
                    }
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) close(e)
            } catch (e: ApiException) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }
}

/**
 * Consumes part headers (boundary line, Content-Type, Content-Length, blank line) and
 * returns the part's Content-Length. Throws EOFException at end of stream.
 */
internal fun readPartHeaders(source: BufferedSource): Int {
    var length = -1
    while (true) {
        val line = source.readUtf8LineStrict()
        if (line.isEmpty()) {
            if (length >= 0) return length
            continue // CRLF after the previous part's body
        }
        val colon = line.indexOf(':')
        if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
            length = line.substring(colon + 1).trim().toInt()
        }
    }
}
