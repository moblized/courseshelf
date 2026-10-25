package com.moblized.courseshelf.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.moblized.courseshelf.data.CourseLogic
import com.moblized.courseshelf.data.Lesson
import com.moblized.courseshelf.data.OfflineUApi
import java.util.concurrent.ConcurrentHashMap

/**
 * The playable lessons of the course being watched. The player screen and the
 * playback service live in the same process and both read from here, so media
 * items can always be rebuilt from a lesson's rel_path (its media id).
 */
object PlaybackQueue {

    data class Entry(val lesson: Lesson, val section: String)

    @Volatile var coursePath: String = ""
        private set
    @Volatile var courseName: String = ""
        private set
    @Volatile var entries: List<Entry> = emptyList()
        private set
    @Volatile var startIndex: Int = 0
        private set
    @Volatile var startPositionMs: Long = 0L
        private set

    /** True while the service is playing or about to play. */
    @Volatile var serviceActive: Boolean = false

    /** relPath -> (uri, mime) used when the server converted a lesson for us. */
    private val overrides = ConcurrentHashMap<String, Pair<String, String?>>()

    fun set(coursePath: String, courseName: String, entries: List<Entry>, startRelPath: String) {
        this.coursePath = coursePath
        this.courseName = courseName
        this.entries = entries
        overrides.clear()
        val index = entries.indexOfFirst { it.lesson.relPath == startRelPath }.coerceAtLeast(0)
        startIndex = index
        startPositionMs = entries.getOrNull(index)?.let { CourseLogic.resumePositionMs(it.lesson) } ?: 0L
    }

    fun entry(relPath: String): Entry? = entries.firstOrNull { it.lesson.relPath == relPath }

    fun override(relPath: String, uri: String, mime: String?) {
        overrides[relPath] = uri to mime
    }

    fun mediaItems(api: OfflineUApi): List<MediaItem> =
        entries.mapNotNull { mediaItemFor(it.lesson.relPath, api) }

    fun mediaItemFor(relPath: String, api: OfflineUApi): MediaItem? {
        val entry = entry(relPath) ?: return null
        val lesson = entry.lesson
        val src = lesson.mediaSrc ?: return null
        val (uri, mime) = overrides[relPath] ?: (api.absolute(src) to null)
        val metadata = MediaMetadata.Builder()
            .setTitle(lesson.title)
            .setDisplayTitle(lesson.title)
            .setArtist(courseName)
            .setAlbumTitle(entry.section)
            .setMediaType(if (lesson.isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        val subtitles = lesson.subtitleSrc?.takeIf { it.isNotBlank() }?.let { sub ->
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.absolute(sub)))
                    .setMimeType(MimeTypes.TEXT_VTT) // the server converts every subtitle format to WebVTT
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        } ?: emptyList()
        return MediaItem.Builder()
            .setMediaId(lesson.relPath)
            .setUri(uri)
            .apply { if (mime != null) setMimeType(mime) }
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitles)
            .build()
    }
}
