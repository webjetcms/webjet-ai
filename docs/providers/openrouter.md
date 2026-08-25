# OpenRouter provider

`OpenRouterProvider` implements text, multimodal text, image generation, image
editing, and text embeddings through OpenRouter's APIs. Its provider identifier
is `openrouter` when it is selected from a client containing multiple providers.

OpenRouter exposes many models with different modalities. The adapter validates
the basic request shape, but the caller must choose a model that supports the
requested text, image-input, image-output, or image-editing capability.
Embedding callers must likewise choose a model exposed by OpenRouter's embedding
endpoint.

OpenRouter ignores `store`. Image rendering controls are backed by a versioned
static snapshot of OpenRouter's Image Models catalogue dated 2026-08-25 and are
available through `AiClient.imageOptions(...)` without credentials or network access.

## Capabilities

| Task | Request | Non-streaming call | `stream` | Important requirements |
| --- | --- | --- | --- | --- |
| Text generation or transformation | `AiRequest` with `TEXT` | `execute` | Yes | A non-blank model |
| Analyze text and an image | `AiRequest` with `TEXT` | `execute` | Yes | A model that accepts image input |
| Generate images | `AiRequest` with `GENERATE_IMAGE` | `execute` | No | A model that produces image output |
| Edit an image | `AiRequest` with `EDIT_IMAGE` | `execute` | No | A model that edits images and non-empty `inputMedia` |
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
import com.webjetcms.ai.provider.openrouter.OpenRouterProvider;

String apiKey = Objects.requireNonNull(
    System.getenv("OPENROUTER_API_KEY"),
    "OPENROUTER_API_KEY is required"
);
AiProviderConfig config = AiProviderConfig.builder(apiKey)
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofMinutes(2))
    .trustedHeader("HTTP-Referer", "https://example.com") // Optional metadata
    .trustedHeader("X-Title", "My application")           // Optional metadata
    .build();

try (AiClient client = AiClient.of(new OpenRouterProvider())) {
    // Execute requests from the examples below.
}
```

Every request snippet below is intended to run inside this `try` block while the
client is open. Because the client contains one provider, the examples use the
identifier-free methods.

The default base URI is `https://openrouter.ai/api/v1/`. A host can set a custom
HTTPS endpoint through `AiProviderConfig.Builder.baseUri(...)`. The API key is
sent as a Bearer token. Trusted metadata headers are forwarded, but
`Authorization`, `Accept`, and POST `Content-Type` are controlled by the adapter.
The two metadata headers shown above are optional and are not library requirements.

Pass `AiRequest` directly to `AiClient` for generation operations. Request
preparation and prompt protection are automatic. Embeddings use the dedicated
`EmbeddingRequest` API shown below.

## Text request

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-openrouter-text-model")
    .instructions("Summarize the supplied source accurately.")
    .inputText(sourceText)
    .userPrompt("Use five bullet points.")
    .build();

AiResponse response = client.execute(request, config);
String result = response.text();
```

For `TEXT`, hardened `instructions` become the system message. Protected
`inputText` and `userPrompt` become ordered text items in the user message.
Optional `inputMedia` is appended as an inline Base64 image URL.

## Multimodal text request

Use `TEXT` when an image is input and the expected result is text.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-openrouter-vision-capable-model")
    .instructions("Describe the supplied image accurately.")
    .userPrompt("Identify the important objects and their positions.")
    .inputMedia(BinaryContent.from(Path.of("photo.png"), "image/png"))
    .build();

AiResponse response = client.execute(request, config);
```

The adapter verifies that supplied media is non-empty, but does not validate the
MIME type or selected model's input capabilities. `BinaryContent.fileName()` is
not sent.

## Streaming text

Streaming is supported only for `TEXT`. The listener receives decoded text
fragments, while the returned response contains the accumulated text, final
usage, and finish reason.

```java
AiResponse completed = client.stream(
    request,
    config,
    System.out::print
);
```

The listener must not be null. OpenRouter must terminate the stream with an SSE
`[DONE]` event and a nonblank `stop` finish reason. Image generation and editing
cannot be streamed through this adapter.

## Text embeddings

Embeddings use `AiClient.embed(...)`, not an `AiOperation` or `AiResponse`.

