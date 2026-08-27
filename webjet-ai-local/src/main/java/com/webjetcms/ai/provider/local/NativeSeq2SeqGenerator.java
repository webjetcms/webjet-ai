package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Runs reusable greedy encoder-decoder generation with merged-decoder KV caching. */
final class NativeSeq2SeqGenerator implements AutoCloseable {
    private static final String ENCODER_INPUT_IDS = "input_ids";
    private static final String ENCODER_ATTENTION_MASK = "attention_mask";
    private static final String ENCODER_OUTPUT = "last_hidden_state";
    private static final String DECODER_INPUT_IDS = "input_ids";
    private static final String DECODER_ATTENTION_MASK = "encoder_attention_mask";
    private static final String DECODER_HIDDEN_STATE = "encoder_hidden_states";
    private static final String USE_CACHE_BRANCH = "use_cache_branch";
    private static final String LOGITS = "logits";

    private final OrtEnvironment environment;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final int decoderStartTokenId;
    private final int eosTokenId;
    private final int vocabularySize;
    private final int layers;
    private final int attentionHeads;
    private final int attentionHeadSize;

    NativeSeq2SeqGenerator(
        OrtEnvironment environment,
        OrtSession encoder,
        OrtSession decoder,
        TranslationBundleManifest manifest
    ) throws IOException, OrtException {
        this.environment = environment;
        this.encoder = encoder;
        this.decoder = decoder;
        this.decoderStartTokenId = manifest.model().decoderStartTokenId();
        this.eosTokenId = manifest.model().eosTokenId();
        this.vocabularySize = manifest.model().vocabularySize();
        this.layers = manifest.model().decoderLayers();
        this.attentionHeads = manifest.model().attentionHeads();
        this.attentionHeadSize = manifest.model().attentionHeadSize();
        validateModelContract();
    }

    public long[] generate(
        NativeTranslationTokenizer.Batch source,
        long targetLanguageTokenId,
        int maximumOutputTokens
    ) throws OrtException, IOException {
        long[] sourceIds = source.inputIds();
        long[] sourceMask = source.attentionMask();
        try (OnnxTensor ids = longTensor(sourceIds, 1, sourceIds.length);
             OnnxTensor mask = longTensor(sourceMask, 1, sourceMask.length);
             OrtSession.Result encoderResult = encoder.run(Map.of(
                 ENCODER_INPUT_IDS, ids,
                 ENCODER_ATTENTION_MASK, mask
             ))) {
            OnnxTensor hiddenState = tensor(encoderResult, ENCODER_OUTPUT);
            validateEncoderOutput(hiddenState, sourceIds.length);
            return generateFromHiddenState(
                hiddenState,
                sourceMask,
                targetLanguageTokenId,
                maximumOutputTokens
            );
        }
    }

