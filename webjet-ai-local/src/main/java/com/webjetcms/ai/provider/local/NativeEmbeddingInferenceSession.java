package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final String inputIdsName;
    private final String attentionMaskName;
    private final String outputName;
    private final int dimensions;

    NativeEmbeddingInferenceSession(
        OrtEnvironment environment,
        OrtSession session,
        List<String> inputNames,
        List<String> outputNames,
        int dimensions
    ) throws IOException, OrtException {
        this.environment = environment;
        this.session = session;
        this.inputIdsName = inputNames.get(0);
        this.attentionMaskName = inputNames.get(1);
        this.outputName = outputNames.get(0);
        this.dimensions = dimensions;
        validateModelContract(inputNames, outputNames);
    }

    public float[][][] run(NativeEmbeddingTokenizer.Batch tokens) throws OrtException, IOException {
        int batchSize = tokens.batchSize();
        int sequenceLength = tokens.sequenceLength();
        long[] shape = {batchSize, sequenceLength};
        long[] ids = flatten(tokens.inputIds(), batchSize, sequenceLength);
        long[] mask = flatten(tokens.attentionMask(), batchSize, sequenceLength);

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(inputIdsName, idsTensor);
            inputs.put(attentionMaskName, maskTensor);
            try (OrtSession.Result result = session.run(inputs, Set.of(outputName))) {
                OnnxValue value = result.get(outputName)
                    .orElseThrow(() -> new IOException("ONNX output is missing: " + outputName));
                if ((value instanceof OnnxTensor) == false) {
                    throw new IOException("ONNX output is not a tensor: " + outputName);
                }
                OnnxTensor tensor = (OnnxTensor) value;
                TensorInfo info = (TensorInfo) tensor.getInfo();
                long[] outputShape = info.getShape();
                if (info.type != OnnxJavaType.FLOAT || outputShape.length != 3
                    || outputShape[0] != batchSize || outputShape[1] != sequenceLength
                    || outputShape[2] != dimensions) {
                    throw new IOException("Unexpected ONNX output shape or type");
                }
                FloatBuffer values = tensor.getFloatBuffer();
                int expectedValues = Math.multiplyExact(
                    Math.multiplyExact(batchSize, sequenceLength),
                    dimensions
                );
                if (values.remaining() != expectedValues) {
                    throw new IOException("Unexpected ONNX output value count");
                }
                float[][][] output = new float[batchSize][sequenceLength][dimensions];
                for (int batch = 0; batch < batchSize; batch++) {
                    for (int token = 0; token < sequenceLength; token++) {
                        values.get(output[batch][token]);
                    }
                }
                return output;
            }
        } catch (ArithmeticException exception) {
            throw new IOException("ONNX tensor size overflow", exception);
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    private void validateModelContract(List<String> inputNames, List<String> outputNames)
        throws IOException, OrtException {
        if (inputNames.size() != 2 || outputNames.size() != 1
            || session.getInputNames().equals(Set.copyOf(inputNames)) == false
            || session.getOutputNames().equals(Set.copyOf(outputNames)) == false) {
            throw new IOException("ONNX model tensor names do not match the approved manifest");
        }
        Map<String, NodeInfo> inputs = session.getInputInfo();
        requireTensor(inputs.get(inputIdsName), OnnxJavaType.INT64, 2, inputIdsName);
        requireTensor(inputs.get(attentionMaskName), OnnxJavaType.INT64, 2, attentionMaskName);
        requireTensor(session.getOutputInfo().get(outputName), OnnxJavaType.FLOAT, 3, outputName);
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

    private static long[] flatten(long[][] values, int rows, int columns) throws IOException {
        long[] flattened = new long[Math.multiplyExact(rows, columns)];
        int offset = 0;
        for (long[] row : values) {
            if (row.length != columns) throw new IOException("Ragged ONNX input tensor");
            System.arraycopy(row, 0, flattened, offset, columns);
            offset += columns;
        }
        return flattened;
    }
}
