package com.webjetcms.ai;

import java.util.List;

/**
 * Immutable provider-neutral embedding request.
 * Input text is retained unchanged because adding prompt-defense markers would
 * alter the resulting vectors.
 *
 * @param model provider-specific embedding model identifier, possibly {@code null}
 * @param inputs text values to embed; response vectors follow this order;
 *     {@code null} becomes an empty list and elements must not be {@code null}
 * @param options embedding settings; {@code null} becomes default options
 */
public record EmbeddingRequest(String model, List<String> inputs, EmbeddingOptions options) {

    /**
     * Normalizes nullable values and stores an immutable copy of the inputs.
     *
     * @throws NullPointerException when an input element is {@code null}
     */
    public EmbeddingRequest {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        options = options == null ? new EmbeddingOptions() : options;
    }

    /**
     * Creates a request using provider-default embedding options.
     *
     * @param model provider-specific embedding model identifier
     * @param inputs text values to embed; response vectors follow this order
     */
    public EmbeddingRequest(String model, List<String> inputs) {
        this(model, inputs, null);
    }

    /**
     * Starts an embedding request.
     *
     * @return a new request builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns request metadata without exposing the text inputs. */
    @Override
    public String toString() {
        return "EmbeddingRequest[model=" + model + ", inputCount=" + inputs.size()
            + ", options=" + options + "]";
    }

    /** Builds an immutable {@link EmbeddingRequest}. */
    public static final class Builder {
        private String model;
        private List<String> inputs;
        private EmbeddingOptions options;

        private Builder() { }

        /**
         * Selects a provider-specific embedding model.
         *
         * @param model provider model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Supplies text values to embed; response vectors follow this order.
         *
         * @param inputs text values to embed
         * @return this builder
         */
        public Builder inputs(List<String> inputs) {
            this.inputs = inputs;
            return this;
        }

        /**
         * Supplies provider-neutral embedding settings.
         *
         * @param options embedding settings; {@code null} uses provider defaults
         * @return this builder
         */
        public Builder options(EmbeddingOptions options) {
            this.options = options;
            return this;
        }

        /**
         * Creates an immutable request from the accumulated values.
         *
         * @return a provider-neutral embedding request
         */
        public EmbeddingRequest build() {
            return new EmbeddingRequest(model, inputs, options);
        }
    }
}
