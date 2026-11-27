package com.ru9n.sratim.core.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.gson.Gson
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val TAG = "NetworkClient"
    private const val HTTP_CACHE_SIZE_BYTES = 50L * 1024L * 1024L // 50 MB
    private const val BASE_URL_CACHE_TTL_MS = 60_000L

    private val IP_HOST_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$")
    private val SIZED_IMAGE_PATH_REGEX = Regex("(/images/(?:backdrops|posters|profiles))/(?!original/)([^/]+)/")

    val sharedGson: Gson = Gson()
    private val sharedGsonConverterFactory: GsonConverterFactory = GsonConverterFactory.create(sharedGson)

    @Volatile
    private var baseOkHttpClient: OkHttpClient? = null

    @Volatile
    private var imageOkHttpClient: OkHttpClient? = null

    private data class CachedBaseUrl(val url: String, val timestampMs: Long)

    private val baseUrlCache = ConcurrentHashMap<String, CachedBaseUrl>()
    private val apiCache = ConcurrentHashMap<String, SratimApi>()

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Returns the process-wide base [OkHttpClient] owning the shared HTTP/2 [ConnectionPool],
     * [okhttp3.Dispatcher], and 50 MB disk [Cache].
     */
    fun getSharedOkHttpClient(context: Context): OkHttpClient {
        baseOkHttpClient?.let { return it }
        return synchronized(this) {
            baseOkHttpClient ?: run {
                val appContext = context.applicationContext
                val cacheDir = File(appContext.cacheDir, "http_cache")
                val builder = OkHttpClient.Builder()
                    .cache(Cache(cacheDir, HTTP_CACHE_SIZE_BYTES))
                    .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)

                if (isDebuggable(appContext)) {
                    val loggingInterceptor = HttpLoggingInterceptor { message ->
                        Log.d("SratimHttp", message)
                    }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                    builder.addInterceptor(loggingInterceptor)
                }

                builder.build().also { baseOkHttpClient = it }
            }
        }
    }

    /**
     * Returns an [OkHttpClient] tailored for Coil and `TvImageProvider`
     * that shares the exact same HTTP/2 [ConnectionPool] and [okhttp3.Dispatcher] as API calls,
     * follows redirects, and transparently falls back to `/original/` if a sized TMDB image
     * (`w300`, `w780`, `w1280`, etc.) returns 404 on the Sratim server.
     */
    fun getImageOkHttpClient(context: Context): OkHttpClient {
        imageOkHttpClient?.let { return it }
        return synchronized(this) {
            imageOkHttpClient ?: run {
                val rootClient = getSharedOkHttpClient(context)
                rootClient.newBuilder()
                    .apply { interceptors().clear() }
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(Interceptor { chain ->
                        val request = chain.request()
                        val response = chain.proceed(request)
                        if (response.code == 404) {
                            val urlString = request.url.toString()
                            if (SIZED_IMAGE_PATH_REGEX.containsMatchIn(urlString)) {
                                val fallbackUrl = SIZED_IMAGE_PATH_REGEX.replaceFirst(urlString, "$1/original/")
                                if (fallbackUrl != urlString) {
                                    response.close()
                                    val fallbackRequest = request.newBuilder()
                                        .url(fallbackUrl)
                                        .build()
                                    return@Interceptor chain.proceed(fallbackRequest)
                                }
                            }
                        }
                        response
                    })
                    .build()
                    .also { imageOkHttpClient = it }
            }
        }
    }

    fun invalidateCaches() {
        baseUrlCache.clear()
        apiCache.clear()
    }

    fun getBaseUrl(context: Context, hostInput: String): String {
        val trimmedInput = hostInput.trim()
        if (trimmedInput.isEmpty()) return ""

        val now = System.currentTimeMillis()
        baseUrlCache[trimmedInput]?.let { cached ->
            if (now - cached.timestampMs < BASE_URL_CACHE_TTL_MS) {
                return cached.url
            }
        }

        val resolved = resolveBaseUrl(context.applicationContext, trimmedInput)
        baseUrlCache[trimmedInput] = CachedBaseUrl(resolved, now)
        return resolved
    }

    private fun resolveBaseUrl(context: Context, hostInput: String): String {
        if (hostInput.startsWith("http://") || hostInput.startsWith("https://")) {
            return if (hostInput.endsWith("/")) hostInput else "$hostInput/"
        }

        val isIp = IP_HOST_REGEX.matches(hostInput)
        val hostPart = if (hostInput.contains(":")) hostInput.substringBefore(":") else hostInput

        var protocol = "https"
        var finalHost = hostInput

        if (isIp && NetworkUtils.isInSameSubnet(context, hostPart)) {
            protocol = "http"
            if (!hostInput.contains(":")) {
                finalHost = "$hostInput:8000"
            }
        }

        val baseUrl = "$protocol://$finalHost"
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    fun createApi(context: Context, hostInput: String, token: String? = null): SratimApi? {
        val trimmedHost = hostInput.trim()
        if (trimmedHost.isEmpty()) return null

        return try {
            val baseUrl = getBaseUrl(context, trimmedHost)
            if (baseUrl.isEmpty()) return null

            val cleanToken = token?.trim().orEmpty()
            val cacheKey = "$baseUrl|$cleanToken"

            apiCache[cacheKey] ?: synchronized(this) {
                apiCache[cacheKey] ?: run {
                    val rootClient = getSharedOkHttpClient(context)
                    val client = if (cleanToken.isNotEmpty()) {
                        val cookieHeader = "session=$cleanToken"
                        rootClient.newBuilder()
                            .addInterceptor { chain ->
                                val authed = chain.request().newBuilder()
                                    .header("Cookie", cookieHeader)
                                    .build()
                                chain.proceed(authed)
                            }
                            .build()
                    } else {
                        rootClient
                    }

                    Log.d(TAG, "Initialized cached SratimApi for baseUrl: $baseUrl")

                    Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .client(client)
                        .addConverterFactory(sharedGsonConverterFactory)
                        .build()
                        .create(SratimApi::class.java)
                        .also { apiCache[cacheKey] = it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create SratimApi for $hostInput: ${e.message}")
            null
        }
    }
}
