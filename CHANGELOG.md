# Changelog

All notable changes to this project are documented in this file. The project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- Local EuroLLM generation places the trusted task after protected source data in
  the user turn, with the full security rules retained in the system turn. This
  prevents harmless input from being copied with internal markers instead of
  processed according to the task.
- Discarded local generation output now throws a non-retryable
  `AiProviderException` with category `OUTPUT_REJECTED`, rather than returning an
  empty successful response. Empty completions and echoed chat control tokens
  are rejected as well; rejected text is never included in exception diagnostics.
- Added production-bundle generation regressions in a fresh JVM, including direct
  provider and `AiClient` execution, prompt injection, and real model marker echo.

## [2.1.1]

### Fixed

- New OpenAI `gpt-image-*` models reuse GPT Image 2 option definitions and size
  validation for generation and editing, while known earlier models retain their
  existing options.

## [2.1.0]

### Added

- Text-only `AiOperation.CHAT` with provider-managed conversation history through
  OpenAI Responses and Gemini Interactions, supporting both completed responses
  and streamed text. OpenRouter and local providers do not support `CHAT`.
- Conversation continuation through `AiRequest.Builder.previousResponseId(...)`
  using the same provider's `AiResponse.responseId()`, without replaying prior
  messages. Continuation IDs are accepted only for `CHAT` requests.
- Explicit `store(true)` opt-in for every `CHAT` request; provider-side storage
  remains disabled by default. Chat requests reject binary input, image options,
  translation options, and blank text.
- Chat-specific prompt defenses that allow conversational questions while keeping
  untrusted content subordinate to security rules and trusted task instructions.
- Immutable `AiCapabilities` metadata through `AiClient.capabilities(...)` and
  backward-compatible provider defaults, without credentials, network access, or
  inference during lookup.
- Separate adapter and model support, per-operation streaming, continuation storage
  requirements, and embedding capabilities for remote and local providers. Unknown
  remote models retain their existing execution behavior.
- Provider-neutral `AiClient.deleteResponse(...)` and compatible `AiProvider`
  defaults, with optional per-call cancellation and `AiResponseDeletion` results
  distinguishing `DELETED` from `ALREADY_ABSENT`.
- OpenAI Responses and Gemini Interactions deletion using existing pooled
  transports, authentication, timeouts, credential redaction, and request-level
  cancellation. Confirmation is conservative: generic 404s, malformed or
  oversized bodies, and unexpected success responses remain failures. Deletion
  does not retry automatically or manage conversations or local history.
- Adapter-level `AiCapabilities.responseDeletionSupport()` metadata. OpenAI and
  Gemini report support; OpenRouter and local providers report unsupported.
- Optional `AiLifecycleListener` overloads for `execute` and `stream`, exposing
  OpenAI response IDs and Gemini interaction IDs before completion validation.
  Lifecycle observation is independent of text filtering and never advances
  successful conversation continuation.
- Immutable `AiRequestLifecycle` metadata on `AiProviderException`, distinguishing
  not-submitted attempts, explicit rejections, unknown remote outcomes, and
  observed response IDs. Known facts survive cancellation, listener failures,
  exception category changes, and credential redaction; retryability stays separate.
- Per-call `AiCancellationToken` overloads for synchronous `execute` and `stream`,
  aborting only the active HTTP request in OpenAI, Gemini, and OpenRouter while
  retaining existing signatures and shared transport resources.
- Non-retryable `AiErrorCategory.CANCELLED` failures preserved through credential
  redaction. Existing exception constructors use `UNKNOWN`.
- Compatibility defaults for custom and local providers that check cancellation
  around blocking execution and streamed callbacks without closing shared resources.
- Stable `INVALID_CONTINUATION` and `UNSUPPORTED_OPERATION` error categories,
  including conservative OpenAI HTTP and SSE continuation classification and
  explicit unsupported-operation failures across bundled providers.
- `AiProviderException.withCategory(...)` preserves diagnostics and credential
  redaction behavior. Ambiguous provider errors retain `UNKNOWN`; categories
  do not trigger retries, conversation resets, or storage changes.

### Fixed

- Chat instructions explicitly exclude internal input boundaries from answers.
  `AiClient` removes echoed untrusted input blocks from completed chat responses
  and streamed text, including markers split across chunks, while preserving
  response IDs, usage, media and completion metadata.
  Leading whitespace is handled consistently across chunk boundaries without
  changing answer indentation.

