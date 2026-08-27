package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.util.List;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

/** Serializes access to one native Hugging Face tokenizer instance. */
final class NativeEmbeddingTokenizer implements AutoCloseable {
    private final HuggingFaceTokenizer tokenizer;
    private final int maximumLength;

    NativeEmbeddingTokenizer(HuggingFaceTokenizer tokenizer, int maximumLength) {
        this.tokenizer = tokenizer;
        this.maximumLength = maximumLength;
    }

    public synchronized Batch encode(List<String> inputs) throws IOException {
        Encoding[] encodings = tokenizer.batchEncode(inputs);
        if (encodings.length != inputs.size() || encodings.length == 0) {
            throw new IOException("Tokenizer returned an unexpected batch size");
        }
        int sequenceLength = encodings[0].getIds().length;
        if (sequenceLength < 1 || sequenceLength > maximumLength) {
            throw new IOException("Tokenizer returned an invalid sequence length: " + sequenceLength);
        }

        long[][] inputIds = new long[encodings.length][sequenceLength];
        long[][] attentionMask = new long[encodings.length][sequenceLength];
        long[][] tokenTypeIds = new long[encodings.length][sequenceLength];
        for (int row = 0; row < encodings.length; row++) {
            long[] ids = encodings[row].getIds();
            long[] mask = encodings[row].getAttentionMask();
            long[] types = encodings[row].getTypeIds();
            if (ids.length != sequenceLength || mask.length != sequenceLength
                || types.length != sequenceLength) {
                throw new IOException("Tokenizer did not pad the batch to a common sequence length");
            }
            for (int column = 0; column < sequenceLength; column++) {
                if (mask[column] != 0 && mask[column] != 1) {
                    throw new IOException("Tokenizer returned a non-binary attention mask");
                }
                if (types[column] < 0) throw new IOException("Tokenizer returned a negative token type ID");
                inputIds[row][column] = ids[column];
                attentionMask[row][column] = mask[column];
                tokenTypeIds[row][column] = types[column];
            }
        }
        return new Batch(inputIds, attentionMask, tokenTypeIds);
    }

    @Override
    public synchronized void close() {
        tokenizer.close();
    }

    record Batch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {
        int batchSize() { return inputIds.length; }

        int sequenceLength() { return inputIds.length == 0 ? 0 : inputIds[0].length; }
    }
}
