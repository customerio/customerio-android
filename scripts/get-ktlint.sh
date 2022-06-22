#!/bin/sh

# Script to install ktlint CLI binary to use for lint checking. 
# This script is used to install a specific version of the CLI as specified in the script. This makes sure that 
# everyone on the team *and* the CI server are all using the same version. 
# 
# Releases of ktlint are not backwards compatible. We have found that when a new version is released, our lint 
# commands on the CI server break and we need to fix it. This prevents that by having everyone on the same version
# and we can be assured that all old and future builds will always compile (because we have a strict ktlint version set). 

# if debugging this script, use "set -ex"
set -e

INSTALL_PATH="$PWD"
KTLINT_VERSION="0.46.1"

install_ktlint() {
    echo "Downloading ktlint CLI to path $INSTALL_PATH"
    curl -sSLO https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint
    chmod a+x ktlint
    echo "ktlint installed and ready to use"
}

# checks if ktlint has never been downloaded before. 
if [[ -f "$INSTALL_PATH/ktlint" ]]; then 
    echo "ktlint is installed in $INSTALL_PATH. Checking if it's the expected version of $KTLINT_VERSION..."
    # Attempts to get the version of installed ktlint to make sure it's the same as rest of team is using. 
    INSTALLED_VERSION=$("$INSTALL_PATH/ktlint" --version)

    echo "Installed version is: $INSTALLED_VERSION, expect $KTLINT_VERSION."

    if [[ $KTLINT_VERSION == $INSTALLED_VERSION ]]; then 
        echo "ktlint installed at $INSTALL_PATH and correct version of $KTLINT_VERSION"
    else 
        echo "Deleting ktlint and installing required version $KTLINT_VERSION"

        rm "$INSTALL_PATH/ktlint"
        install_ktlint
    fi 
else 
    install_ktlint
fi 
