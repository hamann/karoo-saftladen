# The battery report

What Saftladen POSTs, and what an endpoint is expected to do with it. This stands on its
own: nothing here depends on the extension's internals, so it is enough to write a
receiver against.

---

## The request

`POST` to the configured URL, `Content-Type: application/json`. One optional extra header
(name and value) can be configured in the app, e.g. `Authorization: Bearer …`.

The body is below 100 KB — that is the Karoo's limit for an extension HTTP request. At
roughly fifteen fields per sensor it would take thousands of sensors to approach it, so it
is not a practical constraint.

The request goes out over the Karoo System's own connection: wifi, or Bluetooth to the
companion app. The extension holds no `INTERNET` permission of its own.

## The body

```json
{
  "reportId": "0a0f3f0a-6f9a-4b51-8a7a-a7a5b3c7d0e1",
  "schemaVersion": 1,
  "trigger": "RIDE_END",
  "createdAt": "2026-10-07T15:42:11Z",
  "karoo": {
    "serial": "K1234567",
    "hardwareType": "KAROO",
    "extensionVersion": "0.1.0",
    "batteryPercent": 43,
    "battery": "OK"
  },
  "sensors": [
    {
      "id": "ANT_PLUS-12345",
      "name": "Quarq DZero",
      "connectionType": "ANT_PLUS",
      "enabled": true,
      "component": null,
      "battery": "GOOD",
      "batteryUpdatedAt": "2026-10-07T15:41:58Z",
      "manufacturer": "SRAM",
      "serialNumber": "1234567",
      "supportedDataTypes": ["POWER", "CADENCE"]
    },
    {
      "id": "BLE-axs-group",
      "name": "AXS",
      "connectionType": "BLE",
      "enabled": true,
      "component": "rear_derailleur",
      "battery": "LOW",
      "batteryUpdatedAt": "2026-10-07T15:40:02Z",
      "manufacturer": "SRAM",
      "serialNumber": "7654321",
      "supportedDataTypes": []
    }
  ]
}
```

## Fields

`trigger` is `RIDE_END`, `MANUAL` (the test button) or `BONUS_ACTION` (a controller
button).

`battery` on a sensor is the Karoo's coarse status — `NEW`, `GOOD`, `OK`, `LOW`,
`CRITICAL`, `INVALID` — or `null` if that sensor never reported a level. There is no
percentage available for sensors; the Karoo buckets one as `>95 NEW, >80 GOOD, >45 OK,
>15 LOW, >0 CRITICAL`, which is the only resolution there is to pass on.

The head unit under `karoo` is the exception: a real percentage *is* available there, so
`batteryPercent` is exact and `battery` is that number bucketed with the same vocabulary,
so the Karoo can be compared against the sensors without special-casing. Both are `null`
if the level did not arrive within 5 s; a missing head-unit reading never holds up the
sensor report.

A multi-part sensor — an electronic groupset, say — produces one entry per `component`
plus one for the device itself. Entries of the same device share an `id`, so `component`
is what tells them apart. `supportedDataTypes` is carried only on the parent entry.

`serialNumber` is omitted when *Include sensor serial numbers* is switched off in the app.

### Timestamps

Both timestamps are ISO-8601 in UTC.

`createdAt` is when the snapshot was taken — the moment the ride ended — not when the
report was delivered. A report that spent an hour buffered still carries the ride's
timestamp, so it can be trusted as the ride-end time.

`batteryUpdatedAt` is when the Karoo last heard that level from that sensor, which is
frequently *not* recent. The Karoo stores each sensor's last known reading, so a sensor
that slept through the ride still appears, with a timestamp saying the reading is a day
old. If you alert on `LOW`, read this field too, or you will act on stale data.

Fractional seconds are present only when non-zero: `…:32.419Z` usually, but `…:32Z` on the
rare exact second. Parse with a real ISO-8601 parser rather than a fixed format string.

## What to reply

| Status | Meaning |
|---|---|
| `2xx` | Accepted. The report is deleted from the device. |
| `4xx` (not `408`/`429`) | Unacceptable. The report is dropped — it would never succeed. |
| `408`, `429`, `5xx`, or a network failure | Retried later, with backoff. |

Reports are buffered on the device until the endpoint accepts them, so an endpoint that is
down loses nothing — it gets the backlog when it returns. The buffer is capped at 200
reports and 30 days.

**Return a non-2xx when you have not stored the report.** A 2xx is taken as delivery and
the only copy is deleted. An endpoint that answers 200 unconditionally — Home Assistant's
webhook does, see [home-assistant.md](home-assistant.md) — will silently consume every
report.

`reportId` is a UUID and is stable across retries, so it can be used to deduplicate. That
matters because a discarded ride produces a report just like a saved one: the Karoo reports
both the same way, so the two cannot be told apart here.
