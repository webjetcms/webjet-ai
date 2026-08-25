# Changelog

All notable changes to this project are documented in this file. The project
uses [Semantic Versioning](https://semver.org/).

## [1.2.0]

### Added

- Built-in discovery through `AiClient.discover()` and explicit, client-local custom
  providers through `AiClient.discover(AiProvider...)`, without class-path scanning or
  a mutable global registry. Supplied custom providers transfer ownership only after
  successful client creation.
- Immutable, sorted provider IDs through `AiClient.providers()`, public built-in
  `String` constants and `builtIns()` catalogue through `AiProviders`, and a
  standalone custom-provider implementation guide.
- Dynamic model catalogues continue to come from the selected provider through
  `listModels(...)`; `AiClient` does not hardcode model identifiers.
- Immutable, model- and operation-specific image option metadata through
  `AiClient.imageOptions(...)`, plus provider-specific scalar controls in the
  `com.webjetcms.ai.image.ImageOptions` builder. Image option values and metadata
  now live together in the dedicated `com.webjetcms.ai.image` package.
- Validation for image requests, including provider-specific option combinations,
  custom GPT Image 2 dimensions, and required non-blank prompts.
- Image generation and editing through OpenRouter, with support for one reference
  image and PNG, JPEG, and WebP responses.

## [1.1.1]

### Fixed

- Gemini batch embedding requests now set `outputDimensionality` directly on
  each request item when an explicit dimension is configured
  ([2781476](https://github.com/webjetcms/webjet-ai/commit/2781476aef800e51f7ab15ee69d5300b853b82c9)).

## [1.1.0]

### Added

- Provider-neutral batch embedding API through `EmbeddingRequest.builder()`,
  `EmbeddingOptions`, `EmbeddingResponse`, `EmbeddingVector`, and
  `AiClient.embed(...)`.
- Embedding support for OpenAI, Gemini, and OpenRouter with optional output
  dimensions.

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

[Unreleased]: https://github.com/webjetcms/webjet-ai/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/webjetcms/webjet-ai/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/webjetcms/webjet-ai/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/webjetcms/webjet-ai/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/webjetcms/webjet-ai/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/webjetcms/webjet-ai/releases/tag/v0.1.0
