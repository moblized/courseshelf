package com.moblized.courseshelf.playback

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.R
import com.moblized.courseshelf.data.ApiException
import com.moblized.courseshelf.ui.theme.CourseShelfTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var playerView: PlayerView

    private val title = mutableStateOf("")
    private val subtitle = mutableStateOf("")
    private val status = mutableStateOf<String?>(null)
    private val controlsVisible = mutableStateOf(true)
    private val inPip = mutableStateOf(false)

    private var startQueuePending = false
    private var currentIsVideo = true
    private val conversionTried = mutableSetOf<String>()

    private val pipSupported by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startQueuePending = savedInstanceState == null && intent.getBooleanExtra(EXTRA_NEW_QUEUE, false)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setDefaultArtwork(ContextCompat.getDrawable(context, R.drawable.ic_artwork))
            setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_FIT)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                controlsVisible.value = visibility == View.VISIBLE
            })
        }

        setContent {
            CourseShelfTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
                    if (!inPip.value && controlsVisible.value) {
                        TopBar(title.value, subtitle.value) { finish() }
                    }
                    val message = status.value
                    if (message != null && !inPip.value) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        ) {
                            Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NEW_QUEUE, false)) {
            val c = controller
            if (c != null) startQueue(c) else startQueuePending = true
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(controllerListener)
            playerView.player = c
            when {
                startQueuePending -> startQueue(c)
                c.mediaItemCount == 0 -> finish() // nothing to show (e.g. the app was restarted)
                else -> refreshTitle()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        val c = controller
        if (c != null) {
            when {
                // Back pressed: stop the lesson; the service saves progress and shuts down.
                isFinishing -> { c.pause(); c.stop() }
                // Video leaving the screen (PiP closed, or PiP unavailable): pause it.
                // Audio lessons keep playing in the background.
                currentIsVideo && !isChangingConfigurations -> c.pause()
            }
            c.removeListener(controllerListener)
        }
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun startQueue(c: MediaController) {
        startQueuePending = false
        conversionTried.clear()
        status.value = null
        val items = PlaybackQueue.mediaItems(AppGraph.api)
        if (items.isEmpty()) { finish(); return }
        c.setMediaItems(items, PlaybackQueue.startIndex.coerceIn(0, items.lastIndex), PlaybackQueue.startPositionMs)
        c.prepare()
        c.play()
        refreshTitle()
        // Make sure the server's active course is this one before progress starts flowing.
        lifecycleScope.launch { runCatching { AppGraph.api.loadCourse(PlaybackQueue.coursePath) } }
    }

    private fun refreshTitle() {
        val c = controller ?: return
        val item = c.currentMediaItem
        title.value = item?.mediaMetadata?.title?.toString().orEmpty()
        val section = item?.mediaMetadata?.albumTitle?.toString()
        val count = c.mediaItemCount
        val position = if (count > 0) "Lesson ${c.currentMediaItemIndex + 1} of $count" else ""
        subtitle.value = listOfNotNull(position.ifBlank { null }, section, PlaybackQueue.courseName.ifBlank { null })
            .joinToString("  ·  ")
        currentIsVideo = PlaybackQueue.entry(item?.mediaId.orEmpty())?.lesson?.isVideo ?: true
    }

    private val controllerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            status.value = null
            refreshTitle()
            updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying && currentIsVideo
            if (isPlaying) status.value = null
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePipParams()

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.isNotEmpty()) currentIsVideo = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private fun handleError(error: PlaybackException) {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val relPath = item.mediaId
        if (error.errorCode in NETWORK_ERRORS) {
            status.value = "Can't reach your course server. Check Wi-Fi, then press play to retry."
            return
        }
        if (!conversionTried.add(relPath)) {
            status.value = "This lesson can't be played on your phone (${error.errorCodeName})."
            return
        }
        val index = c.currentMediaItemIndex
        val position = c.currentPosition.coerceAtLeast(0)
        lifecycleScope.launch {
            status.value = "Converting this lesson for your phone…"
            val converted = waitForConvertedStream(relPath)
            if (converted == null) {
                status.value = "This lesson can't be played on your phone (${error.errorCodeName})."
                return@launch
            }
            PlaybackQueue.override(relPath, converted.first, converted.second)
            val replacement = PlaybackQueue.mediaItemFor(relPath, AppGraph.api) ?: return@launch
            val live = controller ?: return@launch
            live.replaceMediaItem(index, replacement)
            live.seekTo(index, position)
            live.prepare()
            live.play()
            status.value = null
        }
    }

    /** Asks OfflineU how to play the lesson and waits (up to ~4 min) for the converted version. */
    private suspend fun waitForConvertedStream(relPath: String): Pair<String, String?>? {
        val api = AppGraph.api
        val src = PlaybackQueue.entry(relPath)?.lesson?.mediaSrc ?: return null
        repeat(120) { attempt ->
            val media = try {
                api.mediaStatus(relPath)
            } catch (e: ApiException) {
                if (e.code == 404 && attempt == 0) {
                    runCatching { api.loadCourse(PlaybackQueue.coursePath) }
                    null
                } else return null
            } catch (ignored: Exception) {
                return null
            }
            if (media != null) {
                when (media.mode) {
                    "hls" -> if (media.hlsReady && !media.hlsUrl.isNullOrBlank()) {
                        return api.absolute(media.hlsUrl) to MimeTypes.APPLICATION_M3U8
                    }
                    "remux" -> {
                        if (media.error != null) return null
                        if (media.ready) return api.absolute(src) to null // /files/ now serves the MP4 copy
                    }
                    else -> return null // the server would send the same file again
                }
            }
            delay(2_000)
        }
        return null
    }

    // ---- Picture-in-picture -------------------------------------------------

    private fun pipParams(): PictureInPictureParams {
        val size = controller?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val value = size.width.toFloat() / size.height
            when {
                value > 2.39f -> Rational(239, 100)
                value < 1 / 2.39f -> Rational(100, 239)
                else -> Rational(size.width, size.height)
            }
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(currentIsVideo && controller?.isPlaying == true)
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }

    private fun updatePipParams() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipSupported &&
            currentIsVideo && controller?.isPlaying == true
        ) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        playerView.useController = !isInPictureInPictureMode
    }

    companion object {
        private const val EXTRA_NEW_QUEUE = "new_queue"

        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )

        /** Plays [PlaybackQueue] from its start lesson. */
        fun start(context: Context) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).putExtra(EXTRA_NEW_QUEUE, true)
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun TopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(Modifier.padding(end = 16.dp)) {
            Text(
                title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