### Changed

- The development build defaults to `2.1.0-SNAPSHOT`; release builds use
  `-PreleaseVersion=2.1.0` for both artifacts.
- Maven Central releases now wait for manual publication in Central Portal after
  validation, while the GitHub Release is still created automatically.

### Compatibility

- Existing execution signatures and text-listener lambdas remain available.
  Custom providers inherit lifecycle overloads that observe returned responses;
  earlier IDs and precise submission outcomes require adapter support. Existing
  exception constructors and older serialized failures default to unknown lifecycle.
- Custom providers inherit `UNSUPPORTED_OPERATION` for deletion and need no new
  implementations. The three-argument `AiCapabilities` constructor is available
  with deletion support `UNKNOWN`. The fourth record component,
  `responseDeletionSupport`, participates in equality and record-based serialization.
- Existing execution methods, provider implementations, and the four-argument
  `AiResponse` constructor remain supported. `AiResponse` now has a fifth record
  component, `responseId`, which participates in equality and record-based
  serialization.
- Exhaustive switches over `AiOperation` must handle `CHAT`. See the
  [2.1 upgrade notes](README.md#upgrading-from-20x) for host integration details.

## [2.0.4]

### Added

- Windows x64 support for portable local E5 FP32 embedding, M2M100 FP32/INT8
  translation, and EuroLLM Q4_K_M generation bundles using the existing native
  dependencies and model ZIPs. E5 INT8 AVX-512 VNNI remains Linux x86-64 only.

## [2.0.3]

### Fixed

- Local EuroLLM text generation appends `...` to non-empty answers that do not end
  with `.` after trimming whitespace.
- Local EuroLLM text generation returns empty text when a completion echoes library
  safety markers, including Markdown-escaped tags, instead of returning leaked
  instructions or a tagged answer.

## [2.0.1]

### Fixed

- Move `LocalModelTool` into `webjet-ai-local` project.

## [2.0.0]

### Added

- JDK-only `webjet-ai-local-model-tool` CLI in the existing library JAR for
  preparing verified, reproducible E5 embedding, M2M100 translation, and
  Q4_K_M EuroLLM-1.7B-Instruct generation bundles without executing the model.
- Separate `com.webjetcms:webjet-ai-local` artifact with DJL local tokenization,
  ONNX Runtime inference, llama.cpp GGUF generation, strict schema-v1 bundle
  validation, E5 mean pooling, L2 normalization, model-specific input preparation,
  batching, and provider-owned native/extraction lifecycle.
- Provider-neutral query and document embedding input roles, with automatic E5
  `query:` and `passage:` preparation and document-compatible defaults.
- `AiProviderConfig.empty()` for providers that require no connection settings.
- FP32 local runtime support for Linux x86-64 and macOS ARM64, plus fail-closed
  AVX-512 VNNI validation for the explicit Linux INT8 bundle.
- Normal `AiRequest`/`AiResponse` local text translation through the approved
  `facebook/m2m100_418M` encoder-decoder bundle, with dynamic tokenizer-derived
  language selection, portable FP32 and INT8 variants, and reusable greedy decoding.
- Normal `TEXT` request generation through the approved Q4_K_M
  `utter-project/EuroLLM-1.7B-Instruct` GGUF bundle for multilingual CPU
  summarization, rewriting, classification, extraction, and other CMS tasks.
- Catalogue-driven preparation, strict validation, and greedy local inference for
  ONNX encoder-decoder and GGUF causal model families.
- Provider-neutral `TranslationOptions` and provider-selectable literal translation input handling, so
  deterministic transformations can preserve input without weakening prompt defenses
  for generative providers.

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

[2.1.0]: https://github.com/webjetcms/webjet-ai/compare/v2.0.4...v2.1.0
[2.0.4]: https://github.com/webjetcms/webjet-ai/releases/tag/v2.0.4
[2.0.0]: https://github.com/webjetcms/webjet-ai/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/webjetcms/webjet-ai/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/webjetcms/webjet-ai/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/webjetcms/webjet-ai/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/webjetcms/webjet-ai/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/webjetcms/webjet-ai/releases/tag/v0.1.0
