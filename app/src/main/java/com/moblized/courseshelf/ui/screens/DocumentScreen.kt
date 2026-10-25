package com.moblized.courseshelf.ui.screens

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.data.LessonResponse
import com.moblized.courseshelf.data.TextResource
import com.moblized.courseshelf.ui.CenteredLoading
import com.moblized.courseshelf.ui.Load
import com.moblized.courseshelf.ui.MessageState
import com.moblized.courseshelf.ui.friendlyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Non-video lessons: text, markdown, PDF, HTML/quiz, Word documents and images. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(relPath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Load<LessonResponse>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        // Opening the lesson through the API also marks it done on the server.
        state = runCatching { AppGraph.api.lesson(relPath) }
            .fold({ Load.Ready(it) }, { Load.Failed(friendlyError(it)) })
    }

    val ready = (state as? Load.Ready)?.value
    val resource = ready?.resources?.getOrNull(selected)
    val title = ready?.lesson?.title ?: relPath.substringAfterLast('/')

    fun openExternally(src: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppGraph.api.absolute(src))))
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val external = resource?.src ?: ready?.lesson?.imageSrc
                    if (external != null) IconButton(onClick = { openExternally(external) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in another app")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                Load.Loading -> CenteredLoading()
                is Load.Failed -> MessageState(s.message, actionLabel = "Try again", onAction = { state = Load.Loading; attempt++ })
                is Load.Ready -> {
                    val data = s.value
                    val imageSrc = data.lesson.imageSrc
                    Column(Modifier.fillMaxSize()) {
                        if (data.resources.size > 1) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                data.resources.forEachIndexed { index, r ->
                                    FilterChip(selected = index == selected, onClick = { selected = index }, label = { Text(r.name) })
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when {
                                resource != null -> ResourceView(resource, onOpenExternally = { openExternally(it) })
                                imageSrc != null -> WebPage(imageHtml(AppGraph.api.absolute(imageSrc)), isHtml = true)
                                else -> MessageState("Nothing to show for this lesson.", icon = Icons.Filled.Description)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceView(resource: TextResource, onOpenExternally: (String) -> Unit) {
    val api = AppGraph.api
    val isPdf = resource.name.endsWith(".pdf", ignoreCase = true)
    when {
        resource.mode == "text" || resource.mode == "markdown" -> TextDocument(resource.src, markdown = resource.mode == "markdown")
        resource.mode == "iframe" && isPdf -> PdfDocument(resource.src)
        resource.mode == "iframe" -> WebPage(api.absolute(resource.src))
        resource.mode == "office" -> WebPage(api.previewUrl(resource.url))
        else -> MessageState(
            "This file type can't be shown here.",
            icon = Icons.Filled.Description,
            actionLabel = "Open in another app",
            onAction = { onOpenExternally(resource.src) },
        )
    }
}

@Composable
private fun TextDocument(src: String, markdown: Boolean) {
    val text by produceState<Load<String>>(Load.Loading, src) {
        value = runCatching { AppGraph.api.text(src) }.fold({ Load.Ready(it) }, { Load.Failed(friendlyError(it)) })
    }
    when (val t = text) {
        Load.Loading -> CenteredLoading()
        is Load.Failed -> MessageState(t.message)
        is Load.Ready -> SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (markdown) MarkdownLines(t.value)
                else Text(t.value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A deliberately small Markdown renderer: headings, bullets, code blocks and paragraphs. */
@Composable
private fun MarkdownLines(source: String) {
    var inCode = false
    source.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.trimStart().startsWith("```") -> inCode = !inCode
            inCode -> Text(
                line, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp),
            )
            line.startsWith("### ") -> Text(line.removePrefix("### "), style = MaterialTheme.typography.titleMedium)
            line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleLarge)
            line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineSmall)
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                Text("•  " + line.trimStart().drop(2), style = MaterialTheme.typography.bodyLarge)
            line.isBlank() -> Text("")
            else -> Text(
                line.replace("**", ""), style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (line.startsWith("**") && line.endsWith("**")) FontWeight.SemiBold else null,
            )
        }
    }
}

private fun imageHtml(url: String) =
    "<html><body style=\"margin:0;background:#111;display:flex;align-items:center;justify-content:center;min-height:100vh\">" +
        "<img src=\"$url\" style=\"max-width:100%;height:auto\"></body></html>"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPage(content: String, isHtml: Boolean = false) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true // course quizzes are small HTML apps
                settings.domStorageEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = WebViewClient()
                if (isHtml) loadDataWithBaseURL(null, content, "text/html", "utf-8", null) else loadUrl(content)
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
private fun PdfDocument(src: String) {
    val context = LocalContext.current
    val file by produceState<Load<File>>(Load.Loading, src) {
        value = runCatching {
            val target = File(context.cacheDir, "pdf/${src.hashCode().toUInt()}.pdf")
            if (!target.exists()) AppGraph.api.download(src, target)
            target
        }.fold({ Load.Ready(it) }, { Load.Failed(friendlyError(it)) })
    }
    when (val f = file) {
        Load.Loading -> CenteredLoading()
        is Load.Failed -> MessageState(f.message)
        is Load.Ready -> PdfPages(f.value)
    }
}

@Composable
private fun PdfPages(file: File) {
    val holder = remember(file) {
        runCatching {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor to PdfRenderer(descriptor)
        }.getOrNull()
    }
    DisposableEffect(holder) {
        onDispose {
            holder?.let { (descriptor, renderer) ->
                synchronized(renderer) { renderer.close() } // waits for a page that is still rendering
                descriptor.close()
            }
        }
    }
    if (holder == null) {
        file.delete()
        MessageState("This PDF couldn't be opened.")
        return
    }
    val renderer = holder.second
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(renderer.pageCount) { index ->
            val bitmap by produceState<Bitmap?>(null, index) {
                value = withContext(Dispatchers.IO) {
                    synchronized(renderer) {
                        runCatching {
                            renderer.openPage(index).use { page ->
                                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bmp ->
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(androidx.compose.ui.graphics.Color.White))
            }
        }
    }
}
