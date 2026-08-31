# WebJET AI

WebJET AI is a framework-neutral Java library for communicating with OpenAI,
Google Gemini, and OpenRouter, with optional local embedding, translation, and
text-generation runtimes. It provides provider-neutral request and response types,
streaming support, image operations, text embeddings, model discovery, and
prompt-security utilities without requiring Spring, a servlet container, a database,
or WebJET CMS.

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
    implementation 'com.webjetcms:webjet-ai:1.2.0'
}
```

Maven:

```xml
<dependency>
    <groupId>com.webjetcms</groupId>
    <artifactId>webjet-ai</artifactId>
    <version>1.2.0</version>
</dependency>
```

The current development branch targets `2.0.0-SNAPSHOT`. The latest stable core
artifact remains `1.2.0`; the local runtime described below is currently a
development feature.

## Minimal usage

```java
import java.util.List;

import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.ModelInfo;

try (AiClient client = AiClient.discover()) {
    List<String> providers = client.providers();
    providers.forEach(System.out::println);

    // Let host policy or the user choose a provider with configured credentials.
    String provider = selectConfiguredProvider(providers);
    if (client.hasProvider(provider) == false) {
        throw new IllegalArgumentException("Unknown provider: " + provider);
    }
    AiProviderConfig config = AiProviderConfig.builder(
        secretStore.get(provider + ".apiKey")
    )
        .build();
    List<ModelInfo> models = client.listModels(provider, config);

    // Choose a text-generation model using provider capability information.
    String modelId = selectTextGenerationModel(provider, models);
    if (models.stream().noneMatch(model -> modelId.equals(model.id()))) {
        throw new IllegalArgumentException(
            "Model is not in the provider catalogue: " + modelId
        );
    }

    AiRequest request = AiRequest.builder()
        .model(modelId)
        .instructions("Summarize the supplied text.")
        .inputText(text)
        .store(false)
        .build();

    AiResponse response = client.execute(provider, request, config);
}
```

`AiClient.discover()` creates a client with every provider bundled with WebJET AI.
`client.providers()` returns an immutable `List<String>` sorted by provider ID; it does
not need credentials or make model-catalogue requests. Pass the selected ID to
`listModels`, `execute`, `stream`, or `embed`. The client keeps no hardcoded model list:
`listModels` delegates to the selected provider using the supplied configuration.
The example's `selectConfiguredProvider(...)` and `selectTextGenerationModel(...)`
calls represent host UI or policy. A catalogue entry does not describe model
capabilities, so use provider capability metadata or documentation rather than list
position when selecting a model for an operation.

## Image capability lookup

Image rendering controls are model- and operation-specific. Query them before
building a form or request; the lookup uses a release-time static catalogue and
therefore needs neither credentials nor network access:

```java
import java.util.Map;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviders;
import com.webjetcms.ai.image.ImageOptionDefinition;
import com.webjetcms.ai.image.ImageOptions;

String imageProvider = AiProviders.OPENAI;
String imageModel = "gpt-image-2";
Map<String, ImageOptionDefinition> supported = client.imageOptions(
    imageProvider,
    imageModel,
    AiOperation.GENERATE_IMAGE
);

ImageOptions options = ImageOptions.builder()
    .count(1)
    .size("1024x1024")
    .quality("high")
    .providerOption("background", "transparent")
    .providerOption("output_format", "webp")
    .build();
```

The returned map is immutable and deterministically ordered. Each definition is
a choice list, inclusive integer range, boolean, or patterned string, allowing a
host to render suitable controls. The portable keys are `count`, `size`, and
`quality`; other keys retain the provider's documented wire name. The existing
`new ImageOptions(count, size, quality)` constructor remains available.

Only `GENERATE_IMAGE` and `EDIT_IMAGE` may be queried. An empty map means that
the model is not in this release's static image catalogue. Such model IDs remain
usable without explicit options. For compatibility with custom OpenAI-compatible
endpoints, uncatalogued OpenAI model IDs also retain pass-through support for the
portable `count`, `size`, and `quality` fields; provider-specific options still
require catalogue metadata. Options advertised by a catalogue are validated
locally before transport.

Built-in IDs are also available as the public `String` constants
`AiProviders.OPENAI`, `AiProviders.GEMINI`, and `AiProviders.OPENROUTER`.
`AiProviders.builtIns()` returns the complete immutable, sorted built-in ID list without
creating a client or provider transport. In contrast, `client.providers()` describes
that client and therefore also includes custom providers supplied to `discover(...)`.
A provider added in a newer WebJET AI version appears automatically after the dependency
is updated. Credentials are required only when the application calls that provider.

```java
import java.util.List;

