#!/bin/sh

./gradlew apiCheck -q
EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
  echo "❌ apiCheck failed, running apiDump for you..."

  ./gradlew apiDump -q

  echo "API dump done, please check the results and then try your commit again!"
  exit $EXIT_CODE
fi


exit 0
