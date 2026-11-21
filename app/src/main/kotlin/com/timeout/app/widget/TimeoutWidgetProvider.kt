package com.timeout.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import com.timeout.app.R
import com.timeout.app.TimeOutApplication
import com.timeout.app.data.AppCatalog
import com.timeout.app.enforcement.QuickBlockActivity
import com.timeout.core.PolicyOverview
import com.timeout.core.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class TimeoutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAsync(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        updateAsync(context, intArrayOf(appWidgetId))

    private fun updateAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val container = (context.applicationContext as TimeOutApplication).container
        container.scope.launch {
            try { render(context, ids, container.repository.snapshot().rules) } finally { pending.finish() }
        }
    }

    companion object {
        fun updateAll(context: Context, rules: List<Rule>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimeoutWidgetProvider::class.java))
            if (ids.isNotEmpty()) (context.applicationContext as TimeOutApplication).container.scope.launch { render(context, ids, rules) }
        }

        private suspend fun render(context: Context, ids: IntArray, rules: List<Rule>) = withContext(Dispatchers.Default) {
            val manager = AppWidgetManager.getInstance(context)
            val container = (context.applicationContext as TimeOutApplication).container
            val clock = container.clock()
            val policy = PolicyOverview.evaluate(rules, clock)
            val active = policy.decisions.filterValues { it.restricted }.keys.sorted()
            val nearest = policy.decisions.values.mapNotNull { it.nextAccessAt }.minOrNull()
            val ready = container.protection.disclosureAccepted() &&
                (container.protection.usesManaged() || container.protection.accessibilityEnabled()) && container.runtime.value.connected
            val status = when {
                !ready -> "Setup needed"
                active.isEmpty() -> "Ready"
                nearest != null -> "${active.size} active · until ${DateFormat.getTimeFormat(context).format(Date(nearest))}"
                else -> "${active.size} active"
            }
            val apps = if (active.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
                AppCatalog(context).load().filter { it.packageName in active }.associateBy { it.packageName }
            }
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val minimumWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val minimumHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                val icon = minimumWidth < 120 || minimumHeight < 90
                val wide = !icon && minimumWidth >= 250
                val views = RemoteViews(context.packageName, when { icon -> R.layout.widget_timeout_icon; wide -> R.layout.widget_timeout_wide; else -> R.layout.widget_timeout_compact })
                val intent = Intent(context, QuickBlockActivity::class.java).setAction("com.timeout.app.QUICK_BLOCK")
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                val click = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_action, click)
                views.setOnClickPendingIntent(R.id.widget_root, click)
                views.setContentDescription(R.id.widget_action, "Open Timeout quick block")
                if (icon) {
                    views.setImageViewResource(R.id.widget_status_dot, when { !ready -> R.drawable.widget_dot_setup; active.isNotEmpty() -> R.drawable.widget_dot_active; else -> R.drawable.widget_dot_ready })
                    views.setContentDescription(R.id.widget_root, "Timeout quick block. $status")
                } else {
                    views.setTextViewText(R.id.widget_status, status)
                    views.setTextViewText(R.id.widget_action, if (active.isEmpty()) "Pause an app" else "Pause another")
                }
                if (wide) {
                    val targets = intArrayOf(R.id.widget_app_1, R.id.widget_app_2, R.id.widget_app_3)
                    val showApps = active.isNotEmpty() && minimumWidth >= 430
                    targets.forEachIndexed { index, target ->
                        val app = active.getOrNull(index)?.let(apps::get)
                        views.setViewVisibility(target, if (!showApps || app == null) View.GONE else View.VISIBLE)
                        if (app != null) {
                            views.setImageViewBitmap(target, app.icon.asAndroidBitmap())
                            views.setContentDescription(target, "${app.label} is currently blocked")
                        }
                    }
                    views.setViewVisibility(R.id.widget_apps, if (showApps) View.VISIBLE else View.GONE)
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
