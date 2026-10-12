# Saftladen

When a ride ends, send the battery status of **every sensor on the bike** — and of the
**Karoo itself** — to somewhere you will actually look at it.

```
ride ends  ──▶  Heart rate     OK
                Di2            OK
                Power meter    GOOD
                Radar          OK
                Rear light     OK
                Karoo          54%
                                      ──▶  POST to your URL, as JSON
```

No connection at the finish? The report waits on the device and is sent later, so a ride
that ends out of range is not a ride you lose.

---

## Install

On a **Karoo**, using the Hammerhead Companion app — this is Hammerhead's
[sideloading procedure](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading),
and no cable is involved:

1. On your **phone**, open the
   [latest release](https://github.com/hamann/karoo-saftladen/releases/latest).
2. Long-press the link to `saftladen.apk` and **share it with the Hammerhead Companion
   app**.
3. Your Karoo shows an install prompt. Press **Install**.
4. Open **Saftladen** from the main menu, enter the URL reports should go to, and press
   **Save settings**.
5. Press **Send test report now**. If it says it sent, you are done — every ride from now
   on reports by itself.

No permissions to grant, and nothing to add to a data page: it does its work in the
background and has no field of its own.

### Updating

Long-tap the Saftladen icon on the main menu and choose **Update**. The app points at its
own latest release, so it finds new versions without being told where to look.

### If no report arrives

The buffer count and the last attempt are shown in the app, which is the first place to
look — it will usually name the problem outright.

- **"no endpoint configured"** — the URL is blank, or you changed it without pressing
  **Save settings**.
- **"Upload failed"** with a connection error, and the buffer climbing. The Karoo could
  not reach your endpoint. Reports keep accumulating and go out when it can, so this
  fixes itself once the endpoint is reachable; **Retry buffered reports** forces an
  attempt now.
- **It says sent, but nothing arrived.** Your endpoint answered `2xx` without storing
  anything. Home Assistant does this for a mistyped webhook id — see
  [docs/home-assistant.md](docs/home-assistant.md), which covers how to tell.
- **Reports only after you get home.** Expected if the Karoo only has wifi. Over Bluetooth
  it goes through the companion app, which has to be running on your phone.
- **Nothing at all, ever, including the test button.** Check the extension is enabled in
  the Karoo's extension list.

---

## Status

Working, and used on a real Karoo 3 (Android 12) against real sensors. Version 0.1.0.

What has been tested on the device:

- The Karoo binding the extension as soon as the APK is installed, with no reboot
- A live ride: `Recording` → auto-pause → manual pause → `Idle`, and a report on the end
- Six real sensors in one report — ANT+, BLE, and a Di2 provided by another extension —
  plus the head unit's own charge
- Delivery over wifi through the Karoo's own connection
- The endpoint being down at ride end: buffered, retried, and delivered a minute later
  with the ride's own timestamp intact
- Signed releases built from a tag, and the manifest the Karoo reads for updates

What has not:

- **A Karoo 2.** Nothing here is Karoo 3 specific, and `minSdk` leaves it in range, but it
  has not been run on one.
- **Driving a report from a controller button.** The *Send battery report* bonus action is
  published and handled, and the Karoo is meant to be able to bind it to a controller.
  Whether extension actions appear in that list is unconfirmed — there was no suitable
  controller here to find out.
- **Minified release builds.** Minification is off; see
  [docs/development.md](docs/development.md).

## What it does not do

**Only at the end of a ride.** Not continuously, not on a schedule, and there is no data
field showing a battery mid-ride. If you want a reading now, the app has a test button.

**Percentages, for sensors.** The Karoo exposes only a coarse status for them —
`NEW`, `GOOD`, `OK`, `LOW`, `CRITICAL` — and no finer number exists to pass on. The head
unit is the exception: that one is a real percentage.

**Tell a saved ride from a discarded one.** The Karoo reports both the same way, so a ride
you abandon still produces a report. That is deliberate — the battery reading is true
either way — and each report carries a `reportId` so a consumer can drop what it considers
a duplicate.

**Fetch a battery on demand.** Every level comes from what the Karoo last heard from that
sensor, which can be hours or days old. Each one is timestamped so you can see how stale
it is.

---

## Sending the reports somewhere

The JSON, and what an endpoint should reply, is in
[docs/report-format.md](docs/report-format.md) — enough to write a receiver against,
without reading any of this code.

For Home Assistant specifically, [docs/home-assistant.md](docs/home-assistant.md) has a
webhook setup that turns each sensor into a battery entity you can put on a dashboard and
alert on.

## Building it yourself

See [docs/development.md](docs/development.md).

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Built against [karoo-ext](https://github.com/hammerheadnav/karoo-ext). Hammerhead and
Karoo are trademarks of Hammerhead/SRAM. This project is not affiliated with or endorsed
by them.
