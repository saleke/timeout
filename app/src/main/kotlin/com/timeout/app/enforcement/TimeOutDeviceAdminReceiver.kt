package com.timeout.app.enforcement

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.timeout.app.TimeOutApplication

class TimeOutDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = refresh(context)
    override fun onDisabled(context: Context, intent: Intent) = refresh(context)
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) = refresh(context)

    private fun refresh(context: Context) {
        (context.applicationContext as TimeOutApplication).container.refreshSignals.tryEmit(Unit)
    }
}
