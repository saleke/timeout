package com.timeout.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

object WidgetPinning {
    fun count(context: Context): Int = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, TimeoutWidgetProvider::class.java)).size

    fun request(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, TimeoutWidgetProvider::class.java), null, null)
    }
}
