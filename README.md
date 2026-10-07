# Saftladen

A Hammerhead Karoo extension that collects the battery status of every paired sensor when
a ride ends and POSTs it as JSON to a URL you configure. If the Karoo has no connection at
that moment, the report is buffered on disk and retried until the endpoint accepts it.

## How it works

```
RideState: Recording ──▶ Idle
                          │
                          ▼
            SavedDevices snapshot (battery per sensor)
                          │
                          ▼
              ReportStore  (one JSON file per report, filesDir/reports)
                          │
                          ▼
   ReportFlushWorker ──▶ ReportUploader ──▶ POST via Karoo's network
        (retry/backoff)                     (OnHttpResponse)
```

* **Capture** – [`SaftladenExtension`](app/src/main/kotlin/io/github/hamann/saftladen/extension/SaftladenExtension.kt)
  subscribes to `RideState` and fires on the recording-to-idle transition. The battery
  levels come from Karoo's own saved device list (`SavedDevices`), which holds the last
  reading per sensor, so sensors that dropped out mid-ride are still reported. The
  extension also keeps the list it saw during the ride and falls back to it if Karoo does
  not answer within 10 s of recording stopping.
* **Buffer** – [`ReportStore`](app/src/main/kotlin/io/github/hamann/saftladen/report/ReportStore.kt)
  writes one file per report (write-to-temp-then-rename), oldest-first ordering, capped at
  200 reports and 30 days.
* **Deliver** – [`ReportUploader`](app/src/main/kotlin/io/github/hamann/saftladen/report/ReportUploader.kt)
  POSTs through `OnHttpResponse`, i.e. the Karoo System's own connection (wifi, or
  Bluetooth to the companion app). The app itself has no `INTERNET` permission.
  2xx deletes the report, 4xx (other than 408/429) drops it as unacceptable, anything else
  keeps it buffered.
* **Retry** – [`ReportFlushWorker`](app/src/main/kotlin/io/github/hamann/saftladen/report/ReportFlushWorker.kt)
  retries with exponential backoff, plus an hourly safety net so a buffer that survived a
  reboot still drains. It deliberately sets *no* `NetworkType.CONNECTED` constraint:
  Karoo's Bluetooth-to-phone path is not visible to Android's connectivity manager, so
  such a constraint would park the worker exactly when delivery is possible.

## Payload

```json
{
  "reportId": "0a0f3f0a-6f9a-4b51-8a7a-a7a5b3c7d0e1",
  "schemaVersion": 1,
  "trigger": "RIDE_END",
  "createdAt": "2026-10-07T15:42:11Z",
  "karoo": {
    "serial": "K1234567",
    "hardwareType": "KAROO",
    "extensionVersion": "1.0.0",
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

* `battery` is Karoo's coarse status — `NEW`, `GOOD`, `OK`, `LOW`, `CRITICAL`, `INVALID` —
  or `null` if the sensor never reported a level. The SDK exposes no percentage *for
  sensors*; it buckets `>95 NEW, >80 GOOD, >45 OK, >15 LOW, >0 CRITICAL`.
* The head unit itself is reported under `karoo`, where a real percentage *is* available
  (`DataType.Type.BATTERY_PERCENT`). `battery` there is that percentage bucketed with the
  same vocabulary, so the Karoo can be treated like any other battery. Both are `null` if
  the level did not arrive within 5 s — a missing head-unit reading never blocks the
  sensor report.
* Multi-part sensors (e.g. an electronic groupset) produce one entry per `component` plus
  one for the device itself; entries of the same device share `id`.
* `trigger` is `RIDE_END`, `MANUAL` (test button), or `BONUS_ACTION`.
* `serialNumber` is omitted when "Include sensor serial numbers" is switched off.
* Content type is `application/json`; one optional extra header (name + value) can be
  configured, e.g. `Authorization: Bearer …`.
* Reports must stay below 100 KB — that is Karoo's limit for extension HTTP requests.
  With ~15 fields per sensor that is thousands of sensors, so it is not a practical limit.

Reply `2xx` to acknowledge. Reply `4xx` only if the report is unacceptable and should be
dropped; use `5xx`, `408` or `429` to have it retried later.

## Development

Everything is provided by the Nix flake:

```sh
nix develop            # or: direnv allow
gradle test            # unit tests for report building and the buffer
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Saftladen:V KarooSystem:V KarooExtension:V
```

The flake pins JDK 17, Gradle, and an Android SDK (platform 35, build-tools 35.0.1) via
`androidenv`, so no Android Studio install is needed. If you use Android Studio, point its
JDK and SDK at the ones from `nix develop` (`echo $JAVA_HOME $ANDROID_HOME`).

### karoo-ext dependency

`io.hammerhead:karoo-ext` is published to GitHub Packages, which requires authentication
even for public packages. Either:

1. Put a token with `read:packages` in `~/.gradle/gradle.properties`:

   ```properties
   gpr.user=your-github-user
   gpr.key=ghp_...
   ```

   (or export `GITHUB_ACTOR` / `GITHUB_TOKEN`), or

2. Publish it locally once — `settings.gradle.kts` prefers `mavenLocal` for the
   `io.hammerhead` group:

   ```sh
   git clone https://github.com/hammerheadnav/karoo-ext
   cd karoo-ext && git checkout 1.1.9
   ./gradlew :lib:publishToMavenLocal
   ```

Keep the version in `gradle/libs.versions.toml` in sync with whichever tag you publish.

## Trying it without a ride

Run the throwaway receiver on your machine and point the extension at it:

```sh
python3 tools/echo-server.py 8080     # then use http://<lan-ip>:8080/
```

Then in the Saftladen app on the Karoo: set the URL, **Save settings**, **Send test report
now**. You can also assign the *Send battery report* bonus action to a controller button to
trigger a report mid-ride.

## Notes and limits

Checked on a Karoo 2 (`k24`, Android 12): the Karoo System binds the extension service as
soon as the APK is installed, `RideState` arrives on subscribe, and a report with six
sensors (ANT+, BLE and an extension-provided Di2) was delivered over wifi and, with the
endpoint down, buffered and retried.


* The Karoo System binds extension services while they are installed and enabled; that is
  what keeps the ride-state subscription alive. The extension does not run a foreground
  service of its own.
* A ride that is discarded rather than saved also ends in `RideState.Idle` and therefore
  also produces a report.
* `applicationId` is `io.github.hamann.saftladen`; rename it (and the `MAIN` action in
  `AndroidManifest.xml`) if you publish under a different account.
