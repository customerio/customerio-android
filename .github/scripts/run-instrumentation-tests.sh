#!/bin/bash
# run-instrumentation-tests.sh
# Run instrumentation tests with robust boot verification.
# Called by android-emulator-runner after emulator starts.
#
# Exit codes:
#   0 = tests passed
#   1 = tests failed (genuine test failure)
#   2 = startup/infra failure (boot timeout, device offline, etc.)

SAMPLE_MODULE="$1"
MAX_BOOT_WAIT="${2:-30}"
FAILURE_MARKER_FILE="${GITHUB_WORKSPACE}/.emulator-startup-failed"
SCRIPT_STARTED_FILE="${GITHUB_WORKSPACE}/.script-started"

# Clean up any previous markers
rm -f "$FAILURE_MARKER_FILE"

# Write script-started breadcrumb immediately
# Workflow uses this to detect action-level failures vs script failures
date -Iseconds > "$SCRIPT_STARTED_FILE"

mark_startup_failure() {
    echo "STARTUP_FAILURE" > "$FAILURE_MARKER_FILE"
    echo "::error::Emulator startup failure - eligible for retry"
    exit 2
}

# Bounded ADB wait-for-device with timeout
# If emulator drops offline after action's boot check, this prevents infinite hang
ADB_WAIT_TIMEOUT=120
echo "Waiting for device to come online (timeout: ${ADB_WAIT_TIMEOUT}s)..."
if ! timeout "$ADB_WAIT_TIMEOUT" adb wait-for-device; then
    echo "ERROR: adb wait-for-device timed out after ${ADB_WAIT_TIMEOUT}s"
    mark_startup_failure
fi

echo "Sleeping to let ADB stabilize..."
sleep 10

echo "Verifying boot completion..."
boot_complete=""
attempts=0
while [ "$boot_complete" != "1" ] && [ $attempts -lt "$MAX_BOOT_WAIT" ]; do
    boot_complete=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$boot_complete" != "1" ]; then
        sleep 2
        attempts=$((attempts + 1))
        echo "Boot check attempt $attempts/$MAX_BOOT_WAIT..."
    fi
done

if [ "$boot_complete" != "1" ]; then
    echo "ERROR: Emulator did not boot within timeout ($MAX_BOOT_WAIT attempts)"
    mark_startup_failure
fi

echo "Boot confirmed complete. Verifying package manager readiness..."

# Bounded package-manager readiness probe
# pm wait-for-ready is not supported on API 31 - use pm path android instead
# This command returns success once package manager is ready to respond
PM_READY_TIMEOUT=30
pm_attempts=0
pm_ready=false

while [ $pm_attempts -lt $PM_READY_TIMEOUT ]; do
    # pm path android succeeds once package manager is operational
    if adb shell pm path android 2>/dev/null | grep -q "package:"; then
        pm_ready=true
        echo "Package manager ready after $pm_attempts attempts"
        break
    fi
    sleep 1
    pm_attempts=$((pm_attempts + 1))
    if [ $((pm_attempts % 5)) -eq 0 ]; then
        echo "Package manager check attempt $pm_attempts/$PM_READY_TIMEOUT..."
    fi
done

if [ "$pm_ready" != "true" ]; then
    echo "ERROR: Package manager did not become ready within ${PM_READY_TIMEOUT}s"
    mark_startup_failure
fi

echo "Starting instrumentation tests for $SAMPLE_MODULE..."
# Run tests. Exit code 0 = pass, non-zero = test failure (NOT startup failure)
./gradlew ":samples:${SAMPLE_MODULE}:connectedDebugAndroidTest" --no-daemon --stacktrace -PuseKsp=true
