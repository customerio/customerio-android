# Manual Test Plan — Geofence Transition Exactly-Once Delivery (Android)

Covers the geofence transition delivery refactor (`geofence-exactly-once-delivery`)
that reuses the push "exactly-once" pattern: a transition is recorded in a
disk-backed `PendingDeliveryStore` and delivered by **exactly one** of two
channels — the **WorkManager worker** (direct HTTP `/track`, survives process
death) or the **foreground flush** (analytics pipeline) — arbitrated by an
atomic `claim()`.

> **Core invariant for every case:** each geofence transition (`CIO Geofence
> Entered` / `CIO Geofence Exited`) reaches Customer.io **exactly once** — never
> zero, never twice. Before the refactor both channels fired in parallel, so a
> transition observed while the app was alive was sent **twice**; this plan
> verifies that's now collapsed to one.

---

## Why no synthetic broadcast (read first)

Unlike push (which ships a `SimulatePushDeliveryReceiver`), a geofence
transition **cannot** be faked with `adb am broadcast`: `GeofencingEvent` has no
public constructor and is parsed from GMS-internal intent extras. So we drive
the **real** production path with the emulator's mock location, crossing a
geofence that the SDK has actually registered with GMS from the workspace's
`/geofences/nearby` response.

The exactly-once arbitration logic itself is also covered by automated tests:
- `core` — `PendingDeliveryFlusherTest`, `PendingDeliveryClaimTest`
- `location` — `GeofenceEventWorkerTest` (claim / skip / restore), `GeofenceBroadcastReceiverTest` (append + schedule, no inline publish), `AsyncGeofenceEventTrackerTest`, `LocationLifecycleObserverTest`, `PendingGeofenceDeliveryTest`

---

## Environment & setup

| Item | Value |
|---|---|
| Sample app | `samples/java_layout` (debug build) |
| Package | `io.customer.android.sample.java_layout` |
| Emulator | Google **APIs** image (GMS present), location enabled |
| SDK log level | **DEBUG** (Settings → Log level = Debug) |
| Precondition | Profile identified; workspace has ≥1 geofence configured; `ModuleLocation` registered with background-location permission granted so transitions fire in the background |

### Helper commands

```bash
PKG=io.customer.android.sample.java_layout

# 1) Live geofence logs (logcat tag is [CIO], geofence messages are prefixed [Geofence])
adb logcat | grep -E "\[Geofence\]"

# 2) Inspect the on-disk pending store
adb shell run-as $PKG cat files/cio_pending_geofence_delivery.json

# 3) Move the device to drive real ENTER/EXIT transitions (lng THEN lat)
adb emu geo fix <insideLng> <insideLat>     # cross INTO a geofence  -> ENTER
adb emu geo fix <outsideLng> <outsideLat>   # cross OUT of it        -> EXIT

# 4) Network / lifecycle control
adb shell cmd connectivity airplane-mode enable             # go offline
adb shell cmd connectivity airplane-mode disable            # go online
adb shell input keyevent KEYCODE_HOME                       # background app
adb shell am start -n $PKG/.ui.dashboard.DashboardActivity  # foreground app
adb shell am force-stop $PKG                                # kill process
```

> Tip: confirm geofences are registered first — look for
> `[Geofence] Geofence sync succeeded: N regions registered`, then note the
> registered geofence's center/radius to choose inside/outside coordinates.

### Portal check

Workspace → **Data & Integrations → Activity Logs**, filter for the
`CIO Geofence Entered` / `CIO Geofence Exited` event and confirm the **count** for a
given transition.

### Log hallmarks (message text after the `[Geofence]` prefix)

| Stage | Log line |
|---|---|
| Transition recorded | `'<id>' ENTER: queued for exactly-once delivery (WorkManager now, analytics pipeline on next foreground)` |
| Worker delivered | `'<id>' ENTER: delivered via WorkManager (direct HTTP); removed from pending store` |
| Worker backed off | `'<id>' ENTER: worker skipped — already delivered via the analytics pipeline (claim lost)` |
| Flush start | `Geofence foreground flush: N pending transition(s) to hand off to the analytics pipeline` |
| Flush cancel WM | `'<id>' ENTER: cancelled pending WorkManager delivery before flush` |
| Flush published | `'<id>' ENTER: published to analytics pipeline via foreground flush` |
| Flush done | `Geofence foreground flush complete: N transition(s) handed off this run` |

---

## TC1 — Happy path: online, app in foreground

**Objective:** Transition delivered by the WorkManager worker; no double send.

**Preconditions:** Online; app foreground; inside-vs-outside coords known.

**Steps:**
1. Start log capture.
2. `adb emu geo fix` to cross **into** a geofence (ENTER).

**Expected logs:**
```
[Geofence] '<id>' ENTER: queued for exactly-once delivery ...
[Geofence] '<id>' ENTER: delivered via WorkManager (direct HTTP); removed from pending store
```
_(No `published to analytics pipeline via foreground flush` for this transition — the app was already foregrounded, so no new ON_START fires.)_

