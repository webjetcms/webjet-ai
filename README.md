# WebJET AI

WebJET AI is a framework-neutral Java library for communicating with OpenAI,
Google Gemini, and OpenRouter. It provides provider-neutral request and response
types, streaming support, image operations, model discovery, and prompt-security
utilities without requiring Spring, a servlet container, a database, or WebJET
CMS.

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
    implementation 'com.webjetcms:webjet-ai:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>com.webjetcms</groupId>
    <artifactId>webjet-ai</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Minimal usage

```java
import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
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

    AiResponse response = client.execute("openai", request, config);
}
```

`AiClient` receives provider instances explicitly. The bundled providers use the
identifiers `openai`, `gemini`, and `openrouter`.

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

See [CONTRIBUTING.md](CONTRIBUTING.md) for development rules,
[SECURITY.md](SECURITY.md) for private vulnerability reporting, and
[PROVENANCE.md](PROVENANCE.md) for the extraction history.

## License

Copyright 2026 InterWay, a. s.

Licensed under the [Apache License 2.0](LICENSE).
