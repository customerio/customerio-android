# Geofence Mock — testing workflow

**This branch only.** A short-circuit in `GeofenceApiServiceImpl.fetchGeofences` returns a hardcoded JSON response instead of hitting the real `/v1/geofences/nearby` endpoint. The rest of the geofence flow (decode → toDomain → distance filter → OS registration → transition events) runs unchanged, so this exercises the production path with deterministic data.

## Edit geofences (the common case)

Open [`location/mock/regions.json`](mock/regions.json) and list the regions you want. Required per region: `lat`, `lng`. Everything else has a sensible default:

```json
{
  "geofences": [
    { "name": "Ferry Building", "lat": 37.7955, "lng": -122.3937, "radius": 150 },
    { "name": "Office", "lat": 37.4220, "lng": -122.0841 }
  ]
}
```

Regenerate the mock JSON used by the SDK:

```sh
python3 location/mock/generate.py
```

That rewrites `MOCK_RESPONSE_JSON` in [`GeofenceApiService.kt`](src/main/kotlin/io/customer/location/geofence/api/GeofenceApiService.kt) between the `BEGIN GENERATED MOCK` / `END GENERATED MOCK` markers — don't edit that block by hand.

Then rebuild + install:

```sh
./gradlew :samples:java_layout:installDebug
```

## Toggle the mock off

Open [`GeofenceApiService.kt`](src/main/kotlin/io/customer/location/geofence/api/GeofenceApiService.kt) and flip:

```kotlin
private const val USE_MOCK_RESPONSE = false
```

Rebuild. The SDK now hits the real backend.

## Test loop

1. Edit `location/mock/regions.json`.
2. Run `python3 location/mock/generate.py`.
3. `./gradlew :samples:java_layout:installDebug`
4. Open the sample app, identify a user — kicks off the geofence sync.
5. In Android Studio's emulator → *Extended Controls → Location*, set the device near one of your mock geofence centers (drag the marker or set lat/lng manually).
6. Watch for the local notification ("Geofence ENTER" / "Geofence EXIT") posted by `GeofenceTestNotifier` in the java sample — that confirms the SDK fired a transition event end-to-end.
7. For deeper detail, filter logcat (see below).

## Log filters

All SDK geofence logs use tag `Geofence`. From the terminal:

```sh
adb logcat -s Geofence:*
```

In Android Studio's Logcat panel, filter:

```
tag:Geofence
```

To see only the events the app reacts to (transitions + register/unregister), narrow further:

```sh
adb logcat -s Geofence:* | grep -E "Registered|Removed|transition|ENTER|EXIT"
```

To see ALL output from just the sample process:

```sh
adb logcat --pid=$(adb shell pidof io.customer.android.sample.java_layout)
```

## Local notification on transitions

[`GeofenceTestNotifier.kt`](../samples/java_layout/src/main/java/io/customer/android/sample/java_layout/geofencetest/GeofenceTestNotifier.kt) subscribes to `Event.GeofenceTransitionEvent` from the SDK's EventBus and posts a local notification per transition. Installed from `SampleApplication.onCreate()`. Testing-only — must not ship.

## Input file reference

Per region (only `lat`, `lng` required):

| Key | Type | Default |
|---|---|---|
| `lat` | float | required |
| `lng` | float | required |
| `radius` | int (meters) | 150 |
| `name` | string | `"Region N"` |
| `id` | int or string | auto-incremented from 1 |
| `external_id` | string | `"test-N"` |
| `transition_types` | array of `"enter"` / `"exit"` | `["enter", "exit"]` (server-side absence implied) |

Optional top-level `config` block — passed through verbatim. Omit it to exercise the SDK's built-in fallbacks. Every field in `GeofenceApiConfig` is nullable, so partial blocks fall back per-field.

### Full schema

| Field | Unit | SDK fallback | What it controls |
|---|---|---|---|
| `local_refresh_trigger_radius` | meters | 1000 | Radius of the movement-trigger geofence — when the user exits this circle around their last known position, the SDK does a Tier A (cached) refresh. |
| `remote_fetch_refresh_trigger_radius` | meters | 5000 | Distance from the last API-fetch anchor that promotes Tier A to Tier B (remote fetch). |
| `remote_fetch_refresh_expiry_time` | ms | 86_400_000 (24h) | Freshness window — within it, identify / app-launch reuses the cached set instead of hitting the API. |
| `duplicate_events_expiry_time` | ms | 3_600_000 (1h) | Per-(geofence, transition) cooldown that suppresses duplicate ENTER/EXIT events. |
| `android.max_business_geofence` | count | 19 | Cap on registered business geofences. Server-side kill switch — set to 0 to disable registration entirely. |

```json
"config": {
  "local_refresh_trigger_radius": 1000,
  "remote_fetch_refresh_trigger_radius": 5000,
  "remote_fetch_refresh_expiry_time": 86400000,
  "duplicate_events_expiry_time": 3600000,
  "android": { "max_business_geofence": 19 }
}
```

### Common overrides

```json
"config": { "remote_fetch_refresh_expiry_time": 1 }
```

Forces every identify / app launch to re-fetch the mock so edits to `regions.json` take effect on the next sync. Without it, the SDK's 24h freshness window will skip re-fetches and you'll need `pm clear` to see changes.

```json
"config": { "duplicate_events_expiry_time": 300000 }
```

Shortens the dedupe window from 1h to 5min so you can retest the same region in a single session.

### Cache note

The SDK caches each fetch in SharedPreferences and skips re-fetching while the cache is fresh. With the default 24h freshness, you'll need `adb shell pm clear io.customer.android.sample.java_layout` (or the 1ms override above) to see `regions.json` edits without waiting.

## Don't ship this

`USE_MOCK_RESPONSE = true`, the generated mock JSON, `location/mock/`, and this file must not land on `feature/geofence-on-device` or `main`. The `geofence-testing` branch is throwaway.
