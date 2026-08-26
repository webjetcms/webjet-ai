package com.webjetcms.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.webjetcms.ai.provider.gemini.GeminiProvider;
import com.webjetcms.ai.provider.openai.OpenAiProvider;
import com.webjetcms.ai.provider.openrouter.OpenRouterProvider;

/**
 * Stable string identifiers for the AI providers bundled with WebJET AI.
 *
 * <p>The public catalogue exposes identifiers only. {@link AiClient#discover()} creates
 * fresh provider instances and owns their lifecycle.</p>
 */
public final class AiProviders {

    /** Exact identifier of the bundled OpenAI provider. */
    public static final String OPENAI = OpenAiProvider.PROVIDER_ID;

    /** Exact identifier of the bundled Google Gemini provider. */
    public static final String GEMINI = GeminiProvider.PROVIDER_ID;

    /** Exact identifier of the bundled OpenRouter provider. */
    public static final String OPENROUTER = OpenRouterProvider.PROVIDER_ID;

    private static final List<Entry> BUILT_IN_ENTRIES = List.of(
        new Entry(OPENAI, OpenAiProvider::new),
        new Entry(GEMINI, GeminiProvider::new),
        new Entry(OPENROUTER, OpenRouterProvider::new)
    ).stream()
        .sorted(Comparator.comparing(Entry::id))
        .toList();

    private static final List<String> BUILT_INS = BUILT_IN_ENTRIES.stream()
        .map(Entry::id)
        .toList();

    private AiProviders() { }

    /**
     * Returns every bundled provider identifier.
     *
     * <p>Reading the catalogue does not construct providers, allocate provider-owned
     * transport resources, require credentials, or make network requests.</p>
     *
     * @return an immutable list sorted by the identifiers' natural, case-sensitive order
     */
    public static List<String> builtIns() {
        return BUILT_INS;
    }

    /**
     * Returns the internal provider factories used by {@link AiClient#discover()}.
     *
     * @return immutable entries sorted by provider identifier
     */
    static List<Entry> entries() {
        return BUILT_IN_ENTRIES;
    }

    /**
     * Associates a bundled provider identifier with a factory for fresh provider instances.
     *
     * @param id exact bundled provider identifier
     * @param factory factory that creates a new provider instance
     */
    static record Entry(
        String id,
        Supplier<? extends AiProvider> factory
    ) {
        Entry {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("AI provider id must not be blank");
            }
            Objects.requireNonNull(factory, "factory");
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
