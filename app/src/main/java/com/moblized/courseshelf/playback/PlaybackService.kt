package com.moblized.courseshelf.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.data.ApiException
import com.moblized.courseshelf.data.CourseLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the player so lessons keep playing with the screen off, shows the media
 * notification, and reports watch progress back to OfflineU.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    // Last known state of the current lesson (sampled on the main thread).
    private var lastId: String? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        sample()
        lastId?.let { send(it, lastPositionMs, CourseLogic.isNearEnd(lastPositionMs, lastDurationMs)) }
        PlaybackQueue.serviceActive = false
        ticker?.cancel()
        mainScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Media items arriving from the controller are rebuilt locally so URIs and subtitles are kept. */
    private val callback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                PlaybackQueue.mediaItemFor(item.mediaId, AppGraph.api) ?: item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackQueue.serviceActive = isPlaying || player.playWhenReady
            sample()
            if (isPlaying) {
                startTicker()
            } else {
                ticker?.cancel()
                lastId?.let { send(it, lastPositionMs, CourseLogic.isNearEnd(lastPositionMs, lastDurationMs)) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = lastId
            if (previous != null && previous != mediaItem?.mediaId) {
                val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    CourseLogic.isNearEnd(lastPositionMs, lastDurationMs)
                val position = if (finished && lastDurationMs > 0) lastDurationMs else lastPositionMs
                send(previous, position, finished)
            }
            lastId = mediaItem?.mediaId
            lastPositionMs = 0L
            lastDurationMs = 0L
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    sample()
                    lastId?.let { send(it, lastDurationMs.takeIf { d -> d > 0 } ?: lastPositionMs, true) }
                    PlaybackQueue.serviceActive = false
                }
                Player.STATE_IDLE -> {
                    // Stopped by the user (not an error): nothing left to do in the background.
                    if (player.playerError == null && !player.playWhenReady) {
                        PlaybackQueue.serviceActive = false
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun sample() {
        val item = player.currentMediaItem ?: return
        lastId = item.mediaId
        lastPositionMs = player.currentPosition
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0) lastDurationMs = duration
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = mainScope.launch {
            var elapsed = 0
            while (isActive) {
                delay(2_000)
                sample()
                elapsed += 2
                if (elapsed >= 10) {
                    elapsed = 0
                    lastId?.let { send(it, lastPositionMs, CourseLogic.isNearEnd(lastPositionMs, lastDurationMs)) }
                }
            }
        }
    }

    /** Never sends completed=false, so rewatching a finished lesson keeps it finished. */
    private fun send(relPath: String, positionMs: Long, completed: Boolean) {
        val seconds = (positionMs / 1000L).toInt()
        val done = if (completed) true else null
        val coursePath = PlaybackQueue.coursePath
        AppGraph.appScope.launch {
            try {
                AppGraph.api.progress(relPath, seconds, done)
            } catch (e: ApiException) {
                // The server forgot the active course (restart, or another device switched it).
                if (e.code == 400 && coursePath.isNotBlank()) {
                    runCatching {
                        AppGraph.api.loadCourse(coursePath)
                        AppGraph.api.progress(relPath, seconds, done)
                    }
                }
            } catch (ignored: Exception) {
                // Offline for a moment; the next report catches up.
            }
        }
    }
}