**Expected store:** `[]` (worker claimed + delivered).

**Expected portal:** ✅ `CIO Geofence Entered` = **1** for `<id>`.

---

## TC2 — Foreground flush: offline at transition, then online + foreground

**Objective:** When the worker can't confirm (offline), the transition is
delivered via the analytics-pipeline flush on next foreground, and the worker is
cancelled so it never also sends.

**Preconditions:** **Offline** (airplane mode on); app **backgrounded**;
background-location granted (so the transition still fires offline).

**Steps:**
1. Background the app, go offline.
2. `adb emu geo fix` to cross into a geofence (ENTER).
3. Inspect store.
4. **Go online**, then foreground the app.
5. Inspect store.

**Expected logs (step 2):**
```
[Geofence] '<id>' ENTER: queued for exactly-once delivery ...
```
_(no `delivered via WorkManager` — the worker's CONNECTED constraint is unmet while offline.)_

**Expected store after step 3:**
```
[{"geofenceId":"<id>","transition":"ENTER","latitude":...,"longitude":...,"timestamp":...}]
```

**Expected logs (step 4 — on foreground):**
```
[Geofence] Geofence foreground flush: 1 pending transition(s) ...
[Geofence] '<id>' ENTER: cancelled pending WorkManager delivery before flush
[Geofence] '<id>' ENTER: published to analytics pipeline via foreground flush
[Geofence] Geofence foreground flush complete: 1 transition(s) handed off this run
```

**Expected store after step 5:** `[]`

**Expected portal:** ✅ `CIO Geofence Entered` = **1** for `<id>`, via the analytics
pipeline — **not** the worker.

---

## TC3 — No double delivery (cross-check of TC2)

**Objective:** Confirm the cancelled worker never *also* delivers after the flush.

**Preconditions:** Run TC2; stay online + foreground for ~2–3 min afterward.

**Steps:**
1. After TC2's flush, keep watching logs.
2. Re-check the portal.

**Expected logs:** **No** `'<id>' ENTER: delivered via WorkManager ...` for that
transition at any point after the flush.

**Expected store:** `[]` (stays empty).

**Expected portal:** ✅ `CIO Geofence Entered` = **exactly 1** for `<id>` — **not 2**.
(The headline guarantee — pre-refactor this was 2.)

---

## TC4 — Persistence across process death

**Objective:** A pending transition survives a process kill and is delivered on
relaunch.

**Preconditions:** **Offline**; app backgrounded.

**Steps:**
1. Cross into a geofence (ENTER) while offline / backgrounded.
2. Inspect store (expect `<id>` present).
3. `am force-stop` the app.
4. Inspect store again (must still contain `<id>`).
5. **Go online**, relaunch the app.
6. Inspect store.

**Expected store after steps 2 & 4:** one entry for `<id>` — **survives the kill**.

**Expected logs (step 5 — relaunch foreground):**
```
[Geofence] Geofence foreground flush: 1 pending transition(s) ...
[Geofence] '<id>' ENTER: published to analytics pipeline via foreground flush
[Geofence] Geofence foreground flush complete: 1 transition(s) handed off this run
```
_(Or, if WorkManager runs first on relaunch: `delivered via WorkManager ...` and the flush logs `snapshot ... 0`. Either way — exactly one delivery.)_

**Expected store after step 6:** `[]`

**Expected portal:** ✅ `CIO Geofence Entered` = **1** for `<id>` after relaunch.

---

## TC5 — Empty foreground (nothing pending)

**Objective:** Foregrounding with nothing pending is a clean no-op.

**Preconditions:** Store empty (fresh, or after a completed case); online.

**Steps:**
1. Background, then foreground the app.

**Expected logs:**
```
[Geofence] Geofence foreground flush: 0 pending transition(s) ...
```
_(no `cancelled`, no `published`, no `flush complete`.)_

**Expected store:** absent / `[]`.

**Expected portal:** ⛔ no new geofence event.

---

## TC6 — EXIT transition

**Objective:** Same exactly-once guarantee for EXIT.

**Steps:** Repeat TC1 (or TC2) but cross **out** of the geofence.

**Expected:** identical logs/store/portal with `EXIT` / `CIO Geofence Exited` = **1**.

---

## Summary matrix

| TC | Network @ transition | App state | Delivery channel | Logs hallmark | Portal |
|----|----------------------|-----------|------------------|---------------|--------|
| TC1 | Online | Foreground | WorkManager | `delivered via WorkManager` | **1** |
| TC2 | Offline → Online | Bg → Fg | Pipeline flush | `cancelled ...` + `published to analytics pipeline` | **1** |
| TC3 | (after TC2) | Foreground | — | **no** late `delivered via WorkManager` | **1, not 2** |
| TC4 | Offline → Online | killed → Fg | Pipeline flush (or WM) | store survives `force-stop` | **1** |
| TC5 | Online | Bg → Fg | none | `flush: 0 pending` | **0** |
| TC6 | any | any | one channel | `EXIT` variants | **1** |
