# WebJET AI

WebJET AI is a framework-neutral Java library for communicating with OpenAI,
Google Gemini, and OpenRouter. It provides provider-neutral request and response
types, streaming support, image operations, text embeddings, model discovery,
and prompt-security utilities without requiring Spring, a servlet container, a
database, or WebJET CMS.

## Requirements

- Java 17 or newer
- Gradle or Maven for dependency management

## Installation

Gradle:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.webjetcms:webjet-ai:1.0.0'
}
```

Maven:

```xml
<dependency>
    <groupId>com.webjetcms</groupId>
    <artifactId>webjet-ai</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Minimal usage

```java
import java.util.List;

import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.provider.gemini.GeminiProvider;
import com.webjetcms.ai.provider.openai.OpenAiProvider;

try (AiClient client = AiClient.of(new OpenAiProvider())) {
    AiProviderConfig config = AiProviderConfig.builder(secretStore.get("openai.apiKey"))
        .build();
    AiRequest request = AiRequest.builder()
        .model("gpt-5-mini")
        .instructions("Summarize the supplied text.")
        .inputText(text)
        .store(false)
        .build();

    AiResponse response = client.execute(request, config);
}
```

When `AiClient` contains one provider, `execute`, `stream`, `embed`, and `listModels`
select it automatically. The bundled provider identifiers `openai`, `gemini`,
and `openrouter` are needed only when one client contains multiple providers:

```java
try (AiClient client = AiClient.of(new OpenAiProvider(), new GeminiProvider())) {
    AiResponse response = client.execute("gemini", request, geminiConfig);
}
```

The provider-ID overloads are also available for embedding, streaming, and model
discovery. Identifier-free calls fail clearly if the client contains zero or
multiple providers.

Embeddings use a dedicated request and response API, so the existing
`AiOperation` and `AiResponse` contracts remain unchanged:

```java
EmbeddingResponse embeddingResponse = client.embed(
    EmbeddingRequest.builder()
        .model("your-embedding-model")
        .inputs(List.of("First text", "Second text"))
        .options(new EmbeddingOptions(768))
        .build(),
    config
);
List<EmbeddingVector> vectors = embeddingResponse.embeddings();
```

Omit `.options(...)` from the builder to keep the provider model's default
vector width.

Embedding inputs are sent unchanged. `AiClient` does not apply
`PromptInjectionDefense` to `EmbeddingRequest` because adding protection markers
would make those markers part of the embedded content and change the resulting
vectors. Apply any host-specific privacy or content policy before calling
`embed`. `EmbeddingRequest.toString()` reports only the model, input count, and
options, never the input text.

## Provider guides

Each provider guide shows how to build requests for text, streaming, multimodal
input, image generation, image editing, and text embeddings. The capability
tables also identify fields that a particular adapter forwards or ignores.

- [OpenAI](docs/providers/openai.md)
- [Google Gemini](docs/providers/gemini.md)
- [OpenRouter](docs/providers/openrouter.md)

## Automatic request preparation

Pass the immutable `AiRequest` directly to `AiClient.execute(...)` or
`AiClient.stream(...)`. The client automatically prepares a protected copy before
delegating to the provider: it hardens trusted instructions and protects untrusted
input text and user prompts. The original request keeps its readable input values.

Hosts with an audit trail can inspect the immutable `request.suspiciousSources()`
metadata. Ordinary callers do not need to handle request preparation or detection
metadata. Bundled providers also apply the defenses idempotently so direct provider
calls remain protected.

Standard `{inputText}` and `{userPrompt}` instruction placeholders can be expanded
without giving replacement values instruction authority:

```java
AiPromptTemplate.ExpansionResult expanded = AiPromptTemplate.expand(
    "Summarize: {inputText}\nStyle: {userPrompt}",
    sourceText,
    userPrompt
);
```

Expansion is single-pass and repeat-safe: placeholders introduced inside an
untrusted value remain literal, canonical untrusted-data blocks are not scanned
again, and consumed or suspicious fields are reported to the host.

## Media and response values

Additional immutable value helpers include `BinaryContent.from(path, mediaType)`,
`GeneratedMedia.isImage()` and `suggestedFileExtension()`, `TokenUsage.plus(...)`,
`AiResponse.withText(...)`, and `ModelInfo.displayLabel()`.

## Host-supplied configuration

The host application owns credentials and runtime settings. Resolve them
immediately before a provider call and pass an immutable `AiProviderConfig`:

```java
AiProviderConfig config = AiProviderConfig.builder(secretStore.get("openai.apiKey"))
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofMinutes(2))
    .build();
```

This design lets applications rotate credentials and change endpoints or
timeouts without rebuilding the library. Custom production endpoints must use
HTTPS. Loopback HTTP can be enabled explicitly for local integration tests.

Credentials must never be logged, serialized, or used directly in cache keys.
`AiProviderConfig.toString()` redacts its API key, and provider exceptions redact
the configured API key and trusted-header values. `AiRequest` denies provider
storage by default; applications must opt in explicitly with `store(true)`.

WebJET CMS maps its own runtime constants and request metadata in a CMS-owned
configuration service. Other applications should provide an equivalent adapter
for their configuration system; the library itself does not read host-specific
settings.

## Build and test

```shell
./gradlew clean check javadoc
```

The `checkStandaloneBoundary` verification rejects imports from WebJET CMS,
Spring, servlet APIs, and JPA. Build outputs include the main, sources, and
Javadoc JARs.

For simultaneous local development with a consuming Gradle project, use a
composite build instead of publishing to `mavenLocal()`:

```shell
./gradlew --include-build ../webjet-ai test
```

## Releases

Releases follow semantic versioning and are published to Maven Central from a
protected GitHub environment. A maintainer creates a `vX.Y.Z` tag on `main`;
the tag must be annotated and cryptographically verified by GitHub. After
approval, CI builds a signed Maven bundle, waits for Central publication,
verifies anonymous resolution, and then creates the GitHub Release. Snapshot
artifacts are not published.

Local release builds accept either an ASCII-armored private key in the
`signingKey` Gradle property or a local GPG key selection via
`signingKeyId=0x...` or `signing.gnupg.keyName=0x...`. When a key ID is used,
the build switches to `gpg` and reads the secret key from the local GPG
keyring, matching traditional Maven `gpg:sign-and-deploy-file` usage. Keep
these properties in `~/.gradle/gradle.properties` or CI secrets, for example:

```properties
releaseVersion=1.1.0
signingKeyId=0x36F2327F
```

If you sign with an in-memory exported private key instead, also provide
`signingPassword`. To create the Central bundle locally, run:

```shell
./gradlew centralBundle -PreleaseVersion=1.1.0 -PsigningKeyId=0x36F2327F
```

During a local interactive run, Gradle prints the resolved `releaseVersion` and
waits for Enter before the release flow continues. In CI or other non-interactive
environments, the confirmation step logs the version and continues automatically.

GitHub Packages deployments use the same signed `mavenJava` publication and accept
both stable and `-SNAPSHOT` semantic versions. Configure `githubUsername` and
`githubToken` in `~/.gradle/gradle.properties` or export `GITHUB_USERNAME` and
`GITHUB_TOKEN`, then run:

```shell
./gradlew publishMavenJavaPublicationToGitHubPackagesRepository -PreleaseVersion=1.1.0-SNAPSHOT
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for development rules,
[SECURITY.md](SECURITY.md) for responsible vulnerability reporting, and
[PROVENANCE.md](PROVENANCE.md) for the extraction history.

## License

Copyright 2026 InterWay, a. s.

Licensed under the [Apache License 2.0](LICENSE).
