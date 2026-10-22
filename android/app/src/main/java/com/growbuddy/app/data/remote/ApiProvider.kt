package com.growbuddy.app.data.remote

import com.growbuddy.app.R
import com.growbuddy.app.data.settings.ServerConfig
import com.growbuddy.app.ui.common.appString
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Configuration state as seen by the UI (DataStore loads asynchronously). */
sealed interface ConfigState {
    data object Loading : ConfigState
    data object Missing : ConfigState
    data class Ready(val config: ServerConfig) : ConfigState
}

/** Failure from the server (`{"error": {...}}`) or the network. */
class ApiException(val code: String, override val message: String, val httpStatus: Int? = null) :
    Exception(message)

/**
 * Owns the HTTP stack. One OkHttpClient whose interceptor adds the bearer token of the
 * current config, and a Retrofit instance rebuilt when the server URL changes.
 *
 * Offline reading: API GET responses are kept in an HTTP disk cache ([cacheDir]); when the
 * server is unreachable the last cached copy is served instead and [offline] turns true.
 */
class ApiProvider(config: Flow<ServerConfig?>, scope: CoroutineScope, cacheDir: File? = null) {
    val configState: StateFlow<ConfigState> = config
        .map { if (it == null) ConfigState.Missing else ConfigState.Ready(it) }
        .stateIn(scope, SharingStarted.Eagerly, ConfigState.Loading)

    private val currentConfig: ServerConfig?
        get() = (configState.value as? ConfigState.Ready)?.config

    private val _offline = MutableStateFlow(false)

    /** True while API responses come from the offline cache because the server is unreachable. */
    val offline: StateFlow<Boolean> = _offline

    /** Network first; on failure, serve the cached copy of a GET (however old) if there is one. */
    private val offlineFallback = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET" || !cacheableForOffline(request.url)) return@Interceptor chain.proceed(request)
        try {
            chain.proceed(request).also { if (it.isSuccessful) _offline.value = false }
        } catch (e: IOException) {
            val cached = chain.proceed(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
            if (cached.code == 504) { // not in the cache: report the original failure
                cached.close()
                throw e
            }
            _offline.value = true
            cached
        }
    }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "http"), CACHE_BYTES)) }
        .addInterceptor(offlineFallback)
        .addNetworkInterceptor(storeForOffline)
        .build()

    /**
     * Adds the bearer token, but only to requests for the GrowBuddy server itself. The same
     * client loads third-party images (strain photos), which must never see the token.
     */
    private fun clientWithToken(config: () -> ServerConfig?): OkHttpClient =
        baseClient.newBuilder().addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val target = config()
                val server = target?.baseUrl?.toHttpUrlOrNull()
                val ours = server != null && request.url.host == server.host && request.url.port == server.port
                chain.proceed(
                    if (!ours) request else request.newBuilder().header("Authorization", "Bearer ${target!!.token}").build(),
                )
            },
        ).build()

    /** For API calls and images; always uses the token of the current config. */
    val httpClient: OkHttpClient = clientWithToken { currentConfig }

    /** For Coil: same auth, but no HTTP cache (Coil has its own disk cache for images). */
    val imageClient: OkHttpClient = httpClient.newBuilder().cache(null).build()

    /** For the endless MJPEG stream (no read timeout). */
    val streamClient: OkHttpClient = httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    @Volatile private var cached: Pair<String, GrowBuddyApi>? = null

    /** API for the current config (cached per server URL). */
    fun api(): GrowBuddyApi {
        val config = currentConfig ?: throw ApiException("not_configured", appString(R.string.error_not_configured))
        cached?.let { (url, api) -> if (url == config.apiUrl) return api }
        return build(config.apiUrl, httpClient).also { cached = config.apiUrl to it }
    }

    /** One-off API for a config that is not saved yet (setup screen connection test). */
    fun apiFor(config: ServerConfig): GrowBuddyApi = build(config.apiUrl, clientWithToken { config })

    private fun build(baseUrl: String, client: OkHttpClient): GrowBuddyApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GrowBuddyApi::class.java)

    fun url(path: String): String? = currentConfig?.let { it.apiUrl + path.trimStart('/') }
}

private const val CACHE_BYTES = 50L * 1024 * 1024

/** JSON API reads worth keeping for offline use (images have Coil's cache; the camera is live). */
internal fun cacheableForOffline(url: HttpUrl): Boolean {
    val path = url.encodedPath
    if (!path.startsWith("/api/v1/")) return false
    val rest = path.removePrefix("/api/v1/")
    return listOf("photos/", "snapshots/", "camera/", "sensors/discovered").none { rest.startsWith(it) }
}

/** Stores every cacheable API response as immediately stale: always refetched, kept for offline. */
private val storeForOffline = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (chain.request().method == "GET" && response.isSuccessful && cacheableForOffline(chain.request().url)) {
        response.newBuilder().header("Cache-Control", "max-age=0").removeHeader("Pragma").build()
    } else {
        response
    }
}

/** Runs [block], mapping HTTP and network failures to [ApiException]. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: HttpException) {
    Result.failure(parseHttpError(e.code(), e.response()?.errorBody()?.string()))
} catch (e: IOException) {
    Result.failure(ApiException("network", appString(R.string.error_network, e.message ?: e.javaClass.simpleName)))
} catch (e: ApiException) {
    Result.failure(e)
} catch (e: kotlinx.serialization.SerializationException) {
    Result.failure(ApiException("bad_response", appString(R.string.error_bad_response, e.message.orEmpty())))
}

/** For endpoints without a response body (204): throws the server's error for non-2xx. */
fun Response<Unit>.orThrow() {
    if (!isSuccessful) throw parseHttpError(code(), errorBody()?.string())
}

internal fun parseHttpError(status: Int, body: String?): ApiException {
    val detail = body?.let { runCatching { ApiJson.decodeFromString<ApiErrorBody>(it).error }.getOrNull() }
    return when {
        detail != null -> ApiException(detail.code, detail.message, status)
        status == 401 -> ApiException("unauthorized", appString(R.string.error_unauthorized), status)
        else -> ApiException("http_$status", appString(R.string.error_server, status), status)
    }
}
