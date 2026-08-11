#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -euo pipefail

echo "WARNING: CHECK VERSION IN gradle.properties before deploying"
date

release_version=$(./gradlew -q printReleaseVersion)

if [ -r /dev/tty ]; then
	printf 'Check the artifact version (%s). Press Enter to continue or Ctrl+C to abort...' "$release_version" >/dev/tty
	IFS= read -r _ </dev/tty
	./gradlew --no-daemon publishMavenJavaPublicationToGitHubPackagesRepository -PskipReleaseConfirmation=true </dev/tty
	# ./gradlew --no-daemon centralBundle -PskipReleaseConfirmation=true </dev/tty
else
	echo "Check the artifact version (${release_version}). No interactive terminal detected, continuing without confirmation prompt."
	./gradlew --no-daemon publishMavenJavaPublicationToGitHubPackagesRepository -PskipReleaseConfirmation=true
	# ./gradlew --no-daemon centralBundle -PskipReleaseConfirmation=true
fi

echo "Verify on https://github.com/webjetcms?tab=packages"