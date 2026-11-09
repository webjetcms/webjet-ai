package com.webjetcms.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies that internal input blocks cannot leak through split chat response chunks. */
class ChatResponseTextTest {
    private static final String ECHO = "[BEGIN_UNTRUSTED_INPUT_TEXT]\nAké je hlavné mesto Slovenska?\n[END_UNTRUSTED_INPUT_TEXT]";
    private static final String ANSWER = "Hlavným mestom Slovenska je Bratislava.";

    /** Both input sources are removed while the generated answer remains intact. */
    @Test
    void removesEchoedBlocksFromCompleteResponses() {
        assertCleanAcrossChunkBoundaries(ECHO + "\n\n" + ANSWER, ANSWER);
        assertEquals(ANSWER, ChatResponseText.clean(
            "[BEGIN_UNTRUSTED_USER_PROMPT]Question[END_UNTRUSTED_USER_PROMPT]\n" + ECHO + "\n" + ANSWER));
        assertEquals("Before\n\nAfter", ChatResponseText.clean("Before\n" + ECHO + "\nAfter"));
        assertEquals("    indented code\n", ChatResponseText.clean(ECHO + "\n\n    indented code\n"));
    }

    /** Echo cleanup preserves the same whitespace regardless of chunk boundaries. */
    @Test
    void preservesWhitespaceAcrossChunkBoundaries() {
        assertCleanAcrossChunkBoundaries("    " + ECHO + ANSWER, ANSWER);
        assertCleanAcrossChunkBoundaries("\n" + ECHO + "\n\n" + ANSWER, ANSWER);
        assertCleanAcrossChunkBoundaries(ECHO + "  " + ECHO + ANSWER, ANSWER);
        assertCleanAcrossChunkBoundaries("\r\n\t " + ECHO + "\r\n    indented code\n", "    indented code\n");
    }

    /** A cancelled or truncated echoed block never exposes its enclosed input. */
    @Test
    void hidesUnfinishedBlocks() {
        assertEquals("", ChatResponseText.clean("[BEGIN_UNTRUSTED_INPUT_TEXT]\nPrivate source"));
        assertEquals("Answer\n", ChatResponseText.clean("Answer\n[BEGIN_UNTRUSTED_USER_PROMPT]Question[END_"));
    }

    /** Ordinary Markdown, code indentation and bracket fragments are preserved. */
    @Test
    void preservesResponsesWithoutInternalBlocks() {
        for (String text : new String[] {"", ANSWER, " \t\r\n  ", "  indented code\n", "[link](https://example.test)", "array[", "    [BEGIN"}) {
            assertCleanAcrossChunkBoundaries(text, text);
        }
        assertNull(ChatResponseText.clean(null));
    }

    private static void assertCleanAcrossChunkBoundaries(String response, String expected) {
        assertEquals(expected, ChatResponseText.clean(response));
        for (int split = 0; split <= response.length(); split++) {
            ChatResponseText filter = new ChatResponseText();
            String first = filter.append(response.substring(0, split));
            String second = filter.append(response.substring(split));
            assertEquals(expected, first + second + filter.finish(), "Split at " + split);
        }
        ChatResponseText filter = new ChatResponseText();
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < response.length(); i++) {
            visible.append(filter.append(response.substring(i, i + 1)));
            assertTrue(expected.startsWith(visible.toString()), "Character chunk at " + i);
        }
        assertEquals(expected, visible + filter.finish());
    }
}
