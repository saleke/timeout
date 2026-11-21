package com.timeout.app.enforcement

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast

object AccessibilitySetupLauncher {
    private const val DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val HIGHLIGHT_KEY = ":settings:fragment_args_key"

    /** Opens the service itself where Android exposes that route; otherwise opens and identifies the list row. */
    fun open(context: Context): Boolean {
        val component = ComponentName(context, AppBlockingService::class.java).flattenToString()
        val details = Intent(DETAILS_ACTION).putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        if (context.packageManager.resolveActivity(details, PackageManager.MATCH_DEFAULT_ONLY) != null &&
            runCatching { context.startActivity(details); true }.getOrDefault(false)) return true

        Toast.makeText(context, "Select ‘Timeout app protection’", Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(HIGHLIGHT_KEY, component))
        return false
    }
}
