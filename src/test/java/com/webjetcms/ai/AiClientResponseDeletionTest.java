package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AiClientResponseDeletionTest {

    @Test
    void oldProvidersRemainCompatibleAndReportUnsupportedDeletion() throws Exception {
        try (AiClient client = AiClient.of(new ExistingProvider())) {
            assertEquals(AiCapabilities.Support.UNKNOWN, client.capabilities("model").responseDeletionSupport());
            AiProviderException failure = assertThrows(AiProviderException.class,
                () -> client.deleteResponse("saved", AiProviderConfig.empty()));
            assertEquals(AiErrorCategory.UNSUPPORTED_OPERATION, failure.category());
            assertFalse(failure.retryable());
            AiCancellationToken cancelled = new AiCancellationToken();
            cancelled.cancel();
            assertEquals(AiErrorCategory.CANCELLED, assertThrows(AiProviderException.class,
                () -> client.deleteResponse("saved", AiProviderConfig.empty(), cancelled)).category());
        }
        try (AiClient client = AiClient.discover()) {
            assertEquals(AiErrorCategory.UNSUPPORTED_OPERATION, assertThrows(AiProviderException.class,
                () -> client.deleteResponse("openrouter", "saved", AiProviderConfig.empty())).category());
            assertThrows(IllegalStateException.class, () -> client.deleteResponse("saved", AiProviderConfig.empty()));
            assertThrows(IllegalArgumentException.class,
                () -> client.deleteResponse("unknown", "saved", AiProviderConfig.empty()));
        }
    }

    @Test
    void allClientOverloadsDelegateWithoutRequiringAModelOrCloudConfiguration() throws Exception {
        AiProviderConfig config = AiProviderConfig.empty();
        AtomicInteger calls = new AtomicInteger();
        ExistingProvider provider = new ExistingProvider() {
            @Override
            public AiResponseDeletion deleteResponse(String responseId, AiProviderConfig received) {
                assertEquals("saved", responseId);
                assertSame(config, received);
                calls.incrementAndGet();
                return AiResponseDeletion.ALREADY_ABSENT;
            }
        };
        try (AiClient client = AiClient.of(provider)) {
            assertEquals(AiResponseDeletion.ALREADY_ABSENT, client.deleteResponse("saved", config));
            assertEquals(AiResponseDeletion.ALREADY_ABSENT, client.deleteResponse("custom", "saved", config));
            assertEquals(AiResponseDeletion.ALREADY_ABSENT,
                client.deleteResponse("saved", config, new AiCancellationToken()));
            assertEquals(AiResponseDeletion.ALREADY_ABSENT,
                client.deleteResponse("custom", "saved", config, new AiCancellationToken()));
            assertThrows(AiProviderException.class, () -> client.deleteResponse("..", config));
        }
        assertEquals(4, calls.get());
    }

    @Test
    void cancellationAfterLegacyDeletionDoesNotReturnAConfirmedResult() throws Exception {
        AiCancellationToken token = new AiCancellationToken();
        ExistingProvider provider = new ExistingProvider() {
            @Override
            public AiResponseDeletion deleteResponse(String responseId, AiProviderConfig config) {
                token.cancel();
                return AiResponseDeletion.DELETED;
            }
        };
        try (AiClient client = AiClient.of(provider)) {
            assertEquals(AiErrorCategory.CANCELLED, assertThrows(AiProviderException.class,
                () -> client.deleteResponse("saved", AiProviderConfig.empty(), token)).category());
        }
    }

    private static class ExistingProvider implements AiProvider {
        @Override
        public String id() { return "custom"; }

        @Override
        public List<ModelInfo> listModels(AiProviderConfig config) { throw new AssertionError("Unexpected model lookup"); }

        @Override
        public AiResponse execute(AiRequest request, AiProviderConfig config) { throw new AssertionError("Unexpected generation"); }

        @Override
        public AiResponse stream(AiRequest request, AiProviderConfig config, AiStreamListener listener) {
            throw new AssertionError("Unexpected stream");
        }
    }
}
