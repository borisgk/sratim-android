package com.ru9n.sratim.channel

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.ru9n.sratim.R
import com.ru9n.sratim.core.data.ConfigManager
import com.ru9n.sratim.core.network.NetworkClient
import com.ru9n.sratim.core.network.RecentlyWatchedItem
import com.ru9n.sratim.core.viewmodel.RecentlyWatchedUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("RestrictedApi")
object TvHomeChannelPublisher {
    private const val TAG = "TvHomeChannel"
    private const val PREFS_NAME = "sratim_tv_channel_prefs"
    private const val KEY_CHANNEL_ID = "default_channel_id"
    private const val KEY_CHANNEL_LOGO_VERSION = "channel_logo_version"
    private const val CURRENT_LOGO_VERSION = 2
    private const val CHANNEL_INTERNAL_ID = "sratim_recently_watched"

    private val publishMutex = Mutex()

    @Volatile
    private var lastPublishedSignature: Int? = null

    /**
     * Fetches the latest Recently Watched items from Sratim API and publishes them
     * to the Android TV / Google TV Home screen (used by boot/system broadcast receiver).
     */
    suspend fun fetchAndPublish(context: Context) = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val configManager = ConfigManager(appContext)
            val config = configManager.configFlow.first()
            if (!config.isValid) {
                Log.d(TAG, "Config not valid yet, skipping channel publish")
                return@withContext
            }

            val api = NetworkClient.createApi(appContext, config.serverUrl, config.token)
                ?: return@withContext
            val response = api.getRecentlyWatched()
            if (response.isSuccessful && response.body()?.success == true) {
                val serverBaseUrl = NetworkClient.getBaseUrl(appContext, config.serverUrl)
                val items = response.body()?.items.orEmpty().map { item ->
                    toUiModel(item, serverBaseUrl)
                }
                publishRecentlyWatched(appContext, items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch and publish TV channel", e)
        }
    }

    /**
     * Publishes a list of [RecentlyWatchedUiModel] items to the Sratim default TV channel
     * and the system Watch Next (Continue Watching) row, skipping redundant SQLite writes
     * if the item list and rounded progress values have not changed.
     */
    suspend fun publishRecentlyWatched(
        context: Context,
        items: List<RecentlyWatchedUiModel>
    ) = withContext(Dispatchers.IO) {
        publishMutex.withLock {
            try {
                val signature = computeSignature(items)
                if (lastPublishedSignature == signature) {
                    return@withLock
                }

                val channelId = getOrCreateDefaultChannel(context)
                if (channelId == -1L) {
                    Log.w(TAG, "Could not obtain valid channelId")
                    return@withLock
                }

                updateChannelPrograms(context, channelId, items)
                updateWatchNextPrograms(context, items)
                lastPublishedSignature = signature
                Log.d(TAG, "Published ${items.size} items to TV channel $channelId and Watch Next")
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing recently watched to TV home screen", e)
            }
        }
    }

    private fun computeSignature(items: List<RecentlyWatchedUiModel>): Int {
        var hash = items.size
        for (item in items) {
            hash = 31 * hash + item.id
            hash = 31 * hash + item.type.hashCode()
            hash = 31 * hash + item.progressPercent.toInt()
            hash = 31 * hash + item.backdropUrl.hashCode()
            hash = 31 * hash + item.posterUrl.hashCode()
        }
        return hash
    }

    private fun getOrCreateDefaultChannel(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var channelId = prefs.getLong(KEY_CHANNEL_ID, -1L)

        // Verify if the channel still exists in TvProvider
        if (channelId != -1L) {
            try {
                context.contentResolver.query(
                    TvContractCompat.buildChannelUri(channelId),
                    null,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        channelId = -1L
                    }
                } ?: run { channelId = -1L }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying existing channel $channelId", e)
                channelId = -1L
            }
        }

