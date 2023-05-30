#!/bin/sh

# Script that updates the Kotlin file in the SDK that contains the semantic version of the SDK.
#
# Use script: ./scripts/update-version.sh "0.1.1"

set -e

NEW_VERSION="$1"

RELATIVE_PATH_TO_SCRIPTS_DIR=$(dirname "$0")
ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR=$(realpath "$RELATIVE_PATH_TO_SCRIPTS_DIR/..")
KOTLIN_SOURCE_FILE="$ABSOLUTE_PATH_TO_SOURCE_CODE_ROOT_DIR/sdk/src/main/java/io/customer/sdk/Version.kt"

echo "Updating file: $KOTLIN_SOURCE_FILE to new version: $NEW_VERSION"

# Uses CLI tool sd to replace string in a file: https://github.com/chmln/sd
# Given line: `    static let version: String = "0.1.1"`
# Regex string will match the line of the file that we can then substitute.
sd "const val version: String = \"(.*)\"" "const val version: String = \"$NEW_VERSION\"" $KOTLIN_SOURCE_FILE

echo "Done! Showing changes to confirm it worked: "
git diff $KOTLIN_SOURCE_FILE