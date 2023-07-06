SHELL = /bin/sh

lint-error-message:
	echo "\n\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\nLooks like there are lint errors to fix.\nRead the LINT.md document in this project to learn how to fix these problems.\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"

# Command designed to run on CI server to check if lint was run before pushing code to the repo. 
# This is because the CI server is not meant to commit code so we don't want to format code on the CI 
# server in case files get modified from formatting. 
# 
# This comand runs ktlint to try and find lint errors. If any are found an error message is shown and then the command fails which will 
# trigger the CI server to show an error for linting. 
# If there are no lint errors, the command will succeed and not show any error messages. 
lint-no-format: 
	./ktlint --android "**/src/**/*.kt" 2> /dev/null || (make lint-error-message && false)

# Run ktlint formatter to automatically fix many lint errors. Good to run this before checking for lint errors as this formatting might fix many issues. 
format:
	./ktlint --format --android "**/src/**/*.kt" 2> /dev/null 

# Designed for manually running linter on your computer during development. 
# format first, and then show lint errors that are left over. 
lint:
	make lint-install 
	make format
	make lint-no-format 

# How we install ktlint in the team. 
lint-install:
	./scripts/get-ktlint.sh

# Generate public API binary
generate-api:
	./gradlew apiDump

# Run kotlin binary validator
run-binary-validator:
	./scripts/binary-validation.sh
