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
        require(encodings.length == inputs.size() && encodings.length > 0,
            "Tokenizer returned an unexpected batch size");
        int sequenceLength = encodings[0].getIds().length;
        require(sequenceLength >= 1 && sequenceLength <= maximumLength,
            "Tokenizer returned an invalid sequence length: " + sequenceLength);

        Batch batch = new Batch(
            new long[encodings.length][], new long[encodings.length][], new long[encodings.length][]);
        for (int row = 0; row < encodings.length; row++) {
            Encoding encoding = encodings[row];
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            long[] types = encoding.getTypeIds();
            require(ids.length == sequenceLength && mask.length == sequenceLength && types.length == sequenceLength,
                "Tokenizer did not pad the batch to a common sequence length");
            for (int column = 0; column < sequenceLength; column++) {
                require(mask[column] == 0 || mask[column] == 1,
                    "Tokenizer returned a non-binary attention mask");
                require(types[column] >= 0, "Tokenizer returned a negative token type ID");
            }
            batch.inputIds()[row] = ids.clone();
            batch.attentionMask()[row] = mask.clone();
            batch.tokenTypeIds()[row] = types.clone();
        }
        return batch;
    }

    @Override
    public synchronized void close() { tokenizer.close(); }

    private static void require(boolean valid, String message) throws IOException {
        if (valid == false) throw new IOException(message);
    }

    record Batch(long[][] inputIds, long[][] attentionMask, long[][] tokenTypeIds) {
        int batchSize() { return inputIds.length; }
        int sequenceLength() { return inputIds.length == 0 ? 0 : inputIds[0].length; }
    }
}
