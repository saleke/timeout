package com.timeout.app.enforcement

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.timeout.app.TimeOutApplication
import kotlinx.coroutines.flow.update

/** Android binds the device owner's service to keep its policy process available (API 26+).
 * No accessibility observer, overlay, or perpetual foreground-service notification is needed. */
class ProtectionService : Service() {
    private val container get() = (application as TimeOutApplication).container
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        container.managedRuntime.update { it.copy(serviceConnected = true) }
        container.managed.start()
        container.refreshSignals.tryEmit(Unit)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        container.managedRuntime.update { it.copy(serviceConnected = false) }
        super.onDestroy()
    }
}
