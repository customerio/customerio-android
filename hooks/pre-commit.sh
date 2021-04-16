#!/bin/bash

set -e

RED='\033[0;31m'
YELLOW='\033[0;33m'

if ! [ -x "$(command -v ktlint)" ]; then
    echo -e "${RED}You need to install the program 'ktlint' on your machine to continue."
    echo ""
    echo -e "${RED}The easiest way is 'brew install ktlint'. If you're not on macOS, check out other instructions for installing: https://ktlint.github.io/#getting-started"
    exit 1
fi

ktlint --android --format  "app/src/**/*.kt" "sdk/src/**/*.kt" 2> /dev/null
