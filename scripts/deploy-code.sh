#!/bin/bash

set -e

if [[ "$OSSRH_USERNAME" == "" || 
      "$OSSRH_PASSWORD" == "" || 
      "$SIGNING_KEY_ID" == "" || 
      "$SIGNING_PASSWORD" == "" || 
      "$SIGNING_KEY" == "" || 
      "$SONATYPE_STAGING_PROFILE_ID" == "" ]]; then # makes sure environment variables set 
    echo "Forgot to set environment variables OSSRH_USERNAME, OSSRH_PASSWORD, SIGNING_KEY_ID, SIGNING_PASSWORD, SIGNING_KEY, SONATYPE_STAGING_PROFILE_ID (value found in 1password for android sdk). Set them, then try again."
    echo "Set variable with command (yes, with the double quotes around the variable value): export NAME_OF_VAR=\"foo\""
    exit 1
fi 

if [[ "$MODULE_VERSION" == "" ]]; then 
    echo "Forgot to set environment variable MODULE_VERSION (the next semantic version of the software to deploy). Set the variable, then try again."
    echo "Set variable with command (yes, with the double quotes around the variable value): export NAME_OF_VAR=\"foo\""
    exit 1
fi 

./gradlew androidSourcesJar javadocJar
MODULE_VERSION="$MODULE_VERSION" ./gradlew publishReleasePublicationToSonatypeRepository --max-workers 1 closeAndReleaseSonatypeStagingRepository

