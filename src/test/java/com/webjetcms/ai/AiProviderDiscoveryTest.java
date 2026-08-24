package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AiProviderDiscoveryTest {

    @Test
    void exposesAndDiscoversImmutableBuiltInProviderIds() throws Exception {
        assertEquals("openai", AiProviders.OPENAI);
        assertEquals("gemini", AiProviders.GEMINI);
        assertEquals("openrouter", AiProviders.OPENROUTER);
        assertEquals(
            List.of(AiProviders.GEMINI, AiProviders.OPENAI, AiProviders.OPENROUTER),
            AiProviders.builtIns()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> AiProviders.builtIns().add("custom")
        );

        try (AiClient client = AiClient.discover()) {
            assertEquals(AiProviders.builtIns(), client.providers());
            assertThrows(
                UnsupportedOperationException.class,
                () -> client.providers().add("custom")
            );
        }
    }

    @Test
    void bundledFactoriesCreateFreshProviderInstances() throws Exception {
        for (AiProviders.Entry entry : AiProviders.entries()) {
            try (
                AiProvider first = entry.factory().get();
                AiProvider second = entry.factory().get()
            ) {
                assertNotSame(first, second, entry.id());
                assertEquals(entry.id(), first.id());
                assertEquals(entry.id(), second.id());
            }
        }
    }

    @Test
    void discoversRoutesAndOwnsCustomProvider() throws Exception {
        RecordingProvider custom = new RecordingProvider("zz-custom");
        AiProviderConfig config = AiProviderConfig.builder("key").build();
        AiRequest request = AiRequest.builder().model("text-model").build();
        EmbeddingRequest embeddingRequest = new EmbeddingRequest(
            "embedding-model",
            List.of("input")
        );
        List<String> deltas = new ArrayList<>();

        try (AiClient client = AiClient.discover(custom)) {
            List<String> expectedIds = new ArrayList<>(AiProviders.builtIns());
            expectedIds.add(custom.id());
            expectedIds.sort(String::compareTo);

            assertEquals(expectedIds, client.providers());
            assertTrue(client.hasProvider(custom.id()));
            assertFalse(client.hasProvider("ZZ-CUSTOM"));
            assertEquals("zz-custom-model", client.listModels(custom.id(), config).get(0).id());
            assertEquals("execute:zz-custom", client.execute(custom.id(), request, config).text());
            assertEquals(
                "stream:zz-custom",
                client.stream(custom.id(), request, config, deltas::add).text()
            );
            assertEquals(
                1,
                client.embed(custom.id(), embeddingRequest, config).embeddings().size()
            );
        }

        assertEquals(List.of("delta:zz-custom"), deltas);
        assertEquals(1, custom.closeCount);
    }

    @Test
    void validationFailureSkipsFactoriesAndRetainsCallerOwnership() {
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingProvider collision = new RecordingProvider(AiProviders.OPENAI);
        List<AiProviders.Entry> builtIns = List.of(
            new AiProviders.Entry(AiProviders.OPENAI, () -> {
                factoryCalls.incrementAndGet();
                return new RecordingProvider(AiProviders.OPENAI);
            })
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> AiClient.createDiscoveredClient(builtIns, collision)
        );
        assertEquals(0, factoryCalls.get());
        assertEquals(0, collision.closeCount);

        RecordingProvider firstDuplicate = new RecordingProvider("duplicate");
        RecordingProvider secondDuplicate = new RecordingProvider("duplicate");
        assertThrows(
            IllegalArgumentException.class,
            () -> AiClient.of(firstDuplicate, secondDuplicate)
        );
        assertEquals(0, firstDuplicate.closeCount);
        assertEquals(0, secondDuplicate.closeCount);
    }

    @Test
    void closesMismatchedFactoryResultWithoutTakingCustomOwnership() {
        RecordingProvider mismatched = new RecordingProvider("actual-id");
        RecordingProvider customForMismatch = new RecordingProvider("custom-mismatch");
        IllegalStateException mismatchFailure = assertThrows(
            IllegalStateException.class,
            () -> AiClient.createDiscoveredClient(
                List.of(new AiProviders.Entry("expected-id", () -> mismatched)),
                customForMismatch
            )
        );
        assertTrue(mismatchFailure.getMessage().contains("expected-id"));
        assertTrue(mismatchFailure.getMessage().contains("actual-id"));
        assertEquals(1, mismatched.closeCount);
        assertEquals(0, customForMismatch.closeCount);
    }

    @Test
    void closesCreatedBuiltInsAndSuppressesCleanupFailuresWhenAFactoryThrows() {
        AssertionError closeFailure = new AssertionError("close failure");
        RecordingProvider firstCreated = new RecordingProvider("first-created", closeFailure);
        RecordingProvider secondCreated = new RecordingProvider("second-created");
        RecordingProvider custom = new RecordingProvider("custom");
        IllegalStateException factoryFailure = new IllegalStateException("factory failure");

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> AiClient.createDiscoveredClient(
                List.of(
                    new AiProviders.Entry("first-created", () -> firstCreated),
                    new AiProviders.Entry("second-created", () -> secondCreated),
                    new AiProviders.Entry("failing", () -> {
                        throw factoryFailure;
                    })
                ),
                custom
            )
        );

        assertSame(factoryFailure, thrown);
        assertEquals(1, firstCreated.closeCount);
        assertEquals(1, secondCreated.closeCount);
        assertEquals(0, custom.closeCount);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void continuesClosingAndAggregatesErrors() {
        AssertionError alphaFailure = new AssertionError("alpha close failure");
        AssertionError zetaFailure = new AssertionError("zeta close failure");
        RecordingProvider alpha = new RecordingProvider("alpha", alphaFailure);
        RecordingProvider zeta = new RecordingProvider("zeta", zetaFailure);
        AiClient client = AiClient.of(alpha, zeta);

        AssertionError thrown = assertThrows(AssertionError.class, client::close);

        assertTrue(thrown == alphaFailure || thrown == zetaFailure);
        assertEquals(1, alpha.closeCount);
        assertEquals(1, zeta.closeCount);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(
            thrown == alphaFailure ? zetaFailure : alphaFailure,
            thrown.getSuppressed()[0]
        );
    }

    @Test
    void avoidsSelfSuppressionWhenProvidersThrowTheSameException() {
        Exception sharedFailure = new Exception("shared close failure");
        RecordingProvider alpha = new RecordingProvider("alpha", sharedFailure);
        RecordingProvider zeta = new RecordingProvider("zeta", sharedFailure);
        AiClient client = AiClient.of(alpha, zeta);

        Exception thrown = assertThrows(Exception.class, client::close);

        assertSame(sharedFailure, thrown);
        assertEquals(1, alpha.closeCount);
        assertEquals(1, zeta.closeCount);
        assertEquals(0, thrown.getSuppressed().length);
    }

    private static final class RecordingProvider implements AiProvider {
        private final String id;
        private final Throwable closeFailure;
        private int closeCount;

        private RecordingProvider(String id) {
            this(id, null);
        }

        private RecordingProvider(String id, Throwable closeFailure) {
            this.id = id;
            this.closeFailure = closeFailure;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<ModelInfo> listModels(AiProviderConfig config) {
            return List.of(new ModelInfo(id + "-model", id + " Model"));
        }

        @Override
        public AiResponse execute(AiRequest request, AiProviderConfig config) {
            return AiResponse.text("execute:" + id);
        }

        @Override
        public EmbeddingResponse embed(
            EmbeddingRequest request,
            AiProviderConfig config
        ) {
            return new EmbeddingResponse(List.of(
                new EmbeddingVector(new float[] {1.0f, 2.0f})
            ));
        }

        @Override
        public AiResponse stream(
            AiRequest request,
            AiProviderConfig config,
            AiStreamListener listener
        ) throws AiProviderException {
            try {
                listener.onTextDelta("delta:" + id);
            } catch (Exception exception) {
                throw new AiProviderException(id, "Listener failed", exception);
            }
            return AiResponse.text("stream:" + id);
        }

        @Override
        public void close() throws Exception {
            closeCount++;
            if (closeFailure instanceof Exception exception) {
                throw exception;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
        }
    }
}
