package com.webjetcms.ai;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, advisory metadata for an adapter and a selected model.
 *
 * <p>Adapter support describes implemented library behavior. Model support describes
 * only what the adapter's local metadata knows; it does not guarantee account access,
 * model availability, or compatibility with a custom endpoint. Unknown models remain
 * usable, and reading capabilities never changes request validation or storage consent.</p>
 *
 * @param operations operation metadata; omitted operations become unknown
 * @param embeddingSupport whether the adapter implements {@link AiProvider#embed}
 * @param embeddingModelSupport known embedding support of the selected model
 */
public record AiCapabilities(
    Map<AiOperation, Operation> operations,
    Support embeddingSupport,
    Support embeddingModelSupport
) {

    private static final AiCapabilities UNKNOWN =
        new AiCapabilities(Map.of(), Support.UNKNOWN, Support.UNKNOWN);

    /**
     * Copies operation metadata in enum order and rejects null keys or values.
     *
     * @param operations operation metadata to copy
     * @param embeddingSupport adapter embedding support
     * @param embeddingModelSupport selected model's known embedding support
     */
    public AiCapabilities {
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(embeddingSupport, "embeddingSupport");
        Objects.requireNonNull(embeddingModelSupport, "embeddingModelSupport");
        EnumMap<AiOperation, Operation> copy = new EnumMap<>(AiOperation.class);
        operations.forEach((operation, capability) -> copy.put(
            Objects.requireNonNull(operation, "operation"),
            Objects.requireNonNull(capability, "capability")
        ));
        for (AiOperation operation : AiOperation.values()) {
            copy.putIfAbsent(operation, Operation.unknown());
        }
        operations = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns metadata for a provider that has not published its capabilities.
     *
     * @return immutable metadata with every capability unknown
     */
    public static AiCapabilities unknown() {
        return UNKNOWN;
    }

    /**
     * Returns one operation's adapter and model metadata.
     *
     * @param operation non-null operation to inspect
     * @return operation metadata, including unknown entries omitted by the provider
     */
    public Operation operation(AiOperation operation) {
        return operations.get(Objects.requireNonNull(operation, "operation"));
    }

    /** Distinguishes known support or rejection from missing metadata. */
    public enum Support {
        /** Local metadata explicitly describes this capability as supported. */
        SUPPORTED,
        /** The adapter or known model restriction excludes this capability. */
        UNSUPPORTED,
        /** No reliable local metadata is available; this is not a rejection. */
        UNKNOWN
    }

    /** Adapter continuation behavior; these values never grant permission to store data. */
    public enum Continuation {
        /** The provider has not described its continuation behavior. */
        UNKNOWN,
        /** The operation does not expose conversation continuation. */
        NONE,
        /** Provider-managed continuation requiring explicit {@code store(true)}. */
        PROVIDER_STORAGE_REQUIRED
    }

    /**
     * Describes one operation without conflating adapter behavior with model certainty.
     *
     * <p>Streaming describes the adapter's {@code stream} method, not a promise that
     * every model supports streaming. Listeners receive text fragments only; image
     * data, where supported, is returned in the completed response. Continuation
     * likewise describes the adapter protocol and remains conditional on model support.</p>
     *
     * @param adapterSupport whether the adapter implements the operation
     * @param modelSupport known support for the selected model and operation
     * @param adapterStreaming whether the adapter implements streaming for the operation
     * @param continuation continuation mechanism and any explicit storage requirement
     */
    public record Operation(
        Support adapterSupport,
        Support modelSupport,
        Support adapterStreaming,
        Continuation continuation
    ) {
        private static final Operation UNKNOWN = new Operation(
            Support.UNKNOWN, Support.UNKNOWN, Support.UNKNOWN, Continuation.UNKNOWN);
        private static final Operation UNSUPPORTED = new Operation(
            Support.UNSUPPORTED, Support.UNKNOWN, Support.UNSUPPORTED, Continuation.NONE);

        /**
         * Creates an operation descriptor with non-null classifications.
         *
         * @param adapterSupport adapter operation support
         * @param modelSupport selected model's known support
         * @param adapterStreaming adapter streaming support
         * @param continuation adapter continuation behavior
         */
        public Operation {
            Objects.requireNonNull(adapterSupport, "adapterSupport");
            Objects.requireNonNull(modelSupport, "modelSupport");
            Objects.requireNonNull(adapterStreaming, "adapterStreaming");
            Objects.requireNonNull(continuation, "continuation");
        }

        /**
         * Returns unknown operation metadata.
         *
         * @return immutable unknown descriptor
         */
        public static Operation unknown() { return UNKNOWN; }

        /**
         * Returns metadata for an operation the adapter does not implement, without
         * making a claim about the model's capabilities outside this adapter.
         *
         * @return immutable unsupported descriptor
         */
        public static Operation unsupported() { return UNSUPPORTED; }

        /**
         * Combines adapter and model support, retaining uncertainty about either.
         *
         * @return unsupported if either excludes the operation, supported if both
         *     support it, otherwise unknown
         */
        public Support support() {
            if (adapterSupport == Support.UNSUPPORTED || modelSupport == Support.UNSUPPORTED) {
                return Support.UNSUPPORTED;
            }
            return adapterSupport == Support.SUPPORTED && modelSupport == Support.SUPPORTED
                ? Support.SUPPORTED : Support.UNKNOWN;
        }
    }
}
