#!/bin/bash

# Customer.io Android SDK Startup Benchmark Script
# Usage: ./benchmark-startup.sh

set -e

GRADLE_TASK=":baselineprofile:connectedBenchmarkReleaseAndroidTest"

echo "🚀 Customer.io Android SDK Startup Benchmark"
echo "=========================================="
echo "Branch: $(git branch --show-current)"
echo "Commit: $(git rev-parse --short HEAD)"
echo ""

# Clean previous results
echo "🧹 Cleaning previous benchmark results..."
rm -rf baselineprofile/build/outputs/connected_android_test_additional_output/

# Run benchmarks
echo "📊 Running startup benchmarks on connected device..."

./gradlew $GRADLE_TASK

# Find and parse results
echo ""
echo "📈 Benchmark Results"
echo "===================="

RESULT_FILE=$(find baselineprofile/build/outputs/connected_android_test_additional_output -name "*benchmarkData.json" | head -1)

if [ -z "$RESULT_FILE" ]; then
    echo "❌ No benchmark results found!"
    exit 1
fi

echo "📄 Results file: $RESULT_FILE"
echo ""

# Extract and format results using jq if available, otherwise use grep/sed
if command -v jq >/dev/null 2>&1; then
    echo "📊 Startup Performance Metrics:"
    echo ""
    
    # Parse startup timing with jq
    jq -r '
        .benchmarks[] | 
        "🔸 " + .name + ":" +
        "\n   Median: " + (.metrics.timeToInitialDisplayMs.median | tostring) + "ms" +
        "\n   Min:    " + (.metrics.timeToInitialDisplayMs.minimum | tostring) + "ms" +
        "\n   Max:    " + (.metrics.timeToInitialDisplayMs.maximum | tostring) + "ms" +
        "\n   Runs:   " + (.metrics.timeToInitialDisplayMs.runs | map(tostring) | join(", ")) + "ms" +
        "\n"
    ' "$RESULT_FILE"
    
    # Calculate startup improvement if both tests exist
    NONE_MEDIAN=$(jq -r '.benchmarks[] | select(.name == "startupCompilationNone") | .metrics.timeToInitialDisplayMs.median' "$RESULT_FILE")
    BASELINE_MEDIAN=$(jq -r '.benchmarks[] | select(.name == "startupCompilationBaselineProfiles") | .metrics.timeToInitialDisplayMs.median' "$RESULT_FILE")
    
    if [ "$NONE_MEDIAN" != "null" ] && [ "$BASELINE_MEDIAN" != "null" ]; then
        IMPROVEMENT=$(echo "scale=2; (($NONE_MEDIAN - $BASELINE_MEDIAN) / $NONE_MEDIAN) * 100" | bc -l 2>/dev/null || echo "0")
        if (( $(echo "$IMPROVEMENT > 0" | bc -l) )); then
            echo "✅ Baseline Profile Improvement: ${IMPROVEMENT}% faster"
        else
            REGRESSION=$(echo "scale=2; (($BASELINE_MEDIAN - $NONE_MEDIAN) / $NONE_MEDIAN) * 100" | bc -l)
            echo "⚠️  Baseline Profile Impact: ${REGRESSION}% slower"
        fi
        echo ""
    fi
    
    # Parse frame timing metrics for ANR analysis if available
    FRAME_DATA_BASELINE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationBaselineProfiles") | .sampledMetrics.frameOverrunMs.P99' "$RESULT_FILE" 2>/dev/null)
    FRAME_DATA_NONE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationNone") | .sampledMetrics.frameOverrunMs.P99' "$RESULT_FILE" 2>/dev/null)
    
    if [ "$FRAME_DATA_BASELINE" != "null" ] && [ "$FRAME_DATA_NONE" != "null" ] && [ -n "$FRAME_DATA_BASELINE" ] && [ -n "$FRAME_DATA_NONE" ]; then
        echo "🖼️  Frame Timing ANR Analysis:"
        echo ""
        
        # Parse frame timing data
        jq -r '
            .benchmarks[] | 
            "🔸 " + .name + " Frame Performance:" +
            "\n   Frame Overrun P99: " + (.sampledMetrics.frameOverrunMs.P99 | tostring) + "ms" +
            "\n   Frame Duration P99: " + (.sampledMetrics.frameDurationCpuMs.P99 | tostring) + "ms" +
            "\n   Frame Count Median: " + (.metrics.frameCount.median | tostring) +
            "\n"
        ' "$RESULT_FILE"
        
        # Calculate frame timing impact
        FRAME_IMPACT=$(echo "scale=2; (($FRAME_DATA_BASELINE - $FRAME_DATA_NONE) / $FRAME_DATA_NONE) * 100" | bc -l 2>/dev/null || echo "0")
        
        # ANR risk assessment
        echo "🚨 ANR Risk Assessment:"
        
        # Assess baseline profiles
        if (( $(echo "$FRAME_DATA_BASELINE > 200" | bc -l) )); then
            echo "   With Baseline Profiles: 🔴 HIGH ANR RISK (${FRAME_DATA_BASELINE}ms P99)"
        elif (( $(echo "$FRAME_DATA_BASELINE > 100" | bc -l) )); then
            echo "   With Baseline Profiles: 🟡 MODERATE ANR RISK (${FRAME_DATA_BASELINE}ms P99)"
        else
            echo "   With Baseline Profiles: 🟢 LOW ANR RISK (${FRAME_DATA_BASELINE}ms P99)"
        fi
        
        # Assess without baseline profiles
        if (( $(echo "$FRAME_DATA_NONE > 200" | bc -l) )); then
            echo "   Without Baseline Profiles: 🔴 HIGH ANR RISK (${FRAME_DATA_NONE}ms P99)"
        elif (( $(echo "$FRAME_DATA_NONE > 100" | bc -l) )); then
            echo "   Without Baseline Profiles: 🟡 MODERATE ANR RISK (${FRAME_DATA_NONE}ms P99)"
        else
            echo "   Without Baseline Profiles: 🟢 LOW ANR RISK (${FRAME_DATA_NONE}ms P99)"
        fi
        
        # Frame timing impact
        if (( $(echo "$FRAME_IMPACT > 0" | bc -l) )); then
            echo "   Frame Timing Impact: ⚠️  ${FRAME_IMPACT}% worse with baseline profiles"
        else
            FRAME_IMPROVEMENT=$(echo "scale=2; -1 * $FRAME_IMPACT" | bc -l)
            echo "   Frame Timing Impact: ✅ ${FRAME_IMPROVEMENT}% better with baseline profiles"
        fi
        
        echo ""
        echo "📋 ANR Analysis Notes:"
        echo "   • Frames >16.67ms cause UI jank"
        echo "   • Frames >100ms indicate main thread blocking"
        echo "   • Frames >200ms are high ANR risk"
        echo "   • Android ANRs trigger after ~5 seconds of main thread blocking"
        echo ""
    fi
else
    echo "📊 Raw Results (install 'jq' for formatted output):"
    echo ""
    cat "$RESULT_FILE"
fi

echo "✅ Benchmark completed successfully!"
echo ""
echo "💡 To compare with another branch:"
echo "   git checkout other-branch"
echo "   ./benchmark-startup.sh"