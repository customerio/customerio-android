# Maestro E2E — Android (kotlin_compose)

End-to-end smoke test that drives the `kotlin_compose` sample app through login
and a custom event, and asserts against the Customer.io Ext API that the
backend received the identify and dispatched the expected in-app + push.

## Current coverage

Each run generates a fresh email `maestro+android-<uuid>@cio.test` for isolation.

Steps (all currently passing — 36/36 commands COMPLETED):

1. Launch app, log in with the unique email.
2. **Back-end assertion #1** — poll Ext API until an `in_app` for this email has `metrics.sent` populated. Proves SDK identify → CDP → services → campaign fire → in-app dispatched.
3. Dismiss the welcome "Hey there!" in-app modal once it renders.
4. Assert dashboard buttons (`Send Random Event`, `Send Custom Event`).
5. **Back-end assertion #2** — poll for a `push` with `metrics.drafted`. The emulator has no real FCM token so delivery can't finalize, but `drafted` proves the backend tried to send.
6. Tap `Send Random Event`, then `Send Custom Event`, fill `event=maestro_test_event / run_id=<uuid>`, tap Send Event.
7. Back to dashboard.

## Prereqs

1. `maestro` CLI (tested with 2.0.9).
2. Android SDK + running Pixel emulator.
3. Ext API bearer token for a test-prod Customer.io workspace.
4. `cdpApiKey` in `samples/local.properties` must map to the same workspace the Ext API key queries. In this repo that's `a898c13577974eabf608`.

## Setup

```bash
# 1) Create local env file (gitignored) with your Ext API key:
cp .maestro/.env.example .maestro/.env
# then edit .maestro/.env and paste your key

# 2) Build + install the sample on a booted emulator:
./gradlew :samples:kotlin_compose:installDebug
```

## Run

From the Android repo root:

```bash
export $(grep -v '^#' .maestro/.env | xargs)
maestro -p android test .maestro/smoke_login_event_logout.yaml
```

## Files

| File | Purpose |
|---|---|
| `config.yaml` | Maestro config |
| `smoke_login_event_logout.yaml` | The flow |
| `scripts/setup_run.js` | Generates `output.run_id` + `output.email` |
| `scripts/assert_message_delivered.js` | Polls Ext API for (email, type, min_metric) |
| `.env.example` | Template for local env |
| `.env` | Actual key (gitignored) |

## Selector strategy

Compose `testTag` isn't exposed to Maestro as a resource-id in this sample
(the app doesn't set `testTagsAsResourceId = true` at the semantics root).
Flow targets **visible text** instead — `"Login"`, `"Send Random Event"`, etc.
To migrate to `id:` selectors across all three platforms, the small change
is adding `Modifier.semantics { testTagsAsResourceId = true }` at the root
of each screen's Scaffold.

## Known limitations

- **Real FCM delivery** isn't wired up — we assert `drafted` on push. On a real device change the assertion's `MIN_METRIC` to `"sent"` or `"delivered"`.
- No cleanup of created customers. Test-prod workspace is fine for now.
