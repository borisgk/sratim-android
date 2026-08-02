package com.ru9n.sratim.ui.playback

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ru9n.sratim.core.viewmodel.PlaybackViewModel

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(viewModel: PlaybackViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory).build().apply {
            playWhenReady = true
            
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
        }
    }

    // Manage player lifecycle - release ONLY when screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Load video when URL changes
    LaunchedEffect(uiState.videoUrl) {
        uiState.videoUrl?.let { url ->
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (uiState.videoUrl != null) {
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