```java
EmbeddingRequest embeddingRequest = EmbeddingRequest.builder()
    .model("your-openrouter-embedding-model")
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

Omit `.options(...)` from the builder to let the selected model choose its output
width. When a dimension is supplied, the adapter forwards it; otherwise, the
field is omitted. `EmbeddingResponse.embeddings()` follows the original input
order; the adapter restores that order from the provider's response indices. It
requests float encoding and requires all returned vectors to have the requested
or inferred nonzero width. The selected model determines whether a requested
dimension is supported. Provider-specific task or input-type options are not
added. Embeddings are not streamed.

## Generate images

Select a model in the static image catalogue. Image generation uses OpenRouter's
dedicated `/images` endpoint; text and embeddings retain their existing endpoints.
`instructions`, `inputText`, and `userPrompt` contribute to the protected prompt.
At least one of those fields must contain meaningful prompt text. Optional,
non-empty `inputMedia` is sent as one `input_references` data URL when the selected
model supports reference-guided generation.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.GENERATE_IMAGE)
    .model("openai/gpt-image-2")
    .instructions("Create an illustration from the supplied description.")
    .inputText("A red bicycle beside a lake at sunrise.")
    .userPrompt("Use a clean editorial style without text.")
    .imageOptions(ImageOptions.builder()
        .count(1)
        .quality("high")
        .providerOption("aspect_ratio", "16:9")
        .build())
    .build();

AiResponse response = client.execute(request, config);
List<GeneratedMedia> images = response.media();
```

The static snapshot converts OpenRouter's `n` field to portable `count` and
exposes each model's supported scalar controls, which can include `resolution`,
`aspect_ratio`, `quality`, `output_format`, `background`,
`output_compression`, and `seed`. Query the selected model first; unsupported
keys and values fail before transport.

Vector-only Recraft model IDs are not included in the image-option snapshot;
this adapter accepts PNG, JPEG, and WebP image responses, not SVG output.

## Edit an image

Editing requires non-empty `inputMedia`. Put the edit command in `instructions`
and/or `userPrompt`; at least one must contain meaningful text. `inputText` is
ignored for `EDIT_IMAGE`.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.EDIT_IMAGE)
    .model("your-openrouter-image-edit-model")
    .instructions("Edit the supplied image while preserving its main subject.")
    .userPrompt("Replace the background with a mountain landscape.")
    .inputMedia(BinaryContent.from(Path.of("source.png"), "image/png"))
    .build();

AiResponse response = client.execute(request, config);
GeneratedMedia editedImage = response.media().get(0);
```

For both generation and editing, the one supplied `inputMedia` value is encoded
as one `input_references` data URL. Editing requires it; generation permits it.
When present it must contain bytes. Masks, multiple references, routing objects,
passthrough objects, and partial-image streaming are not exposed by this adapter.

## `AiRequest` fields

This table applies to generation requests. Embeddings use `EmbeddingRequest`
with the separate `model`, `inputs`, and `options` components.

| Field | `TEXT` | `GENERATE_IMAGE` | `EDIT_IMAGE` |
| --- | --- | --- | --- |
| `operation` | Use `TEXT` or omit it | Required value | Required value |
| `model` | Required | Required; must support image output | Required; must support image editing |
| `instructions` | Trusted system instructions | Trusted task text in user content | Trusted task text in user content |
| `inputText` | Protected and sent | Protected and sent | Ignored |
| `userPrompt` | Protected and sent | Protected and sent | Protected and sent |
| `inputMedia` | Optional multimodal input | Optional reference input | Required and must contain bytes |
| `store` | Ignored | Ignored | Ignored |
| `imageOptions` | Ignored | Validated and sent to `/images` | Validated and sent to `/images` |

For image operations, prompt-security rules, trusted task instructions, and
protected untrusted prompts are combined into the dedicated Images API prompt.

## Models and responses

```java
List<ModelInfo> models = client.listModels(config);
```

The dynamic model catalogue asks OpenRouter for all output modalities and sorts
models by creation time, newest first. Separately, `client.imageOptions(...)`
uses the library's release-time scalar capability snapshot. An image model added
after that snapshot returns an empty option map until WebJET AI is updated, but
can still be called without explicit image options.

Response behavior:

- text comes from the first choice's message content;
- token accounting is available through `response.usage()`;
- `TEXT` responses always expose an empty `response.media()` list;
- image operations decode `data[].b64_json` and honor each declared `media_type`;
- PNG, JPEG, and WebP responses become `GeneratedMedia`;
- missing image data, malformed Base64, SVG, or another unsupported media type
  fails parsing;
- embedding responses expose input-ordered vectors and token accounting through
  `EmbeddingResponse`.

Saving returned media is optional:

```java
GeneratedMedia image = response.media().get(0);
String extension = image.suggestedFileExtension().orElse("bin");
Files.write(Path.of("result." + extension), image.data());
```
