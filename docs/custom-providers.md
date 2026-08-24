# Implementing and using a custom AI provider

This guide shows how an application can implement `AiProvider`, include it during
client discovery, list its models, and execute requests through `AiClient`.

## 1. Implement `AiProvider`

Every provider needs a stable, non-blank identifier and implementations for model
discovery, non-streaming requests, and streaming requests. The following complete
provider is intentionally simple so that it compiles without a vendor SDK:

```java
package com.example.ai;

import java.util.List;

import com.webjetcms.ai.AiOperation;
import com.webjetcms.ai.AiProvider;
import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.AiProviderException;
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;
import com.webjetcms.ai.AiStreamListener;
import com.webjetcms.ai.ModelInfo;

public final class ExampleProvider implements AiProvider {

    public static final String PROVIDER_ID = "example";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ModelInfo> listModels(AiProviderConfig config) {
        // A real adapter normally loads this catalogue from its remote API.
        return List.of(new ModelInfo("example-text-1", "Example Text 1"));
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderConfig config)
        throws AiProviderException {
        validateTextRequest(request);

        // Replace this deterministic response with a call to the vendor API.
        return AiResponse.text("Response from " + request.model());
    }

    @Override
    public AiResponse stream(
        AiRequest request,
        AiProviderConfig config,
        AiStreamListener listener
    ) throws AiProviderException {
        if (listener == null) {
            throw new AiProviderException(PROVIDER_ID, "Stream listener is required");
        }

        AiResponse response = execute(request, config);
        try {
            listener.onTextDelta(response.text());
        } catch (Exception exception) {
            throw new AiProviderException(PROVIDER_ID, "Stream listener failed", exception);
        }
        return response;
    }

    private static void validateTextRequest(AiRequest request) throws AiProviderException {
        if (request == null) {
            throw new AiProviderException(PROVIDER_ID, "Request is required");
        }
        if (request.operation() != AiOperation.TEXT) {
            throw new AiProviderException(PROVIDER_ID, "Only text requests are supported");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new AiProviderException(PROVIDER_ID, "Model is required");
        }
    }
}
```

The default `AiProvider.embed(...)` implementation reports that embeddings are not
supported. Override it only when the backend supports embeddings. Override `close()`
when the provider owns an HTTP client, executor, or another closeable resource.

A provider instance is reused for calls made by its owning client and may receive
concurrent calls. Keep mutable state thread-safe and reuse a pooled transport instead
of creating a new HTTP client for every request. Treat the provider ID as a public
configuration key: keep it stable, unique, and preferably lowercase.

## 2. Discover and select the provider

Pass a custom implementation directly to `AiClient.discover(...)`:

```java
import java.util.List;

import com.example.ai.ExampleProvider;
import com.webjetcms.ai.AiClient;

ExampleProvider custom = new ExampleProvider();
try (AiClient client = AiClient.discover(custom)) {
    List<String> providers = client.providers();
    String example = ExampleProvider.PROVIDER_ID;

    if (providers.contains(example) == false) {
        throw new IllegalStateException("Example provider was not discovered");
    }
    // Use example with the configuration and calls in the next sections.
}
```

The parameter type is `AiProvider...`, so Java rejects a custom object that does not
implement `AiProvider`. The exact value returned by `provider.id()` appears in the
immutable, sorted `List<String>` returned by `client.providers()`. IDs are compared
exactly and case-sensitively; the library does not trim or normalize them. A custom
provider cannot silently replace a bundled or custom provider with the same ID;
duplicate IDs fail discovery before bundled factories are invoked.

Constructor injection remains direct:

```java
MyProvider custom = new MyProvider(sharedHttpClient, applicationMetrics);
try (AiClient client = AiClient.discover(
    custom
)) {
    // Bundled providers and MyProvider are available here.
}
```

Discovery is local to the new client. It does not mutate global state or scan the class
path. Each call creates fresh bundled provider instances; pass a fresh custom instance
to each client as well. A successfully created client owns and closes all its providers.
If discovery throws before returning a client, the library closes bundled instances it
created; supplied custom providers remain caller-owned and must be closed by the caller.
Keep the custom instance in a variable so failure handling can still access it. Do not
share one provider instance between clients. When a custom provider receives an
application-owned dependency, its `close()` implementation must respect that
dependency's ownership.

Use `AiClient.of(...)` when the application wants only explicitly supplied providers:

