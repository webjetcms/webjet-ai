package com.webjetcms.ai;

import java.util.Arrays;
import java.util.List;

import com.webjetcms.ai.security.PromptInjectionDefense;
import com.webjetcms.ai.security.PromptInjectionDefense.UntrustedSource;

/** Removes echoed internal input blocks before chat text reaches the caller. */
final class ChatResponseText {
    private static final List<String> BEGIN_MARKERS = Arrays.stream(UntrustedSource.values())
        .map(PromptInjectionDefense::getUntrustedBeginMarker).toList();
    private static final List<String> END_MARKERS = Arrays.stream(UntrustedSource.values())
        .map(PromptInjectionDefense::getUntrustedEndMarker).toList();

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder leadingWhitespace = new StringBuilder();
    private String endMarker;
    private boolean removedPrefix;
    private boolean emitted;

    /** Filters a delta, retaining possible marker fragments and undecided leading whitespace. */
    public String append(String delta) {
        if (delta == null || delta.isEmpty()) return "";
        pending.append(delta);
        StringBuilder output = new StringBuilder();
        while (!pending.isEmpty()) {
            List<String> markers = endMarker == null ? BEGIN_MARKERS : List.of(endMarker);
            int position = -1;
            int markerIndex = -1;
            for (int i = 0; i < markers.size(); i++) {
                int found = pending.indexOf(markers.get(i));
                if (found >= 0 && (position < 0 || found < position)) {
                    position = found;
                    markerIndex = i;
                }
            }
            if (position >= 0) {
                if (endMarker == null) {
                    output.append(pending, 0, position);
                    endMarker = END_MARKERS.get(markerIndex);
                    if (!emitted && output.toString().isBlank()) {
                        output.setLength(0);
                        leadingWhitespace.setLength(0);
                        removedPrefix = true;
                    }
                } else {
                    endMarker = null;
                }
                pending.delete(0, position + markers.get(markerIndex).length());
                continue;
            }
            int retained = 0;
            for (String marker : markers) {
                for (int length = Math.min(pending.length(), marker.length() - 1); length > retained; length--) {
                    if (pending.substring(pending.length() - length).equals(marker.substring(0, length))) {
                        retained = length;
                        break;
                    }
                }
            }
            int available = pending.length() - retained;
            if (endMarker == null) output.append(pending, 0, available);
            pending.delete(0, available);
            break;
        }
        return visible(output.toString());
    }

    /** Flushes ordinary trailing text; an unfinished internal block remains hidden. */
    public String finish() {
        String tail = endMarker == null ? pending.toString() : "";
        pending.setLength(0);
        String text = visible(tail);
        if (!emitted && !removedPrefix) {
            text = leadingWhitespace.toString();
            leadingWhitespace.setLength(0);
        }
        return text;
    }

    private String visible(String text) {
        if (!emitted) {
            int start = 0;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                char character = text.charAt(start++);
                if (removedPrefix && (character == '\n' || character == '\r')) leadingWhitespace.setLength(0);
                else leadingWhitespace.append(character);
            }
            if (start == text.length()) return "";
            text = leadingWhitespace + text.substring(start);
            leadingWhitespace.setLength(0);
        }
        if (!text.isEmpty()) emitted = true;
        return text;
    }

    /** Filters a complete provider response. */
    public static String clean(String text) {
        if (text == null) return null;
        ChatResponseText filter = new ChatResponseText();
        return filter.append(text) + filter.finish();
    }
}
