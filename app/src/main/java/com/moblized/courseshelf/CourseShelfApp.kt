package com.moblized.courseshelf

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.moblized.courseshelf.data.OfflineUApi
import com.moblized.courseshelf.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CourseShelfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Swap the launcher icon (light/dark) while the app is in the background, so the
        // running task is never affected by the alias change.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppGraph.appScope.launch {
                    IconSwitcher.sync(this@CourseShelfApp, AppGraph.settings.themeMode.first())
                }
            }
        })
    }
}

/** Tiny manual dependency container; everything lives for the whole process. */
object AppGraph {
    lateinit var settings: SettingsStore
        private set
    lateinit var api: OfflineUApi
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun init(context: Context) {
        settings = SettingsStore(context.applicationContext)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        api = OfflineUApi(client, json)
        appScope.launch { settings.serverUrl.collect { api.baseUrl = it } }
    }
}
