#!/bin/bash

echo "Deploying WebJET AI release tag to GitHub..."

set -euo pipefail

repository_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "${repository_directory}"

properties_file='gradle.properties'

for required_command in awk git grep; do
	if ! command -v "${required_command}" >/dev/null 2>&1; then
		echo "Required command is not available: ${required_command}" >&2
		exit 1
	fi
done

if [ ! -f "${properties_file}" ]; then
	echo "Gradle properties file was not found: ${properties_file}" >&2
	exit 1
fi

release_version=$(
	awk '
		/^[[:space:]]*#/ { next }
		/^[[:space:]]*releaseVersion[[:space:]]*=/ {
			value = $0
			sub(/^[^=]*=/, "", value)
			sub(/^[[:space:]]*/, "", value)
			sub(/[[:space:]]*$/, "", value)
			print value
			exit
		}
	' "${properties_file}"
)

if [[ ! "${release_version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
	echo "releaseVersion must be a stable semantic version such as 1.0.0; received: ${release_version:-<empty>}" >&2
	exit 1
fi

tag_name="v${release_version}"
tag_message="WebJET AI ${release_version}"

if [ -n "$(git status --porcelain)" ]; then
	echo 'The working tree is not clean. Commit or stash all changes before creating a release tag.' >&2
	git status --short >&2
	exit 1
fi

echo 'Switching to main and updating it from origin...'
git switch main
git pull --ff-only origin main

if ! grep -Fq "## [${release_version}]" CHANGELOG.md; then
	echo "CHANGELOG.md has no entry for ${release_version}." >&2
	echo "Add a heading such as: ## [${release_version}] - YYYY-MM-DD" >&2
	exit 1
fi

local_commit=$(git rev-parse HEAD)
remote_commit=$(git rev-parse refs/remotes/origin/main)
if [ "${local_commit}" != "${remote_commit}" ]; then
	echo 'Local main is not identical to origin/main. Push the release commit to main before tagging it.' >&2
	exit 1
fi

if git show-ref --verify --quiet "refs/tags/${tag_name}"; then
	echo "Local tag already exists: ${tag_name}" >&2
	exit 1
fi

remote_tag_refs=$(git ls-remote --tags origin "refs/tags/${tag_name}" "refs/tags/${tag_name}^{}")
if [ -n "${remote_tag_refs}" ]; then
	echo "Remote tag already exists: ${tag_name}" >&2
	exit 1
fi

git_user_email=$(git config --get user.email || true)
git_signing_key=$(git config --get user.signingkey || true)
if [ -z "${git_user_email}" ]; then
	echo 'Git user.email is not configured.' >&2
	exit 1
fi
if [ -z "${git_signing_key}" ]; then
	echo 'Git user.signingkey is not configured.' >&2
	exit 1
fi

echo "-------------------------------------"
echo "Release version: ${release_version}"
echo "Tag: ${tag_name}"
echo "Commit: ${local_commit}"
echo "Tagger email: ${git_user_email}"
echo "Signing key: ${git_signing_key}"

if [ ! -r /dev/tty ]; then
	echo 'An interactive terminal is required to confirm the release.' >&2
	exit 1
fi

printf 'Press Enter to create and push the signed release tag, or Ctrl+C to abort...' >/dev/tty
IFS= read -r _ </dev/tty

git tag -s "${tag_name}" -m "${tag_message}"
git tag -v "${tag_name}"
git push origin "${tag_name}"

echo "Release workflow started for ${tag_name}."
echo 'Monitor it at https://github.com/webjetcms/webjet-ai/actions/workflows/release.yml'
