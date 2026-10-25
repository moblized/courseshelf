package com.moblized.courseshelf.data

/** One folder of a course shown as a list section. */
data class Section(val title: String, val lessons: List<Lesson>) {
    val completed: Int get() = lessons.count { it.completed }
}

object CourseLogic {

    /** Folder tree -> flat, ordered sections ("Section 2 / resources" for nested folders). */
    fun sections(root: DirNode): List<Section> {
        val out = mutableListOf<Section>()
        if (root.lessons.isNotEmpty()) out += Section("Lessons", root.lessons)
        fun walk(node: DirNode, prefix: String) {
            for (child in node.children) {
                val title = if (prefix.isEmpty()) child.name else "$prefix / ${child.name}"
                if (child.lessons.isNotEmpty()) out += Section(title, child.lessons)
                walk(child, title)
            }
        }
        walk(root, "")
        return out
    }

    fun allLessons(sections: List<Section>): List<Lesson> = sections.flatMap { it.lessons }

    /** Where "Continue" should go: the last opened lesson if unfinished, else the next unfinished one. */
    fun continueTarget(lessons: List<Lesson>, lastAccessedPath: String?): Lesson? {
        if (lessons.isEmpty()) return null
        val lastIndex = lessons.indexOfFirst { it.relPath == lastAccessedPath }
        if (lastIndex >= 0) {
            val last = lessons[lastIndex]
            if (!last.completed) return last
            lessons.drop(lastIndex + 1).firstOrNull { !it.completed }?.let { return it }
        }
        return lessons.firstOrNull { !it.completed } ?: lessons.first()
    }

    /** Resume a few seconds early so the viewer catches the context; restart finished lessons. */
    fun resumePositionMs(lesson: Lesson): Long =
        if (lesson.completed || lesson.progressSeconds < 10) 0L
        else (lesson.progressSeconds - 3).toLong() * 1000L

    fun isNearEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * 0.95).toLong()

    fun formatSeconds(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
