# OpenAI provider

`OpenAiProvider` implements text, multimodal text, image generation, image
editing, and text embeddings through OpenAI's REST APIs. Its provider identifier
is `openai` when it is selected from a client containing multiple providers.

This guide describes what the WebJET AI adapter sends and validates. Model
capabilities are controlled by OpenAI and can differ between model identifiers.
`listModels` returns the catalogue but does not determine which operation or
option each model supports.

## Capabilities

| Task | Request | Non-streaming call | `stream` | Important requirements |
| --- | --- | --- | --- | --- |
| Text generation or transformation | `AiRequest` with `TEXT` | `execute` | Yes | A non-blank model |
| Analyze text and an image | `AiRequest` with `TEXT` | `execute` | Yes | A model that accepts image input |
| Generate images | `AiRequest` with `GENERATE_IMAGE` | `execute` | No | An image-generation model; callers should supply a meaningful prompt |
| Edit an image | `AiRequest` with `EDIT_IMAGE` | `execute` | No | A non-empty `inputMedia`; DALL-E 3 is generation-only |
| Create text embeddings | `EmbeddingRequest` | `embed` | No | A non-blank embedding model and at least one non-blank input |

## Configuration and client

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.webjetcms.ai.AiClient;
import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.BinaryContent;
import com.webjetcms.ai.EmbeddingOptions;
import com.webjetcms.ai.EmbeddingRequest;
import com.webjetcms.ai.EmbeddingResponse;
import com.webjetcms.ai.EmbeddingVector;
import com.webjetcms.ai.GeneratedMedia;
import com.webjetcms.ai.ImageOptions;
import com.webjetcms.ai.ModelInfo;
import com.webjetcms.ai.provider.openai.OpenAiProvider;

String apiKey = Objects.requireNonNull(
    System.getenv("OPENAI_API_KEY"),
    "OPENAI_API_KEY is required"
);
AiProviderConfig config = AiProviderConfig.builder(apiKey)
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofMinutes(2))
    .build();

try (AiClient client = AiClient.of(new OpenAiProvider())) {
    // Execute requests from the examples below.
}
```

Every request snippet below is intended to run inside this `try` block while the
client is open. Because the client contains one provider, the examples use the
identifier-free methods.

The default base URI is `https://api.openai.com/v1/`. A host can set a custom
HTTPS endpoint with `AiProviderConfig.Builder.baseUri(...)`. Trusted headers,
connection timeout, and response timeout are also configurable. The API key is
sent as a Bearer token.

Pass `AiRequest` directly to `AiClient` for generation operations. Request
preparation and prompt protection are automatic; applications do not call a
separate preparer. Embeddings use the dedicated `EmbeddingRequest` API shown
below.

## Text request

Use `instructions` for trusted application rules, `inputText` for source data,
and `userPrompt` for the end-user request.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-openai-text-model")
    .instructions("Summarize the supplied source accurately.")
    .inputText(sourceText)
    .userPrompt("Use five bullet points.")
    .store(false)
    .build();

AiResponse response = client.execute(request, config);
String result = response.text();
```

For `TEXT`, OpenAI receives:

1. hardened `instructions` as system input;
2. protected `inputText` as user input;
3. protected `userPrompt` as another user input;
4. optional `inputMedia` as an inline data URL.

`store` is forwarded only for text requests. It defaults to `false`.

## Multimodal text request

Use `TEXT` with `inputMedia` when the expected result is text about an image.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-openai-vision-capable-model")
    .instructions("Describe the supplied image accurately.")
    .userPrompt("Focus on details useful to a screen-reader user.")
    .inputMedia(BinaryContent.from(Path.of("photo.png"), "image/png"))
    .build();

AiResponse response = client.execute(request, config);
```

The library does not validate whether the chosen model accepts the supplied MIME
type or image input.

## Streaming text

Streaming is available only for `TEXT` requests. The listener receives decoded
text fragments in provider order, and the returned response contains the complete
text and final usage information.

```java
AiResponse completed = client.stream(
    request,
    config,
    System.out::print
);
```

Calling `stream` with `GENERATE_IMAGE` or `EDIT_IMAGE` fails with
`AiProviderException`.

## Text embeddings

Embeddings use `AiClient.embed(...)`, not an `AiOperation` or `AiResponse`.
`EmbeddingResponse.embeddings()` follows the original input order.

```java
EmbeddingRequest embeddingRequest = EmbeddingRequest.builder()
    .model("text-embedding-3-small")
    .inputs(List.of("First text", "Second text"))
    .options(new EmbeddingOptions(768))
    .build();

EmbeddingResponse embeddingResponse = client.embed(embeddingRequest, config);
List<EmbeddingVector> vectors = embeddingResponse.embeddings();
long inputTokens = embeddingResponse.usage().inputTokens();
```

Embedding inputs are sent unchanged. `AiClient` does not apply prompt-injection
protection to `EmbeddingRequest` because protection markers would become part of
the embedded content and change the resulting vectors. Apply host-specific
privacy or content policy before calling `embed`.

The output dimension is optional. Omit `.options(...)` from the builder to use
the model's default width. OpenAI supports the `dimensions` request field only
for `text-embedding-3` and later models, so it must be omitted for
`text-embedding-ada-002` and compatible fixed-width endpoints. The adapter
requests float encoding, validates that every returned vector has the expected
or inferred nonzero width, and restores input order from the provider's response
indices. Embeddings are not streamed.

## Generate images

