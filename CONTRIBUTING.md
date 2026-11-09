# Contributing

Thank you for helping improve WebJET AI.

## Development

Use Java 17 and the included Gradle wrapper. Before opening a pull request, run:

```shell
./gradlew clean check javadoc
```

Keep the library framework-neutral:

- Do not import WebJET CMS, Spring, servlet, or persistence APIs.
- Pass host-owned configuration through `AiProviderConfig`.
- Do not log credentials or include them in exceptions, `toString()` output, or
  cache identifiers.
- Add tests for provider payload parsing, error handling, and streaming changes.
- Write code, comments, commit messages, and test assertions in English.

## Changes and compatibility

- Use a focused branch and pull request for each change.
- Document user-visible changes under `Unreleased` in `CHANGELOG.md`.
- Preserve the public `com.webjetcms.ai` API. Call out any necessary
  incompatibility explicitly and release it only in an appropriate major version.
- Do not commit generated `build/` output or credentials.

Releases are produced only from protected version tags by the repository's
release workflow.

## Preparing a release

For 2.1.0, update the dependency and CLI examples, finalize the `CHANGELOG.md`
entry, and review the [upgrade notes](README.md#upgrading-from-20x). Keep the
development fallback at `2.1.0-SNAPSHOT`; the signed tag supplies the stable
version to the release workflow for both `webjet-ai` and `webjet-ai-local`.

Run the same release-candidate verification used by CI:

```shell
./gradlew clean check javadoc -PreleaseVersion=2.1.0
```

This verifies tests, framework boundaries, main/source/Javadoc JARs, legal files,
Maven coordinates, the local artifact's matching core dependency, and the local
CLI's help/version output. Run the opt-in native smoke test separately when a
verified model bundle is available; normal checks do not download or run a model.

Merge the reviewed release preparation into `main` before creating a signed,
annotated `v2.1.0` tag on that commit. The workflow rejects tags outside `main`,
unsigned tags, and versions missing from the changelog. The protected
`maven-central` environment must provide Central credentials and signing secrets
as configured in [release.yml](.github/workflows/release.yml).

Pushing the tag starts publication. After environment approval and successful
Central validation, review the staged deployment and publish it manually in
Central Portal. The GitHub Release is created after validation, so its existence
alone does not establish Maven availability. After manual publication, verify
that a consumer can resolve both 2.1.0 artifacts from Maven Central.
