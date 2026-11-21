package com.timeout.app

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import com.timeout.app.data.RuleRepository
import com.timeout.app.data.TimeOutDatabase
import com.timeout.app.data.DraftCheckpointStore
import com.timeout.app.data.PreferencesDraftCheckpoint
import com.timeout.app.enforcement.ManagedProtectionController
import com.timeout.app.enforcement.ProtectionCoordinator
import com.timeout.app.enforcement.BlockingState
import com.timeout.app.widget.TimeoutWidgetController
import com.timeout.core.ClockSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.core.content.edit

class TimeOutApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        container.protection.start()
        container.widgets.start()
    }
}

class AppContainer(val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = Room.databaseBuilder(application, TimeOutDatabase::class.java, "timeout.db").build()
    val repository = RuleRepository(database)
    val preferences = application.getSharedPreferences("preferences", Application.MODE_PRIVATE)
    val draftStore = DraftCheckpointStore(PreferencesDraftCheckpoint(application.getSharedPreferences("editor_draft", Application.MODE_PRIVATE)))
    private val _appearance = MutableStateFlow(preferences.getString("appearance", "system") ?: "system")
    val appearance = _appearance.asStateFlow()
    private val _reducedMotion = MutableStateFlow(preferences.getBoolean("reduced_motion", false))
    val reducedMotion = _reducedMotion.asStateFlow()
    val runtime = MutableStateFlow(ProtectionRuntime())
    val standardRuntime = MutableStateFlow(ProtectionRuntime())
    val managedRuntime = MutableStateFlow(ProtectionRuntime(mode = ProtectionMode.MANAGED))
    val blockingState = MutableStateFlow<BlockingState?>(null)
    val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val currentBootCount = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
    val managed = ManagedProtectionController(this)
    val protection = ProtectionCoordinator(this)
    val widgets = TimeoutWidgetController(this)

    fun clock() = ClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        currentBootCount)

    fun setAppearance(value: String) {
        preferences.edit { putString("appearance", value) }
        _appearance.value = value
    }

    fun setReducedMotion(value: Boolean) {
        preferences.edit { putBoolean("reduced_motion", value) }
        _reducedMotion.value = value
    }
}

data class ProtectionRuntime(
    val connected: Boolean = false,
    val configurationLoaded: Boolean = false,
    val checkedAt: Long = 0,
    val error: String? = null,
    val configuredKeys: Set<String> = emptySet(),
    val serviceConnected: Boolean = false,
    val exactAlarms: Boolean = false,
    val suspendedPackages: Set<String> = emptySet(),
    val releasePending: Set<String> = emptySet(),
    val mode: ProtectionMode = ProtectionMode.STANDARD,
    val blockedPackage: String? = null,
)

enum class ProtectionMode { STANDARD, MANAGED }