For generation, `inputText` and `userPrompt` both contribute to the image prompt.
`inputMedia` and `store` are ignored. At least one of `instructions`, `inputText`,
or `userPrompt` must contain meaningful text; automatically injected security
rules do not satisfy this requirement.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.GENERATE_IMAGE)
    .model("gpt-image-2")
    .instructions("Create a clean product illustration.")
    .inputText("A red touring bicycle on a neutral background.")
    .userPrompt("Use soft studio lighting and do not include text or logos.")
    .imageOptions(ImageOptions.builder()
        .count(1)
        .size("1024x1024")
        .quality("high")
        .providerOption("background", "transparent")
        .providerOption("output_format", "webp")
        .build())
    .build();

AiResponse response = client.execute(request, config);
List<GeneratedMedia> images = response.media();
```

Call `client.imageOptions("gpt-image-2", AiOperation.GENERATE_IMAGE)` to obtain
the immutable option definitions before constructing a request. Explicit values
are validated against that model and operation before transport. Unset values are
omitted so OpenAI defaults apply.

An uncatalogued model ID returns an empty option map. For compatibility with
custom OpenAI-compatible endpoints and future aliases, the adapter still forwards
the portable `count`, `size`, and `quality` fields for those IDs without applying
model-specific validation. Provider-specific options require a catalogued model.

## Edit an image

Editing requires non-empty `inputMedia`. Put the edit instruction in
`instructions` and/or `userPrompt`. Do not use `inputText` for an image path or
edit prompt because OpenAI image editing ignores that field. At least one of
`instructions` or `userPrompt` must contain meaningful text.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.EDIT_IMAGE)
    .model("gpt-image-2")
    .instructions("Edit the supplied image while preserving its main subject.")
    .userPrompt("Replace the background with a mountain landscape.")
    .inputMedia(BinaryContent.from(Path.of("source.png"), "image/png"))
    .imageOptions(ImageOptions.builder()
        .size("1024x1024")
        .build())
    .build();

AiResponse response = client.execute(request, config);
GeneratedMedia editedImage = response.media().get(0);
```

This adapter does not expose an image mask option.

## `AiRequest` fields

This table applies to generation requests. Embeddings use `EmbeddingRequest`
with the separate `model`, `inputs`, and `options` components.

| Field | `TEXT` | `GENERATE_IMAGE` | `EDIT_IMAGE` |
| --- | --- | --- | --- |
| `operation` | Use `TEXT` or omit it | Required value | Required value |
| `model` | Required | Required | Required; DALL-E 3 is rejected |
| `instructions` | Trusted system/task instructions | Included in the prompt | Included in the prompt |
| `inputText` | Protected and sent | Protected and included in the prompt | Ignored |
| `userPrompt` | Protected and sent | Protected and included in the prompt | Protected and included in the prompt |
| `inputMedia` | Optional multimodal input | Ignored | Required and must contain bytes |
| `store` | Forwarded | Ignored | Ignored |
| `imageOptions` | Ignored | Validated for catalogued models; portable fields pass through for uncatalogued IDs | Validated for catalogued models; portable fields pass through for uncatalogued IDs |

## `ImageOptions`

| Option | Adapter behavior |
| --- | --- |
| `count` | Portable field sent as `n`; the advertised range is model-specific |
| `size` | Portable model-specific choice, or a patterned dimension for GPT Image 2 |
| `quality` | Portable model-specific choice |
| `background` | `auto`, `transparent`, or `opaque` on supported GPT Image models |
| `output_format` | `png`, `jpeg`, or `webp` on supported GPT Image models |
| `output_compression` | Integer 0–100; requires explicit JPEG or WebP output |
| `moderation` | Supported GPT Image moderation choice |
| `style` | DALL-E 3 generation style |
| `input_fidelity` | GPT Image editing control where advertised |

Transparent output requires PNG or WebP. GPT Image 2 additionally accepts
arbitrary `WIDTHxHEIGHT` dimensions when both edges are divisible by 16, neither
edge exceeds 3840, the aspect ratio is at most 3:1, and total pixels are between
655,360 and 8,294,400. Invalid counts, types, choices, dimensions, and option
combinations fail locally.

For an uncatalogued model, only the portable fields are accepted and forwarded;
the compatible endpoint determines whether their values are valid. This fallback
preserves existing custom-endpoint behavior without accepting unknown
provider-specific wire options.

## Models and responses

The image-option catalogue retains DALL-E 2 and DALL-E 3 metadata for legacy
compatibility. OpenAI has removed these models from its current public API, so
non-empty option metadata does not imply that a legacy model is currently
available.

```java
List<ModelInfo> models = client.listModels(config);
```

The returned list is sorted by creation time, newest first. It is not filtered by
embedding, text, vision, generation, or editing capability.

- Text responses use `response.text()` and expose token accounting through
  `response.usage()`.
- Image responses use `response.media()`. Every supported Base64 image returned
  by the API is decoded into `GeneratedMedia`.
- The returned MIME type follows the validated `output_format`, even when OpenAI
  omits that field from the response.
- Image response URLs are not downloaded by this adapter; the response must
  contain Base64 image data. For generation, the adapter explicitly requests
  `b64_json` for DALL-E 2/3 generation and DALL-E 2 editing. Other image
  requests rely on the endpoint returning Base64 data without that legacy option.
- `GeneratedMedia.data()` returns a defensive byte-array copy. Saving it to an
  output file is optional.
- Embedding responses expose ordered vectors through `embeddingResponse.embeddings()`
  and token accounting through `embeddingResponse.usage()`.

```java
GeneratedMedia image = response.media().get(0);
String extension = image.suggestedFileExtension().orElse("bin");
Files.write(Path.of("result." + extension), image.data());
```
