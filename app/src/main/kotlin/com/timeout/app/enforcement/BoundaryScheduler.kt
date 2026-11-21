package com.timeout.app.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timeout.app.TimeOutApplication
import com.timeout.core.ClockSnapshot
import com.timeout.core.Rule
import com.timeout.core.RuleEngine
import kotlinx.coroutines.launch

object BoundaryScheduler {
    const val ACTION_BOUNDARY = "com.timeout.app.action.BOUNDARY"
    fun exactAccess(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    fun schedule(context: Context, rules: List<Rule>, clock: ClockSnapshot): Boolean =
        scheduleAt(context, RuleEngine.nextBoundary(rules, clock), clock)

    fun scheduleAt(context: Context, next: Long?, clock: ClockSnapshot): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 100, Intent(context, BoundaryReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (next == null) { alarm.cancel(pending); return exactAccess(context) }
        val trigger = clock.elapsedTime + (next - clock.wallTime).coerceAtLeast(1)
        if (exactAccess(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
                return true
            } catch (_: SecurityException) { /* Keep a best-effort release callback if access changes. */ }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
        return false
    }
}

class BoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(BoundaryScheduler.ACTION_BOUNDARY, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                android.app.admin.DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED)) return
        val pending = goAsync()
        val container = (context.applicationContext as TimeOutApplication).container
        container.scope.launch {
            try {
                container.protection.reconcile()
            } finally { pending.finish() }
        }
    }
}
