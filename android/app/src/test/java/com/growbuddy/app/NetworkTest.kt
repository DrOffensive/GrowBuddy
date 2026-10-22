package com.growbuddy.app

import com.growbuddy.app.data.remote.ApiException
import com.growbuddy.app.data.remote.ApiProvider
import com.growbuddy.app.data.remote.ConfigState
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.remote.parseHttpError
import com.growbuddy.app.data.remote.readPartHeaders
import com.growbuddy.app.data.settings.ServerConfig
import com.growbuddy.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkTest {
    init {
        TestStrings.install()
    }

    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readBytes()

    @Test
    fun mjpegPartsAreReadExactly() {
        val source = Buffer().write(fixture("stream_2frames.bin"))
        repeat(2) {
            val length = readPartHeaders(source)
            val jpeg = source.readByteArray(length.toLong())
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), jpeg.copyOfRange(0, 2))
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), jpeg.copyOfRange(jpeg.size - 2, jpeg.size))
        }
    }

    @Test
    fun serverErrorBodiesBecomeTypedErrors() {
        val conflict = parseHttpError(409, String(fixture("error_conflict.json")))
        assertEquals("already_adopted", conflict.code)
        assertTrue(conflict.message.contains("already adopted"))
        assertEquals("validation_error", parseHttpError(422, String(fixture("error_validation.json"))).code)
        assertEquals("unauthorized", parseHttpError(401, null).code)
        assertEquals("http_502", parseHttpError(502, "<html>Bad gateway</html>").code)
    }

    @Test
    fun requestsCarryTheConfiguredToken() = runTest {
        val config = MutableStateFlow<ServerConfig?>(ServerConfig(server.url("/").toString(), "secret-token"))
        val provider = ApiProvider(config, backgroundScope)
        provider.configState.first { it is ConfigState.Ready }
        server.enqueue(MockResponse.Builder().body("""{"status":"ok","version":"2.0.0"}""").build())

        val health = apiCall { provider.api().health() }.getOrThrow()

        assertEquals("2.0.0", health.version)
        val request = server.takeRequest()
        assertEquals("/api/v1/health", request.url.encodedPath)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
    }

    @Test
    fun setupTestUsesTheUnsavedToken() = runTest {
        val provider = ApiProvider(MutableStateFlow(null), backgroundScope)
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"unauthorized","message":"Missing or invalid bearer token"}}""").build())

        val result = apiCall { provider.apiFor(ServerConfig(server.url("/").toString(), "typed-in")).status() }

        val error = result.exceptionOrNull() as ApiException
        assertEquals("unauthorized", error.code)
        assertEquals("Bearer typed-in", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun unreachableServerIsANetworkError() = runTest {
        val url = server.url("/").toString()
        server.close()
        val provider = ApiProvider(MutableStateFlow(null), backgroundScope)
        val error = apiCall { provider.apiFor(ServerConfig(url, "t")).health() }.exceptionOrNull() as ApiException
        assertEquals("network", error.code)
    }

    @Test
    fun urlNormalisation() {
        assertEquals("http://growbuddy:8000", SettingsStore.normalizeUrl(" growbuddy:8000/ "))
        assertEquals("https://growbuddy.tail.ts.net", SettingsStore.normalizeUrl("https://growbuddy.tail.ts.net"))
        assertEquals("http://10.0.0.5:8000/api/v1/", ServerConfig("http://10.0.0.5:8000/", "t").apiUrl)
    }
}

class TokenScopeTest {
    @Test
    fun tokenIsOnlySentToTheGrowBuddyServer() = runTest {
        val ours = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() } // e.g. a seed shop's image host (different port)
        try {
            val provider = ApiProvider(MutableStateFlow(ServerConfig(ours.url("/").toString(), "secret")), backgroundScope)
            provider.configState.first { it is ConfigState.Ready }
            ours.enqueue(MockResponse.Builder().body("ok").build())
            other.enqueue(MockResponse.Builder().body("img").build())
            provider.httpClient.newCall(okhttp3.Request.Builder().url(ours.url("/x")).build()).execute().close()
            provider.httpClient.newCall(okhttp3.Request.Builder().url(other.url("/strain.jpg")).build()).execute().close()
            assertEquals("Bearer secret", ours.takeRequest().headers["Authorization"])
            assertEquals(null, other.takeRequest().headers["Authorization"])
        } finally {
            ours.close()
            other.close()
        }
    }
}
