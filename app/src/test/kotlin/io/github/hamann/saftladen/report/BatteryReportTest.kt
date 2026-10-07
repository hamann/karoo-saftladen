package io.github.hamann.saftladen.report

import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.SavedDevices.SavedDevice
import io.hammerhead.karooext.models.SavedDevices.SavedDevice.DeviceDetail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BatteryReportTest {
    private val karoo = KarooDeviceInfo(
        serial = "K1234",
        hardwareType = "KAROO",
        extensionVersion = "1.0.0",
        batteryPercent = 43,
        battery = "OK",
    )

    private fun detail(
        battery: BatteryStatus? = BatteryStatus.GOOD,
        updatedAt: Long? = 1_700_000_000_000,
        manufacturer: String? = "SRAM",
        serialNumber: String? = "SN-1",
    ) = DeviceDetail(
        lastBattery = battery,
        lastBatteryUpdate = updatedAt,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
    )

    private fun device(
        id: String = "ANT_PLUS-1234",
        name: String = "Power Meter",
        connectionType: String = "ANT_PLUS",
        enabled: Boolean = true,
        details: DeviceDetail = detail(),
        components: Map<String, DeviceDetail>? = null,
        supportedDataTypes: List<String> = listOf("POWER", "CADENCE"),
    ) = SavedDevice(
        id = id,
        connectionType = connectionType,
        name = name,
        enabled = enabled,
        details = details,
        components = components,
        supportedDataTypes = supportedDataTypes,
        gearInfo = null,
    )

    private fun report(
        vararg devices: SavedDevice,
        includeSerialNumbers: Boolean = true,
    ) = buildBatteryReport(
        savedDevices = SavedDevices(devices.toList()),
        karoo = karoo,
        trigger = ReportTrigger.RIDE_END,
        createdAt = Instant.parse("2026-10-07T12:00:00Z"),
        includeSerialNumbers = includeSerialNumbers,
        reportId = "report-1",
    )

    @Test
    fun `reports one sensor per saved device`() {
        val result = report(device())

        assertEquals(1, result.sensors.size)
        val sensor = result.sensors.single()
        assertEquals("ANT_PLUS-1234", sensor.id)
        assertEquals("Power Meter", sensor.name)
        assertEquals("GOOD", sensor.battery)
        assertEquals("2023-11-14T22:13:20Z", sensor.batteryUpdatedAt)
        assertEquals(listOf("POWER", "CADENCE"), sensor.supportedDataTypes)
        assertNull(sensor.component)
    }

    @Test
    fun `reports each component of a multi-part sensor`() {
        val result = report(
            device(
                id = "BLE-axs",
                name = "AXS Group",
                connectionType = "BLE",
                components = mapOf(
                    "rear_derailleur" to detail(battery = BatteryStatus.LOW, serialNumber = "SN-R"),
                    "front_derailleur" to detail(battery = BatteryStatus.NEW, serialNumber = "SN-F"),
                ),
            ),
        )

        // Parent first, then components in a stable order.
        assertEquals(
            listOf(null, "front_derailleur", "rear_derailleur"),
            result.sensors.map { it.component },
        )
        assertEquals(listOf("GOOD", "NEW", "LOW"), result.sensors.map { it.battery })
        // Only the parent carries the data types.
        assertEquals(
            listOf(emptyList<String>(), emptyList<String>()),
            result.sensors.drop(1).map { it.supportedDataTypes },
        )
        assertTrue(result.sensors.all { it.id == "BLE-axs" })
    }

    @Test
    fun `keeps devices that never reported a battery level`() {
        val result = report(device(details = detail(battery = null, updatedAt = null)))

        val sensor = result.sensors.single()
        assertNull(sensor.battery)
        assertNull(sensor.batteryUpdatedAt)
    }

    @Test
    fun `omits serial numbers when the rider opted out`() {
        val result = report(
            device(components = mapOf("rear_derailleur" to detail())),
            includeSerialNumbers = false,
        )

        assertTrue(result.sensors.all { it.serialNumber == null })
        // Manufacturer is not considered identifying and stays.
        assertTrue(result.sensors.all { it.manufacturer == "SRAM" })
    }

    @Test
    fun `serializes to the documented json shape`() {
        val json = reportJson.encodeToString(report(device(enabled = false)))
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals("report-1", root["reportId"]?.jsonPrimitive?.content)
        assertEquals(REPORT_SCHEMA_VERSION, root["schemaVersion"]?.jsonPrimitive?.content?.toInt())
        assertEquals("RIDE_END", root["trigger"]?.jsonPrimitive?.content)
        assertEquals("2026-10-07T12:00:00Z", root["createdAt"]?.jsonPrimitive?.content)
        val karooBlock = root["karoo"]?.jsonObject
        assertEquals("K1234", karooBlock?.get("serial")?.jsonPrimitive?.content)
        assertEquals(43, karooBlock?.get("batteryPercent")?.jsonPrimitive?.content?.toInt())
        assertEquals("OK", karooBlock?.get("battery")?.jsonPrimitive?.content)

        val sensor = root["sensors"]?.jsonArray?.single()?.jsonObject
        assertEquals(false, sensor?.get("enabled")?.jsonPrimitive?.content?.toBoolean())
        assertEquals("GOOD", sensor?.get("battery")?.jsonPrimitive?.content)
    }
}
