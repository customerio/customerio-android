# Dwell acquisition experiments

These experiments are off by default. They create opportunities to obtain polygon evidence;
they do not relax accuracy, freshness, visit identity or elapsed-time checks. No public API changes
are required. Restart the host app after changing its manifest flags.

## Native polygon dwell wake

In the host application's manifest:

```xml
<meta-data
    android:name="io.customer.geofence.experimental.POLYGON_DWELL_WAKE"
    android:value="true" />
```

After the SDK establishes a polygon visit, it can replace that polygon's existing wake-circle
registration with ENTER, EXIT and DWELL. The remaining delay comes from the original visit entry,
plus a 60-second grace. Ordinary location evaluations do not repeatedly restart the native timer.
The experiment uses the same request ID and consumes no additional geofence slot.

The native DWELL callback requests polygon evaluation. Its enclosing circle proves no polygon
membership. A qualifying event still needs a fresh fix whose accuracy circle is inside the polygon.
An ended visit, changed identity or changed catalog cannot reuse the old wake as proof.
Registration cleanup retains recovery records when Play Services does not confirm success.

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

`NativeDwellWakeProbeTest` measures raw Play Services behavior on a physical device. It is skipped
unless the instrumentation argument `cioNativeDwellProbe=true` is present. It does not use the SDK's
event pipeline or contact the backend. Run it only with the isolated test APK and its location
permissions granted, while stationary with location services enabled.

The test registers two 1,000 m circles with 60-second loitering delays, then re-adds one under the
same ID after 30 seconds with a 120-second delay. It observes for at most eight minutes and removes
both fences and their PendingIntent afterward. A late callback is consistent with a timer reset;
batched delivery can produce the same observation. Compare runs under the same screen and power
conditions before drawing a timing conclusion.

The module test APK currently contains duplicate JUnit license resources. For this probe only,
save the following as a temporary `native-dwell-probe.init.gradle` outside the repository:

```groovy
gradle.beforeProject { Project p ->
    if (p.path != ':geofence') return
    p.pluginManager.withPlugin('com.android.library') {
        def android = p.extensions.getByName('android')
        def packaging = android.respondsTo('getPackaging') ? android.packaging : android.packagingOptions
        packaging.resources.pickFirsts.addAll(['/META-INF/LICENSE.md', '/META-INF/LICENSE-notice.md'])
    }
}
```

Build and run with the selected device:

```sh
./gradlew -I /path/to/native-dwell-probe.init.gradle :geofence:assembleDebugAndroidTest
adb -s <serial> install -r -t -g geofence/build/outputs/apk/androidTest/debug/geofence-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r \
  -e class io.customer.geofence.probe.NativeDwellWakeProbeTest \
  -e cioNativeDwellProbe true \
  io.customer.geofence.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s NativeDwellProbe
adb -s <serial> uninstall io.customer.geofence.test
```

Check the instrumentation result and cleanup assertion. The temporary packaging script belongs only
to this local probe; do not install it in Gradle's global init directory or use it for release builds.
A live instrumentation run does not establish SDK cold-process delivery, small-polygon accuracy or
backend ingestion.
