package com.ru9n.sratim.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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
import com.ru9n.sratim.core.viewmodel.AudioTrackInfo
import com.ru9n.sratim.core.viewmodel.PlaybackViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun ViewGroup.containsDescendant(target: View?): Boolean {
    var current: View? = target
    while (current != null) {
        if (current === this) return true
        current = current.parent as? View
    }
    return false
}

@OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(viewModel: PlaybackViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var isControllerVisible by remember { mutableStateOf(true) }
    var isAudioButtonFocused by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    val audioButtonFocusRequester = remember { FocusRequester() }

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

                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        val hasAudio = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_AUDIO && group.isSelected
                        }
                        val hasVideo = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                        }
                        android.util.Log.i("SratimPlayback", "Tracks changed: Audio=$hasAudio, Video=$hasVideo")
                    }

                    override fun onPlayerError(error: PlaybackException) {
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

    // Auto-hide top bar if user stays idle on the Audio button without opening the dialog
    LaunchedEffect(isAudioButtonFocused, showAudioDialog) {
        if (isAudioButtonFocused && !showAudioDialog) {
            playerViewRef?.showController()
            delay(6_000)
            if (isAudioButtonFocused && !showAudioDialog) {
                playerViewRef?.hideController()
                playerViewRef?.requestFocus()
            }
        }
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
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exoPlayer?.release()
        }
    }

    // Handle Back when focused on top Audio button (hide controls instead of exiting movie)
    BackHandler(enabled = isAudioButtonFocused && !showAudioDialog) {
        playerViewRef?.hideController()
        playerViewRef?.requestFocus()
    }

    // Handle Back when Audio Track picker modal is open
    BackHandler(enabled = showAudioDialog) {
        showAudioDialog = false
        playerViewRef?.requestFocus()
    }

    val activeTrack = remember(uiState.audioTracks, uiState.selectedAudioTrackId) {
        uiState.audioTracks.find { it.id == uiState.selectedAudioTrackId }
            ?: uiState.audioTracks.firstOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (uiState.audioTracks.isEmpty() || exoPlayer == null) {
                    return@onPreviewKeyEvent false
                }

                // Dedicated remote Audio Track button opens/closes the track dialog directly
                if (native.keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) {
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        showAudioDialog = !showAudioDialog
                    }
                    return@onPreviewKeyEvent true
                }

                // When controller is visible and focus is at the top of PlayerView, DPAD_UP jumps to Audio Track button
                if (!showAudioDialog && !isAudioButtonFocused && isControllerVisible && native.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val pv = playerViewRef
                    if (pv != null) {
                        val focused = pv.findFocus()
                        val nextUp = if (focused != null && focused !== pv) {
                            pv.focusSearch(focused, View.FOCUS_UP)
                        } else {
                            null
                        }
                        if (nextUp == null || !pv.containsDescendant(nextUp)) {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                pv.showController()
                                runCatching { audioButtonFocusRequester.requestFocus() }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
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

            // Top-Right Audio Track Pill Button (synced with PlayerView controller)
            val showTopBar = (isControllerVisible || isAudioButtonFocused) &&
                !showAudioDialog &&
                uiState.audioTracks.isNotEmpty()

            AnimatedVisibility(
                visible = showTopBar,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 36.dp)
            ) {
                TvAudioTrackPillButton(
                    activeTrack = activeTrack,
                    totalTracks = uiState.audioTracks.size,
                    isFocused = isAudioButtonFocused,
                    focusRequester = audioButtonFocusRequester,
                    onFocusChanged = { focused -> isAudioButtonFocused = focused },
                    onMoveDownToPlayer = {
                        playerViewRef?.showController()
                        playerViewRef?.requestFocus()
                    },
                    onClick = {
                        playerViewRef?.hideController()
                        showAudioDialog = true
                    }
                )
            }

            // TV Audio Track Selection Modal
            if (showAudioDialog && uiState.audioTracks.isNotEmpty()) {
                TvAudioTrackDialog(
                    tracks = uiState.audioTracks,
                    selectedTrackId = activeTrack?.id ?: uiState.selectedAudioTrackId,
                    onSelectTrack = { track ->
                        val currentPos = exoPlayer.currentPosition
                        showAudioDialog = false
                        playerViewRef?.hideController()
                        playerViewRef?.requestFocus()
                        viewModel.selectAudioTrack(track.id, currentPos)
                    },
                    onDismiss = {
                        showAudioDialog = false
                        playerViewRef?.requestFocus()
                    }
                )
            }
        }
    }
}

