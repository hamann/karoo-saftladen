# Changelog

## 0.1.0 — 2026-10-08

- Report every paired sensor's battery status as JSON when a ride ends.
- The head unit's own charge, as a real percentage, alongside the sensors.
- Buffer reports on disk and retry until the endpoint accepts them.
- Configurable endpoint URL and one optional request header.
- Test report and a *Send battery report* bonus action for triggering by hand.
- Signed releases built from a tag, with a manifest the Karoo reads for updates.
