package com.growbuddy.app

import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.ApiProvider
import com.growbuddy.app.data.remote.ConfigState
import com.growbuddy.app.data.remote.LogCreate
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.cacheableForOffline
import com.growbuddy.app.data.settings.ServerConfig
import com.growbuddy.app.data.sync.CreateLogItem
import com.growbuddy.app.data.sync.Outbox
import com.growbuddy.app.data.sync.OutboxSync
import com.growbuddy.app.data.sync.UploadPhotoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PLANTS_JSON =
    """[{"id":"p1","nickname":"Mary","is_autoflower":true,"archived":false,"created_ts":1,"milestones":{},"phase":"Seedling"}]"""

class OfflineTest {
    init {
        TestStrings.install()
    }

    @get:Rule val tmp = TemporaryFolder()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    @Test
    fun cachedResponsesAreServedWhenTheServerIsGone() = runTest {
        val server = MockWebServer().apply { start() }
        val provider = ApiProvider(MutableStateFlow(ServerConfig(server.url("/").toString(), "t")), backgroundScope, tmp.newFolder())
        provider.configState.first { it is ConfigState.Ready }
        server.enqueue(json(PLANTS_JSON))

        assertEquals("Mary", apiCall { provider.api().plants() }.getOrThrow().single().nickname)
        assertFalse(provider.offline.value)

        server.close() // server unreachable from now on
        val offline = apiCall { provider.api().plants() }
        assertEquals("Mary", offline.getOrThrow().single().nickname)
        assertTrue(provider.offline.value)

        val neverFetched = apiCall { provider.api().sensors() }.exceptionOrNull() as ApiException
        assertEquals("network", neverFetched.code) // nothing cached for it
    }

    @Test
    fun onlineAlwaysFetchesFresh() = runTest {
        val server = MockWebServer().apply { start() }
        val provider = ApiProvider(MutableStateFlow(ServerConfig(server.url("/").toString(), "t")), backgroundScope, tmp.newFolder())
        provider.configState.first { it is ConfigState.Ready }
        server.enqueue(json(PLANTS_JSON))
        server.enqueue(json(PLANTS_JSON.replace("Mary", "Jane")))
        apiCall { provider.api().plants() }.getOrThrow()
        assertEquals("Jane", apiCall { provider.api().plants() }.getOrThrow().single().nickname)
        assertEquals(2, server.requestCount)
        server.close()
    }

    @Test
    fun imagesAndCameraAreNotCached() {
        fun ok(path: String) = cacheableForOffline("http://growbuddy:8000$path".toHttpUrl())
        assertTrue(ok("/api/v1/plants"))
        assertTrue(ok("/api/v1/environment/current"))
        assertFalse(ok("/api/v1/photos/abc?thumb=1"))
        assertFalse(ok("/api/v1/snapshots/3/image"))
        assertFalse(ok("/api/v1/camera/stream"))
        assertFalse(ok("/api/v1/sensors/discovered"))
        assertFalse(ok("/somewhere/else"))
    }

    @Test
    fun outboxPersistsDedupsAndCleansUp() = runTest {
        val dir = tmp.newFolder()
        val log = CreateLogItem(LogCreate("l1", "Inspection", listOf("p1"), ts = 5))
        Outbox(dir).apply {
            addPhoto("ph1", byteArrayOf(1, 2, 3))
            add(log)
            add(log) // same id: ignored
        }
        val reloaded = Outbox(dir) // e.g. after an app restart
        assertEquals(listOf("ph1", "l1"), reloaded.state.value.pending.map { it.id })
        assertTrue(reloaded.photoFile("ph1").exists())
        reloaded.done(reloaded.state.value.pending.first())
        assertFalse(reloaded.photoFile("ph1").exists())
        assertEquals(listOf("l1"), Outbox(dir).state.value.pending.map { it.id })
    }

    @Test
    fun syncReplaysInOrderAndRecordsRejections() = runTest {
        val server = MockWebServer().apply { start() }
        val provider = ApiProvider(MutableStateFlow(ServerConfig(server.url("/").toString(), "t")), backgroundScope)
        provider.configState.first { it is ConfigState.Ready }
        val outbox = Outbox(tmp.newFolder())
        outbox.addPhoto("ph1", byteArrayOf(1, 2, 3))
        outbox.add(CreateLogItem(LogCreate("l1", "Inspection", listOf("p1"), ts = 5, photoIds = listOf("ph1"))))
        outbox.add(CreateLogItem(LogCreate("l2", "Inspection", listOf("gone"), ts = 6)))
        server.enqueue(json("""{"id":"ph1","ts":1,"width":10,"height":10,"size_bytes":3}""", 201))
        server.enqueue(json("""{"id":"l1","ts":5,"type":"Inspection","created_ts":5,"updated_ts":5}""", 201))
        server.enqueue(json("""{"error":{"code":"unknown_plant","message":"Unknown plant(s): gone"}}""", 422))

        assertTrue(OutboxSync(outbox, { provider.api() }, now = { 99 }).flush())

        assertEquals(listOf("/api/v1/photos", "/api/v1/logs", "/api/v1/logs"), List(3) { server.takeRequest().url.encodedPath })
        assertTrue(outbox.state.value.pending.isEmpty())
        val failed = outbox.state.value.failed.single()
        assertEquals("Inspection log", failed.label)
        assertTrue(failed.error.contains("Unknown plant"))
        server.close()
    }

    @Test
    fun syncKeepsItemsWhileTheServerIsUnreachable() = runTest {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.close()
        val provider = ApiProvider(MutableStateFlow(ServerConfig(url, "t")), backgroundScope)
        provider.configState.first { it is ConfigState.Ready }
        val outbox = Outbox(tmp.newFolder())
        outbox.addPhoto("ph1", byteArrayOf(1))
        outbox.addPhoto("ph2", byteArrayOf(2))

        assertFalse(OutboxSync(outbox, { provider.api() }).flush())
        assertEquals(2, outbox.state.value.pending.size)
        assertTrue(outbox.state.value.failed.isEmpty())
    }

    @Test
    fun aQueuedPhotoWithoutItsFileIsRejectedNotRetriedForever() = runTest {
        val provider = ApiProvider(MutableStateFlow(ServerConfig("http://127.0.0.1:9/", "t")), backgroundScope)
        provider.configState.first { it is ConfigState.Ready }
        val outbox = Outbox(tmp.newFolder())
        outbox.add(UploadPhotoItem("lost"))
        assertTrue(OutboxSync(outbox, { provider.api() }).flush())
        assertEquals("Photo", outbox.state.value.failed.single().label)
    }
}
