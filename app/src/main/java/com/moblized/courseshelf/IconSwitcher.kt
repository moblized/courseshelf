package com.moblized.courseshelf

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.moblized.courseshelf.data.ThemeMode

/**
 * Shows the dark launcher icon whenever the app is in dark mode (chosen in Settings,
 * or following the system). Two activity-aliases in the manifest carry the two icons.
 */
object IconSwitcher {

    private const val LIGHT_ALIAS = "com.moblized.courseshelf.LauncherLight"
    private const val DARK_ALIAS = "com.moblized.courseshelf.LauncherDark"

    fun isDark(context: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun sync(context: Context, mode: ThemeMode) {
        val dark = isDark(context, mode)
        val pm = context.packageManager
        val light = ComponentName(context.packageName, LIGHT_ALIAS)
        val darkAlias = ComponentName(context.packageName, DARK_ALIAS)
        val wantOn = if (dark) darkAlias else light
        val wantOff = if (dark) light else darkAlias
        val onDefault = wantOn == light // the light alias is enabled in the manifest
        if (isEnabled(pm, wantOn, onDefault) && !isEnabled(pm, wantOff, !onDefault)) return
        pm.setComponentEnabledSetting(
            wantOn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            wantOff, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
    }

    private fun isEnabled(pm: PackageManager, component: ComponentName, manifestDefault: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestDefault
            else -> false
        }
}
