# Segment Storage Injection Spike — Android Report

**Date:** 2026-05-20
**Repo:** `customerio-android`
**Branch:** `spike/segment-storage-injection-2026-05-19` (local-only, not pushed)
**Analytics-kotlin version:** `com.segment.analytics.kotlin:android:1.24.1`
**Sample app:** `samples/java_layout` — `applicationId = io.customer.android.sample.java_layout`
**Emulator:** `emulator-5554` (Android 16k / API 37 image)
**Spike UUID:** `dd099ca9-d52e-4093-b1e8-b6d1b3b9bd33`

## What was implemented

- **New file:** `datapipelines/src/main/kotlin/io/customer/datapipelines/spike/WrappingStorageProvider.kt`. Implements `StorageProvider` by delegating to `AndroidStorageProvider.getStorage(...)`/`createStorage(...)` and returning a `WrappingStorage` that:
  - Logs every Storage method call via logcat tag `SegmentStorageTrace` (op, key/path, byte count, ASCII + hex preview).
  - On `write` and `writePrefs`: prepends the 13-byte ASCII sentinel `WRAPPED::v1::` to the payload and XORs every subsequent byte with `0x55` before forwarding to the delegate.
  - On `read` and `readAsStream`: detects the sentinel, strips it, re-XORs, and returns the original UTF-8 to the caller so the library never sees the mutation.
- **Modified:** `datapipelines/src/main/kotlin/io/customer/datapipelines/extensions/AnalyticsExt.kt`. `updateAnalyticsConfig` now sets `this.storageProvider = WrappingStorageProvider()` on the Segment `Configuration`.
- **Modified:** `samples/java_layout/src/main/java/io/customer/android/sample/java_layout/SampleApplication.java`. Added `runSegmentStorageSpike()` which is called from `onCreate()` after `initializeSdk(...)`. Plants `SPIKE_*` needles via `identify` → `track` × 3 → `screen` → `clearIdentify`. (`group`/`alias` are not on the CIO public API and were skipped per the plan.)
- The existing `PreInitBufferScenario` toggle was **not** touched.

Build: `./gradlew :samples:java_layout:assembleDebug` → `BUILD SUCCESSFUL`.
Install: clean `adb uninstall` + `adb install` of `java_layout-debug.apk` → spike sequence ran exactly once on launch; trace log captured `SegmentStorageSpike: spike sequence complete; uuid=dd099ca9-...`.

## File-by-file reconciliation

Snapshot taken ~20 s after launch via `adb shell run-as $PKG find /data/data/$PKG -type f`. All 17 files reconciled. Sentinel check is whether the file's first 13 bytes match `WRAPPED::v1::` (hex `5752 4150 5045 443a 3a76 313a 3a`).

| # | File path | Owned by | Contains `SPIKE_` needle in plaintext? | Starts with `WRAPPED::v1::`? | Verdict |
|---|---|---|---|---|---|
| 1 | `/data/data/.../cache/data/user/0/.../no_backup/androidx.work.workdb.lck` | OS (AndroidX WorkManager lock file, 0 bytes) | no | no (empty) | unrelated |
| 2 | `/data/data/.../cache/http_cache/journal` | OkHttp cache journal (36 bytes; `libcore.io.Di…`) | no | no | unrelated |
| 3 | `/data/data/.../shared_prefs/FirebaseHeartBeat...xml` | Firebase | no | no (XML preamble) | unrelated |
| 4 | `/data/data/.../shared_prefs/analytics-android-a898c13577974eabf608.xml` | **Segment KVS** | no (no `SPIKE_` text after wrapping/uploading) | no (XML preamble) — see "Partial bypass" below | **partial bypass** (3 keys wrapped via Storage interface, 3 keys plaintext via direct SharedPreferences) |
| 5 | `/data/data/.../shared_prefs/com.google.firebase.messaging.xml` | Firebase | no | no | unrelated |
| 6 | `/data/data/.../shared_prefs/io.customer.sdk.io.customer.android.sample.java_layout.xml` | CIO core SDK (not Segment) | no | no | unrelated to this spike |
| 7 | `/data/data/.../shared_prefs/io.customer.sdk.location.....xml` | CIO location module | no | no | unrelated |
| 8 | `/data/data/.../shared_prefs/io.customer.sdk.location_sync.....xml` | CIO location module | no | no | unrelated |
| 9 | `/data/data/.../shared_prefs/io.customer.sdk.inApp.....xml` | CIO in-app module | no | no | unrelated |
| 10 | `/data/data/.../shared_prefs/com.google.android.gms.appid.xml` | Google Play Services | no | no | unrelated |
| 11 | `/data/data/.../no_backup/com.google.android.gms.appid-no-backup` | GMS (0 bytes) | no | no (empty) | unrelated |
| 12 | `/data/data/.../no_backup/androidx.work.workdb` | AndroidX WorkManager (SQLite) | no | no | unrelated |
| 13 | `/data/data/.../no_backup/androidx.work.workdb-wal` | WorkManager WAL | no | no | unrelated |
| 14 | `/data/data/.../no_backup/androidx.work.workdb-shm` | WorkManager SHM | no | no | unrelated |
| 15 | `/data/data/.../files/generatefid.lock` | Firebase Installations (0 bytes) | no | no (empty) | unrelated |
| 16 | `/data/data/.../files/profileInstalled` | ART/baseline profile marker (24 bytes) | no | no | unrelated |
| 17 | `/data/data/.../files/PersistedInstallation....json` | Firebase Installations | no | no | unrelated |

