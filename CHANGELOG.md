# Changelog

All notable changes to this project are documented in this file. The project
uses [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-08-18

### Added

- Provider-neutral embedding requests and responses through
  `AiOperation.EMBEDDING`.
- Batch embedding support for OpenAI, Gemini, and OpenRouter with explicit
  output dimensions and retrieval task types.

## [1.0.0] - 2026-08-13

### Added

- Automatic immutable request preparation in `AiClient`, with host-auditable
  detection metadata exposed by `AiRequest`.
- Identifier-free `execute`, `stream`, and `listModels` overloads for clients
  containing exactly one provider.
- Safe, repeatable standard prompt expansion through `AiPromptTemplate`.
- Filesystem-to-binary, generated-image MIME, token-usage aggregation, response
  text-copy, and model display-label conveniences.
- Provider-specific request examples and capability guides for OpenAI, Gemini,
  and OpenRouter.
- Local release signing with GPG keyring keys, including interactive version
  confirmation before publication.
- Signed stable and snapshot artifact publication to GitHub Packages.
- Reused immutable prompt-protection results while preparing provider requests,
  avoiding redundant scans of untrusted text.
- Disabled automatic HTTP retries for OpenAI, aligning its transport policy
  with the bundled Gemini and OpenRouter providers.

## [0.1.0] - 2026-08-06

### Added

- Framework-neutral AI client and immutable provider configuration.
- OpenAI, Google Gemini, and OpenRouter providers.
- Text, streaming, image generation, image editing, and model-list operations.
- Provider-neutral response, token-usage, media, and error types.
- Prompt-injection defense utilities.
- Java 17 build, tests, API documentation, and Maven Central publication.

[Unreleased]: https://github.com/webjetcms/webjet-ai/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/webjetcms/webjet-ai/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/webjetcms/webjet-ai/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/webjetcms/webjet-ai/releases/tag/v0.1.0
