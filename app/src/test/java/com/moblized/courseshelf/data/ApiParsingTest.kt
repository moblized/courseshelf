package com.moblized.courseshelf.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow OfflineU's MarshalJSON output (model.go, server.go, store.go). */
class ApiParsingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private val activeState = """
    {"version":"2.1.0","roots":["/courses"],
     "course":{"name":"Go in Depth","path":"/courses/Go in Depth","display_path":"Go in Depth","progress_file":"/app/data/1a2b-progress.json"},
     "tree":{"name":"Go in Depth","path":"/courses/Go in Depth","type":"directory","has_content":true,
       "stats":{"total_lessons":4,"completed_lessons":1,"completion_percentage":25},
       "lessons":[],
       "children":[
        {"name":"01 - Basics","path":"/courses/Go in Depth/01 - Basics","type":"directory","has_content":true,"children":[],
         "stats":{"total_lessons":2,"completed_lessons":1,"completion_percentage":50},
         "lessons":[
          {"title":"Welcome","path":"/courses/Go in Depth/01 - Basics/01 - Welcome.mp4","rel_path":"01 - Basics/01 - Welcome.mp4",
           "url":"01 - Basics/01 - Welcome.mp4/Welcome","lesson_type":"video","video_file":"01 - Basics/01 - Welcome.mp4",
           "video_mime":"video/mp4","video_src":"/files/01%20-%20Basics/01%20-%20Welcome.mp4","text_files":[],
           "completed":true,"progress_seconds":6,"last_accessed":"2026-10-25T10:00:00"},
          {"title":"Notes","path":"/courses/Go in Depth/01 - Basics/02 - Notes.txt","rel_path":"01 - Basics/02 - Notes.txt",
           "url":"01 - Basics/02 - Notes.txt/Notes","lesson_type":"text","text_files":["01 - Basics/02 - Notes.txt"],
           "completed":false,"progress_seconds":0}
         ]},
        {"name":"02 - Advanced","path":"/courses/Go in Depth/02 - Advanced","type":"directory","has_content":true,
         "stats":{"total_lessons":2,"completed_lessons":0,"completion_percentage":0},
         "lessons":[
          {"title":"Goroutines","path":"/courses/Go in Depth/02 - Advanced/01 - Goroutines.mkv","rel_path":"02 - Advanced/01 - Goroutines.mkv",
           "url":"02 - Advanced/01 - Goroutines.mkv/Goroutines","lesson_type":"video","video_file":"02 - Advanced/01 - Goroutines.mkv",
           "video_mime":"video/x-matroska","video_src":"/files/02%20-%20Advanced/01%20-%20Goroutines.mkv",
           "subtitle_file":"02 - Advanced/01 - Goroutines.srt","subtitle_src":"/subtitles/02%20-%20Advanced/01%20-%20Goroutines.srt",
           "text_files":[],"completed":false,"progress_seconds":125},
          {"title":"Recap","path":"/courses/Go in Depth/02 - Advanced/02 - Recap.mp3","rel_path":"02 - Advanced/02 - Recap.mp3",
           "url":"02 - Advanced/02 - Recap.mp3/Recap","lesson_type":"audio","audio_file":"02 - Advanced/02 - Recap.mp3",
           "audio_mime":"audio/mpeg","audio_src":"/files/02%20-%20Advanced/02%20-%20Recap.mp3","text_files":[],
           "completed":false,"progress_seconds":0}
         ],
         "children":[{"name":"resources","path":"/courses/Go in Depth/02 - Advanced/resources","type":"directory","has_content":true,
           "children":[],"stats":{"total_lessons":1,"completed_lessons":0,"completion_percentage":0},
           "lessons":[{"title":"cheat-sheet","path":"/x.pdf","rel_path":"02 - Advanced/resources/cheat-sheet.pdf","url":"u",
             "lesson_type":"text","text_files":["02 - Advanced/resources/cheat-sheet.pdf"],"completed":false,"progress_seconds":0}]}]}
       ]},
     "stats":{"total_lessons":5,"completed_lessons":1,"completion_percentage":20,
              "last_accessed_path":"02 - Advanced/01 - Goroutines.mkv","last_accessed_url":"02 - Advanced/01 - Goroutines.mkv/Goroutines",
              "last_accessed_title":"Goroutines"},
     "excluded":null,"recent_courses":[],"roots_display":["courses"],"needs_mount":false,"read_capability":null,
     "mount_issue":"","roots_detail":[{"path":"/courses","readable":true,"entries":3}],"mount_hint":"","dlna_enabled":true}
    """

    @Test
    fun parsesActiveCourseState() {
        val state = json.decodeFromString(StateResponse.serializer(), activeState)
        assertEquals("Go in Depth", state.course?.name)
        assertEquals(20.0, state.stats.completionPercentage, 0.0)
        val sections = CourseLogic.sections(state.tree!!)
        assertEquals(listOf("01 - Basics", "02 - Advanced", "02 - Advanced / resources"), sections.map { it.title })
        val lessons = CourseLogic.allLessons(sections)
        assertEquals(5, lessons.size)
        val goroutines = lessons[2]
        assertTrue(goroutines.isVideo)
        assertEquals("/subtitles/02%20-%20Advanced/01%20-%20Goroutines.srt", goroutines.subtitleSrc)
        val recap = lessons[3]
        assertTrue(recap.isMedia)
        assertFalse(recap.isVideo)
        assertFalse(lessons[1].isMedia)
    }

    @Test
    fun continueAndResume() {
        val state = json.decodeFromString(StateResponse.serializer(), activeState)
        val lessons = CourseLogic.allLessons(CourseLogic.sections(state.tree!!))
        val target = CourseLogic.continueTarget(lessons, state.stats.lastAccessedPath)
        assertEquals("Goroutines", target?.title)
        assertEquals(122_000L, CourseLogic.resumePositionMs(target!!))
        assertEquals(0L, CourseLogic.resumePositionMs(lessons[0])) // finished lessons restart
        // Last opened lesson finished -> next unfinished one after it.
        val next = CourseLogic.continueTarget(lessons, "01 - Basics/01 - Welcome.mp4")
        assertEquals("Notes", next?.title)
        // Nothing opened yet -> first unfinished.
        assertEquals("Notes", CourseLogic.continueTarget(lessons, null)?.title)
    }

    @Test
    fun parsesPickerState() {
        val picker = """
        {"version":"2.1.0","roots":["/courses"],"course":null,"tree":null,
         "stats":{"total_lessons":0,"completed_lessons":0,"completion_percentage":0},
         "recent_courses":[{"path":"/courses/Go in Depth","name":"Go in Depth","last_opened":"2026-10-25T10:00:00",
           "total_lessons":5,"completed_lessons":1,"display_path":"Go in Depth"},
           {"path":"/courses/Rust","name":"Rust"}],
         "roots_display":["courses"],"needs_mount":false,"read_capability":true,"mount_issue":"",
         "roots_detail":[],"mount_hint":"","dlna_enabled":true}
        """
        val state = json.decodeFromString(StateResponse.serializer(), picker)
        assertNull(state.course)
        assertNull(state.tree)
        assertEquals(2, state.recentCourses.size)
        assertEquals(0.2f, state.recentCourses[0].fraction, 0.0001f)
        assertEquals(0f, state.recentCourses[1].fraction, 0.0f)
    }

    @Test
    fun parsesBrowseLessonAndMediaStatus() {
        val browse = json.decodeFromString(
            BrowseResponse.serializer(),
            """{"current_path":"/courses","current_display_path":"courses","parent_path":null,
               "directories":[{"name":"Go in Depth","path":"/courses/Go in Depth","display_path":"Go in Depth",
               "media_files":3,"is_course_candidate":true},{"name":"Empty","path":"/courses/Empty","media_files":0,
               "is_course_candidate":false}],"roots":["/courses"],"roots_display":["courses"]}"""
        )
        assertNull(browse.parentPath)
        assertEquals(listOf(true, false), browse.directories.map { it.isCourseCandidate })

        val lesson = json.decodeFromString(
            LessonResponse.serializer(),
            """{"course":{"name":"Go in Depth","path":"/courses/Go in Depth","progress_file":"p"},
               "lesson":{"title":"cheat-sheet","path":"/x.pdf","rel_path":"02 - Advanced/resources/cheat-sheet.pdf","url":"u",
               "lesson_type":"text","text_files":["02 - Advanced/resources/cheat-sheet.pdf"],"completed":true,"progress_seconds":0},
               "resources":[{"name":"cheat-sheet.pdf","url":"02 - Advanced/resources/cheat-sheet.pdf",
               "src":"/files/02%20-%20Advanced/resources/cheat-sheet.pdf","mode":"iframe"}],
               "requested_autoplay":false,"position":4,"total":5,"dlna_enabled":true,
               "cast_plan":{"needs_transcode":false,"transcode_available":true,"reason":""}}"""
        )
        assertEquals("iframe", lesson.resources.single().mode)
        assertEquals(4, lesson.position)

        val hls = json.decodeFromString(
            MediaStatus.serializer(),
            """{"mode":"hls","ready":true,"preparing":false,"hls_url":"/api/hls/playlist.m3u8?lesson=a.wmv",
               "hls_ready":true,"duration":61.5,"video_codec":"wmv3","needs_reencode":true}"""
        )
        assertEquals("hls", hls.mode)
        assertTrue(hls.hlsReady)
    }

    @Test
    fun normalizesServerAddress() {
        assertEquals("http://192.168.1.50:30500", OfflineUApi.normalizeServerUrl(" 192.168.1.50:30500/ "))
        assertEquals("https://nas.local", OfflineUApi.normalizeServerUrl("https://nas.local"))
        assertEquals("", OfflineUApi.normalizeServerUrl("  "))
    }

    @Test
    fun formatsTimesAndEnds() {
        assertEquals("2:05", CourseLogic.formatSeconds(125))
        assertEquals("1:01:01", CourseLogic.formatSeconds(3661))
        assertTrue(CourseLogic.isNearEnd(96_000, 100_000))
        assertFalse(CourseLogic.isNearEnd(90_000, 100_000))
        assertFalse(CourseLogic.isNearEnd(5_000, 0))
    }
}
