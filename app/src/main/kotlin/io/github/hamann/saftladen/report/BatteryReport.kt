package io.github.hamann.saftladen.report

import io.hammerhead.karooext.models.SavedDevices
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

const val REPORT_SCHEMA_VERSION = 1

/** What caused a report to be taken. */
@Serializable
enum class ReportTrigger {
    /** The ride recording went from recording/paused to idle. */
    RIDE_END,

    /** The rider pressed "send test report" in the app. */
    MANUAL,

    /** The rider triggered the `report-now` bonus action from a controller. */
    BONUS_ACTION,
}

/** One battery reading: either a whole sensor, or one component of a multi-part sensor. */
@Serializable
data class SensorBattery(
    /** Karoo's id for the saved device. */
    val id: String,
    val name: String,
    /** One of `ANT_PLUS`, `BLE`, `OTHER`, `EXTENSION`. */
    val connectionType: String,
    /** Whether the sensor is currently enabled in the Karoo's sensor list. */
    val enabled: Boolean,
    /** Component name for multi-part sensors (e.g. a derailleur of a shifting group), else null. */
    val component: String? = null,
    /** `NEW`, `GOOD`, `OK`, `LOW`, `CRITICAL`, `INVALID`, or null when never reported. */
    val battery: String? = null,
    /** When [battery] was last updated, ISO-8601, or null. */
    val batteryUpdatedAt: String? = null,
    val manufacturer: String? = null,
    val serialNumber: String? = null,
    val supportedDataTypes: List<String> = emptyList(),
)

@Serializable
data class KarooDeviceInfo(
    val serial: String? = null,
    val hardwareType: String? = null,
    /** Version of this extension that produced the report. */
    val extensionVersion: String,
)

/** The JSON document that gets POSTed to the configured endpoint. */
@Serializable
data class BatteryReport(
    val reportId: String,
    val schemaVersion: Int = REPORT_SCHEMA_VERSION,
    val trigger: ReportTrigger,
    /** When the report was taken, ISO-8601. */
    val createdAt: String,
    val karoo: KarooDeviceInfo,
    val sensors: List<SensorBattery>,
)

/** JSON used both for the uploaded body and for the on-disk buffer. */
val reportJson: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Flatten Karoo's saved device list into one battery reading per device and per component.
 *
 * Devices without any battery information are kept with `battery = null` so that a
 * disappearing sensor can be told apart from one that simply never reported a level.
 */
fun buildBatteryReport(
    savedDevices: SavedDevices,
    karoo: KarooDeviceInfo,
    trigger: ReportTrigger,
    createdAt: Instant,
    includeSerialNumbers: Boolean = true,
    reportId: String = UUID.randomUUID().toString(),
): BatteryReport {
    val sensors = savedDevices.devices.flatMap { device ->
        val top = device.toSensorBattery(
            component = null,
            detail = device.details,
            includeSerialNumbers = includeSerialNumbers,
        )
        val components = device.components.orEmpty().map { (component, detail) ->
            device.toSensorBattery(
                component = component,
                detail = detail,
                includeSerialNumbers = includeSerialNumbers,
            )
        }
        listOf(top) + components.sortedBy { it.component }
    }
    return BatteryReport(
        reportId = reportId,
        trigger = trigger,
        createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt),
        karoo = karoo,
        sensors = sensors,
    )
}

private fun SavedDevices.SavedDevice.toSensorBattery(
    component: String?,
    detail: SavedDevices.SavedDevice.DeviceDetail,
    includeSerialNumbers: Boolean,
) = SensorBattery(
    id = id,
    name = name,
    connectionType = connectionType,
    enabled = enabled,
    component = component,
    battery = detail.lastBattery?.name,
    batteryUpdatedAt = detail.lastBatteryUpdate
        ?.let { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(it)) },
    manufacturer = detail.manufacturer,
    serialNumber = detail.serialNumber.takeIf { includeSerialNumbers },
    // Components share the parent's data types; only report them once.
    supportedDataTypes = if (component == null) supportedDataTypes else emptyList(),
)
