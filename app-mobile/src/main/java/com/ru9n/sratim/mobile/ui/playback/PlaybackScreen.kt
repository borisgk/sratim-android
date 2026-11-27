package com.ru9n.sratim.mobile.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.PlayerView
import com.ru9n.sratim.core.playback.RobustSeekableFmp4Extractor
import com.ru9n.sratim.core.viewmodel.AudioTrackInfo
import com.ru9n.sratim.core.viewmodel.PlaybackViewModel
import kotlinx.coroutines.delay

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

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var isControllerVisible by remember { mutableStateOf(true) }
    var showAudioDialog by remember { mutableStateOf(false) }

    val exoPlayer = remember(uiState.baseStreamUrl, uiState.cookie, uiState.durationUs, uiState.fileSize) {
        if (uiState.baseStreamUrl == null) return@remember null

        val baseDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            uiState.cookie?.let { cookie ->
                setDefaultRequestProperties(mapOf("Cookie" to cookie))
            }
        }

        val dataSourceFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec ->
            if (dataSpec.position > 0 && uiState.durationUs > 0 && uiState.fileSize > 0) {
                val bitrate = (uiState.fileSize * 8L * 1_000_000L) / uiState.durationUs
                val startTimeSeconds = (dataSpec.position * 8) / bitrate

                android.util.Log.d("PlaybackScreen", "Translating seek: byte ${dataSpec.position} -> time ${startTimeSeconds}s")

                val cleanUriBuilder = dataSpec.uri.buildUpon().clearQuery()
                for (paramName in dataSpec.uri.queryParameterNames) {
                    if (paramName != "start") {
                        for (paramVal in dataSpec.uri.getQueryParameters(paramName)) {
                            cleanUriBuilder.appendQueryParameter(paramName, paramVal)
                        }
                    }
                }
                cleanUriBuilder.appendQueryParameter("start", startTimeSeconds.toString())

                dataSpec.buildUpon()
                    .setUri(cleanUriBuilder.build())
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

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)
            }
    }

    // Load initial stream or switch audio track seamlessly without tearing down ExoPlayer
    LaunchedEffect(exoPlayer, uiState.videoUrl, uiState.audioSwitchNonce) {
        val player = exoPlayer ?: return@LaunchedEffect
        val url = uiState.videoUrl ?: return@LaunchedEffect

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()
        if (uiState.resumePositionMs > 0) {
            android.util.Log.i("SratimPlayback", "Resuming/switching playback at ${uiState.resumePositionMs / 1000}s")
            player.setMediaItem(mediaItem, uiState.resumePositionMs)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.playWhenReady = true
        player.prepare()
    }

    // Report playback progress periodically while playing (every 10s, matching desktop player.js)
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        var lastReportedSec = -1L
        while (true) {
            delay(5_000)
            if (player.isPlaying) {
                val currentSec = player.currentPosition / 1000L
                if (lastReportedSec == -1L || kotlin.math.abs(currentSec - lastReportedSec) >= 10) {
                    lastReportedSec = currentSec
                    viewModel.reportWatchProgress(player.currentPosition, player.duration, "progress")
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val activity = context.findActivity()
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (window != null) {
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

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
                    } else if (!player.playWhenReady && player.playbackState == Player.STATE_READY) {
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
            activity?.requestedOrientation = originalOrientation
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            exoPlayer?.release()
        }
    }

    BackHandler(enabled = showAudioDialog) {
        showAudioDialog = false
    }

    val activeTrack = remember(uiState.audioTracks, uiState.selectedAudioTrackId) {
        uiState.audioTracks.find { it.id == uiState.selectedAudioTrackId }
            ?: uiState.audioTracks.firstOrNull()
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
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            isControllerVisible = (visibility == View.VISIBLE)
                        })
                        playerViewRef = this
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
                    playerViewRef = view
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = isControllerVisible && !showAudioDialog && uiState.audioTracks.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .padding(top = 16.dp, end = 20.dp)
            ) {
                MobileAudioTrackPill(
                    activeTrack = activeTrack,
                    totalTracks = uiState.audioTracks.size,
                    onClick = {
                        playerViewRef?.hideController()
                        showAudioDialog = true
                    }
                )
            }

            if (showAudioDialog && uiState.audioTracks.isNotEmpty()) {
                MobileAudioTrackSheet(
                    tracks = uiState.audioTracks,
                    selectedTrackId = activeTrack?.id ?: uiState.selectedAudioTrackId,
                    onSelectTrack = { track ->
                        val currentPos = exoPlayer.currentPosition
                        showAudioDialog = false
                        viewModel.selectAudioTrack(track.id, currentPos)
                    },
                    onDismiss = { showAudioDialog = false }
                )
            }
        }
    }
}

@Composable
private fun MobileAudioTrackPill(
    activeTrack: AudioTrackInfo?,
    totalTracks: Int,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xD916181E))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Audio Track",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = activeTrack?.label ?: "Audio",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val codec = activeTrack?.codec
        if (!codec.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = codec,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (totalTracks > 1) {
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "• $totalTracks",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MobileAudioTrackSheet(
    tracks: List<AudioTrackInfo>,
    selectedTrackId: Int,
    onSelectTrack: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF181A20))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Audio Track",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(tracks, key = { it.id }) { track ->
                    val isSelected = track.id == selectedTrackId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .clickable { onSelectTrack(track) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(18.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = track.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val codec = track.codec
                        if (!codec.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = codec,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
