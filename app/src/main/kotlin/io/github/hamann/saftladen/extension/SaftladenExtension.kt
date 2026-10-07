package io.github.hamann.saftladen.extension

import io.github.hamann.saftladen.BuildConfig
import io.github.hamann.saftladen.container
import io.github.hamann.saftladen.karoo.consumerFlow
import io.github.hamann.saftladen.report.BatteryReporter
import io.github.hamann.saftladen.report.ReportFlushWorker
import io.github.hamann.saftladen.report.ReportTrigger
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Watches ride recording and reports sensor batteries once the ride is over.
 *
 * The Karoo System binds this service while the extension is installed and enabled, which
 * is what keeps the ride state subscription alive across a whole ride.
 */
class SaftladenExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var karooSystem: KarooSystemService

    /**
     * The sensor list as last seen during the ride.
     *
     * Used if Karoo does not answer with a fresh list once recording has stopped.
     */
    private val lastSavedDevices = MutableStateFlow<SavedDevices?>(null)

    private val reporter: BatteryReporter
        get() = container.reporter

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            Timber.i("Karoo System connected=%s", connected)
        }
        scope.launch { watchRideState() }
        scope.launch {
            karooSystem.consumerFlow<SavedDevices>().collect { lastSavedDevices.value = it }
        }
        // A ride may have ended while the extension was not running, or the upload may
        // have failed last time around.
        ReportFlushWorker.flushNow(applicationContext)
    }

    /**
     * Report when recording stops.
     *
     * Karoo goes to [RideState.Idle] both when a ride is saved and when it is discarded,
     * and also reports `Idle` on subscribe, so only a recording-to-idle transition counts.
     */
    private suspend fun watchRideState() {
        var previous: RideState? = null
        karooSystem.consumerFlow<RideState>()
            .distinctUntilChanged()
            .collect { state ->
                val wasRiding = previous is RideState.Recording || previous is RideState.Paused
                previous = state
                Timber.d("Ride state: %s", state)
                if (state is RideState.Idle && wasRiding) {
                    report(ReportTrigger.RIDE_END)
                }
            }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_REPORT_NOW) {
            Timber.w("Unknown bonus action %s", actionId)
            return
        }
        scope.launch { report(ReportTrigger.BONUS_ACTION) }
    }

    private suspend fun report(trigger: ReportTrigger) {
        when (val result = reporter.capture(karooSystem, trigger, lastSavedDevices.value)) {
            is BatteryReporter.Result.Buffered -> {
                if (trigger == ReportTrigger.BONUS_ACTION) {
                    notify("Battery report queued", "${result.sensorCount} sensor reading(s)")
                }
                ReportFlushWorker.flushNow(applicationContext)
            }

            is BatteryReporter.Result.Skipped -> {
                Timber.w("No %s report: %s", trigger, result.reason)
                if (trigger != ReportTrigger.RIDE_END) {
                    notify("No battery report", result.reason)
                }
            }
        }
    }

    private fun notify(message: String, subText: String) {
        karooSystem.dispatch(
            SystemNotification(
                id = "saftladen-report",
                message = message,
                subText = subText,
                style = SystemNotification.Style.EVENT,
                action = "Open",
                actionIntent = "io.github.hamann.saftladen.MAIN",
            ),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    private companion object {
        /** Must match the `id` in `res/xml/extension_info.xml`. */
        const val EXTENSION_ID = "saftladen"

        /** Must match the `actionId` in `res/xml/extension_info.xml`. */
        const val ACTION_REPORT_NOW = "report-now"
    }
}
