package com.example.sratim.network

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private fun createOkHttpClient(token: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)

        if (!token.isNullOrBlank()) {
            builder.addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val cookieValue = "session=${token.trim()}"
                Log.e("SratimNet", "Outgoing Header -> Cookie: $cookieValue")
                val request = original.newBuilder()
                    .header("Cookie", cookieValue)
                    .build()
                chain.proceed(request)
            })
        }

        return builder.build()
    }

    fun getBaseUrl(context: Context, hostInput: String): String {
        val isIp = hostInput.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?$"))
        val hostPart = if (hostInput.contains(":")) hostInput.substringBefore(":") else hostInput
        
        var protocol = "https"
        var finalHost = hostInput

        if (isIp) {
            if (NetworkUtils.isInSameSubnet(context, hostPart)) {
                protocol = "http"
                if (!hostInput.contains(":")) {
                    finalHost = "$hostInput:8000"
                }
            }
        }

        val baseUrl = if (finalHost.startsWith("http")) finalHost else "$protocol://$finalHost"
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    fun createApi(context: Context, hostInput: String, token: String? = null): SratimApi? {
        return try {
            val urlWithTrailingSlash = getBaseUrl(context, hostInput)
            
            Log.d("NetworkClient", "Creating API with baseUrl: $urlWithTrailingSlash")
            
            Retrofit.Builder()
                .baseUrl(urlWithTrailingSlash)
                .client(createOkHttpClient(token))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SratimApi::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
