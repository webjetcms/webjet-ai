package com.webjetcms.ai;

/**
 * Selects how {@link AiClient} prepares textual request fields before provider delegation.
 *
 * <p>Prompt-generating models normally use {@link #PROTECTED_PROMPT}. Deterministic text
 * processors such as translation models use {@link #LITERAL} so security boundary markers do
 * not become part of the transformed text.</p>
 */
public enum AiInputHandling {
    /** Applies prompt-injection boundaries and hardened system instructions. */
    PROTECTED_PROMPT,

    /** Delegates the immutable request without modifying its textual fields. */
    LITERAL
}