Notably **absent** from the snapshot: `app_segment-disk-queue/a898c13577974eabf608-0`. That file *was* created during the run (we saw 12× `op=write key=segment.events` in the trace, each with `WRAPPED::v1::`-prefixed bytes inside the file body), then `op=rollover` + `op=readAsStream` + `op=removeFile` cleaned it up after the batch flushed to the Segment CDP HTTP endpoint. The directory `/data/data/$PKG/app_segment-disk-queue/` remains but is empty at snapshot time. The "wrapped" verdict for that file is established from the live trace, not from a residual on-disk artifact.

### Partial bypass detail (file #4)

The Segment KVS file `analytics-android-a898c13577974eabf608.xml` contains the following keys at snapshot time. The verdict per key:

| Key in shared_prefs | Value (verbatim) | Path written through |
|---|---|---|
| `segment.anonymousId` | `266bb5a5-d138-42a0-b912-5372db522e41` (plaintext UUID) | **bypasses** the `Storage` interface — direct SharedPreferences write via `AndroidKVS` |
| `segment.settings` | `{"integrations":{...},"metrics":{"sampleRate":0}}` (plaintext JSON) | **bypasses** the `Storage` interface |
| `segment.events.file.index.a898c13577974eabf608` | `1` (int; index counter) | bypasses |
| `segment.app.version` | `WRAPPED::v1::d{e` (sentinel + XORed `1.0`) | wrapped via `Storage.write(AppVersion, ...)` |
| `segment.app.build` | `WRAPPED::v1::d` (sentinel + XORed `1`) | wrapped via `Storage.write(AppBuild, ...)` |
| `segment.device.id` | `WRAPPED::v1::a1blfm3mxa1adxagd...` (sentinel + XORed UUID) | wrapped via `Storage.write(DeviceId, ...)` |

`segment.userId` and `segment.traits` were **read** through the Storage interface (we see `op=read key=segment.userId`) but were never written through it during this run — yet `identify(...)` was called. Either (a) the library decided not to persist the identify (e.g. because the auto-persist for `Constants.UserId`/`Constants.Traits` uses the KVS directly, mirroring the `anonymousId`/`settings` pattern), or (b) the writes happened but bypassed our wrapper. Either way, **the `Storage` interface is not the exclusive write path** for KVS-tier data on analytics-kotlin 1.24.1.

## Needle leak report

**Zero plaintext `SPIKE_*` leaks.** Confirmed three ways:

1. Per-file `grep SPIKE_` over all 17 files in the data dir: 0 matches.
2. Recursive `grep -rl 'SPIKE_' /data/data/<pkg>`: 0 matches.
3. Recursive `grep -rl 'dd099ca9'` (the spike UUID): 0 matches.

The needles either (a) never survived to a final on-disk artifact (the event file was uploaded and deleted before the snapshot — we *did* see the wrapped-and-XORed form of `SPIKE_EVENT_1/2/3`, `SPIKE_SCREEN_1`, `SPIKE_USER_<uuid>` written to `app_segment-disk-queue/a898c13577974eabf608-0` in the readAsStream trace at line 30 of the logcat capture, hex `5752 4150 5045 4423 3a76 313a 3a2e 7725 273a …` confirming the sentinel + XORed body), or (b) had no need to persist at all (e.g. `userId`/`traits` cleared on `clearIdentify`).

The **identify userId** specifically — `SPIKE_USER_dd099ca9-d52e-4093-b1e8-b6d1b3b9bd33` — is the most interesting one. It does NOT appear plaintext in the on-disk snapshot, but it also does NOT appear in the wrapped form in `segment.userId` inside the analytics-android xml. That means either it was cleared by the subsequent `clearIdentify()`, or it's never persisted to that KVS file (only held in memory + flushed into each event payload). For Phase 3 planning purposes, this means **we cannot yet positively confirm** whether a `userId` set without an immediate `clearIdentify` would land plaintext in the KVS file via the bypass path; the `anonymousId` and `settings` results say it almost certainly would.

## Trace log summary

Captured to `/tmp/segment-spike-android-logcat.txt`. Counts:

| Operation | Count |
|---|---:|
| `initialize` | 1 |
| `write` | 15 |
| `writePrefs` | **0** |
| `read` | 10 |
| `readAsStream` | 1 |
| `remove` | 0 |
| `removeFile` | 1 |
| `rollover` | 1 |
| `close` | 0 |

`write` breakdown by key:

