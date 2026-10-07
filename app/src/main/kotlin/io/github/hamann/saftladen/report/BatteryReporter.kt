package io.github.hamann.saftladen.report

import io.github.hamann.saftladen.karoo.connectAndAwait
import io.github.hamann.saftladen.karoo.consumerFlow
import io.github.hamann.saftladen.karoo.streamDataFlow
import io.github.hamann.saftladen.settings.SettingsRepository
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Takes a snapshot of every saved sensor's battery status and buffers it for upload.
 *
 * The battery levels come from the Karoo's own saved device list ([SavedDevices]), which
 * is where it records the last reading of each sensor, so the snapshot is available even
 * for sensors that disconnected during the ride.
 */
class BatteryReporter(
    private val store: ReportStore,
    private val settingsRepository: SettingsRepository,
    private val extensionVersion: String,
    private val karooSystemFactory: () -> KarooSystemService,
) {
    sealed interface Result {
        /** Report written to the buffer. */
        data class Buffered(val reportId: String, val sensorCount: Int) : Result

        /** Nothing was buffered; [reason] is suitable for the UI and the log. */
        data class Skipped(val reason: String) : Result
    }

    /**
     * Capture using an existing, already connected Karoo System binding.
     *
     * [fallback] is used if Karoo does not answer with a device list in time — the
     * extension keeps the list it saw during the ride for exactly that case.
     */
    suspend fun capture(
        karooSystem: KarooSystemService,
        trigger: ReportTrigger,
        fallback: SavedDevices? = null,
    ): Result {
        val settings = settingsRepository.current()
        if (!settings.enabled && trigger == ReportTrigger.RIDE_END) {
            return Result.Skipped("Reporting is switched off")
        }

        val savedDevices = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            karooSystem.consumerFlow<SavedDevices>().first()
        }
            ?: fallback
            ?: return Result.Skipped("Karoo did not report its sensor list in time")

        val report = buildBatteryReport(
            savedDevices = savedDevices,
            karoo = karooSystem.deviceInfo(),
            trigger = trigger,
            createdAt = Instant.now(),
            includeSerialNumbers = settings.includeSerialNumbers,
        )

        val entryId = store.enqueue(report)
            ?: return Result.Skipped("Could not write the report to the buffer")

        Timber.i(
            "Buffered %s report %s with %d sensor readings",
            trigger,
            entryId,
            report.sensors.size,
        )
        return Result.Buffered(report.reportId, report.sensors.size)
    }

    /** Capture with a Karoo System binding of our own, for callers outside the extension. */
    suspend fun capture(trigger: ReportTrigger): Result {
        val karooSystem = karooSystemFactory()
        if (!karooSystem.connectAndAwait()) {
            return Result.Skipped("Karoo System is not available")
        }
        return try {
            capture(karooSystem, trigger)
        } finally {
            karooSystem.disconnect()
        }
    }

    /**
     * Identity and charge of the head unit itself.
     *
     * The charge is streamed rather than read once, so a missing or slow stream degrades
     * to a null battery instead of holding up the whole report.
     */
    private suspend fun KarooSystemService.deviceInfo(): KarooDeviceInfo {
        val percent = withTimeoutOrNull(BATTERY_TIMEOUT_MS) {
            streamDataFlow(DataType.Type.BATTERY_PERCENT)
                .filterIsInstance<StreamState.Streaming>()
                .first()
                .dataPoint
                .values[DataType.Field.BATTERY_PERCENT]
        }?.roundToInt()

        if (percent == null) {
            Timber.w("Karoo did not report its own battery level")
        }

        return KarooDeviceInfo(
            serial = serial,
            hardwareType = hardwareType?.name,
            extensionVersion = extensionVersion,
            batteryPercent = percent,
            battery = percent?.let { BatteryStatus.fromPercentage(it).name },
        )
    }

    private companion object {
        const val SNAPSHOT_TIMEOUT_MS = 10_000L
        const val BATTERY_TIMEOUT_MS = 5_000L
    }
}
