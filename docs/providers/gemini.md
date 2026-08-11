# Google Gemini provider

`GeminiProvider` implements text, multimodal text, image generation, and image
editing through the Google Gemini REST API. Its provider identifier is `gemini`
when it is selected from a client containing multiple providers.

This guide describes the WebJET AI adapter. The selected Gemini model must
support the requested input and output modalities. `listModels` does not filter
models by operation or capability.

> In the current library version, Gemini ignores `store` and every
> `ImageOptions` value. Image count, size, and quality cannot be controlled through
> `AiRequest` for this provider.

## Capabilities

| Task | `AiOperation` | `execute` | `stream` | Important requirements |
| --- | --- | --- | --- | --- |
| Text generation or transformation | `TEXT` | Yes | Yes | A non-blank model |
| Analyze text and media | `TEXT` | Yes | Yes | A model that accepts the supplied media |
| Generate images | `GENERATE_IMAGE` | Yes | Adapter allows it | A model that produces image output |
| Edit an image | `EDIT_IMAGE` | Yes | Adapter allows it | A model that edits images and non-empty `inputMedia` |

Image-operation streaming uses Gemini's streaming endpoint, but actual support is
model-dependent. The stream listener receives only text fragments; generated
binary media is available in the returned `AiResponse` after completion.

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
import com.webjetcms.ai.provider.gemini.GeminiProvider;

String apiKey = Objects.requireNonNull(
    System.getenv("GEMINI_API_KEY"),
    "GEMINI_API_KEY is required"
);
AiProviderConfig config = AiProviderConfig.builder(apiKey)
    .connectTimeout(Duration.ofSeconds(10))
    .responseTimeout(Duration.ofMinutes(2))
    .build();

try (AiClient client = AiClient.of(new GeminiProvider())) {
    // Execute requests from the examples below.
}
```

Every request snippet below is intended to run inside this `try` block while the
client is open. Because the client contains one provider, the examples use the
identifier-free methods.

The default base URI is
`https://generativelanguage.googleapis.com/v1beta/`. A custom HTTPS endpoint can
be supplied with `AiProviderConfig.Builder.baseUri(...)`. The API key is sent in
the `x-goog-api-key` header.

Model identifiers can be passed as either `model-name` or `models/model-name`;
the adapter removes the optional `models/` prefix.

Pass `AiRequest` directly to `AiClient`. Request preparation and prompt
protection are automatic.

## Text request

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-gemini-text-model")
    .instructions("Summarize the supplied source accurately.")
    .inputText(sourceText)
    .userPrompt("Use five bullet points.")
    .build();

AiResponse response = client.execute(request, config);
String result = response.text();
```

For `TEXT`, hardened `instructions` become Gemini `systemInstruction`.
`inputText` and `userPrompt` become protected user content parts. Optional
`inputMedia` is appended as Base64 `inlineData`.

## Multimodal text request

Use `TEXT` when media is input and the expected response is text.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.TEXT)
    .model("your-gemini-multimodal-model")
    .instructions("Describe the supplied image accurately.")
    .userPrompt("Identify the main objects and their positions.")
    .inputMedia(BinaryContent.from(Path.of("photo.png"), "image/png"))
    .build();

AiResponse response = client.execute(request, config);
```

Only one `BinaryContent` can be supplied. Its MIME type and bytes are sent, but
its file name is not. The adapter does not validate model-specific MIME or size
limits.

## Streaming

```java
AiResponse completed = client.stream(
    request,
    config,
    System.out::print
);
```

The listener must not be null. It receives text deltas only. The returned response
contains the accumulated text, generated media, usage, and terminal finish reason.
A successful stream must end with Gemini's `STOP` finish reason.

The adapter does not restrict streaming to `TEXT`, but image generation/editing
over the streaming endpoint still depends on the selected model.

## Generate images

Use a model that supports image output. `inputText` and `userPrompt` are both
included in the request. Optional `inputMedia` can be supplied as reference input
when the selected model supports it.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.GENERATE_IMAGE)
    .model("your-gemini-image-model")
    .instructions("Create an illustration from the supplied description.")
    .inputText("A red bicycle beside a lake at sunrise.")
    .userPrompt("Use a clean editorial style without text.")
    .build();

AiResponse response = client.execute(request, config);
List<GeneratedMedia> images = response.media();
```

Do not set `ImageOptions`: Gemini requests produced by this adapter do not send
`count`, `size`, or `quality`.

## Edit an image

Editing requires non-empty `inputMedia`. Put the edit command in `instructions`
and/or `userPrompt`. `inputText` is intentionally ignored for `EDIT_IMAGE`, so a
CMS path or other host-side image identifier cannot leak as prompt text.

```java
AiRequest request = AiRequest.builder()
    .operation(AiOperation.EDIT_IMAGE)
    .model("your-gemini-image-edit-model")
    .instructions("Edit the image while preserving its main subject.")
    .userPrompt("Replace the background with a mountain landscape.")
    .inputMedia(BinaryContent.from(Path.of("source.png"), "image/png"))
    .build();

AiResponse response = client.execute(request, config);
if (response.media().isEmpty()) {
    throw new IllegalStateException("Gemini did not return an edited image");
}
GeneratedMedia editedImage = response.media().get(0);
```

## `AiRequest` fields

| Field | `TEXT` | `GENERATE_IMAGE` | `EDIT_IMAGE` |
| --- | --- | --- | --- |
| `operation` | Use `TEXT` or omit it | Required value | Required value |
| `model` | Required | Required; must support image output | Required; must support editing |
| `instructions` | Trusted system instructions | Task text is sent as user content | Task text is sent as user content |
| `inputText` | Protected and sent | Protected and sent | Ignored |
| `userPrompt` | Protected and sent | Protected and sent | Protected and sent |
| `inputMedia` | Optional multimodal input | Optional reference input | Required and must contain bytes |
| `store` | Ignored | Ignored | Ignored |
| `imageOptions` | Ignored | Ignored | Ignored |

For image operations, the adapter keeps prompt-security rules in
`systemInstruction` and moves trusted task instructions into the user-content
parts expected by the image request.

If a request has no user content, the adapter adds a neutral fallback instruction.

## Models and responses

```java
List<ModelInfo> models = client.listModels(config);
```

The provider follows Gemini catalogue pagination, removes the `models/` prefix,
and sorts results by model identifier. The result does not describe whether a
model supports text, image input, image output, or editing.

Gemini may return text and media in the same response:

- only the first response candidate is consumed;
- all text parts in that candidate are concatenated into `response.text()`;
- every valid inline-media part in that candidate becomes `GeneratedMedia`;
- usage is exposed through `response.usage()`;
- a present non-`STOP` finish reason, blocked prompt, missing candidate, or
  unusable response causes `AiProviderException`; streaming additionally requires
  a terminal `STOP`.

Image count is determined by the model response. Although all inline-media parts
from the first candidate are returned, the adapter provides no request option for
the desired number of images.

Saving returned media is optional:

```java
if (response.media().isEmpty()) {
    throw new IllegalStateException("Gemini did not return an image");
}
GeneratedMedia image = response.media().get(0);
String extension = image.suggestedFileExtension().orElse("bin");
Files.write(Path.of("result." + extension), image.data());
```