    private long[] generateFromHiddenState(
        OnnxTensor hiddenState,
        long[] sourceMask,
        long targetLanguageTokenId,
        int maximumOutputTokens
    ) throws OrtException, IOException {
        List<Long> generated = new ArrayList<>();
        OrtSession.Result initial = null;
        OrtSession.Result previous = null;
        Throwable failure = null;
        try {
            long[] decoderInput = {decoderStartTokenId, targetLanguageTokenId};
            for (int index = 0; index < maximumOutputTokens; index++) {
                boolean useCache = previous != null;
                List<OnnxTensor> ownedInputs = new ArrayList<>();
                OrtSession.Result result;
                try {
                    Map<String, OnnxTensorLike> inputs = decoderInputs(
                        decoderInput,
                        sourceMask,
                        hiddenState,
                        useCache,
                        initial,
                        previous,
                        ownedInputs
                    );
                    result = decoder.run(inputs);
                } finally {
                    closeTensors(ownedInputs);
                }

                if (initial == null) initial = result;
                if (previous != null && previous != initial) previous.close();
                previous = result;
                long nextToken = argmax(tensor(result, LOGITS));
                if (nextToken == eosTokenId) break;
                generated.add(nextToken);
                decoderInput = new long[]{nextToken};
            }
        } catch (OrtException | IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                if (previous != null && previous != initial) previous.close();
            } catch (RuntimeException exception) {
                if (failure != null) failure.addSuppressed(exception);
                else throw exception;
            } finally {
                if (initial != null) initial.close();
            }
        }
        long[] result = new long[generated.size()];
        for (int index = 0; index < generated.size(); index++) result[index] = generated.get(index);
        return result;
    }

    private Map<String, OnnxTensorLike> decoderInputs(
        long[] decoderInput,
        long[] sourceMask,
        OnnxTensor hiddenState,
        boolean useCache,
        OrtSession.Result initial,
        OrtSession.Result previous,
        List<OnnxTensor> ownedInputs
    ) throws OrtException, IOException {
        Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>();
        putOwned(inputs, DECODER_INPUT_IDS, longTensor(decoderInput, 1, decoderInput.length), ownedInputs);
        putOwned(inputs, DECODER_ATTENTION_MASK, longTensor(sourceMask, 1, sourceMask.length), ownedInputs);
        inputs.put(DECODER_HIDDEN_STATE, hiddenState);
        putOwned(
            inputs,
            USE_CACHE_BRANCH,
            OnnxTensor.createTensor(environment, new boolean[]{useCache}),
            ownedInputs
        );
        for (int layer = 0; layer < layers; layer++) {
            for (String scope : List.of("decoder", "encoder")) {
                for (String kind : List.of("key", "value")) {
                    String inputName = pastName(layer, scope, kind);
                    if (useCache == false) {
                        OnnxTensor empty = OnnxTensor.createTensor(
                            environment,
                            FloatBuffer.allocate(0),
                            new long[]{1, attentionHeads, 0, attentionHeadSize}
                        );
                        putOwned(inputs, inputName, empty, ownedInputs);
                    } else {
                        OrtSession.Result cache = "encoder".equals(scope) ? initial : previous;
                        inputs.put(inputName, tensor(cache, presentName(layer, scope, kind)));
                    }
                }
            }
        }
        return inputs;
    }

    private long argmax(OnnxTensor logits) throws IOException {
        TensorInfo info = (TensorInfo) logits.getInfo();
        long[] shape = info.getShape();
        if (info.type != OnnxJavaType.FLOAT || shape.length != 3 || shape[0] != 1
            || shape[1] < 1 || shape[2] != vocabularySize) {
            throw new IOException("Unexpected decoder logits shape or type");
        }
        int sequenceLength = Math.toIntExact(shape[1]);
        FloatBuffer values = logits.getFloatBuffer();
        int offset = Math.multiplyExact(sequenceLength - 1, vocabularySize);
        float bestValue = Float.NEGATIVE_INFINITY;
        int bestToken = -1;
        for (int token = 0; token < vocabularySize; token++) {
            float value = values.get(offset + token);
            if (Float.isFinite(value) == false) throw new IOException("Decoder logits contain a non-finite value");
            if (bestToken < 0 || value > bestValue) {
                bestValue = value;
                bestToken = token;
            }
        }
        if (bestToken < 0) throw new IOException("Decoder did not produce a token");
        return bestToken;
    }

    private void validateModelContract() throws IOException, OrtException {
        Set<String> encoderInputs = Set.of(ENCODER_INPUT_IDS, ENCODER_ATTENTION_MASK);
        if (encoder.getInputNames().equals(encoderInputs) == false
            || encoder.getOutputNames().equals(Set.of(ENCODER_OUTPUT)) == false) {
            throw new IOException("Encoder tensor names do not match the approved translation model contract");
        }
        requireTensor(encoder.getInputInfo().get(ENCODER_INPUT_IDS), OnnxJavaType.INT64, 2, ENCODER_INPUT_IDS);
        requireTensor(encoder.getInputInfo().get(ENCODER_ATTENTION_MASK), OnnxJavaType.INT64, 2, ENCODER_ATTENTION_MASK);
        requireTensor(encoder.getOutputInfo().get(ENCODER_OUTPUT), OnnxJavaType.FLOAT, 3, ENCODER_OUTPUT);

        Set<String> decoderInputs = new LinkedHashSet<>(Set.of(
            DECODER_INPUT_IDS, DECODER_ATTENTION_MASK, DECODER_HIDDEN_STATE, USE_CACHE_BRANCH
        ));
        Set<String> decoderOutputs = new LinkedHashSet<>(Set.of(LOGITS));
        for (int layer = 0; layer < layers; layer++) {
            for (String scope : List.of("decoder", "encoder")) {
                for (String kind : List.of("key", "value")) {
                    decoderInputs.add(pastName(layer, scope, kind));
                    decoderOutputs.add(presentName(layer, scope, kind));
                }
            }
        }
        if (decoder.getInputNames().equals(decoderInputs) == false
            || decoder.getOutputNames().equals(decoderOutputs) == false) {
            throw new IOException("Decoder tensor names do not match the approved translation model contract");
        }
        Map<String, NodeInfo> inputInfo = decoder.getInputInfo();
        requireTensor(inputInfo.get(DECODER_INPUT_IDS), OnnxJavaType.INT64, 2, DECODER_INPUT_IDS);
        requireTensor(inputInfo.get(DECODER_ATTENTION_MASK), OnnxJavaType.INT64, 2, DECODER_ATTENTION_MASK);
        requireTensor(inputInfo.get(DECODER_HIDDEN_STATE), OnnxJavaType.FLOAT, 3, DECODER_HIDDEN_STATE);
        requireTensor(inputInfo.get(USE_CACHE_BRANCH), OnnxJavaType.BOOL, 1, USE_CACHE_BRANCH);
        for (String input : decoderInputs) {
            if (input.startsWith("past_key_values.")) {
                requireTensor(inputInfo.get(input), OnnxJavaType.FLOAT, 4, input);
            }
        }
        Map<String, NodeInfo> outputInfo = decoder.getOutputInfo();
        requireTensor(outputInfo.get(LOGITS), OnnxJavaType.FLOAT, 3, LOGITS);
        for (String output : decoderOutputs) {
            if (output.startsWith("present.")) {
                requireTensor(outputInfo.get(output), OnnxJavaType.FLOAT, 4, output);
            }
        }
    }

    private void validateEncoderOutput(OnnxTensor hiddenState, int sequenceLength) throws IOException {
        TensorInfo info = (TensorInfo) hiddenState.getInfo();
        long[] shape = info.getShape();
        int hiddenSize = Math.multiplyExact(attentionHeads, attentionHeadSize);
        if (info.type != OnnxJavaType.FLOAT || shape.length != 3 || shape[0] != 1
            || shape[1] != sequenceLength || shape[2] != hiddenSize) {
            throw new IOException("Unexpected encoder hidden-state shape or type");
        }
    }

    @Override
    public void close() throws OrtException {
        Throwable failure = null;
        try {
            decoder.close();
        } catch (Throwable exception) {
            failure = exception;
        }
        try {
            encoder.close();
        } catch (Throwable exception) {
            if (failure == null) failure = exception;
            else if (failure != exception) failure.addSuppressed(exception);
        }
        if (failure instanceof OrtException exception) throw exception;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private OnnxTensor longTensor(long[] values, long rows, long columns) throws OrtException {
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(values), new long[]{rows, columns});
    }

    private static void putOwned(
        Map<String, OnnxTensorLike> inputs,
        String name,
        OnnxTensor tensor,
        List<OnnxTensor> ownedInputs
    ) {
        inputs.put(name, tensor);
        ownedInputs.add(tensor);
    }

    private static void closeTensors(List<OnnxTensor> tensors) {
        for (OnnxTensor tensor : tensors) tensor.close();
    }

    private static OnnxTensor tensor(OrtSession.Result result, String name) throws IOException {
        if (result == null) throw new IOException("Missing decoder cache result for " + name);
        OnnxValue value = result.get(name).orElseThrow(() -> new IOException("ONNX output is missing: " + name));
        if (value instanceof OnnxTensor tensor) return tensor;
        throw new IOException("ONNX output is not a tensor: " + name);
    }

    private static void requireTensor(NodeInfo node, OnnxJavaType type, int rank, String name)
        throws IOException {
        if (node == null || (node.getInfo() instanceof TensorInfo) == false) {
            throw new IOException("Unexpected ONNX tensor contract for " + name);
        }
        TensorInfo info = (TensorInfo) node.getInfo();
        if (info.type != type || info.getShape().length != rank) {
            throw new IOException("Unexpected ONNX tensor contract for " + name);
        }
    }

    private static String pastName(int layer, String scope, String kind) {
        return "past_key_values." + layer + "." + scope + "." + kind;
    }

    private static String presentName(int layer, String scope, String kind) {
        return "present." + layer + "." + scope + "." + kind;
    }
}