| Key | Count |
|---|---:|
| `segment.events` (event queue file appends) | 12 |
| `segment.device.id` | 1 |
| `segment.app.version` | 1 |
| `segment.app.build` | 1 |

`read` breakdown by key:

| Key | Count |
|---|---:|
| `segment.anonymousId` | 2 |
| `segment.userId` | 1 |
| `segment.traits` | 1 |
| `segment.settings` | 1 |
| `segment.device.id` | 1 |
| `segment.app.version` | 1 |
| `segment.app.build` | 1 |
| `segment.events` | 1 |
| `build` (bare) | 1 |

**Most important trace observation:** `writePrefs` was called **zero** times during the entire run, despite Segment's KVS file ending up with plaintext `segment.anonymousId` and `segment.settings`. The on-disk evidence + trace evidence together prove there is at least one write path that bypasses the `Storage` interface on Android. That bypass path is the `AndroidKVS` direct-SharedPreferences write — visible in `AndroidStorageImpl.kt` source, used unconditionally for the auto-generated `anonymousId` at SDK init and for the settings response when the CDN fetch lands.

## Verdict

**Phase 3 Android is *partially* feasible without forking analytics-kotlin.**

What works cleanly via `Configuration.storageProvider`:

- The **event queue file** (`app_segment-disk-queue/<writeKey>-N`) — every `track`/`screen`/`identify` payload flows through `Storage.write(Constants.Events, json)` and our wrapper sees 100% of those bytes. Verified live: the only artifact ever written to that file during the run was the wrapped + XORed JSON.
- `segment.app.version`, `segment.app.build`, `segment.device.id` in the KVS file. All three landed with the sentinel.

What bypasses `Configuration.storageProvider` on analytics-kotlin 1.24.1:

- `segment.anonymousId` — written by the library at init via direct `SharedPreferences.edit().putString(...)` through `AndroidKVS`, not via `Storage.writePrefs(Constants.AnonymousId, ...)`. Lands plaintext.
- `segment.settings` — written by the library when the CDN settings response arrives, same bypass. Lands plaintext.
- Likely `segment.userId` and `segment.traits` (could not be definitively confirmed because the spike's `clearIdentify()` call cleared them before snapshot; reads through our wrapper observed but writes did not).

What this means for Phase 3 as currently planned:

- The "encrypt everything analytics-kotlin persists" goal is **not** achievable purely by setting `Configuration.storageProvider = WrappingStorageProvider(...)`. A custom `StorageProvider` covers the event queue (the largest surface — every track/screen/identify body) but does not cover the KVS keys that the library writes directly to SharedPreferences.
- To close the gap without forking, Phase 3 must additionally wrap the SharedPreferences file `analytics-android-<writeKey>` — e.g. by handing analytics-kotlin a custom `Context` whose `getSharedPreferences("analytics-android-${writeKey}", MODE_PRIVATE)` returns an `EncryptedSharedPreferences` (or a manual at-rest-encrypted wrapper). The known ANR caveat with `EncryptedSharedPreferences` on cold-init still applies; the parent plan's "EncryptedSharedPreferences-as-secondary-defense" fallback is the most likely landing spot.
- Alternatively: implement `Storage` from scratch (not wrap `AndroidStorageImpl`) and stop using `AndroidKVS` entirely. That's still a no-fork option — our spike already proves `StorageProvider` is the swap point — but it means re-implementing the events file format and the `Constants → SharedPreferences key` mapping ourselves. Higher risk of behavior drift across Segment library upgrades.

A pure "set `storageProvider` and we're done" Phase 3 is disproven for Android. A "set `storageProvider` + intercept the `analytics-android-<writeKey>` SharedPreferences file" Phase 3 is feasible without a fork.

## Branch state

```text
$ cd customerio-android && git rev-parse --abbrev-ref HEAD
spike/segment-storage-injection-2026-05-19

$ git log --oneline origin/main..HEAD
# (no commits — spike changes are unstaged on the branch, per the plan)

$ git status --short
 M datapipelines/src/main/kotlin/io/customer/datapipelines/extensions/AnalyticsExt.kt
 M samples/java_layout/src/main/java/io/customer/android/sample/java_layout/SampleApplication.java
?? datapipelines/src/main/kotlin/io/customer/datapipelines/spike/WrappingStorageProvider.kt
```

- Branch `spike/segment-storage-injection-2026-05-19` exists locally and is **not** pushed.
- All production-source edits live inside `datapipelines/` and `samples/java_layout/` per the plan's hard constraints. No other module was touched.
- `PreInitBufferScenario` toggle was not modified.

## Artifacts

- `/tmp/segment-spike-android-logcat.txt` — full logcat capture, filtered to `SegmentStorageTrace`, `SegmentStorageSpike`, `CustomerIO`, `SegmentSDK`.
- `/tmp/segment-spike-android-files.txt` — post-run `find -type f` output.
- `/tmp/segment-spike-android-inspect.txt` — per-file size + first-13-byte hex + needle hit/miss.
- `/tmp/segment-spike-android-report-data.txt` — (empty; abandoned earlier attempt — see inspect.txt instead).