        // Also check all channels for this package in case preferences were cleared
        if (channelId == -1L) {
            try {
                context.contentResolver.query(
                    TvContractCompat.Channels.CONTENT_URI,
                    null,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val channel = Channel.fromCursor(cursor)
                        if (channel.internalProviderId == CHANNEL_INTERNAL_ID || channel.displayName != null) {
                            channelId = channel.id
                            prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error scanning channels", e)
            }
        }

        val appLinkUri = Uri.parse("sratim://home")
        val channelBuilder = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("Recently Watched")
            .setDescription("Recently watched movies and shows on Sratim")
            .setInternalProviderId(CHANNEL_INTERNAL_ID)
            .setAppLinkIntentUri(appLinkUri)

        if (channelId == -1L) {
            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channelBuilder.build().toContentValues()
            ) ?: return -1L

            channelId = ContentUris.parseId(channelUri)
            storeLogo(context, channelId)
            TvContractCompat.requestChannelBrowsable(context, channelId)
            prefs.edit()
                .putLong(KEY_CHANNEL_ID, channelId)
                .putInt(KEY_CHANNEL_LOGO_VERSION, CURRENT_LOGO_VERSION)
                .apply()
            Log.i(TAG, "Created new default TV channel: $channelId")
        } else if (prefs.getInt(KEY_CHANNEL_LOGO_VERSION, 0) < CURRENT_LOGO_VERSION) {
            context.contentResolver.update(
                TvContractCompat.buildChannelUri(channelId),
                channelBuilder.build().toContentValues(),
                null,
                null
            )
            storeLogo(context, channelId)
            TvContractCompat.requestChannelBrowsable(context, channelId)
            prefs.edit().putInt(KEY_CHANNEL_LOGO_VERSION, CURRENT_LOGO_VERSION).apply()
        }

        return channelId
    }

    private fun storeLogo(context: Context, channelId: Long) {
        try {
            val bitmap = createOpaqueLogoBitmap(context)
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store channel logo", e)
        }
    }

