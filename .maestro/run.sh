#!/usr/bin/env bash
# Run a Maestro flow end-to-end and produce an HTML tick-mark report.
#
#   .maestro/run.sh [flow-file]                     # defaults to maestro_test_campaign.yaml
#   .maestro/run.sh smoke_login_event_logout.yaml
#
# Outputs land in artifacts/<flow-name>/:
#   device.mp4        - adb screenrecord of the run
#   tickmarks.html    - rich per-step report w/ screenshots + video embedded
#   report.html       - Maestro's built-in HTML summary
#   debug/            - maestro --debug-output dir (commands-*.json, maestro.log)
#
# Requires: adb on PATH, maestro on PATH, python3, and .maestro/.env with
# MAESTRO_EXT_API_KEY=<bearer token>.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# --- Shared harness: sink + report/video renderers + Ext-API assertion helper.
# Pulled from https://github.com/customerio/mobile-e2e on first run and
# refreshed on subsequent runs. Gitignored in this repo.
HARNESS_DIR="$SCRIPT_DIR/harness"
HARNESS_REPO="https://github.com/customerio/mobile-e2e.git"
if [[ ! -d "$HARNESS_DIR/.git" ]]; then
  echo ">> cloning harness from $HARNESS_REPO"
  git clone --depth 1 "$HARNESS_REPO" "$HARNESS_DIR"
else
  git -C "$HARNESS_DIR" pull --ff-only >/dev/null 2>&1 || true
fi

FLOW="${1:-campaign_141.yaml}"
FLOW_NAME="$(basename "$FLOW" .yaml)"
OUT_DIR="artifacts/$FLOW_NAME"
DEBUG_DIR="$OUT_DIR/debug"

# Flow resolution: prefer a local override in .maestro/, fall back to the
# shared flow in the harness. Lets a sample repo author a platform-specific
# flow without losing access to the shared cross-platform flows.
resolve_flow() {
  local name="$1"
  if [[ -f ".maestro/$name" ]]; then echo ".maestro/$name"; return; fi
  if [[ -f ".maestro/harness/flows/$name" ]]; then echo ".maestro/harness/flows/$name"; return; fi
  echo "" ; return
}

mkdir -p "$OUT_DIR" "$DEBUG_DIR"
rm -rf "$DEBUG_DIR"/*

if [[ -f "$SCRIPT_DIR/.env" ]]; then
  set -a; source "$SCRIPT_DIR/.env"; set +a
fi
if [[ -z "${MAESTRO_EXT_API_KEY:-}" ]]; then
  echo "warn: MAESTRO_EXT_API_KEY not set; backend assertions will fail auth" >&2
fi

if ! adb devices | grep -q "device$"; then
  echo "error: no adb device attached" >&2
  exit 2
fi

echo ">> starting local sink (captures backend assertion values)"
SINK_LOG="$OUT_DIR/sink.jsonl"
python3 "$HARNESS_DIR/scripts/sink.py" "$SINK_LOG" --port 8899 >"$OUT_DIR/sink.stderr" 2>&1 &
SINK_PID=$!
# Give it a moment to bind the port.
for _ in 1 2 3 4 5; do
  if curl -s -o /dev/null http://127.0.0.1:8899/ ; then break; fi
  sleep 0.2
done

echo ">> starting device recording"
RUN_STARTED_AT_MS=$(python3 -c "import time;print(int(time.time()*1000))")
adb shell screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 180 /sdcard/maestro-run.mp4 &
REC_PID=$!
REC_STARTED_AT_MS=$(python3 -c "import time;print(int(time.time()*1000))")
trap 'adb shell pkill -2 screenrecord >/dev/null 2>&1 || true; kill $REC_PID 2>/dev/null || true; kill $SINK_PID 2>/dev/null || true' EXIT

FLOW_PATH="$(resolve_flow "$FLOW")"
if [[ -z "$FLOW_PATH" ]]; then
  echo "error: flow '$FLOW' not found in .maestro/ or .maestro/harness/flows/" >&2
  exit 2
fi
echo ">> running maestro: $FLOW_PATH"
set +e
maestro test \
  --format=HTML \
  --output="$OUT_DIR/report.html" \
  --debug-output="$DEBUG_DIR" \
  --flatten-debug-output \
  -e APP_ID=io.customer.android.sample.java_layout \
  "$FLOW_PATH" \
  | tee "$OUT_DIR/run.log"
EXIT=$?
set -e

echo ">> stopping recording"
adb shell pkill -2 screenrecord >/dev/null 2>&1 || true
sleep 2
adb pull /sdcard/maestro-run.mp4 "$OUT_DIR/device.mp4" >/dev/null || echo "warn: failed to pull recording"

echo ">> stopping sink"
kill $SINK_PID >/dev/null 2>&1 || true
wait $SINK_PID 2>/dev/null || true

echo ">> rendering tick-mark report"
python3 "$HARNESS_DIR/scripts/render_report.py" \
  "$DEBUG_DIR" \
  "$OUT_DIR/tickmarks.html" \
  --screens-dir artifacts \
  --video "$OUT_DIR/device.mp4" \
  --sink "$SINK_LOG" \
  --title "$FLOW_NAME"

echo ">> rendering annotated side-by-side video"
python3 "$HARNESS_DIR/scripts/render_video.py" \
  --commands "$DEBUG_DIR"/commands-*.json \
  --device "$OUT_DIR/device.mp4" \
  --rec-started-ms "$REC_STARTED_AT_MS" \
  --sink "$SINK_LOG" \
  --out "$OUT_DIR/annotated.mp4" \
  || echo "warn: annotated video render failed"

echo ">> done: $OUT_DIR/tickmarks.html (exit=$EXIT)"
open "$OUT_DIR/tickmarks.html" 2>/dev/null || true
open "$OUT_DIR/annotated.mp4" 2>/dev/null || true
exit "$EXIT"
