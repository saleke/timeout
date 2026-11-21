package com.timeout.app.widget

import com.timeout.app.AppContainer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TimeoutWidgetController(private val container: AppContainer) {
    private val started = AtomicBoolean()
    fun start() {
        if (!started.compareAndSet(false, true)) return
        container.scope.launch {
            container.repository.state.catch { /* next database emission retries */ }.collect { state ->
                TimeoutWidgetProvider.updateAll(container.application, state.rules)
            }
        }
    }
}