```java
ExampleProvider customOnly = new ExampleProvider();
try (AiClient client = AiClient.of(customOnly)) {
    // Only ExampleProvider is registered.
}
```

`of(...)` transfers ownership of supplied providers only when it returns successfully.
If validation fails before a client is returned, the caller still owns those instances.

The built-in provider IDs are available as the public `String` constants
`AiProviders.OPENAI`, `AiProviders.GEMINI`, and `AiProviders.OPENROUTER`:

```java
import java.util.List;

import com.webjetcms.ai.AiProviders;

List<String> bundledProviderIds = AiProviders.builtIns();
String openAiProviderId = AiProviders.OPENAI;
```

`AiProviders.builtIns()` returns the complete immutable, sorted built-in ID list without
constructing provider instances. It never contains application-supplied providers;
those appear only in `client.providers()` for the client that received them. Reading
either list does not call `listModels` or require credentials.

## 3. Configure the provider and list its models

Resolve configuration from the selected provider ID so that the host does not need a
provider-specific switch statement. Run this code inside the open `AiClient` scope
from the previous section:

```java
import java.util.List;

import com.webjetcms.ai.AiProviderConfig;
import com.webjetcms.ai.ModelInfo;

AiProviderConfig config = AiProviderConfig.builder(
    secretStore.get(example + ".apiKey")
).build();

List<ModelInfo> models = client.listModels(example, config);
String modelId = models.get(0).id();
```

Here, `secretStore` represents the host application's credential store.

A real remote adapter should reject a null configuration and check
`config.isConfigured()` before making a request. It can also use `config.baseUri()`,
timeout values, and trusted headers. Never include `config.apiKey()` or trusted-header
values in logs, exception messages, cache keys, or `toString()` output.

`listModels` should return an immutable or caller-safe list. Model identifiers are
scoped to their provider, so two providers may expose the same model ID.
`AiClient` does not keep a hardcoded model catalogue; it delegates each catalogue request
to the selected provider with the supplied configuration.
`ModelInfo.createdAt()` is an optional provider-reported Unix timestamp in seconds.
A model catalogue does not by itself prove that a model supports streaming, images,
or embeddings.

## 4. Execute and stream requests

Select both the provider and a model returned by that provider:

```java
import com.webjetcms.ai.AiRequest;
import com.webjetcms.ai.AiResponse;

AiRequest request = AiRequest.builder()
    .model(modelId)
    .instructions("Answer concisely.")
    .inputText("Source text supplied by the application")
    .userPrompt("Summarize the source")
    .store(false)
    .build();

AiResponse response = client.execute(example, request, config);
System.out.println(response.text());

AiResponse streamed = client.stream(
    example,
    request,
    config,
    System.out::print
);
```

Call custom providers through `AiClient`, not directly. The client prepares an
immutable request copy and applies prompt-injection protection to untrusted input
before delegating. `store` defaults to `false`.

Throw `AiProviderException` with the stable provider ID for validation, transport,
protocol, and parsing failures. `AiClient` applies credential redaction at the provider
boundary, but providers must still avoid logging secrets themselves.

Embedding inputs are intentionally delegated without prompt-protection markers because
changing their text would change the generated vectors. Apply host privacy and content
policies before calling or implementing `embed(...)`.

## 5. Test the provider adapter

At minimum, cover model-catalogue parsing, empty and malformed responses, backend error
mapping, timeouts, unsupported operations, streaming fragments, listener failures,
credential redaction, and resource cleanup. Transport tests should use a local test
server rather than a live vendor API.

## 6. Distribute a custom provider

A custom provider can be packaged in its own library, but placing that JAR on the
class path does not register it implicitly. The host constructs the provider and passes
it to `AiClient.discover(...)` or `AiClient.of(...)`. This explicit, client-local
discovery prevents unrelated applications in the same JVM from changing each other's
provider catalogue.

## Checklist

- Return a stable, unique, non-blank provider ID.
- Return caller-safe model catalogues.
- Validate requests, models, configuration, and supported operations.
- Reuse thread-safe transport resources and close owned resources.
- Never log or serialize credentials.
- Wrap backend failures in `AiProviderException` without exposing secrets.
- Invoke providers through `AiClient` so request protection and redaction remain active.
- Pass a fresh custom provider instance to each client and do not share it between clients.
- Use `AiClient.discover(...)` for bundled plus custom providers, or `AiClient.of(...)`
  for only explicitly supplied providers.