import com.webjetcms.ai.AiProviders;

List<String> bundledProviders = AiProviders.builtIns();
String openAi = AiProviders.OPENAI;
```

Add an application-owned provider directly during discovery:

```java
MyProvider custom = new MyProvider(sharedHttpClient, applicationMetrics);
try (AiClient client = AiClient.discover(
    custom
)) {
    // Bundled provider IDs plus MyProvider.PROVIDER_ID are available here.
}
```

The `AiProvider...` parameter lets Java verify that every custom implementation
implements `AiProvider`. Its exact `provider.id()` value appears in
`client.providers()` and selects its operations. Registration is local to that client:
there is no global mutable catalogue or class-path scan. Provider IDs are matched exactly
and case-sensitively and are never trimmed or normalized; duplicate IDs fail discovery.
Pass a fresh custom provider instance to each client. After successful discovery the client
owns and closes all its providers.
If discovery fails, the library closes bundled instances it created, while the caller
retains ownership of supplied custom instances and must close them. Keep each custom
instance in a variable, as above, so failure handling can still access it.

See [Implementing and using a custom AI provider](docs/custom-providers.md)
for a complete implementation, configuration, lifecycle, and usage guide.

Use `AiClient.of(...)` when a host wants only explicitly supplied providers and not
the bundled set:

```java
MyProvider customOnly = new MyProvider();
try (AiClient client = AiClient.of(customOnly)) {
    // Only MyProvider is registered.
}
```

As with `discover(...)`, ownership of the supplied provider transfers to the client only
when `of(...)` returns successfully. If validation fails, the caller retains ownership.

When `AiClient` contains one provider, `execute`, `stream`, `embed`, and `listModels`
select it automatically. Use their provider-ID overloads when the client contains
multiple providers. Identifier-free calls fail clearly if the client contains zero or
multiple providers.

Embeddings use a dedicated request and response API, so the existing
`AiOperation` and `AiResponse` contracts remain unchanged:

```java
import java.util.List;

import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;

