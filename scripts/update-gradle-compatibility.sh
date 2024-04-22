#!/bin/sh

# Script that update files required for the Android Gradle Plugin version compatibility with
# older versions of the Android Gradle Plugin (AGP) and related dependencies.
#
# Usage:
# ./scripts/update-gradle-compatibility.sh
#   --agpVersion <new_agp_version>
#   --gradleVersion <new_gradle_version>
#   --javaVersion <new_java_version>
#   --kotlinJVMVersion <new_kotlin_jvm_version>
#   --apkScaleVersion <new_apk_scale_version>
#   --packagingResourcesAction <packaging_resources_action>
# Example with only AGP version:
# ./scripts/update-gradle-compatibility.sh --agpVersion 7.4.1
# Example with all arguments:
# ./scripts/update-gradle-compatibility.sh
#   --agpVersion 7.4.1
#   --gradleVersion 7.6.4
#   --javaVersion JavaVersion.VERSION_1_8
#   --kotlinJVMVersion 1.8
#   --apkScaleVersion 0.1.4
#   --packagingResourcesAction packagingOptions

set -e

NEW_AGP_VERSION=""
NEW_GRADLE_VERSION=""
NEW_JAVA_VERSION=""
NEW_KOTLIN_JVM_VERSION=""
NEW_APK_SCALE_VERSION=""
PACKAGING_RESOURCES_ACTION=""

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
        --javaVersion)
            NEW_JAVA_VERSION="$2"
            shift
            ;;
        --kotlinJVMVersion)
            NEW_KOTLIN_JVM_VERSION="$2"
            shift
            ;;
        --apkScaleVersion)
            NEW_APK_SCALE_VERSION="$2"
            shift
            ;;
        --packagingResourcesAction)
            PACKAGING_RESOURCES_ACTION="$2"
            shift
            ;;
        *)  # Handle unrecognized options
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
    shift
done

# Setting default values for arguments if not provided. Defaults are based on given AGP version.
case "$NEW_AGP_VERSION" in
    8.*)
      echo "AGP version is already 8.0.0 or higher. No need to update the project. Exiting without changes."
      exit 0 # Exit successfully
        ;;
    7.*)
        NEW_GRADLE_VERSION="${NEW_GRADLE_VERSION:-7.6.4}"
        NEW_JAVA_VERSION="${NEW_JAVA_VERSION:-JavaVersion.VERSION_1_8}"
        NEW_KOTLIN_JVM_VERSION="${NEW_KOTLIN_JVM_VERSION:-1.8}"
        NEW_APK_SCALE_VERSION="${NEW_APK_SCALE_VERSION:-0.1.4}"
        PACKAGING_RESOURCES_ACTION="${PACKAGING_RESOURCES_ACTION:-packagingOptions}"
        ;;
esac

RELATIVE_PATH_TO_SCRIPTS_DIR=$(dirname "$0")
ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR=$(realpath "$RELATIVE_PATH_TO_SCRIPTS_DIR/..")

# Obtains path to the files that need to be updated.
# Uses CLI tool sd to replace string in a file: https://github.com/chmln/sd
# Uses regex string to match the line of the file that we can then substitute.

VERSIONS_BUILD_SOURCE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/buildSrc/src/main/kotlin/io.customer/android/Versions.kt"
echo "Updating file: $VERSIONS_BUILD_SOURCE_FILE to new AGP version: $NEW_AGP_VERSION"
# Given line: `internal const val ANDROID_GRADLE_PLUGIN = "8.3.1"`
sd "internal const val ANDROID_GRADLE_PLUGIN = \"(.*)\"" "internal const val ANDROID_GRADLE_PLUGIN = \"$NEW_AGP_VERSION\"" "$VERSIONS_BUILD_SOURCE_FILE"
# Given line: `internal const val APK_SCALE = "0.1.7"`
sd "internal const val APK_SCALE = \"(.*)\"" "internal const val APK_SCALE = \"$NEW_APK_SCALE_VERSION\"" "$VERSIONS_BUILD_SOURCE_FILE"

GRADLE_WRAPPER_PROPERTIES_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
echo "Updating file: $GRADLE_WRAPPER_PROPERTIES_FILE to new gradle version: $NEW_GRADLE_VERSION"
# Given line: `distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip`
sd "gradle-[0-9.]+-bin.zip" "gradle-${NEW_GRADLE_VERSION}-bin.zip" "$GRADLE_WRAPPER_PROPERTIES_FILE"

BASE_MODULE_BUILD_GRADLE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/base/build.gradle"
echo "Updating file: $BASE_MODULE_BUILD_GRADLE_FILE to new Java version: $NEW_JAVA_VERSION"
# Given line: `sourceCompatibility = JavaVersion.VERSION_17`
sd "sourceCompatibility = JavaVersion.VERSION_[0-9]+" "sourceCompatibility = $NEW_JAVA_VERSION" "$BASE_MODULE_BUILD_GRADLE_FILE"
# Given line: `targetCompatibility = JavaVersion.VERSION_17`
sd "targetCompatibility = JavaVersion.VERSION_[0-9]+" "targetCompatibility = $NEW_JAVA_VERSION" "$BASE_MODULE_BUILD_GRADLE_FILE"

ANDROID_CONFIG_BUILD_GRADLE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/scripts/android-config.gradle"
echo "Updating file: $ANDROID_CONFIG_BUILD_GRADLE_FILE to new Java version: $NEW_JAVA_VERSION and Kotlin JVM version: $NEW_KOTLIN_JVM_VERSION"
# Given line: `sourceCompatibility = JavaVersion.VERSION_17`
sd "sourceCompatibility = JavaVersion.VERSION_[0-9]+" "sourceCompatibility = $NEW_JAVA_VERSION" "$ANDROID_CONFIG_BUILD_GRADLE_FILE"
# Given line: `targetCompatibility = JavaVersion.VERSION_17`
sd "targetCompatibility = JavaVersion.VERSION_[0-9]+" "targetCompatibility = $NEW_JAVA_VERSION" "$ANDROID_CONFIG_BUILD_GRADLE_FILE"
# Given line: `jvmTarget = '17'`
sd "jvmTarget = '[0-9]+'$" "jvmTarget = '$NEW_KOTLIN_JVM_VERSION'" "$ANDROID_CONFIG_BUILD_GRADLE_FILE"

KOTLIN_COMPOSE_BUILD_GRADLE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/samples/kotlin_compose/build.gradle.kts"

echo "Updating file: $KOTLIN_COMPOSE_BUILD_GRADLE_FILE to packaging options: $PACKAGING_RESOURCES_ACTION"
# Given line: `packaging {`
sd "packaging \{" "$PACKAGING_RESOURCES_ACTION {" "$KOTLIN_COMPOSE_BUILD_GRADLE_FILE"

echo "Done! Showing changes to confirm it worked: "
git diff
