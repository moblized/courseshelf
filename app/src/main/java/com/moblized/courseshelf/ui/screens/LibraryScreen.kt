package com.moblized.courseshelf.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.data.BrowseEntry
import com.moblized.courseshelf.data.RecentCourse
import com.moblized.courseshelf.playback.PlaybackQueue
import com.moblized.courseshelf.ui.CenteredLoading
import com.moblized.courseshelf.ui.Load
import com.moblized.courseshelf.ui.MessageState
import com.moblized.courseshelf.ui.friendlyError

private data class LibraryData(
    val title: String?,
    val recent: List<RecentCourse>,
    val folders: List<BrowseEntry>,
)

/** Last known course progress; reused when the server can't be asked without disturbing playback. */
private object RecentCache {
    @Volatile var courses: List<RecentCourse> = emptyList()
}

private suspend fun loadLibrary(folderPath: String?): LibraryData {
    val api = AppGraph.api
    if (folderPath == null && !PlaybackQueue.serviceActive) {
        // The server only lists remembered courses (with progress) when no course is active.
        runCatching {
            api.resetCourse()
            RecentCache.courses = api.state().recentCourses
        }
    }
    val listing = api.browse(folderPath)
    val title = if (folderPath == null) null
    else listing.currentDisplayPath ?: folderPath.substringAfterLast('/')
    return LibraryData(title, RecentCache.courses, listing.directories)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    folderPath: String?,
    onOpenCourse: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var state by remember { mutableStateOf<Load<LibraryData>>(Load.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadKey++ }
    LaunchedEffect(reloadKey) {
        if (reloadKey == 0) return@LaunchedEffect
        state = runCatching { loadLibrary(folderPath) }
            .fold({ Load.Ready(it) }, { Load.Failed(friendlyError(it)) })
        refreshing = false
    }

    val title = (state as? Load.Ready)?.value?.title
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: "CourseShelf", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onBack == null) IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reloadKey++ },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val s = state) {
                Load.Loading -> CenteredLoading()
                is Load.Failed -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        MessageState(
                            s.message, Modifier.fillParentMaxSize(),
                            actionLabel = "Try again", onAction = { state = Load.Loading; reloadKey++ },
                        )
                    }
                }
                is Load.Ready -> LibraryList(
                    data = s.value,
                    isRoot = folderPath == null,
                    onOpenCourse = onOpenCourse,
                    onOpenFolder = onOpenFolder,
                )
            }
        }
    }
}

@Composable
private fun LibraryList(
    data: LibraryData,
    isRoot: Boolean,
    onOpenCourse: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val progressByPath = data.recent.associateBy { it.path }
    val continueCourses = if (isRoot) {
        data.recent.filter { it.totalLessons > 0 && it.completedLessons < it.totalLessons }.take(6)
    } else emptyList()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (continueCourses.isNotEmpty()) {
            item { SectionLabel("Continue learning") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(continueCourses, key = { it.path }) { course ->
                        ContinueCard(course) { onOpenCourse(course.path) }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        if (data.folders.isEmpty()) {
            item {
                MessageState(
                    if (isRoot) "No courses yet. Copy course folders into your courses dataset on TrueNAS, then pull down to refresh."
                    else "This folder is empty.",
                    Modifier.fillParentMaxSize(),
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                )
            }
        } else {
            item { SectionLabel(if (isRoot) "All courses" else "Courses") }
            items(data.folders, key = { it.path }) { folder ->
                FolderRow(
                    folder = folder,
                    progress = progressByPath[folder.path],
                    onOpen = { if (folder.isCourseCandidate) onOpenCourse(folder.path) else onOpenFolder(folder.path) },
                    onBrowse = { onOpenFolder(folder.path) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ContinueCard(course: RecentCourse, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                course.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(progress = { course.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${course.completedLessons} of ${course.totalLessons} lessons",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: BrowseEntry,
    progress: RecentCourse?,
    onOpen: () -> Unit,
    onBrowse: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = {
            Icon(
                if (folder.isCourseCandidate) Icons.Filled.School else Icons.Filled.FolderOpen,
                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val files = when {
                    folder.mediaFiles == 1 -> "1 video or audio file"
                    folder.mediaFiles > 0 -> "${folder.mediaFiles} video & audio files"
                    else -> "Folder"
                }
                Text(
                    if (progress != null && progress.totalLessons > 0)
                        "$files · ${progress.completedLessons}/${progress.totalLessons} done"
                    else files
                )
                if (progress != null && progress.totalLessons > 0) {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(0.6f))
                }
            }
        },
        trailingContent = {
            if (folder.isCourseCandidate) IconButton(onClick = onBrowse) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Browse inside ${folder.name}")
            }
        },
    )
}
