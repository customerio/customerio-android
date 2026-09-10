#!/bin/sh

# Updates the Android Gradle Plugin (AGP) and Gradle wrapper versions used by
# the sample-app compatibility builds.
#
# Usage:
# ./scripts/update-gradle-compatibility.sh
#   --agpVersion <new_agp_version>
#   --gradleVersion <new_gradle_version>
# Example:
# ./scripts/update-gradle-compatibility.sh
#   --agpVersion 8.9.1
#   --gradleVersion 8.11.1

set -e

NEW_AGP_VERSION=""
NEW_GRADLE_VERSION=""

# Parsing named arguments
while [ "$#" -gt 0 ]; do
    case "$1" in
        --agpVersion)
            NEW_AGP_VERSION="$2"
            shift
            ;;
        --gradleVersion)
            NEW_GRADLE_VERSION="$2"
            shift
            ;;
        *)  # Handle unrecognized options
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
    shift
done

if [ -z "$NEW_AGP_VERSION" ] || [ -z "$NEW_GRADLE_VERSION" ]; then
    echo "Both --agpVersion and --gradleVersion are required."
    exit 1
fi

case "$NEW_AGP_VERSION:$NEW_GRADLE_VERSION" in
    *[!0-9.:]*)
        echo "AGP and Gradle versions may contain only numbers and periods."
        exit 1
        ;;
esac

RELATIVE_PATH_TO_SCRIPTS_DIR=$(dirname "$0")
ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR=$(realpath "$RELATIVE_PATH_TO_SCRIPTS_DIR/..")

VERSIONS_BUILD_SOURCE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/buildSrc/src/main/kotlin/io.customer/android/Versions.kt"
echo "Updating file: $VERSIONS_BUILD_SOURCE_FILE to new AGP version: $NEW_AGP_VERSION"
# Given line: `internal const val ANDROID_GRADLE_PLUGIN = "8.3.1"`
sed -i.bak -E "s|internal const val ANDROID_GRADLE_PLUGIN = \"[0-9.]+\"|internal const val ANDROID_GRADLE_PLUGIN = \"$NEW_AGP_VERSION\"|" "$VERSIONS_BUILD_SOURCE_FILE"
rm "$VERSIONS_BUILD_SOURCE_FILE.bak"
grep -Fq "internal const val ANDROID_GRADLE_PLUGIN = \"$NEW_AGP_VERSION\"" "$VERSIONS_BUILD_SOURCE_FILE"

GRADLE_WRAPPER_PROPERTIES_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
echo "Updating file: $GRADLE_WRAPPER_PROPERTIES_FILE to new gradle version: $NEW_GRADLE_VERSION"
# Given line: `distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip`
sed -i.bak -E "s|gradle-[0-9.]+-bin.zip|gradle-${NEW_GRADLE_VERSION}-bin.zip|" "$GRADLE_WRAPPER_PROPERTIES_FILE"
rm "$GRADLE_WRAPPER_PROPERTIES_FILE.bak"
grep -Fq "gradle-${NEW_GRADLE_VERSION}-bin.zip" "$GRADLE_WRAPPER_PROPERTIES_FILE"

echo "Done! Showing compatibility-version changes:"
git -C "$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR" diff -- \
    buildSrc/src/main/kotlin/io.customer/android/Versions.kt \
    gradle/wrapper/gradle-wrapper.properties
