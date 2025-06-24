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
    
    # Parse SDK startup trace metrics if available
    SDK_TIME_BASELINE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationBaselineProfiles") | .metrics.cio_sdk_startupSumMs.median' "$RESULT_FILE" 2>/dev/null)
    SDK_TIME_NONE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationNone") | .metrics.cio_sdk_startupSumMs.median' "$RESULT_FILE" 2>/dev/null)
    
    if [ "$SDK_TIME_BASELINE" != "null" ] && [ "$SDK_TIME_NONE" != "null" ] && [ -n "$SDK_TIME_BASELINE" ] && [ -n "$SDK_TIME_NONE" ]; then
        echo "🔧 SDK Main Thread Analysis:"
        echo ""
        
        # Calculate SDK performance metrics
        SDK_IMPROVEMENT=$(echo "scale=2; (($SDK_TIME_NONE - $SDK_TIME_BASELINE) / $SDK_TIME_NONE) * 100" | bc -l 2>/dev/null || echo "0")
        
        # Get SDK execution counts
        SDK_COUNT_BASELINE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationBaselineProfiles") | .metrics.cio_sdk_startupCount.median' "$RESULT_FILE")
        SDK_COUNT_NONE=$(jq -r '.benchmarks[] | select(.name == "startupCompilationNone") | .metrics.cio_sdk_startupCount.median' "$RESULT_FILE")
        
        # Display core metrics
        echo "🔸 SDK Main Thread Time:"
        echo "   With Baseline Profiles: ${SDK_TIME_BASELINE}ms (${SDK_COUNT_BASELINE} execution)"
        echo "   Without Baseline Profiles: ${SDK_TIME_NONE}ms (${SDK_COUNT_NONE} execution)"
        echo ""
        
        # Performance assessment
        if (( $(echo "$SDK_IMPROVEMENT > 0" | bc -l) )); then
            echo "📊 SDK Performance: ✅ ${SDK_IMPROVEMENT}% faster with baseline profiles"
        else
            SDK_REGRESSION=$(echo "scale=2; -1 * $SDK_IMPROVEMENT" | bc -l)
            echo "📊 SDK Performance: ⚠️  ${SDK_REGRESSION}% slower with baseline profiles"
        fi
        
        # ANR risk assessment
        if (( $(echo "$SDK_TIME_BASELINE > 100" | bc -l) )) || (( $(echo "$SDK_TIME_NONE > 100" | bc -l) )); then
            echo "🚨 SDK ANR Risk: 🔴 HIGH - SDK blocking main thread >100ms"
        elif (( $(echo "$SDK_TIME_BASELINE > 50" | bc -l) )) || (( $(echo "$SDK_TIME_NONE > 50" | bc -l) )); then
            echo "🚨 SDK ANR Risk: 🟡 MODERATE - SDK blocking main thread 50-100ms"  
        else
            echo "🚨 SDK ANR Risk: 🟢 LOW - SDK main thread time <50ms"
        fi
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