EmbeddingResponse embeddingResponse = client.embed(
    provider,
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

## Preparing local models

The published `webjet-ai` JAR also contains a JDK-only preparation tool for the
approved E5 embedding, M2M100 translation, and EuroLLM generation models. The tool
downloads a pinned, verified set of model and tokenizer files and writes one
reproducible ZIP for later use. It does not load or execute the model; runtime ZIP
consumption is a separate integration concern.

When using a source or composite build, no JAR is required. If the consuming
project has `includeBuild('../webjet-ai')`, run the included build's task from the
consuming project:

```shell
./gradlew :webjet-ai:localModelTool \
  --args='prepare --model multilingual-e5-base --output /path/to/multilingual-e5-base-fp32.zip'
```

The task compiles the required classes and uses the same catalogue and command-line
options as the packaged JAR. From the `webjet-ai` checkout itself, omit the included
build prefix and run `./gradlew localModelTool --args='...'`.

Prepare the portable FP32 bundle:

```shell
java -jar webjet-ai-VERSION.jar prepare \
  --model multilingual-e5-base \
  --output /path/to/multilingual-e5-base-fp32.zip
```

The canonical model ID `intfloat/multilingual-e5-base` is accepted as an alias.
If `--output` is omitted, the tool writes
`multilingual-e5-base-fp32.zip` in the current directory. Existing files are kept
unless `--overwrite` is specified.

Use the same command for the smaller 384-value model:

```shell
java -jar webjet-ai-VERSION.jar prepare \
  --model multilingual-e5-small
```

Its default FP32 output is `multilingual-e5-small-fp32.zip`; the canonical
`intfloat/multilingual-e5-small` ID is also accepted.

An explicitly selected quantized bundle is available for CPUs with AVX-512 VNNI:

```shell
java -jar webjet-ai-VERSION.jar prepare \
  --model multilingual-e5-base \
  --variant int8-avx512-vnni
```

Its default output is `multilingual-e5-base-int8-avx512-vnni.zip`. FP32 is the
portable default; select INT8 only when the target server supports AVX-512 VNNI.

The generated ZIP contains these root-level entries in a stable order:

```text
webjet-model.json
model.onnx
config.json
tokenizer.json
tokenizer_config.json
special_tokens_map.json
sentencepiece.bpe.model
MODEL_CARD.md
SHA256SUMS
```

Every upstream file is pinned to a full Hugging Face revision and accepted only
after its byte size and SHA-256 match the built-in recipe. `SHA256SUMS` records
the packaged contents. Downloads and ZIP creation use temporary files, so a failed
run does not expose a partial destination.

The embedding width is fixed at 768 for E5 base and 384 for E5 small.
`--dimensions` can be used as a configuration check; a value that does not match
the selected model is rejected before a download starts. Preparation progress is
written to standard error, while a successful run writes only the absolute ZIP
path to standard output. Use `--help` for all options and `--version` to print the
JAR version.

Prepare the portable quantized M2M100 translation bundle in the same way:

```shell
java -jar webjet-ai-VERSION.jar prepare \
  --model facebook/m2m100_418M \
  --variant int8
```

Its default output is `m2m100-418m-int8.zip`. M2M100 also accepts `--variant fp32`,
but INT8 is the portable default because the two FP32 graphs require substantially
more memory and disk space. Both variants are pinned to one approved
`Xenova/m2m100_418M` ONNX conversion revision. The text bundle includes separate
encoder and merged-decoder graphs plus all tokenizer assets; language tokens are
read from that tokenizer rather than hardcoded by the library.

Prepare EuroLLM-1.7B-Instruct for local multilingual text generation:

```shell
java -jar webjet-ai-VERSION.jar prepare \
  --model utter-project/EuroLLM-1.7B-Instruct
```

The alias `eurollm-1.7b-instruct` is also accepted. Its only approved variant is
the portable `q4-k-m` GGUF quantization and its default output is
`eurollm-1.7b-instruct-q4-k-m.zip`. The bundle contains the approximately 1.05 GB
model, its model card, a schema-v1 manifest, and checksums from the pinned
`QuantFactory/EuroLLM-1.7B-Instruct-GGUF` conversion. Tokenizer data is embedded
in the GGUF model.

`webjet-ai-local-model-tool` only prepares local model bundles. Model execution is
provided by the separate `webjet-ai-local` artifact, so ordinary cloud-provider
users do not receive ONNX Runtime, llama.cpp, or tokenizer native dependencies.

## Running a local embedding model

Add the runtime artifact through Maven or Gradle so its pinned native dependency
graph is resolved. Copying only `webjet-ai-local.jar` is not sufficient.

```gradle
dependencies {
    implementation 'com.webjetcms:webjet-ai-local:VERSION'
}
```

Open the ZIP once, reuse the provider, and close it during application shutdown:

```java
import java.nio.file.Path;
import java.util.List;

import com.webjetcms.ai.EmbeddingInputType;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.provider.local.LocalEmbeddingModelProvider;

try (LocalEmbeddingModelProvider provider = LocalEmbeddingModelProvider.open(
    Path.of("/models/multilingual-e5-small-fp32.zip")
)) {
    EmbeddingResponse documents = provider.embed(new EmbeddingRequest(
        null,
        List.of("Bratislava is the capital of Slovakia."),
        new EmbeddingOptions(null, EmbeddingInputType.DOCUMENT)
    ));
    EmbeddingResponse queries = provider.embed(new EmbeddingRequest(
        null,
        List.of("What is the capital of Slovakia?"),
        new EmbeddingOptions(null, EmbeddingInputType.QUERY)
    ));
}
```

The provider applies the approved model's input preparation before tokenization;
applications must not add model-specific prefixes themselves. Use `DOCUMENT` for
passages being indexed and `QUERY` for search text. The default is `DOCUMENT`,
which preserves the behavior of the one-argument and no-argument
`EmbeddingOptions` constructors. Inputs are truncated to the bundle limit of 512
tokens, batched eight at a time by default, mean-pooled with the attention mask,
and L2-normalized to 768 values for E5 base or 384 for E5 small.

The provider can also be owned by an explicitly configured client:

```java
try (AiClient client = AiClient.of(LocalEmbeddingModelProvider.open(modelZip))) {
    EmbeddingResponse response = client.embed(
        request,
        AiProviderConfig.empty()
    );
}
```

`AiClient.discover()` does not create a local provider because the ZIP path and
lifecycle must be explicit. Advanced initialization supports `intraOpThreads`,
`maximumBatchSize`, and an existing writable temporary-directory parent through
`LocalEmbeddingModelProvider.builder(path)`.

FP32 bundles are supported on Linux x86-64 and macOS ARM64. The INT8 variant is
accepted only on Linux x86-64 when `/proc/cpuinfo` proves AVX-512 VNNI support;
there is no automatic fallback. Opening a provider temporarily requires enough
free disk space to extract the model in addition to the original ZIP: about 1.11
GB for E5 base FP32 or 470 MB for E5 small FP32. Normal `close()` removes the
private extracted directory; abrupt JVM termination can leave it for
operating-system or administrator cleanup.

The runtime uses only files from the validated ZIP. Initialization forces DJL's
JVM-wide `ai.djl.offline=true` policy and disables ONNX Runtime telemetry. It never
downloads a model or tokenizer and accepts no model URL.

## Running local translation

M2M100 uses the normal `TEXT` request and text-only response. Supply the text
literally through `inputText` and the language pair through `TranslationOptions`:

```java
import java.nio.file.Path;

import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.TranslationOptions;
import com.webjetcms.ai.provider.local.LocalTranslationModelProvider;

try (AiClient client = AiClient.of(LocalTranslationModelProvider.open(
    Path.of("/models/m2m100-418m-int8.zip")
))) {
    AiRequest request = AiRequest.builder()
        .model("facebook/m2m100_418M")
        .inputText("Hello, how are you?")
        .translationOptions(new TranslationOptions("en", "sk", 100))
        .build();

    AiResponse response = client.execute(request, AiProviderConfig.empty());
    String translated = response.text();
}
```

This model is a deterministic text transformer, not an instruction-following
language model. Do not set `instructions` or `userPrompt`; those fields are rejected.
The provider deliberately tells `AiClient` to preserve the `inputText` value
unchanged instead of adding prompt-security boundary text. Other providers retain
the default protected-prompt behavior.

Language selection may instead be configured as provider defaults, which makes the
direct convenience call return only a string:

```java
try (LocalTranslationModelProvider provider = LocalTranslationModelProvider.builder(modelZip)
    .sourceLanguage("en")
    .targetLanguage("sk")
    .build()) {
    String translated = provider.translate("Hello, how are you?");
}
```

Per-request `TranslationOptions` override builder defaults. `supportedLanguages()` returns
the immutable set discovered from the validated tokenizer. Generation currently uses
deterministic greedy decoding, is capped at 200 output tokens by the approved M2M100
bundle, and does not support streaming. The provider and its ONNX sessions are reusable;
open them once and close them during application shutdown.

## Running local text generation

EuroLLM-1.7B-Instruct uses the normal `TEXT` request. Trusted instructions describe
the task, `inputText` supplies the CMS content, and the result is returned through
`AiResponse.text()`:

```java
import java.nio.file.Path;

import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.provider.local.LocalGenerationModelProvider;

try (AiClient client = AiClient.of(LocalGenerationModelProvider.builder(
    Path.of("/models/eurollm-1.7b-instruct-q4-k-m.zip")
).maximumOutputTokens(80).build())) {
    AiRequest request = AiRequest.builder()
        .model("utter-project/EuroLLM-1.7B-Instruct")
        .instructions("Summarize the input in one sentence.")
        .inputText(articleText)
        .build();

    AiResponse response = client.execute(request, AiProviderConfig.empty());
    String summary = response.text();
}
```

For a simple end-user prompt, the direct convenience method returns only a string:

```java
try (LocalGenerationModelProvider provider = LocalGenerationModelProvider.open(modelZip)) {
    String result = provider.generate("Explain this product description in plain language.");
}
```

The provider can perform multilingual CMS tasks such as summarization, rewriting,
classification, and extraction; behavior is selected by the request text, not by a
separate operation enum. EuroLLM-1.7B-Instruct is a quantized 1.7-billion-parameter
model, so output quality and factual reliability remain below larger hosted language
models. Review generated content before publishing it.

The model has a 4,096-token context. Requests that do not leave room for the configured
output limit are rejected rather than truncated. Generation uses deterministic greedy
decoding, defaults to at most 200 output tokens, and does not support streaming. Calls
on one provider instance are serialized by the native runtime. The Q4_K_M bundle is
accepted on Linux x86-64 and macOS ARM64. The provider retains the default
protected-prompt handling, rejects requests whose immutable `suspiciousSources()`
metadata indicates prompt injection, and rejects reserved chat control tokens. Apply
any additional host content policy before inference.

## Provider guides

Each provider guide shows how to build requests for text, streaming, multimodal
input, image generation, image editing, and text embeddings. The capability
tables also identify fields that a particular adapter forwards or ignores.

- [OpenAI](docs/providers/openai.md)
- [Google Gemini](docs/providers/gemini.md)
- [OpenRouter](docs/providers/openrouter.md)
- [Custom providers](docs/custom-providers.md)

## Automatic request preparation

Pass the immutable `AiRequest` directly to `AiClient.execute(...)` or
`AiClient.stream(...)`. The client automatically prepares a protected copy before
delegating to the provider: it hardens trusted instructions and protects untrusted
input text and user prompts. The original request keeps its readable input values.

An `AiProvider` may explicitly select literal handling when boundary markers would
change the result. The local M2M100 provider uses it for deterministic translation.
The local EuroLLM provider retains protected prompt preparation and also rejects
requests already detected as prompt injection. Protected prompt preparation remains
the default for existing and custom generative providers.

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

To test a `2.0.0-SNAPSHOT` through a normal Maven dependency graph instead:

```shell
./gradlew -PreleaseVersion=2.0.0-SNAPSHOT publishToMavenLocal
```

Then add `mavenLocal()` and depend on
`com.webjetcms:webjet-ai-local:2.0.0-SNAPSHOT` in the consuming project.

The opt-in production-bundle smoke test performs no download:

```shell
./gradlew :webjet-ai-local:localModelSmokeTest \
  -PlocalModelBundle=/path/multilingual-e5-base-fp32.zip
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
releaseVersion=1.2.0
signingKeyId=0x36F2327F
```

If you sign with an in-memory exported private key instead, also provide
`signingPassword`. To create the Central bundle locally, run:

```shell
./gradlew centralBundle -PreleaseVersion=1.2.0 -PsigningKeyId=0x36F2327F
```

During a local interactive run, Gradle prints the resolved `releaseVersion` and
waits for Enter before the release flow continues. In CI or other non-interactive
environments, the confirmation step logs the version and continues automatically.

GitHub Packages deployments use the same signed `mavenJava` publication and accept
both stable and `-SNAPSHOT` semantic versions. Configure `githubUsername` and
`githubToken` in `~/.gradle/gradle.properties` or export `GITHUB_USERNAME` and
`GITHUB_TOKEN`, then run:

```shell
./gradlew publishAllPublicationsToGitHubPackagesRepository -PreleaseVersion=1.2.0-SNAPSHOT
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for development rules,
[SECURITY.md](SECURITY.md) for responsible vulnerability reporting, and
[PROVENANCE.md](PROVENANCE.md) for the extraction history.

## License

Copyright 2026 InterWay, a. s.

Licensed under the [Apache License 2.0](LICENSE).
