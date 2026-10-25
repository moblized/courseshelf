package com.moblized.courseshelf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class SettingsStore(private val context: Context) {

    private val keyServer = stringPreferencesKey("server_url")
    private val keyTheme = stringPreferencesKey("theme_mode")

    val serverUrl: Flow<String> = context.dataStore.data.map { it[keyServer].orEmpty() }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[keyTheme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[keyServer] = url }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[keyTheme] = mode.name }
    }
}
