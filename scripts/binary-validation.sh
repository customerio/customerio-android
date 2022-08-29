#!/bin/sh

./gradlew apiCheck -q
EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
  # shellcheck disable=SC2028
  echo "❌ Looks like some 'public' mode was added or removed from the project. If we're not careful, this can break the SDK for customers.\n\nGenerating new .api files for you to add to your commit. Make sure to review these files in your pull request to make sure your modifications were not on purpose."

  ./gradlew apiDump -q

  echo "API dump done, please check the modified '.api' files to make sure that the 'public' code changes that were done were done on purpose and not by accident. If done on purpose, please try committing again!"
  exit $EXIT_CODE
fi


exit 0
