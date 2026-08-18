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
