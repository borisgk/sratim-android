package com.ru9n.sratim.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
                // starting from headers. We also unset the length to avoid Range header conflicts.
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
                setWakeMode(C.WAKE_MODE_NETWORK)
                
                // Set audio attributes for movie playback
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)

                // Add listener to log track info and handle errors
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val hasAudio = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_AUDIO && group.isSelected
                        }
                        val hasVideo = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                        }
                        android.util.Log.i("SratimPlayback", "Tracks changed: Audio=$hasAudio, Video=$hasVideo")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val cause = error.cause
                        val errorMessage = if (cause != null && cause.javaClass.name.contains("MediaCodec")) {
                            "Codec Error: ${cause.message}. Try a different device or video format."
                        } else {
                            error.message ?: "Unknown playback error"
                        }
                        android.util.Log.e("SratimPlayback", "Player Error: $errorMessage", error)
                    }
                })

                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                    .build()
                setMediaItem(mediaItem)
                if (uiState.resumePositionMs > 0) {
                    android.util.Log.i("SratimPlayback", "Resuming playback at ${uiState.resumePositionMs / 1000}s")
                    seekTo(uiState.resumePositionMs)
                }
                prepare()
            }
    }

    // Report playback progress periodically while playing (every 10s, matching desktop player.js)
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        var lastReportedSec = -1L
        while (true) {
            kotlinx.coroutines.delay(5_000)
            if (player.isPlaying) {
                val currentSec = player.currentPosition / 1000L
                if (lastReportedSec == -1L || Math.abs(currentSec - lastReportedSec) >= 10) {
                    lastReportedSec = currentSec
                    viewModel.reportWatchProgress(player.currentPosition, player.duration, "progress")
                }
            }
        }
    }

    // Manage player lifecycle and keep screen awake
    DisposableEffect(exoPlayer) {
        val activity = context.findActivity()
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer?.let { player ->
                        viewModel.reportWatchProgress(player.currentPosition, player.duration, "stop")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                exoPlayer?.let { player ->
                    if (isPlaying) {
                        viewModel.reportWatchProgress(player.currentPosition, player.duration, "start")
                    } else if (player.playbackState != Player.STATE_ENDED) {
                        viewModel.reportWatchProgress(player.currentPosition, player.duration, "pause")
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    exoPlayer?.let { player ->
                        viewModel.reportWatchProgress(newPosition.positionMs, player.duration, "seek")
                    }
                }
            }
        }
        exoPlayer?.addListener(listener)

        onDispose {
            exoPlayer?.let { player ->
                viewModel.reportWatchProgress(player.currentPosition, player.duration, "stop")
                player.removeListener(listener)
            }
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
