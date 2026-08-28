package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.Arrays;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

/** Serializes FLAN text encoding and generated-token decoding through one native tokenizer. */
final class NativeGenerationTokenizer implements AutoCloseable {
    private final HuggingFaceTokenizer tokenizer;
    private final int maximumLength, vocabularySize;
    NativeGenerationTokenizer(HuggingFaceTokenizer tokenizer, int maximumLength, int vocabularySize) {
        this.tokenizer = tokenizer;
        this.maximumLength = maximumLength; this.vocabularySize = vocabularySize;
    }
    synchronized NativeTokenizerBatch encode(String input) throws IOException {
        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = encoding.getIds(), attentionMask = encoding.getAttentionMask();
        if (inputIds.length < 1 || inputIds.length > maximumLength
            || inputIds.length != attentionMask.length)
            throw new IOException("Tokenizer returned an invalid generation input");
        validateTokenIds(inputIds);
        if (Arrays.stream(attentionMask).anyMatch(value -> value != 0 && value != 1))
            throw new IOException("Tokenizer returned a non-binary attention mask");
        return new NativeTokenizerBatch(inputIds, attentionMask);
    }
    synchronized String decode(long[] tokenIds) throws IOException {
        validateTokenIds(tokenIds);
        return tokenizer.decode(tokenIds, true).trim();
    }
    @Override public synchronized void close() { tokenizer.close(); }
    private void validateTokenIds(long[] tokenIds) throws IOException {
        if (Arrays.stream(tokenIds).anyMatch(token -> token < 0 || token >= vocabularySize))
            throw new IOException("Tokenizer returned an out-of-range token ID");
    }
}

/** Defensive token IDs and matching encoder attention mask shared by local tokenizers. */
record NativeTokenizerBatch(long[] inputIds, long[] attentionMask) {
    NativeTokenizerBatch {
        inputIds = inputIds.clone(); attentionMask = attentionMask.clone();
        if (inputIds.length == 0 || inputIds.length != attentionMask.length)
            throw new IllegalArgumentException("Token IDs and attention mask must have equal non-zero length");
    }
    @Override public long[] inputIds() { return inputIds.clone(); }
    @Override public long[] attentionMask() { return attentionMask.clone(); }
}
