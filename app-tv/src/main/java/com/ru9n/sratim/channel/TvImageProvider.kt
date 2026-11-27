package com.ru9n.sratim.channel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.NetworkClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Proxies Sratim artwork (posters and backdrops) via a `content://` URI
 * so that Google TV Launcher (`com.google.android.apps.tv.launcherx`) can load images
 * reliably using the shared HTTP/2 connection pool and size fallback pipeline.
 */
class TvImageProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.ru9n.sratim.tv.images"
        private const val TAG = "TvImageProvider"

        fun buildImageUri(relativePath: String): Uri {
            val cleanPath = relativePath.trimStart('/')
            return Uri.parse("content://$AUTHORITY/$cleanPath")
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val appContext = context.applicationContext
        val relativePath = uri.path?.trimStart('/') ?: return null
        if (relativePath.isEmpty()) return null

        val cacheDir = File(appContext.cacheDir, "tv_channel_art").apply { mkdirs() }
        val cacheKey = sha256(relativePath)
        val cachedFile = File(cacheDir, "$cacheKey.jpg")

        if (!cachedFile.exists() || cachedFile.length() == 0L) {
            try {
                val config = runBlocking {
                    ConfigManager(appContext).configFlow.first()
                }
                if (!config.isValid) return null

                val baseUrl = NetworkClient.getBaseUrl(appContext, config.serverUrl)
                val fullUrl = "$baseUrl$relativePath"

                val requestBuilder = Request.Builder().url(fullUrl)
                if (config.token.isNotBlank()) {
                    requestBuilder.header("Cookie", "session=${config.token.trim()}")
                }

                val client = NetworkClient.getImageOkHttpClient(appContext)
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Failed to download $fullUrl: HTTP ${response.code}")
                        return null
                    }
                    val body = response.body ?: return null
                    val tempFile = File(cacheDir, "$cacheKey.tmp")
                    FileOutputStream(tempFile).use { out ->
                        body.byteStream().copyTo(out)
                    }
                    if (tempFile.length() > 0L) {
                        tempFile.renameTo(cachedFile)
                    } else {
                        tempFile.delete()
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching TV channel image for $relativePath", e)
                return null
            }
        }

        return ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