    private fun createOpaqueLogoBitmap(context: Context): Bitmap {
        val sizePx = 240
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Opaque midnight navy background (#0A1633)
        canvas.drawColor(0xFF0A1633.toInt())

        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        if (drawable != null) {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun updateChannelPrograms(
        context: Context,
        channelId: Long,
        items: List<RecentlyWatchedUiModel>
    ) {
        val existingIds = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = PreviewProgram.fromCursor(cursor)
                    existingIds.add(program.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying existing channel programs", e)
        }

        for (progId in existingIds) {
            try {
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(progId),
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting preview program $progId", e)
            }
        }

        items.forEachIndexed { index, item ->
            try {
                val weight = (items.size - index) * 10
                val internalId = "${item.type}_${item.id}"
                val deepLinkUri = buildDeepLinkUri(item)

                val displayTitle = if (item.type == "episode" && !item.showTitle.isNullOrBlank()) {
                    item.showTitle!!
                } else {
                    item.title
                }

                val episodeSubtitle = if (item.type == "episode") {
                    val se = if (item.season != null && item.episode != null) {
                        "S${item.season}:E${item.episode} • "
                    } else ""
                    "$se${item.title}"
                } else null

                val hasBackdrop = item.backdropUrl.isNotEmpty()
                val rawUrl = if (hasBackdrop) item.backdropUrl else item.posterUrl.replace("/w185/", "/w500/")
                val artUri = toContentProviderUri(rawUrl)
                val aspectRatio = if (hasBackdrop) {
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                } else {
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                }

                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(
                        if (item.type == "episode") {
                            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                        } else {
                            TvContractCompat.PreviewPrograms.TYPE_MOVIE
                        }
                    )
                    .setTitle(displayTitle)
                    .setInternalProviderId(internalId)
                    .setIntentUri(deepLinkUri)
                    .setWeight(weight)
                    .setPosterArtAspectRatio(aspectRatio)

                if (!episodeSubtitle.isNullOrBlank()) {
                    builder.setDescription(episodeSubtitle)
                    builder.setEpisodeTitle(item.title)
                }
                if (item.season != null) {
                    builder.setSeasonNumber(item.season!!)
                }
                if (item.episode != null) {
                    builder.setEpisodeNumber(item.episode!!)
                }
                if (artUri != null) {
                    builder.setPosterArtUri(artUri)
                }
                if (item.durationSeconds > 0) {
                    builder.setDurationMillis((item.durationSeconds * 1000).toInt())
                }
                if (item.positionSeconds > 0) {
                    builder.setLastPlaybackPositionMillis((item.positionSeconds * 1000).toInt())
                }

                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    builder.build().toContentValues()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error inserting preview program for ${item.title}", e)
            }
        }
    }

    private fun updateWatchNextPrograms(
        context: Context,
        items: List<RecentlyWatchedUiModel>
    ) {
        val existingIds = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                null,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    existingIds.add(program.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying Watch Next programs", e)
        }

        for (progId in existingIds) {
            try {
                context.contentResolver.delete(
                    TvContractCompat.buildWatchNextProgramUri(progId),
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting Watch Next program $progId", e)
            }
        }

        val now = System.currentTimeMillis()
        items.filter { it.progressPercent in 1f..95f }.take(10).forEachIndexed { index, item ->
            try {
                val internalId = "${item.type}_${item.id}"
                val deepLinkUri = buildDeepLinkUri(item)

                val displayTitle = if (item.type == "episode" && !item.showTitle.isNullOrBlank()) {
                    item.showTitle!!
                } else {
                    item.title
                }

                val episodeSubtitle = if (item.type == "episode") {
                    val se = if (item.season != null && item.episode != null) {
                        "S${item.season}:E${item.episode} • "
                    } else ""
                    "$se${item.title}"
                } else null

                val hasBackdrop = item.backdropUrl.isNotEmpty()
                val rawUrl = if (hasBackdrop) item.backdropUrl else item.posterUrl.replace("/w185/", "/w500/")
                val artUri = toContentProviderUri(rawUrl)
                val aspectRatio = if (hasBackdrop) {
                    TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9
                } else {
                    TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3
                }

                val builder = WatchNextProgram.Builder()
                    .setType(
                        if (item.type == "episode") {
                            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                        } else {
                            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
                        }
                    )
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(now - (index * 1000L))
                    .setTitle(displayTitle)
                    .setInternalProviderId(internalId)
                    .setIntentUri(deepLinkUri)
                    .setPosterArtAspectRatio(aspectRatio)

                if (!episodeSubtitle.isNullOrBlank()) {
                    builder.setDescription(episodeSubtitle)
                    builder.setEpisodeTitle(item.title)
                }
                if (item.season != null) {
                    builder.setSeasonNumber(item.season!!)
                }
                if (item.episode != null) {
                    builder.setEpisodeNumber(item.episode!!)
                }
                if (artUri != null) {
                    builder.setPosterArtUri(artUri)
                }
                if (item.durationSeconds > 0) {
                    builder.setDurationMillis((item.durationSeconds * 1000).toInt())
                }
                if (item.positionSeconds > 0) {
                    builder.setLastPlaybackPositionMillis((item.positionSeconds * 1000).toInt())
                }

                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    builder.build().toContentValues()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error inserting Watch Next program for ${item.title}", e)
            }
        }
    }

    private fun toContentProviderUri(rawUrl: String): Uri? {
        if (rawUrl.isEmpty()) return null
        val imagesIdx = rawUrl.indexOf("images/")
        if (imagesIdx != -1) {
            return TvImageProvider.buildImageUri(rawUrl.substring(imagesIdx))
        }
        return Uri.parse(rawUrl)
    }

    private fun buildDeepLinkUri(item: RecentlyWatchedUiModel): Uri {
        return if (item.type == "episode" && item.showId != null) {
            Uri.parse("sratim://playback/show/${item.showId}/episode/${item.id}")
        } else {
            Uri.parse("sratim://playback/movie/${item.id}")
        }
    }

    private fun toUiModel(item: RecentlyWatchedItem, serverBaseUrl: String): RecentlyWatchedUiModel {
        val effectivePoster = if (item.type == "episode") {
            item.showPosterPath?.takeIf { it.isNotEmpty() } ?: item.posterPath
        } else {
            item.posterPath
        }
        val posterUrl = if (!effectivePoster.isNullOrEmpty()) {
            "${serverBaseUrl}images/posters/w500/${effectivePoster.removePrefix("/")}"
        } else ""
        val backdropPath = item.backdropPath
        val backdropUrl = if (!backdropPath.isNullOrEmpty()) {
            "${serverBaseUrl}images/backdrops/w780/${backdropPath.removePrefix("/")}"
        } else ""
        val dur = item.duration ?: 0.0
        val pos = item.position ?: 0.0
        val progress = item.progressPercent?.toFloat()
            ?: if (dur > 0.0 && pos > 0.0) {
                ((pos / dur) * 100.0).toFloat().coerceIn(0f, 100f)
            } else 0f
        return RecentlyWatchedUiModel(
            id = item.id,
            type = item.type,
            showId = item.showId,
            showTitle = item.showTitle,
            title = item.title,
            season = item.season,
            episode = item.episode,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            positionSeconds = pos,
            durationSeconds = dur,
            progressPercent = progress
        )
    }
}
