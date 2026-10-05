# Automated dwell replay

`DwellReplayTest` runs synthetic scenarios through the SDK's real API mapper, registration,
crossing pipeline, visit store and persisted delivery queue. Play Services, HTTP, clock and worker
scheduling use replay doubles. These tests run in the ordinary JVM test job without the private
corpus or a device.
`GeofenceApiResponseTest` separately covers wire JSON decoding and raw catalogue values.

```sh
./gradlew :geofence:assembleDebug :geofence:testDebugUnitTest \
  --tests 'io.customer.geofence.replay.DwellReplayTest' \
  --tests 'io.customer.geofence.replay.ReplayDeliveryAssertionsTest'
```

The Gradle test task can exit successfully despite failed tests. Read the JUnit XML in
`geofence/build/test-results/testDebugUnitTest`; CI's JUnit reporter fails on reported failures.
When replaying the private corpus, the existing `geofence_replay_gate.py` also checks that the
expected corpus cases ran and passed.

## Inputs and checkpoints

API fixtures preserve optional `dwellThresholdSeconds` for circles and polygons. An omitted
threshold follows the legacy decoder path. `os.callback` supports `t: "dwell"`, just as it
supports ENTER and EXIT.

An authored scenario can check the actual persisted queue at a specific point in virtual time:

```json
{"k":"then","at":89,"ev":"delivery.queued","id":"B","t":"dwell","count":0}
{"k":"then","at":90,"ev":"delivery.queued","id":"B","t":"dwell","count":1,"timestampAt":90,"enteredAtAt":30,"dwellThresholdSeconds":60,"dwellDurationSeconds":60,"hasVisitId":true,"detectionSource":"native"}
```

The runner preserves recorded file order, including late records with an earlier timestamp. Put a checkpoint
after its triggering input. Counts are exact for the fence and transition across the entire
pending queue; they include geoset fan-out. Optional property checks apply to every matching row.
`timestampAt` and `enteredAtAt` are seconds from the scenario's clock origin. A null duration
asserts omission; zero is a known duration. `hasVisitId` requires a nonblank visit ID, and
`hasEnteredAt` checks whether the entry timestamp is present. Unknown assertion fields fail.

These checkpoints complement the existing registration, accepted-transition and reset
expectations in corpus scenarios. They do not replace those decision-count checks.

## What the tests establish

The suite covers positive dwell delivery, no delivery from elapsed time alone, duplicate callbacks,
missing or stale attributing fixes, leave-before-threshold, re-entry, shared DWELL/EXIT visit IDs,
EXIT duration, geoset fan-out and omitted facts for an unobserved arrival.

A native Play Services DWELL callback qualifies dwell independently of the app's observed ENTER
receipt time. An early callback relative to that receipt can therefore qualify dwell while its
duration remains omitted. Observed arrival fixtures first establish outside membership through a
real callback; registration coordinates alone do not establish it.

Replay checks SDK decisions and queued payloads. Physical device delivery timing and live backend
ingestion require separate validation.
