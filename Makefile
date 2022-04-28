SHELL = /bin/sh

lint: 
	ktlint --android "**/src/**/*.kt" 2> /dev/null 

format:
	ktlint --format --android "**/src/**/*.kt" 2> /dev/null 