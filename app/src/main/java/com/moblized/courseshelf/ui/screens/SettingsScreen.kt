package com.moblized.courseshelf.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.BuildConfig
import com.moblized.courseshelf.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    val server by AppGraph.settings.serverUrl.collectAsState(initial = "")
    val mode by AppGraph.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val serverVersion by produceState<String?>(null, server) {
        value = runCatching { AppGraph.api.state().version }.getOrNull()
    }
    val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                modifier = Modifier.clickable(onClick = onChangeServer),
                colors = itemColors,
                leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                headlineContent = { Text("Course server") },
                supportingContent = { Text(server.ifBlank { "Not set" } + "\nTap to change") },
            )
            HorizontalDivider()
            Text(
                "Appearance", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
            listOf(
                ThemeMode.SYSTEM to "Follow system",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark",
            ).forEach { (option, label) ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = mode == option,
                        role = Role.RadioButton,
                        onClick = { scope.launch { AppGraph.settings.setThemeMode(option) } },
                    ),
                    colors = itemColors,
                    leadingContent = { RadioButton(selected = mode == option, onClick = null) },
                    headlineContent = { Text(label) },
                )
            }
            Text(
                "The app icon switches to its dark version when the app is dark. The change shows after you leave the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            HorizontalDivider(Modifier.padding(top = 12.dp))
            ListItem(
                colors = itemColors,
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                headlineContent = { Text("CourseShelf ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text("OfflineU server: " + (serverVersion ?: "not reachable"))
                },
            )
        }
    }
}
