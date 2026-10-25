package com.moblized.courseshelf.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.data.ApiException
import com.moblized.courseshelf.data.CourseLogic
import com.moblized.courseshelf.data.DirNode
import com.moblized.courseshelf.data.Lesson
import com.moblized.courseshelf.data.Section
import com.moblized.courseshelf.data.Stats
import com.moblized.courseshelf.playback.PlaybackQueue
import com.moblized.courseshelf.playback.PlayerActivity
import com.moblized.courseshelf.ui.CenteredLoading
import com.moblized.courseshelf.ui.Load
import com.moblized.courseshelf.ui.MessageState
import com.moblized.courseshelf.ui.friendlyError
import kotlinx.coroutines.launch

private data class CourseData(
    val name: String,
    val path: String,
    val stats: Stats,
    val sections: List<Section>,
) {
    val lessons: List<Lesson> = CourseLogic.allLessons(sections)
}

private suspend fun fetchCourse(path: String): CourseData {
    val api = AppGraph.api
    api.loadCourse(path)
    val state = api.state()
    val course = state.course ?: throw ApiException(0, "The server couldn't open this course.")
    return CourseData(course.name, course.path, state.stats, CourseLogic.sections(state.tree ?: DirNode()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScreen(
    coursePath: String,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<Load<CourseData>>(Load.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadKey++ }
    LaunchedEffect(reloadKey) {
        if (reloadKey == 0) return@LaunchedEffect
        val result = runCatching { fetchCourse(coursePath) }
        state = result.fold({ Load.Ready(it) }, { error ->
            // Keep what is on screen if a background refresh fails.
            if (state is Load.Ready) { snackbar.showSnackbar(friendlyError(error)); state }
            else Load.Failed(friendlyError(error))
        })
    }

    fun open(data: CourseData, lesson: Lesson) {
        if (lesson.isMedia) {
            val entries = data.sections.flatMap { section ->
                section.lessons.filter { it.isMedia }.map { PlaybackQueue.Entry(it, section.title) }
            }
            PlaybackQueue.set(data.path, data.name, entries, lesson.relPath)
            PlayerActivity.start(context)
        } else {
            onOpenDocument(lesson.relPath)
        }
    }

    fun toggleDone(lesson: Lesson) {
        scope.launch {
            val done = !lesson.completed
            runCatching { AppGraph.api.progress(lesson.relPath, completed = done) }
                .onSuccess {
                    reloadKey++
                    snackbar.showSnackbar(if (done) "Marked as done" else "Marked as not done")
                }
                .onFailure { snackbar.showSnackbar(friendlyError(it)) }
        }
    }

    val name = (state as? Load.Ready)?.value?.name ?: coursePath.substringAfterLast('/')
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            Load.Loading -> CenteredLoading(Modifier.padding(padding))
            is Load.Failed -> MessageState(
                s.message, Modifier.padding(padding),
                actionLabel = "Try again", onAction = { state = Load.Loading; reloadKey++ },
            )
            is Load.Ready -> {
                val data = s.value
                if (data.lessons.isEmpty()) {
                    MessageState("No lessons found in this folder.", Modifier.padding(padding), icon = Icons.Filled.Description)
                } else {
                    CourseList(
                        data = data,
                        padding = padding,
                        collapsed = collapsed,
                        onOpen = { open(data, it) },
                        onToggleDone = ::toggleDone,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseList(
    data: CourseData,
    padding: PaddingValues,
    collapsed: MutableMap<String, Boolean>,
    onOpen: (Lesson) -> Unit,
    onToggleDone: (Lesson) -> Unit,
) {
    val target = CourseLogic.continueTarget(data.lessons, data.stats.lastAccessedPath)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        item { ProgressHeader(data, target, onOpen) }
        data.sections.forEach { section ->
            val isCollapsed = collapsed[section.title] == true
            stickyHeader(key = "h:" + section.title) {
                SectionHeader(section, isCollapsed) { collapsed[section.title] = !isCollapsed }
            }
            if (!isCollapsed) {
                items(section.lessons, key = { "l:" + it.relPath }) { lesson ->
                    LessonRow(
                        lesson = lesson,
                        isNext = lesson.relPath == target?.relPath,
                        onClick = { onOpen(lesson) },
                        onLongClick = { onToggleDone(lesson) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressHeader(data: CourseData, target: Lesson?, onOpen: (Lesson) -> Unit) {
    val stats = data.stats
    val total = if (stats.totalLessons > 0) stats.totalLessons else data.lessons.size
    val done = stats.completedLessons
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$done of $total lessons done", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { (stats.completionPercentage / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (target != null) {
                val label = when {
                    done == 0 && target.progressSeconds == 0 -> "Start course"
                    done >= total -> "Watch again"
                    else -> "Continue"
                }
                Button(onClick = { onOpen(target) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(
                        "  $label: ${target.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "Tip: long-press a lesson to mark it done or not done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(section: Section, collapsed: Boolean, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    section.title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${section.completed}/${section.lessons.size} done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LessonRow(lesson: Lesson, isNext: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
        colors = ListItemDefaults.colors(
            containerColor = if (isNext) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.background,
        ),
        leadingContent = {
            Icon(
                lessonIcon(lesson), contentDescription = lesson.type,
                tint = if (lesson.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(lesson.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val note = when {
                lesson.completed -> null
                lesson.isMedia && lesson.progressSeconds >= 10 ->
                    "Resume at ${CourseLogic.formatSeconds(lesson.progressSeconds)}"
                isNext -> "Up next"
                else -> null
            }
            if (note != null) Text(note)
        },
        trailingContent = {
            if (lesson.completed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

private fun lessonIcon(lesson: Lesson): ImageVector = when {
    lesson.isVideo -> Icons.Filled.PlayCircle
    lesson.isMedia -> Icons.Filled.Headphones
    lesson.type == "quiz" -> Icons.Filled.Quiz
    lesson.type == "image" -> Icons.Filled.Image
    lesson.textFiles.any { it.endsWith(".pdf", ignoreCase = true) } -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.Description
}
