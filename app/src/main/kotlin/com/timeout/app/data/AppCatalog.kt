package com.timeout.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt
import android.telecom.TelecomManager
import android.provider.AlarmClock
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.inputmethod.InputMethodManager

@Immutable
data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap)

class AppCatalog(private val context: Context) {
    fun find(packageName: String): InstalledApp? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val size = (48 * context.resources.displayMetrics.density).roundToInt().coerceIn(96, 192)
        InstalledApp(packageName, info.loadLabel(context.packageManager).toString(), info.loadIcon(context.packageManager).toBitmap(size, size).asImageBitmap())
    }.getOrNull()
    val protectedPackages: Set<String> by lazy {
        buildSet {
            add(context.packageName)
            add("android")
            add("com.android.settings")
            add("com.android.systemui")
            add("com.android.permissioncontroller")
            add("com.google.android.permissioncontroller")
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(::add)
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(::add)
            runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
            context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_ALL)
                .forEach { add(it.activityInfo.packageName) }
            context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) }
            context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { add(it.resolveInfo.serviceInfo.packageName) }
            context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().forEach { add(it.packageName) }
        }
    }

    // Called on Dispatchers.IO. Package resources and bitmap rasterization never run in a row's
    // composition/scroll path; each loaded image is reused across all screens and app stacks.
    fun load(): List<InstalledApp> {
        val iconSize = (48 * context.resources.displayMetrics.density).roundToInt().coerceIn(96, 192)
        return context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL,
    ).distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName !in protectedPackages }
        .mapNotNull { info -> runCatching {
            InstalledApp(info.activityInfo.packageName, info.loadLabel(context.packageManager).toString(),
                info.loadIcon(context.packageManager).toBitmap(iconSize, iconSize).asImageBitmap())
        }.getOrNull() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
