package io.github.hamann.saftladen.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hamann.saftladen.Container
import io.github.hamann.saftladen.report.BatteryReporter
import io.github.hamann.saftladen.report.ReportTrigger
import io.github.hamann.saftladen.report.ReportUploader
import io.github.hamann.saftladen.settings.DeliveryStatus
import io.github.hamann.saftladen.settings.SaftladenSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: SaftladenSettings = SaftladenSettings(),
    val status: DeliveryStatus = DeliveryStatus(),
    val pendingCount: Int = 0,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    /** Feedback for the last button press. */
    val message: String? = null,
    val unsavedChanges: Boolean = false,
)

class SettingsViewModel(private val container: Container) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed the editable form once so typing is not overwritten by the store.
            val initial = container.settings.current()
            _state.update { it.copy(settings = initial, loaded = true) }
            refreshPendingCount()
        }
        viewModelScope.launch {
            container.settings.status.collect { status ->
                _state.update { it.copy(status = status) }
            }
        }
    }

    fun edit(transform: (SaftladenSettings) -> SaftladenSettings) = _state.update {
        it.copy(settings = transform(it.settings), unsavedChanges = true, message = null)
    }

    fun save() = viewModelScope.launch {
        container.settings.save(_state.value.settings)
        _state.update { it.copy(unsavedChanges = false, message = "Settings saved") }
    }

    /** Capture the current sensor batteries and try to deliver the report right away. */
    fun sendTestReport() = runAction {
        when (val capture = container.reporter.capture(ReportTrigger.MANUAL)) {
            is BatteryReporter.Result.Skipped -> capture.reason
            is BatteryReporter.Result.Buffered ->
                "Captured ${capture.sensorCount} reading(s). ${flush()}"
        }
    }

    fun flushNow() = runAction { flush() }

    fun clearBuffer() = runAction {
        container.store.clear()
        "Buffer cleared"
    }

    private suspend fun flush(): String = when (val result = container.uploader.flush()) {
        is ReportUploader.Result.Done -> result.message
        is ReportUploader.Result.Retry -> "${result.message} – will retry later"
    }

    private fun runAction(block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        // Act on stored settings, so a pending edit in the form is applied first.
        container.settings.save(_state.value.settings)
        _state.update { it.copy(busy = true, unsavedChanges = false, message = null) }
        val message = block()
        _state.update { it.copy(busy = false, message = message) }
        refreshPendingCount()
    }

    private suspend fun refreshPendingCount() {
        val count = container.store.count()
        _state.update { it.copy(pendingCount = count) }
    }

    /** Pick up changes made outside the UI, e.g. by the retry worker. */
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(status = container.settings.status.first()) }
        refreshPendingCount()
    }
}
