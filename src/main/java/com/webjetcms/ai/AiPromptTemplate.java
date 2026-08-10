package com.webjetcms.ai;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.ProtectionResult;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Safely expands standard untrusted-value placeholders in task instructions. */
public final class AiPromptTemplate {

    private static final Pattern EXPANSION_TOKEN = Pattern.compile(
        protectedBlockPattern(UntrustedSource.INPUT_TEXT)
            + "|" + protectedBlockPattern(UntrustedSource.USER_PROMPT)
            + "|\\{inputText\\}|\\{userPrompt\\}",
        Pattern.DOTALL
    );

    private AiPromptTemplate() {
    }

    /**
     * Expands standard untrusted placeholders without changing their protected representation.
     *
     * @param instructions instruction template, possibly {@code null}
     * @param inputText untrusted source text, possibly {@code null}
     * @param userPrompt untrusted end-user prompt, possibly {@code null}
     * @return immutable expansion result
     */
    public static ExpansionResult expand(String instructions, String inputText, String userPrompt) {
        return expand(instructions, inputText, userPrompt, UnaryOperator.identity());
    }

    /**
     * Expands standard untrusted placeholders in one pass.
     *
     * <p>The formatter receives the complete protected replacement and can encode it
     * for a host-owned template convention, such as JSON string content. It must retain
     * exactly one canonical opening and closing boundary; the library validates this
     * invariant. Replacement text and previously protected blocks are never scanned for
     * more placeholders.</p>
     *
     * @param instructions instruction template, possibly {@code null}
     * @param inputText untrusted source text, possibly {@code null}
     * @param userPrompt untrusted end-user prompt, possibly {@code null}
     * @param protectedValueFormatter formatter applied to each protected replacement
     * @return immutable expansion result
     * @throws NullPointerException when the formatter is {@code null} or returns {@code null}
     * @throws IllegalArgumentException when the formatter changes canonical boundaries
     */
    public static ExpansionResult expand(
        String instructions,
        String inputText,
        String userPrompt,
        UnaryOperator<String> protectedValueFormatter
    ) {
        Objects.requireNonNull(protectedValueFormatter, "protectedValueFormatter");
        if (instructions == null || instructions.isEmpty()) {
            return new ExpansionResult(instructions, Set.of(), Set.of());
        }

        Matcher matcher = EXPANSION_TOKEN.matcher(instructions);
        if (matcher.find() == false) {
            return new ExpansionResult(instructions, Set.of(), Set.of());
        }

        Map<UntrustedSource, PreparedValue> preparedValues = new EnumMap<>(UntrustedSource.class);
        EnumSet<UntrustedSource> consumedSources = EnumSet.noneOf(UntrustedSource.class);
        EnumSet<UntrustedSource> suspiciousSources = EnumSet.noneOf(UntrustedSource.class);
        StringBuffer expanded = new StringBuffer();

        do {
            if (matcher.group().startsWith("[BEGIN_UNTRUSTED_")) {
                UntrustedSource protectedSource = matcher.group().startsWith(
                    PromptInjectionDefense.getUntrustedBeginMarker(UntrustedSource.INPUT_TEXT)
                ) ? UntrustedSource.INPUT_TEXT : UntrustedSource.USER_PROMPT;
                consumedSources.add(protectedSource);
                if (PromptInjectionDefense.hasSuspiciousContentNotice(matcher.group())) {
                    suspiciousSources.add(protectedSource);
                }
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            UntrustedSource source = "{inputText}".equals(matcher.group())
                ? UntrustedSource.INPUT_TEXT
                : UntrustedSource.USER_PROMPT;
            PreparedValue preparedValue = preparedValues.get(source);
            if (preparedValue == null) {
                String value = UntrustedSource.INPUT_TEXT.equals(source) ? inputText : userPrompt;
                ProtectionResult protection = PromptInjectionDefense.protectUntrustedText(value, source);
                String protectedText = protection.protectedText() == null ? "" : protection.protectedText();
                String formattedText = Objects.requireNonNull(
                    protectedValueFormatter.apply(protectedText),
                    "protectedValueFormatter result"
                );
                validateProtectedBoundaries(protectedText, formattedText, source);
                preparedValue = new PreparedValue(formattedText, protection.suspiciousContentDetected());
                preparedValues.put(source, preparedValue);
            }

            consumedSources.add(source);
            if (preparedValue.suspicious()) suspiciousSources.add(source);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(preparedValue.text()));
        } while (matcher.find());
        matcher.appendTail(expanded);

        return new ExpansionResult(expanded.toString(), consumedSources, suspiciousSources);
    }

