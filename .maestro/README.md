# Maestro E2E — Android (java_layout)

End-to-end Maestro flows that drive the `java_layout` sample app through
identify + event tracking and assert against the Customer.io Ext API that
the backend received the events and dispatched the expected in-app + push.

The main cross-platform flow (Campaign 141) lives in the shared harness at
[customerio/mobile-e2e](https://github.com/customerio/mobile-e2e). It's
pulled into `.maestro/harness/` automatically on the first `./run.sh`. This
directory holds only the platform-specific wrapper: `run.sh`, workspace
config, and a couple of optional smoke/inline flows that exercise features
unique to the Android sample.

## Prereqs

1. `maestro` CLI.
2. Android SDK + Java 17. The runner creates/boots the emulator.
3. `ffmpeg` and Python 3. Pillow is optional but enables annotated MP4s.
4. An Ext API bearer token for the test-prod Customer.io workspace.
5. `cdpApiKey` + `siteId` in `samples/local.properties` set to the same
   workspace the Ext API key targets.

## Setup

```bash
cp .maestro/.env.example .maestro/.env
# Fill MAESTRO_EXT_API_KEY; message Inbox uses fixture ID 21.
make e2e-setup
```

## Run

```bash
make e2e          # smoke + geofence + message Inbox; one build
make e2e-quick    # smoke only
make e2e-inbox    # message Inbox only
```

`make e2e` clones/updates the shared harness, provisions the emulator, builds
and installs the sample, runs the deterministic Android profile, and prints one
combined summary. Nothing needs to be started manually.

`./.maestro/run.sh <flow.yaml>` remains available when an app is already
installed and a single low-level flow is being debugged.

Outputs land in `artifacts/e2e/android/<flow>/` (gitignored):

| File | What it is |
|---|---|
| `device.mp4` | Raw emulator screen recording |
| `annotated.mp4` | Side-by-side device + live step panel + backend response card |
| `tickmarks.html` | Per-step pass/fail with Ext API responses inline |
| `sink.jsonl` | Raw JSON events posted by the flow's assertion scripts |
| `debug/` | Maestro's native debug output (commands JSON, maestro.log, failure screenshot) |

## Files here

| File | Purpose |
|---|---|
| `e2e.sh` | One-command setup/profile wrapper around the shared top-level runner |
| `run.sh` | Starts sink + emulator capture, runs Maestro, renders HTML + annotated video |
| `.env.example` | Template — copy to `.env` and fill in `MAESTRO_EXT_API_KEY` |
| `.env` | Your `MAESTRO_EXT_API_KEY` (gitignored) |
| `harness/` | Shared scripts + flows auto-cloned from [`customerio/mobile-e2e`](https://github.com/customerio/mobile-e2e) (gitignored) |

## Selector strategy

The sample exposes the same accessibility ID on every widget the shared
flow drives, matching the iOS APN-UIKit sample — one snake_case vocabulary:

| id | widget |
|---|---|
| `login_button` | Login button |
| `first_name_input` | Display name input |
| `email_input` | Email input |
| `custom_event_button` | Dashboard "Send Custom Event" |
| `event_name_input` | Custom-event name input |
| `property_name_input` | Custom-event property name |
| `property_value_input` | Custom-event property value |
| `send_event_button` | Fire-event button on the custom-event screen |

Set via `android:id` in XML layouts + `ViewUtils.setAccessibilityId`
(for `contentDescription`). The same shared flow targets iOS using each
widget's matching `accessibilityIdentifier`.

## Known limitations

- `simctl`-style simulator recording collides with Maestro's session, so
  Android uses `adb shell screenrecord` (supported) while iOS falls back
  to a 5 fps `screenshot` poll — see the iOS sample's `capture_frames.sh`.
- No cleanup of created Customer.io customers. Test-prod workspace is
  fine for now.
