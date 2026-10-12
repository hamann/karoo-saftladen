# Developing karoo-saftladen

For working on the extension. If you just want to use it, the [README](../README.md) is
all you need.

The report Saftladen sends is written up separately in
[report-format.md](report-format.md), which stands on its own if you are writing an
endpoint rather than working on this code.

---

## How it is put together

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

```
app/src/main/kotlin/io/github/hamann/saftladen/
  extension/SaftladenExtension.kt   karoo-ext service: ride state, bonus action
  karoo/KarooFlows.kt               consumerFlow, streamDataFlow, HTTP, connect
  report/BatteryReport.kt           the JSON model and how a report is built
  report/BatteryReporter.kt         snapshot the sensors, buffer a report
  report/ReportStore.kt             the on-disk FIFO buffer
  report/ReportUploader.kt          drain it to the endpoint
  report/ReportFlushWorker.kt       retry outside the extension's lifetime
  settings/SettingsRepository.kt    DataStore-backed settings and last status
  ui/                               Compose settings screen
```

**Capture.** `SaftladenExtension` subscribes to `RideState` and fires on the
recording-to-idle transition. The levels come from the Karoo's own saved device list
(`SavedDevices`), which holds the last reading per sensor, so a sensor that dropped out
mid-ride is still reported. The extension also keeps the list it saw during the ride and
falls back to it if the Karoo does not answer within 10 s of recording stopping.

Two details the Karoo forces, both load-bearing:

- `RideState` delivers the current state **on subscribe**, so a naive "is Idle → report"
  fires a bogus report every time the service starts. Only a transition counts.
- Ending a stationary ride goes `Recording` → `Paused(auto=true)` → `Paused(auto=false)` →
  `Idle`. Matching only on `Recording` would miss a ride ended from a pause, which is the
  normal way a ride ends.

**Buffer.** `ReportStore` writes one file per report, temp-then-rename, named so that
lexicographic order is chronological. Capped at 200 reports and 30 days.

**Deliver.** `ReportUploader` POSTs through `OnHttpResponse` — the Karoo System's own
connection — with `waitForConnection = false`, so a missing network fails fast and the
report stays in our buffer, which unlike the Karoo's in-memory queue survives a reboot.

**Retry.** `ReportFlushWorker` backs off exponentially, with an hourly safety net so a
buffer that survived a reboot still drains. It deliberately sets **no**
`NetworkType.CONNECTED` constraint: the Karoo reaches the internet over Bluetooth to the
companion app, which Android's connectivity manager does not report as a connection, so
such a constraint would park the worker exactly when delivery is possible.

---

## Building

Everything is in the flake — JDK 17, Gradle, the Android SDK (platform 35, build-tools
35.0.1) and `adb`. Verified on `aarch64-darwin`.

```sh
nix develop            # or: direnv allow
gradle test            # report building and the buffer
gradle assembleDebug
```

There is no Gradle wrapper on purpose, so CI and a developer machine cannot end up on
different versions of anything. If you use Android Studio, point its JDK and SDK at the
ones from `nix develop` (`echo $JAVA_HOME $ANDROID_HOME`).

### One-time, for the APK: the karoo-ext dependency

`io.hammerhead:karoo-ext` is published only to GitHub Packages, and GitHub requires
authentication there **even for public packages**. The token needs the `read:packages`
scope specifically — a `repo`-scoped token returns 401.

The credential belongs to your GitHub account rather than to this project, so mint it at
<https://github.com/settings/tokens> and put it in `~/.gradle/gradle.properties`, which
Gradle merges into every build:

```properties
gpr.user=your-github-username
gpr.key=ghp_...
```

(`GITHUB_ACTOR` / `GITHUB_TOKEN` in the environment work too; that is what CI uses.)

Failing that, publish the library locally once — `settings.gradle.kts` prefers `mavenLocal`
for the `io.hammerhead` group:

```sh
git clone https://github.com/hammerheadnav/karoo-ext
cd karoo-ext && git checkout 1.1.9
./gradlew :lib:publishToMavenLocal
```

Keep the version in `gradle/libs.versions.toml` in sync with whichever tag you publish.

### If you fork this

`applicationId` is `io.github.hamann.saftladen`. Rename it, the `MAIN` action in
`AndroidManifest.xml`, and the `MANIFEST_URL` next to it — that last one points at this
repository's releases, so a fork that leaves it alone offers its users *these* updates.

### Sideloading your own build

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s SaftladenExtension:V BatteryReporter:V ReportUploader:V KarooSystem:V
```

The Karoo System binds the extension service as soon as the APK is installed — no reboot,
no toggle. You should see `extension saftladen [x.y.z] started by Karoo System` in logcat.

A debug build and a release build are signed with different keys, and Android refuses to
replace one with the other. Moving between them needs an `adb uninstall
io.github.hamann.saftladen` first.

---

## Trying it without a ride

Run the throwaway receiver on your machine and point the extension at it:

```sh
python3 tools/echo-server.py 8080     # then use http://<lan-ip>:8080/
```

Then in the Saftladen app on the Karoo: set the URL, **Save settings**, **Send test report
now**. That exercises the whole path except the ride-end trigger itself — capture, buffer,
POST — and prints the report it received.

The *Send battery report* bonus action does the same from a controller button, which is
the only way to get a report mid-ride.

---

## Releasing

Tagging cuts a release:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` runs the tests, builds a signed APK and writes
`manifest.json` from the APK's own version data — so the manifest and the APK cannot
disagree about what a release is. Both are attached to the GitHub release, which is where
the `MANIFEST_URL` meta-data in `AndroidManifest.xml` points, so an installed extension is
offered the update.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` before tagging. The Karoo
decides an update exists by comparing `versionCode`, so a release that forgets to raise it
is invisible to everyone who already has the extension.

### Signing

The signing key lives encrypted in `secrets.yaml`, committed. Losing it means no existing
install can ever be updated — Android refuses an APK signed with a different key — which
is exactly why it is in the repository rather than on one machine.

One-time setup:

```sh
nix develop --command tools/init-signing.sh   # creates the keystore, seals it into secrets.yaml
```

Then add the age private key matching `github_actions` in `.sops.yaml` as the repository
secret `SOPS_AGE_KEY`. CI uses that one secret to decrypt everything else.

To build a signed release locally:

```sh
source tools/load-signing.sh
gradle assembleRelease
tools/package-release.sh
```

Without the keystore the release build is simply left unsigned, so a clone without the key
still builds. `tools/package-release.sh` refuses to package an unsigned APK rather than
publish a release that fails to install for everyone who downloads it.

---

## Why minification is off

`isMinifyEnabled = false` on the release build. karoo-ext serialises its models with
kotlinx.serialization, whose generated serializers R8 cannot see; `proguard-rules.pro`
keeps them, but that has not been exercised on-device. Turning it on without testing a
release build on a real Karoo would trade a few hundred KB for a crash in the field.
