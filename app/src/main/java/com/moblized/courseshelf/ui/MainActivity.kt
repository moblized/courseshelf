package com.moblized.courseshelf.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moblized.courseshelf.AppGraph
import com.moblized.courseshelf.data.ThemeMode
import com.moblized.courseshelf.ui.screens.CourseScreen
import com.moblized.courseshelf.ui.screens.DocumentScreen
import com.moblized.courseshelf.ui.screens.LibraryScreen
import com.moblized.courseshelf.ui.screens.SettingsScreen
import com.moblized.courseshelf.ui.screens.SetupScreen
import com.moblized.courseshelf.ui.theme.CourseShelfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by AppGraph.settings.themeMode.collectAsState(initial = null)
            val server by AppGraph.settings.serverUrl.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> systemDark
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CourseShelfTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val url = server
                    if (mode == null || url == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        AppNav(url)
                    }
                }
            }
        }
    }
}

object Routes {
    const val SETUP = "setup"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
    fun course(path: String) = "course/${Uri.encode(path)}"
    fun document(relPath: String) = "doc/${Uri.encode(relPath)}"
}

@Composable
private fun AppNav(serverUrl: String) {
    // Set before any screen loads so the very first request has an address.
    remember(serverUrl) { AppGraph.api.baseUrl = serverUrl; serverUrl }
    val nav = rememberNavController()
    // Decided once: changing the start destination later would reset the back stack.
    val start = remember { if (serverUrl.isBlank()) Routes.SETUP else Routes.LIBRARY }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SETUP) {
            SetupScreen(
                initialUrl = serverUrl,
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onConnected = {
                    nav.navigate(Routes.LIBRARY) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                folderPath = null,
                onOpenCourse = { nav.navigate(Routes.course(it)) },
                onOpenFolder = { nav.navigate(Routes.folder(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onBack = null,
            )
        }
        composable(
            "folder/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            LibraryScreen(
                folderPath = path,
                onOpenCourse = { nav.navigate(Routes.course(it)) },
                onOpenFolder = { nav.navigate(Routes.folder(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "course/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
            CourseScreen(
                coursePath = path,
                onBack = { nav.popBackStack() },
                onOpenDocument = { nav.navigate(Routes.document(it)) },
            )
        }
        composable(
            "doc/{rel}",
            arguments = listOf(navArgument("rel") { type = NavType.StringType }),
        ) { entry ->
            val rel = Uri.decode(entry.arguments?.getString("rel").orEmpty())
            DocumentScreen(relPath = rel, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onChangeServer = { nav.navigate(Routes.SETUP) },
            )
        }
    }
}
