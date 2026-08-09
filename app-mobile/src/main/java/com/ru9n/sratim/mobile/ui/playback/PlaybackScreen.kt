package com.ru9n.sratim.mobile.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import com.ru9n.sratim.core.playback.RobustSeekableFmp4Extractor
import com.ru9n.sratim.core.viewmodel.PlaybackViewModel

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(viewModel: PlaybackViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val exoPlayer = remember(uiState.videoUrl) {
        val url = uiState.videoUrl ?: return@remember null

        // Configure base Data Source with authentication cookie
        val baseDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            uiState.cookie?.let { cookie ->
                setDefaultRequestProperties(mapOf("Cookie" to cookie))
            }
        }

        // Configure Resolving Data Source to translate byte-seeks into time-seeks (?start=X)
        val dataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            if (dataSpec.position > 0 && uiState.durationUs > 0 && uiState.fileSize > 0) {
                // Calculate average bitrate
                val bitrate = (uiState.fileSize * 8L * 1_000_000L) / uiState.durationUs
                val startTimeSeconds = (dataSpec.position * 8) / bitrate
                
                android.util.Log.d("PlaybackScreen", "Translating seek: byte ${dataSpec.position} -> time ${startTimeSeconds}s")
                
                // IMPORTANT: We MUST reset the position to 0 because the server sends a fresh fMP4 stream
                // starting from headers. If we keep the old position, ExoPlayer will try to skip data
                // it actually needs or throw a Source Error. We also unset the length to avoid Range header conflicts.
                dataSpec.buildUpon()
                    .setUri(dataSpec.uri.buildUpon()
                        .appendQueryParameter("start", startTimeSeconds.toString())
                        .build())
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            } else {
                dataSpec
            }
        }

        // Configure Custom Extractor for fMP4 seeking alignment
        val extractorsFactory = ExtractorsFactory {
            arrayOf(RobustSeekableFmp4Extractor(uiState.durationUs, uiState.fileSize))
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, extractorsFactory)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .build().apply {
                playWhenReady = true
                
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)

                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                    .build()
                setMediaItem(mediaItem)
                prepare()
            }
    }

    DisposableEffect(exoPlayer) {
        val activity = context.findActivity()
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Force Landscape and Keep Screen On
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide System Bars (Immersive Mode)
        if (window != null) {
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            // Restore orientation, flags, and system bars
            activity?.requestedOrientation = originalOrientation
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            exoPlayer?.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
