#!/bin/bash

# Simple script to check the size of the SDK modules
# Run this script from the root of the repository

echo "Building all modules..."
./gradlew assembleRelease

echo -e "\nSDK Module Sizes:\n"
echo "Module | Size (in MB)"
echo "------ | -----------"

for module in base core datapipelines messaginginapp messagingpush tracking-migration
do
    if [ -f "$module/build/outputs/aar/$module-release.aar" ]; then
        size=$(du -h "$module/build/outputs/aar/$module-release.aar" | cut -f1)
        echo "$module | $size"
    else
        echo "$module | Not found"
    fi
done