@Composable
private fun TvAudioTrackPillButton(
    activeTrack: AudioTrackInfo?,
    totalTracks: Int,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onMoveDownToPlayer: () -> Unit,
    onClick: () -> Unit
) {
    val bgColor = if (isFocused) Color.White else Color(0xD916181E)
    val contentColor = if (isFocused) Color(0xFF101216) else Color.White
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.28f)
    val badgeBg = if (isFocused) Color(0xFF101216).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.14f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            onMoveDownToPlayer()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (native.action == KeyEvent.ACTION_UP) {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(BorderStroke(1.5.dp, borderColor), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        SpeakerWaveIcon(
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = activeTrack?.label ?: "Audio",
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val codec = activeTrack?.codec
        if (!codec.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeBg)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = codec,
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (totalTracks > 1) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "• $totalTracks",
                color = contentColor.copy(alpha = 0.72f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun TvAudioTrackDialog(
    tracks: List<AudioTrackInfo>,
    selectedTrackId: Int,
    onSelectTrack: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val itemFocusRequesters = remember(tracks.size) {
        List(tracks.size) { FocusRequester() }
    }

    LaunchedEffect(tracks, selectedTrackId) {
        yield()
        val targetIdx = tracks.indexOfFirst { it.id == selectedTrackId }.coerceAtLeast(0)
        if (targetIdx in itemFocusRequesters.indices) {
            runCatching { itemFocusRequesters[targetIdx].requestFocus() }
        }
    }

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
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                // Keep D-pad events inside the modal so they never leak to PlayerView
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            }
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF181A20))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakerWaveIcon(
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Audio Track",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (tracks.size > 1) {
                            "${tracks.size} audio tracks available"
                        } else {
                            "1 audio track available"
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    var isRowFocused by remember { mutableStateOf(false) }
                    val isSelected = track.id == selectedTrackId

                    val rowBg = when {
                        isRowFocused -> Color.White
                        isSelected -> Color.White.copy(alpha = 0.14f)
                        else -> Color.White.copy(alpha = 0.05f)
                    }
                    val textColor = if (isRowFocused) Color(0xFF101216) else Color.White
                    val badgeBg = if (isRowFocused) {
                        Color(0xFF101216).copy(alpha = 0.12f)
                    } else {
                        Color.White.copy(alpha = 0.14f)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(itemFocusRequesters[index])
                            .onFocusChanged { isRowFocused = it.isFocused }
                            .onKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        if (native.action == KeyEvent.ACTION_UP) {
                                            onSelectTrack(track)
                                        }
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> index == 0
                                    KeyEvent.KEYCODE_DPAD_DOWN -> index == tracks.lastIndex
                                    else -> false
                                }
                            }
                            .focusable()
                            .clip(RoundedCornerShape(12.dp))
                            .background(rowBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelectTrack(track) }
                            )
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(20.dp)
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = textColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = track.label,
                            color = textColor,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected || isRowFocused) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        val codec = track.codec
                        if (!codec.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeBg)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = codec,
                                    color = textColor,
                                    fontSize = 11.sp,
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

@Composable
private fun SpeakerWaveIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        val speakerPath = Path().apply {
            moveTo(3f * sx, 9f * sy)
            lineTo(3f * sx, 15f * sy)
            lineTo(7f * sx, 15f * sy)
            lineTo(12f * sx, 20f * sy)
            lineTo(12f * sx, 4f * sy)
            lineTo(7f * sx, 9f * sy)
            close()
        }
        drawPath(speakerPath, color = tint)

        val innerWave = Path().apply {
            moveTo(14f * sx, 7.97f * sy)
            lineTo(14f * sx, 16.02f * sy)
            cubicTo(15.48f * sx, 15.29f * sy, 16.5f * sx, 13.77f * sy, 16.5f * sx, 12f * sy)
            cubicTo(16.5f * sx, 10.23f * sy, 15.48f * sx, 8.71f * sy, 14f * sx, 7.97f * sy)
            close()
        }
        drawPath(innerWave, color = tint)

        val outerWave = Path().apply {
            moveTo(14f * sx, 3.23f * sy)
            lineTo(14f * sx, 5.29f * sy)
            cubicTo(16.89f * sx, 6.15f * sy, 19f * sx, 8.83f * sy, 19f * sx, 12f * sy)
            cubicTo(19f * sx, 15.17f * sy, 16.89f * sx, 17.85f * sy, 14f * sx, 18.71f * sy)
            lineTo(14f * sx, 20.77f * sy)
            cubicTo(18.01f * sx, 19.86f * sy, 21f * sx, 16.28f * sy, 21f * sx, 12f * sy)
            cubicTo(21f * sx, 7.72f * sy, 18.01f * sx, 4.14f * sy, 14f * sx, 3.23f * sy)
            close()
        }
        drawPath(outerWave, color = tint)
    }
}
