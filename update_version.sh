#!/bin/sh

# Script that updates the Kotlin file in the SDK that contains the semantic version of the SDK.
#
# Use script: ./update_version.sh 0.1.1 sdk/src/main/java/io/customer/sdk/Version.kt

set -e

NEW_VERSION="$1"
KOTLIN_SOURCE_FILE="$2"

# Given line: `    static let version: String = "0.1.1"`
# Regex string will match the line of the file that we can then substitute.
LINE_PATTERN="const val version: String = \"\(.*\)\""

echo "Updating file: $KOTLIN_SOURCE_FILE to new version: $NEW_VERSION"

# -i overwrites file
# "s/" means substitute given pattern with given string.
#
sed -i "s/$LINE_PATTERN/const val version: String = \"$NEW_VERSION\"/" "$KOTLIN_SOURCE_FILE"

echo "Done! New version: "

# print the line (/p) that is matched in the file to show the change.
sed -n "/$LINE_PATTERN/p" KOTLIN_SOURCE_FILE