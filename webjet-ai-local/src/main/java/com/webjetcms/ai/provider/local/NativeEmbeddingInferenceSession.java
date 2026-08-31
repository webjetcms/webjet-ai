package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.webjetcms.ai.AiProviderException;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Executes concurrent inference calls through one validated ONNX Runtime session. */
final class NativeEmbeddingInferenceSession implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final boolean tokenTypeIds;
    private final String outputName;
    private final int dimensions;

    NativeEmbeddingInferenceSession(OrtEnvironment environment, OrtSession session,
        List<String> inputNames, List<String> outputNames, int dimensions) throws IOException, OrtException {
        this.environment = environment;
        this.session = session;
        this.tokenTypeIds = inputNames.contains("token_type_ids");
        this.outputName = outputNames.get(0);
        this.dimensions = dimensions;
        validateModelContract(inputNames, outputNames);
    }
    public PoolingResult[] run(NativeEmbeddingTokenizer.Batch tokens)
        throws OrtException, IOException, AiProviderException {
        int batchSize = tokens.batchSize();
        int sequenceLength = tokens.sequenceLength();
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, tokens.inputIds());
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, tokens.attentionMask());
             OnnxTensor typesTensor = OnnxTensor.createTensor(environment, tokens.tokenTypeIds())) {
            Map<String, OnnxTensor> inputs = tokenTypeIds
                ? Map.of("input_ids", idsTensor, "attention_mask", maskTensor, "token_type_ids", typesTensor)
                : Map.of("input_ids", idsTensor, "attention_mask", maskTensor);
            try (OrtSession.Result result = session.run(inputs, Set.of(outputName))) {
                return readOutput(result, tokens);
            }
        }
    }
    @Override
    public void close() throws OrtException { session.close(); }
    private void validateModelContract(List<String> inputNames, List<String> outputNames)
        throws IOException, OrtException {
        boolean supportedInputs = inputNames.equals(List.of("input_ids", "attention_mask"))
            || inputNames.equals(List.of("input_ids", "attention_mask", "token_type_ids"));
        if (supportedInputs == false || outputNames.size() != 1
            || session.getInputNames().equals(Set.copyOf(inputNames)) == false
            || session.getOutputNames().equals(Set.copyOf(outputNames)) == false) {
            throw new IOException("ONNX model tensor names do not match the approved manifest");
        }
        Map<String, NodeInfo> inputs = session.getInputInfo();
        for (String name : inputNames) OnnxSupport.requireTensor(inputs, name, OnnxJavaType.INT64, 2);
        OnnxSupport.requireTensor(session.getOutputInfo(), outputName, OnnxJavaType.FLOAT, 3);
    }
    private PoolingResult[] readOutput(OrtSession.Result result, NativeEmbeddingTokenizer.Batch tokens)
        throws IOException, OrtException, AiProviderException {
        int batchSize = tokens.batchSize();
        int sequenceLength = tokens.sequenceLength();
        OnnxTensor tensor = OnnxSupport.tensor(result, outputName);
        long[] shape = OnnxSupport.shape(
            tensor, OnnxJavaType.FLOAT, 3, "Unexpected ONNX output shape or type");
        if (shape[0] != batchSize || shape[1] != sequenceLength || shape[2] != dimensions) {
            throw new IOException("Unexpected ONNX output shape or type");
        }
        FloatBuffer values = tensor.getFloatBuffer();
        int expected = Math.multiplyExact(Math.multiplyExact(batchSize, sequenceLength), dimensions);
        if (values.remaining() != expected) throw new IOException("Unexpected ONNX output value count");
        PoolingResult[] output = new PoolingResult[batchSize];
        for (int row = 0; row < batchSize; row++) {
            output[row] = poolAndNormalize(values, tokens.attentionMask()[row], sequenceLength);
        }
        return output;
    }
    private PoolingResult poolAndNormalize(FloatBuffer values, long[] attentionMask, int sequenceLength)
        throws AiProviderException {
        check(attentionMask.length == sequenceLength && sequenceLength > 0,
            "ONNX output sequence does not match the attention mask");
        double[] pooled = new double[dimensions];
        long processedTokens = 0;
        for (int token = 0; token < sequenceLength; token++) {
            long mask = attentionMask[token];
            for (int dimension = 0; dimension < dimensions; dimension++) {
                float value = values.get();
                check(Float.isFinite(value), "ONNX output contains a non-finite value");
                if (mask == 1) pooled[dimension] += value;
            }
            check(mask == 0 || mask == 1, "Attention mask must contain only zero or one");
            if (mask == 1) processedTokens++;
        }
        check(processedTokens > 0, "Attention mask does not contain a processed token");

        double squaredNorm = 0;
        for (int dimension = 0; dimension < dimensions; dimension++) {
            pooled[dimension] /= processedTokens;
            squaredNorm += pooled[dimension] * pooled[dimension];
        }
        double norm = Math.sqrt(squaredNorm);
        check(Double.isFinite(norm) && norm > 0, "Local embedding has an invalid L2 norm");

        float[] normalized = new float[dimensions];
        for (int dimension = 0; dimension < dimensions; dimension++) {
            normalized[dimension] = (float) (pooled[dimension] / norm);
            check(Float.isFinite(normalized[dimension]),
                "Local embedding contains a non-finite normalized value");
        }
        return new PoolingResult(normalized, processedTokens);
    }
    private static void check(boolean valid, String message) throws AiProviderException {
        if (valid == false) throw new AiProviderException(LocalEmbeddingModelProvider.PROVIDER_ID, message);
    }
    record PoolingResult(float[] vector, long processedTokens) { }
}

/** Common strict ONNX tensor lookup and type/rank validation. */
final class OnnxSupport {
    private OnnxSupport() { }
    static OnnxTensor tensor(OrtSession.Result result, String name) throws IOException {
        if (result == null) throw new IOException("Missing decoder cache result for " + name);
        OnnxValue value = result.get(name)
            .orElseThrow(() -> new IOException("ONNX output is missing: " + name));
        if (value instanceof OnnxTensor) return (OnnxTensor) value;
        throw new IOException("ONNX output is not a tensor: " + name);
    }
    static long[] shape(OnnxTensor tensor, OnnxJavaType type, int rank, String message)
        throws IOException {
        TensorInfo info = (TensorInfo) tensor.getInfo();
        if (info.type != type || info.getShape().length != rank) throw new IOException(message);
        return info.getShape();
    }
    static void requireTensor(Map<String, NodeInfo> nodes, String name,
        OnnxJavaType type, int rank) throws IOException {
        NodeInfo node = nodes.get(name);
        if (node == null || (node.getInfo() instanceof TensorInfo) == false)
            throw new IOException("Unexpected ONNX tensor contract for " + name);
        TensorInfo info = (TensorInfo) node.getInfo();
        if (info.type != type || info.getShape().length != rank)
            throw new IOException("Unexpected ONNX tensor contract for " + name);
    }
}