    private static String protectedBlockPattern(UntrustedSource source) {
        return Pattern.quote(PromptInjectionDefense.getUntrustedBeginMarker(source))
            + ".*?"
            + Pattern.quote(PromptInjectionDefense.getUntrustedEndMarker(source));
    }

    private static void validateProtectedBoundaries(
        String protectedText,
        String formattedText,
        UntrustedSource source
    ) {
        if (protectedText.isBlank()) {
            if (formattedText.isBlank() == false) {
                throw new IllegalArgumentException("Protected-value formatter changed a blank replacement");
            }
            return;
        }
        String begin = PromptInjectionDefense.getUntrustedBeginMarker(source);
        String end = PromptInjectionDefense.getUntrustedEndMarker(source);
        if (countOccurrences(formattedText, begin) != 1
            || countOccurrences(formattedText, end) != 1
            || formattedText.startsWith(begin) == false
            || formattedText.endsWith(end) == false
            || (PromptInjectionDefense.hasSuspiciousContentNotice(protectedText)
                && PromptInjectionDefense.hasSuspiciousContentNotice(formattedText) == false)) {
            throw new IllegalArgumentException("Protected-value formatter changed canonical boundaries for " + source);
        }
    }

    private static int countOccurrences(String value, String pattern) {
        int count = 0;
        int index = value.indexOf(pattern);
        while (index >= 0) {
            count++;
            index = value.indexOf(pattern, index + pattern.length());
        }
        return count;
    }

    private record PreparedValue(String text, boolean suspicious) {
    }

    /** Immutable result of expanding an instruction template. */
    public static final class ExpansionResult {
        private final String instructions;
        private final Set<UntrustedSource> consumedSources;
        private final Set<UntrustedSource> suspiciousSources;

        private ExpansionResult(
            String instructions,
            Set<UntrustedSource> consumedSources,
            Set<UntrustedSource> suspiciousSources
        ) {
            this.instructions = instructions;
            this.consumedSources = immutableEnumSet(consumedSources);
            this.suspiciousSources = immutableEnumSet(suspiciousSources);
        }

        /**
         * Returns instructions with standard placeholders expanded.
         *
         * @return expanded instructions, possibly {@code null}
         */
        public String instructions() {
            return instructions;
        }

        /**
         * Returns request fields represented inside the expanded instructions.
         *
         * @return immutable consumed-source set
         */
        public Set<UntrustedSource> consumedSources() {
            return consumedSources;
        }

        /**
         * Returns consumed fields that matched prompt-injection patterns or reserved markers.
         *
         * @return immutable suspicious-source set
         */
        public Set<UntrustedSource> suspiciousSources() {
            return suspiciousSources;
        }

        /**
         * Returns a result with host-resolved instructions and unchanged source metadata.
         *
         * @param replacementInstructions replacement instructions, possibly {@code null}
         * @return immutable result retaining consumed and suspicious sources
         */
        public ExpansionResult withInstructions(String replacementInstructions) {
            return new ExpansionResult(replacementInstructions, consumedSources, suspiciousSources);
        }

        private static Set<UntrustedSource> immutableEnumSet(Set<UntrustedSource> sources) {
            EnumSet<UntrustedSource> copy = EnumSet.noneOf(UntrustedSource.class);
            copy.addAll(Objects.requireNonNull(sources, "sources"));
            return Collections.unmodifiableSet(copy);
        }
    }
}
