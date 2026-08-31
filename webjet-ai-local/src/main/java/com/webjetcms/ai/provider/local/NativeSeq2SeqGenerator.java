package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.ModelDefinition;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxTensorLike;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** Runs reusable greedy encoder-decoder generation with merged-decoder KV caching. */
final class NativeSeq2SeqGenerator implements AutoCloseable {
    private static final String INPUT_IDS = "input_ids", ENCODER_ATTENTION_MASK = "attention_mask";
    private static final String ENCODER_OUTPUT = "last_hidden_state", LOGITS = "logits";
    private static final String DECODER_ATTENTION_MASK = "encoder_attention_mask";
    private static final String DECODER_HIDDEN_STATE = "encoder_hidden_states", USE_CACHE_BRANCH = "use_cache_branch";
    private static final String[] CACHE_COMPONENTS = {"decoder.key", "decoder.value", "encoder.key", "encoder.value"};
    private final OrtEnvironment environment;
    private final OrtSession encoder, decoder;
    private final ModelDefinition model;
    NativeSeq2SeqGenerator(OrtEnvironment environment, OrtSession encoder,
        OrtSession decoder, ModelDefinition model) throws IOException, OrtException {
        this.environment = environment; this.encoder = encoder; this.decoder = decoder; this.model = model;
        validateModelContract();
    }
    static NativeSeq2SeqGenerator open(Path directory, ModelDefinition model, Integer intraOpThreads) throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        OrtSession encoder = null, decoder = null;
        try {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (intraOpThreads != null) options.setIntraOpNumThreads(intraOpThreads);
                encoder = environment.createSession(
                    directory.resolve(model.encoderFile()).toAbsolutePath().toString(), options);
                decoder = environment.createSession(
                    directory.resolve(model.decoderFile()).toAbsolutePath().toString(), options);
            }
            NativeSeq2SeqGenerator generator = new NativeSeq2SeqGenerator(environment, encoder, decoder, model);
            encoder = decoder = null;
            return generator;
        } catch (Exception | Error failure) {
            LocalProviderLifecycle.cleanupAfterFailure(null, failure, decoder, encoder);
            throw failure;
        }
    }

    long[] generate(long[] sourceIds, long[] sourceMask, long[] initialDecoderInput,
        int maximumOutputTokens) throws OrtException, IOException {
        if (sourceIds == null || sourceMask == null || sourceIds.length == 0
            || sourceIds.length != sourceMask.length)
            throw new IOException("Encoder input IDs and attention mask must have the same non-zero length");
        if (initialDecoderInput == null || initialDecoderInput.length == 0)
            throw new IOException("Initial decoder input must not be empty");
        if (initialDecoderInput[0] != model.decoderStartTokenId())
            throw new IOException("Initial decoder input must start with the approved decoder-start token");
        if (maximumOutputTokens < 1) throw new IOException("Maximum output tokens must be positive");
        try (OnnxTensor ids = longTensor(sourceIds);
             OnnxTensor mask = longTensor(sourceMask);
             OrtSession.Result encoderResult = encoder.run(Map.of(
                 INPUT_IDS, ids, ENCODER_ATTENTION_MASK, mask))) {
            OnnxTensor hiddenState = OnnxSupport.tensor(encoderResult, ENCODER_OUTPUT);
            validateEncoderOutput(hiddenState, sourceIds.length);
            return generateFromHiddenState(hiddenState, sourceMask, initialDecoderInput, maximumOutputTokens);
        }
    }
    private long[] generateFromHiddenState(OnnxTensor hiddenState, long[] sourceMask,
        long[] initialDecoderInput, int maximumOutputTokens) throws OrtException, IOException {
        long[] generated = new long[maximumOutputTokens]; int generatedCount = 0;
        OrtSession.Result initial = null, previous = null;
        Throwable failure = null;
        try (OnnxTensor encoderMask = longTensor(sourceMask)) {
            long[] decoderInput = initialDecoderInput.clone();
            for (int index = 0; index < maximumOutputTokens; index++) {
                boolean useCache = previous != null;
                OrtSession.Result result;
                try (OnnxTensor inputIds = longTensor(decoderInput);
                     OnnxTensor branch = OnnxTensor.createTensor(environment, new boolean[]{useCache});
                     OnnxTensor emptyCache = useCache ? null : emptyCache()) {
                    result = decoder.run(decoderInputs(inputIds, encoderMask, hiddenState,
                        branch, emptyCache, initial, previous));
                }
                if (initial == null) initial = result;
                if (previous != null && previous != initial) previous.close();
                previous = result;
                long nextToken = argmax(OnnxSupport.tensor(result, LOGITS));
                if (nextToken == model.eosTokenId()) break;
                generated[generatedCount++] = nextToken;
                decoderInput = new long[]{nextToken};
            }
        } catch (OrtException | IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally { closeResults(initial, previous, failure); }
        return Arrays.copyOf(generated, generatedCount);
    }
    private Map<String, OnnxTensorLike> decoderInputs(OnnxTensor inputIds, OnnxTensor attentionMask,
        OnnxTensor hiddenState, OnnxTensor branch, OnnxTensor emptyCache,
        OrtSession.Result initial, OrtSession.Result previous) throws IOException {
        Map<String, OnnxTensorLike> inputs = new LinkedHashMap<>(Map.of(
            INPUT_IDS, inputIds, DECODER_ATTENTION_MASK, attentionMask,
            DECODER_HIDDEN_STATE, hiddenState, USE_CACHE_BRANCH, branch));
        for (int layer = 0; layer < model.decoderLayers(); layer++) {
            for (String component : CACHE_COMPONENTS) {
                OnnxTensorLike value = emptyCache != null ? emptyCache
                    : OnnxSupport.tensor(component.startsWith("encoder.") ? initial : previous,
                        cacheName("present", layer, component));
                inputs.put(cacheName("past_key_values", layer, component), value);
            }
        }
        return inputs;
    }
    private long argmax(OnnxTensor logits) throws IOException {
        long[] shape = OnnxSupport.shape(logits, OnnxJavaType.FLOAT, 3,
            "Unexpected decoder logits shape or type");
        if (shape[0] != 1 || shape[1] < 1 || shape[2] != model.vocabularySize())
            throw new IOException("Unexpected decoder logits shape or type");
        FloatBuffer values = logits.getFloatBuffer();
        int offset = Math.multiplyExact(Math.toIntExact(shape[1]) - 1, model.vocabularySize());
        float bestValue = Float.NEGATIVE_INFINITY; int bestToken = -1;
        for (int token = 0; token < model.vocabularySize(); token++) {
            float value = values.get(offset + token);
            if (Float.isFinite(value) == false) throw new IOException("Decoder logits contain a non-finite value");
            if (bestToken < 0 || value > bestValue) {
                bestValue = value;
                bestToken = token;
            }
        }
        return bestToken;
    }

    private void validateModelContract() throws IOException, OrtException {
        validateSession(encoder, "Encoder",
            new String[]{INPUT_IDS, ENCODER_ATTENTION_MASK}, new String[]{ENCODER_OUTPUT}, false);
        validateSession(decoder, "Decoder",
            new String[]{INPUT_IDS, DECODER_ATTENTION_MASK, DECODER_HIDDEN_STATE, USE_CACHE_BRANCH},
            new String[]{LOGITS}, true);
    }
    private void validateEncoderOutput(OnnxTensor hiddenState, int sequenceLength) throws IOException {
        long[] shape = OnnxSupport.shape(hiddenState, OnnxJavaType.FLOAT, 3,
            "Unexpected encoder hidden-state shape or type");
        if (shape[0] != 1 || shape[1] != sequenceLength || shape[2] != model.hiddenSize())
            throw new IOException("Unexpected encoder hidden-state shape or type");
    }
    @Override public void close() throws Exception { LocalProviderLifecycle.closeResources(decoder, encoder); }
    private OnnxTensor longTensor(long[] values) throws OrtException { return OnnxTensor.createTensor(
        environment, LongBuffer.wrap(values), new long[]{1, values.length}); }
    private OnnxTensor emptyCache() throws OrtException {
        return OnnxTensor.createTensor(environment, FloatBuffer.allocate(0),
            new long[]{1, model.attentionHeads(), 0, model.attentionHeadSize()});
    }
    private static String cacheName(String prefix, int layer, String component) { return prefix + "." + layer + "." + component; }
    private static void closeResults(OrtSession.Result initial, OrtSession.Result previous, Throwable failure) {
        RuntimeException closeFailure = null;
        for (OrtSession.Result result : new OrtSession.Result[]{previous != initial ? previous : null, initial}) {
            try { if (result != null) result.close(); }
            catch (RuntimeException exception) {
                if (failure == null) {
                    if (closeFailure == null) closeFailure = exception;
                    else if (closeFailure != exception) closeFailure.addSuppressed(exception);
                } else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (closeFailure != null) throw closeFailure;
    }
    private void validateSession(OrtSession session, String name, String[] inputs,
        String[] outputs, boolean cached) throws IOException, OrtException {
        int cacheNames = cached ? Math.multiplyExact(model.decoderLayers(), CACHE_COMPONENTS.length) : 0;
        if (session.getInputNames().size() != inputs.length + cacheNames
            || session.getOutputNames().size() != outputs.length + cacheNames)
            throw new IOException(name + " tensor names do not match the approved sequence-to-sequence model contract");
        requireTensors(session.getInputInfo(), inputs);
        requireTensors(session.getOutputInfo(), outputs);
        if (cached == false) return;
        for (int layer = 0; layer < model.decoderLayers(); layer++) {
            for (String component : CACHE_COMPONENTS) {
                requireTensors(session.getInputInfo(), cacheName("past_key_values", layer, component));
                requireTensors(session.getOutputInfo(), cacheName("present", layer, component));
            }
        }
    }
    private static void requireTensors(Map<String, NodeInfo> nodes, String... expected) throws IOException {
        for (String name : expected) {
            OnnxJavaType type = USE_CACHE_BRANCH.equals(name) ? OnnxJavaType.BOOL
                : INPUT_IDS.equals(name) || name.endsWith("attention_mask")
                    ? OnnxJavaType.INT64 : OnnxJavaType.FLOAT;
            int rank = type == OnnxJavaType.BOOL ? 1 : type == OnnxJavaType.INT64 ? 2
                : name.startsWith("past_") || name.startsWith("present.") ? 4 : 3;
            OnnxSupport.requireTensor(nodes, name, type, rank);
        }
    }
}
