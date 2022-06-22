SHELL = /bin/sh

# Command designed to run on CI server to check if lint was run before pushing code to the repo. 
# This is because the CI server is not meant to commit code so we don't want to format code on the CI 
# server in case files get modified from formatting. 
lint-no-format: 
	ktlint --android "**/src/**/*.kt" 2> /dev/null 

format:
	ktlint --format --android "**/src/**/*.kt" 2> /dev/null 

# Designed for manually running linter on your computer during development. 
# format first, and then show lint errors that are left over. 
lint:
	make format && make lint-no-format 