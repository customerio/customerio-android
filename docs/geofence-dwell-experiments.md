# Dwell diagnostics experiment

This experiment is off by default. It records why a dwell did or did not qualify; it does not
change accuracy, freshness, visit identity or elapsed-time checks. No public API changes are
required. Restart the host app after changing its manifest flag.

The earlier native polygon dwell wake experiment (`POLYGON_DWELL_WAKE`) is retired. A WorkManager
check at each pending polygon visit's threshold replaces it for every host; see
[transition delivery](manual-tests/geofence-transition-delivery.md). The flag is now ignored.

## Persistent diagnostics

Enable separately in the host application's manifest:

```xml
<meta-data
    android:name="io.customer.geofence.experimental.DWELL_DIAGNOSTIC_JOURNAL"
    android:value="true" />
```

The private preferences file is `io.customer.geofence.dwell_diagnostics.xml`, under the app's
`shared_prefs` directory. The journal keeps at most 64 records by default. It records timing,
fence IDs, request priorities, returned accuracy and fix age, without coordinates or user/device IDs.
Fence IDs and timing remain location-derived history. Identity changes, reset and opt-out clear it.
A separate private preference records the opaque session generation. A same-session relaunch keeps
history; a changed or signed-out session erases old history even if the previous process missed its
queued clear. The journal does not send data to the backend.

## Replay and device checks

Use [the replay harness](geofence-dwell-replay.md) for deterministic suspension, stale fixes,
outside fixes and duplicate-event checks. Replay does not establish OS wake delivery.
