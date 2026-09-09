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

# Clean up any previous marker
rm -f "$FAILURE_MARKER_FILE"

mark_startup_failure() {
    echo "STARTUP_FAILURE" > "$FAILURE_MARKER_FILE"
    echo "::error::Emulator startup failure - eligible for retry"
    exit 2
}

echo "Waiting for device to come online..."
if ! adb wait-for-device; then
    echo "ERROR: adb wait-for-device failed"
    mark_startup_failure
fi

echo "Sleeping to let ADB stabilize..."
sleep 10

echo "Verifying boot completion..."
boot_complete=""
attempts=0
while [ "$boot_complete" != "1" ] && [ $attempts -lt $MAX_BOOT_WAIT ]; do
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

echo "Boot confirmed complete. Waiting for package manager..."
if ! adb shell pm wait-for-ready 2>/dev/null; then
    echo "Warning: pm wait-for-ready failed, sleeping instead..."
    sleep 5
fi

echo "Starting instrumentation tests for $SAMPLE_MODULE..."
# Run tests. Exit code 0 = pass, non-zero = test failure (NOT startup failure)
./gradlew ":samples:${SAMPLE_MODULE}:connectedDebugAndroidTest" --no-daemon --stacktrace -PuseKsp=true
