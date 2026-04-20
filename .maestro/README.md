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

1. `maestro` CLI (tested with 2.0.9).
2. Android SDK + a booted Pixel emulator.
3. `ffmpeg`, Python 3 with Pillow (`pip3 install pillow`).
4. An Ext API bearer token for the test-prod Customer.io workspace.
5. `cdpApiKey` + `siteId` in `samples/local.properties` set to the same
   workspace the Ext API key targets (currently
   `cdpApiKey=a898c13577974eabf608`, `siteId=38eda114ab3f4593e11f`).

## Setup

```bash
cp .maestro/.env.example .maestro/.env
# paste MAESTRO_EXT_API_KEY into .maestro/.env

./gradlew :samples:java_layout:installDebug
```

## Run

```bash
./.maestro/run.sh                             # default: campaign_141 (shared)
./.maestro/run.sh smoke_login_event.yaml      # also shared (in harness)
./.maestro/run.sh inline_messages.yaml        # also shared (in harness)
```

All three flows live in [customerio/mobile-e2e/flows/](https://github.com/customerio/mobile-e2e/tree/main/flows) — the `run.sh` wrapper resolves them from `.maestro/harness/flows/` automatically.

Outputs land in `artifacts/<flow>/` (gitignored):

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

Set via `android:id` in XML layouts + `ViewUtils.prepareForAutomatedTests`
(for `contentDescription`). The same shared flow targets iOS using each
widget's matching `accessibilityIdentifier`.

## Known limitations

- `simctl`-style simulator recording collides with Maestro's session, so
  Android uses `adb shell screenrecord` (supported) while iOS falls back
  to a 5 fps `screenshot` poll — see the iOS sample's `capture_frames.sh`.
- No cleanup of created Customer.io customers. Test-prod workspace is
  fine for now.
