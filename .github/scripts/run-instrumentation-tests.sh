#!/bin/bash
# run-instrumentation-tests.sh
# Run instrumentation tests with robust boot verification.
# Called by android-emulator-runner after emulator starts.

set -e

SAMPLE_MODULE="$1"
MAX_BOOT_WAIT="${2:-30}"

echo "Waiting for device to come online..."
adb wait-for-device

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
    echo "ERROR: Emulator did not boot within timeout"
    exit 1
fi

echo "Boot confirmed complete. Waiting for package manager..."
adb shell pm wait-for-ready 2>/dev/null || sleep 5

echo "Starting instrumentation tests for $SAMPLE_MODULE..."
./gradlew ":samples:${SAMPLE_MODULE}:connectedDebugAndroidTest" --no-daemon --stacktrace -PuseKsp=true
