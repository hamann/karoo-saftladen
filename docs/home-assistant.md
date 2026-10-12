# Home Assistant as the endpoint

Turning the battery report into entities you can see on a dashboard and alert on.
The payload itself is described in [report-format.md](report-format.md).

---

A webhook is the right fit: the webhook id *is* the credential, so Saftladen's auth header
fields stay empty. (The REST API at `/api/states/…` is not usable here — it expects a
`{"state": …, "attributes": …}` body, and Saftladen posts its own shape.)

## The template block

Pick a long random id, put it in `secrets.yaml` as `karoo_webhook_id`, and add one
trigger-based template sensor per device:

```yaml
# configuration.yaml
template:
  - triggers:
      - trigger: webhook
        webhook_id: !secret karoo_webhook_id
        allowed_methods: [POST]
        local_only: false
    sensor:
      # The head unit — the one reading that is a real percentage.
      - name: Karoo battery
        unique_id: karoo_battery
        state: "{{ trigger.json.karoo.batteryPercent }}"
        # `is number` rejects both a null level and a field that is absent entirely
        # (an older report, a future schema); `is not none` would let the latter through.
        availability: "{{ trigger.json.karoo.batteryPercent is number }}"
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        attributes:
          reported_at: "{{ trigger.json.createdAt }}"
          report_trigger: "{{ trigger.json.trigger }}"
          sensors: "{{ trigger.json.sensors }}"

      # One entity per sensor, matched on the name Karoo shows in its sensor list.
      # Karoo reports a bucket, not a percentage, so the bucket is mapped to the bottom
      # of its range: that makes `device_class: battery` work (proper battery icon, %,
      # battery cards) without ever overstating the charge. The real word is kept in
      # the `status` attribute. Anything unmapped — INVALID, a battery that was never
      # reported, a name that matches nothing — renders as `unknown`.
      - name: Heart rate battery
        unique_id: karoo_hr_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'Herzfrequenz 503512')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'Herzfrequenz 503512')
               | map(attribute='battery') | first | default('unknown', true) }}

      - name: Di2 battery
        unique_id: karoo_di2_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'Di2 1249')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'Di2 1249')
               | map(attribute='battery') | first | default('unknown', true) }}

      - name: Power meter battery
        unique_id: karoo_power_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'ASSIOMA31241L')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'ASSIOMA31241L')
               | map(attribute='battery') | first | default('unknown', true) }}

      - name: Radar battery
        unique_id: karoo_radar_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'Radar 20031')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'Radar 20031')
               | map(attribute='battery') | first | default('unknown', true) }}

      - name: Rear light battery
        unique_id: karoo_light_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'Leicht 20031')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'Leicht 20031')
               | map(attribute='battery') | first | default('unknown', true) }}

      - name: Speed sensor battery
        unique_id: karoo_speed_battery
        unit_of_measurement: "%"
        device_class: battery
        state_class: measurement
        state: >
          {% set s = trigger.json.sensors | selectattr('name', 'eq', 'Geschwindigkeit 24769')
             | map(attribute='battery') | first | default('', true) %}
          {{- {'NEW': 96, 'GOOD': 81, 'OK': 46, 'LOW': 16, 'CRITICAL': 1}.get(s, 'unknown') }}
        attributes:
          status: >
            {{ trigger.json.sensors | selectattr('name', 'eq', 'Geschwindigkeit 24769')
               | map(attribute='battery') | first | default('unknown', true) }}

      # Anything that needs charging, so one automation covers the whole bike.
      - name: Bike batteries needing attention
        unique_id: karoo_batteries_low
        icon: mdi:battery-alert-variant-outline
        state: >
          {{ trigger.json.sensors
             | selectattr('battery', 'in', ['LOW', 'CRITICAL'])
             | map(attribute='name') | list | join(', ') | default('none', true) }}
```

## Adding it to an existing `template:` key

**Add the whole thing as a new list item** — do not merge it into an existing one. Two
rules decide the shape, and breaking either fails quietly:

* `triggers:` and `sensor:` must be **sibling keys of the same list item**. A `- triggers:`
  item with no entity domain is an orphan; HA logs *"Incomplete template configuration"*
  and the entities never appear. (A warning today, a hard error from Core 2026.5.)
* Everything in a block with `triggers:` updates **only** when that trigger fires. Moving
  an existing state-based sensor under the webhook would freeze it between rides — so keep
  state-based entities in their own item, without a trigger.

```yaml
template:
  - sensor:                  # existing, state-based — leave it alone
    - name: Something else
      state: "{{ states('sensor.x') }}"

  - triggers:                # new item: trigger and entities together
    - trigger: webhook
      webhook_id: !secret karoo_webhook_id
    sensor:                  # sibling of `triggers`, not nested inside it
    - name: Karoo battery
```

## Pointing Saftladen at it

In the Saftladen app set the URL to
`http://homeassistant.local:8123/api/webhook/<your-webhook-id>` and leave both auth header
fields blank. Reload templates (or restart HA) before testing.

## Adding a sensor

Copy a block and change the name — take it verbatim from a delivered report (`"name"`),
not from memory. A name that matches nothing yields `unknown` rather than an error, so if
an entity is stuck at `unknown`, that is the first thing to check. Renaming a sensor on
the Karoo changes the name here too.

## Checking it actually worked

**Home Assistant answers `200 OK` even when it did nothing with your report.** Checked
against [`webhook/__init__.py`][ha-webhook]: an unregistered id and an exception inside the
handler both return `200`. Saftladen treats any 2xx as delivered and deletes the report,
so a typo in the webhook id silently throws every report away. Before trusting it:

```sh
curl -i -X POST -H 'Content-Type: application/json' \
  -d '{"karoo":{"batteryPercent":42},"sensors":[]}' \
  http://homeassistant.local:8123/api/webhook/<your-webhook-id>
```

then confirm `sensor.karoo_battery` actually moved, and check the HA log for
`Received message for unregistered webhook`. A 200 on its own proves nothing.

[ha-webhook]: https://github.com/home-assistant/core/blob/dev/homeassistant/components/webhook/__init__.py
