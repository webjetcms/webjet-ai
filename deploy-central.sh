#!/bin/bash

set -euo pipefail

bundle_path='build/central/central-bundle.zip'
central_portal_url='https://central.sonatype.com/publishing'

for required_command in curl base64 tr; do
	if ! command -v "${required_command}" >/dev/null 2>&1; then
		echo "Required command is not available: ${required_command}" >&2
		exit 1
	fi
done

release_version=$(./gradlew -q printReleaseVersion)

echo "Maven Central deployment version: ${release_version}"
echo 'The bundle will be uploaded for validation and will require manual publication in Central Portal.'

if [ -r /dev/tty ]; then
	printf 'Press Enter to continue or Ctrl+C to abort...' >/dev/tty
	IFS= read -r _ </dev/tty
else
	echo 'No interactive terminal detected, continuing without confirmation prompt.'
fi

central_username=${CENTRAL_USERNAME:-}
central_password=${CENTRAL_PASSWORD:-}

if [ -z "${central_username}" ] || [ -z "${central_password}" ]; then
	if [ ! -r /dev/tty ]; then
		echo 'Set CENTRAL_USERNAME and CENTRAL_PASSWORD to Central Portal User Token credentials.' >&2
		exit 1
	fi

	if [ -z "${central_username}" ]; then
		printf 'Central Portal token username: ' >/dev/tty
		IFS= read -r central_username </dev/tty
	fi
	if [ -z "${central_password}" ]; then
		printf 'Central Portal token password: ' >/dev/tty
		IFS= read -r -s central_password </dev/tty
		printf '\n' >/dev/tty
	fi
fi

if [ -z "${central_username}" ] || [ -z "${central_password}" ]; then
	echo 'Central Portal User Token username and password must not be empty.' >&2
	exit 1
fi

echo 'Building the signed Maven Central bundle...'
if [ -r /dev/tty ]; then
	./gradlew --no-daemon clean centralBundle \
		-PreleaseVersion="${release_version}" \
		-PskipReleaseConfirmation=true </dev/tty
else
	./gradlew --no-daemon clean centralBundle \
		-PreleaseVersion="${release_version}" \
		-PskipReleaseConfirmation=true
fi

if [ ! -s "${bundle_path}" ]; then
	echo "Central bundle was not created: ${bundle_path}" >&2
	exit 1
fi

central_token=$(printf '%s:%s' "${central_username}" "${central_password}" | base64 | tr -d '\r\n')
trap 'unset central_token central_password' EXIT

echo 'Uploading the bundle to Central Portal for validation...'
upload_response=''
if ! upload_response=$(
	curl --request POST --silent --show-error --fail-with-body \
		--header "Authorization: Bearer ${central_token}" \
		--form "bundle=@${bundle_path};type=application/octet-stream" \
		"https://central.sonatype.com/api/v1/publisher/upload?name=com.webjetcms%3Awebjet-ai%3A${release_version}&publishingType=USER_MANAGED"
); then
	echo 'Central Portal rejected the bundle upload:' >&2
	printf '%s\n' "${upload_response}" >&2
	exit 1
fi

deployment_id=$(printf '%s' "${upload_response}" | tr -d '\r\n')
if [[ ! "${deployment_id}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
	echo "Central Portal returned an invalid deployment identifier: ${upload_response}" >&2
	exit 1
fi

echo "Central deployment created: ${deployment_id}"
echo "Review validation results and publish it manually at ${central_portal_url}"
