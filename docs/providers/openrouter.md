# OpenRouter provider

`OpenRouterProvider` implements text, multimodal text, image generation, and
image editing through OpenRouter's chat-completions API. Its provider identifier
is `openrouter` when it is selected from a client containing multiple providers.

OpenRouter exposes many models with different modalities. The adapter validates
the basic request shape, but the caller must choose a model that supports the
requested text, image-input, image-output, or image-editing capability.

> In the current library version, OpenRouter ignores `store` and every
> `ImageOptions` value. Image count, size, and quality cannot be controlled through
> `AiRequest` for this provider.

## Capabilities

| Task | `AiOperation` | `execute` | `stream` | Important requirements |
| --- | --- | --- | --- | --- |
| Text generation or transformation | `TEXT` | Yes | Yes | A non-blank model |
| Analyze text and an image | `TEXT` | Yes | Yes | A model that accepts image input |
| Generate images | `GENERATE_IMAGE` | Yes | No | A model that produces image output |
| Edit an image | `EDIT_IMAGE` | Yes | No | A model that edits images and non-empty `inputMedia` |

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
import com.webjetcms.ai.GeneratedMedia;
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

Pass `AiRequest` directly to `AiClient`. Request preparation and prompt
protection are automatic.

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

## Generate images

Select a model that supports image output. The adapter requests both `image` and
`text` modalities. `instructions`, `inputText`, and `userPrompt` are sent;
optional `inputMedia` can also be included when supported by the model.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.GENERATE_IMAGE)
    .model("your-openrouter-image-output-model")
    .instructions("Create an illustration from the supplied description.")
    .inputText("A red bicycle beside a lake at sunrise.")
    .userPrompt("Use a clean editorial style without text.")
    .build();

AiResponse response = client.execute(request, config);
List<GeneratedMedia> images = response.media();
```

Do not set `ImageOptions`: `count`, `size`, and `quality` are not serialized by
the OpenRouter adapter. The response may still contain multiple images, and all
valid returned images are exposed through `response.media()`.

## Edit an image

Editing requires non-empty `inputMedia`. Put the edit command in `instructions`
and/or `userPrompt`. `inputText` is ignored for `EDIT_IMAGE`.

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

## `AiRequest` fields

| Field | `TEXT` | `GENERATE_IMAGE` | `EDIT_IMAGE` |
| --- | --- | --- | --- |
| `operation` | Use `TEXT` or omit it | Required value | Required value |
| `model` | Required | Required; must support image output | Required; must support image editing |
| `instructions` | Trusted system instructions | Trusted task text in user content | Trusted task text in user content |
| `inputText` | Protected and sent | Protected and sent | Ignored |
| `userPrompt` | Protected and sent | Protected and sent | Protected and sent |
| `inputMedia` | Optional multimodal input | Optional reference input | Required and must contain bytes |
| `store` | Ignored | Ignored | Ignored |
| `imageOptions` | Ignored | Ignored | Ignored |

For image operations, prompt-security rules remain in the system message and the
trusted task instructions are included in user content. The request asks for both
image and text output modalities.

## Models and responses

```java
List<ModelInfo> models = client.listModels(config);
```

The catalogue request asks OpenRouter for all output modalities and sorts models
by creation time, newest first. The adapter does not retain capability metadata,
so each returned `ModelInfo` contains only the identifier/display label and
optional creation time. The caller must select an appropriate model.

Response behavior:

- text comes from the first choice's message content;
- token accounting is available through `response.usage()`;
- `TEXT` responses always expose an empty `response.media()` list;
- image operations require at least one valid Base64 `data:image/*` entry in the
  response's `message.images` array;
- entries marked as text or without an image URL are skipped; a present malformed,
  non-Base64, or non-image data URL fails the response;
- every accepted returned image entry is decoded into `GeneratedMedia`;
- a nonblank finish reason other than `stop` causes `AiProviderException`.

Saving returned media is optional:

```java
GeneratedMedia image = response.media().get(0);
String extension = image.suggestedFileExtension().orElse("bin");
Files.write(Path.of("result." + extension), image.data());
```
