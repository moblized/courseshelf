package com.moblized.courseshelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes returned by the OfflineU server (internal/offlineu/model.go, server.go, store.go).

@Serializable
data class Stats(
    @SerialName("total_lessons") val totalLessons: Int = 0,
    @SerialName("completed_lessons") val completedLessons: Int = 0,
    @SerialName("completion_percentage") val completionPercentage: Double = 0.0,
    @SerialName("last_accessed_path") val lastAccessedPath: String? = null,
    @SerialName("last_accessed_url") val lastAccessedUrl: String? = null,
    @SerialName("last_accessed_title") val lastAccessedTitle: String? = null,
)

@Serializable
data class Lesson(
    val title: String,
    val path: String = "",
    @SerialName("rel_path") val relPath: String,
    val url: String = "",
    @SerialName("lesson_type") val type: String = "text",
    @SerialName("video_file") val videoFile: String? = null,
    @SerialName("video_src") val videoSrc: String? = null,
    @SerialName("audio_file") val audioFile: String? = null,
    @SerialName("audio_src") val audioSrc: String? = null,
    @SerialName("image_src") val imageSrc: String? = null,
    @SerialName("subtitle_file") val subtitleFile: String? = null,
    @SerialName("subtitle_src") val subtitleSrc: String? = null,
    @SerialName("text_files") val textFiles: List<String> = emptyList(),
    val completed: Boolean = false,
    @SerialName("progress_seconds") val progressSeconds: Int = 0,
) {
    val mediaSrc: String? get() = videoSrc ?: audioSrc
    val isMedia: Boolean get() = !mediaSrc.isNullOrBlank()
    val isVideo: Boolean get() = !videoSrc.isNullOrBlank()
}

@Serializable
data class DirNode(
    val name: String = "",
    val path: String = "",
    val children: List<DirNode> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val stats: Stats = Stats(),
)

@Serializable
data class CourseSummary(
    val name: String,
    val path: String,
    @SerialName("display_path") val displayPath: String? = null,
)

@Serializable
data class RecentCourse(
    val path: String,
    val name: String = "",
    @SerialName("last_opened") val lastOpened: String? = null,
    @SerialName("total_lessons") val totalLessons: Int = 0,
    @SerialName("completed_lessons") val completedLessons: Int = 0,
    @SerialName("display_path") val displayPath: String? = null,
) {
    val fraction: Float
        get() = if (totalLessons > 0) (completedLessons.toFloat() / totalLessons).coerceIn(0f, 1f) else 0f
}

@Serializable
data class StateResponse(
    val version: String? = null,
    val course: CourseSummary? = null,
    val tree: DirNode? = null,
    val stats: Stats = Stats(),
    @SerialName("recent_courses") val recentCourses: List<RecentCourse> = emptyList(),
    @SerialName("needs_mount") val needsMount: Boolean = false,
)

@Serializable
data class BrowseEntry(
    val name: String,
    val path: String,
    @SerialName("display_path") val displayPath: String? = null,
    @SerialName("media_files") val mediaFiles: Int = 0,
    @SerialName("is_course_candidate") val isCourseCandidate: Boolean = false,
)

@Serializable
data class BrowseResponse(
    @SerialName("current_path") val currentPath: String = "",
    @SerialName("current_display_path") val currentDisplayPath: String? = null,
    @SerialName("parent_path") val parentPath: String? = null,
    val directories: List<BrowseEntry> = emptyList(),
    val roots: List<String> = emptyList(),
)

@Serializable
data class TextResource(
    val name: String,
    val url: String,
    val src: String,
    val mode: String = "download",
)

@Serializable
data class LessonResponse(
    val course: CourseSummary? = null,
    val lesson: Lesson,
    val resources: List<TextResource> = emptyList(),
    @SerialName("prev_url") val prevUrl: String? = null,
    @SerialName("next_url") val nextUrl: String? = null,
    val position: Int = 0,
    val total: Int = 0,
)

@Serializable
data class MediaStatus(
    val mode: String = "raw",
    val ready: Boolean = false,
    val preparing: Boolean = false,
    @SerialName("hls_url") val hlsUrl: String? = null,
    @SerialName("hls_ready") val hlsReady: Boolean = false,
    val error: String? = null,
)
