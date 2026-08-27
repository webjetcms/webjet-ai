package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Initializes the pinned DJL tokenizer and ONNX Runtime implementations. */
final class NativeEmbeddingRuntimeFactory {
    private static final Object OFFLINE_LOCK = new Object();

    private NativeEmbeddingRuntimeFactory() { }

    static Resources create(
        EmbeddingBundleValidator.PreparedBundle bundle,
        Integer intraOpThreads
    ) throws Exception {
        requireDjlOffline();
        Path directory = bundle.directory();
        EmbeddingBundleManifest manifest = bundle.manifest();

        HuggingFaceTokenizer tokenizer = null;
        OrtSession session = null;
        Throwable failure = null;
        try {
            tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(directory.resolve("tokenizer.json"))
                .optTokenizerConfigPath(directory.resolve("tokenizer_config.json").toString())
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optPadding(true)
                .optMaxLength(manifest.maximumLength())
                .build();

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            environment.setTelemetry(false);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (intraOpThreads != null) options.setIntraOpNumThreads(intraOpThreads);
                session = environment.createSession(
                    directory.resolve("model.onnx").toAbsolutePath().toString(),
                    options
                );
            }

            NativeEmbeddingInferenceSession inference = new NativeEmbeddingInferenceSession(
                environment,
                session,
                manifest.inputNames(),
                manifest.outputNames(),
                manifest.dimensions()
            );
            session = null;
            NativeEmbeddingTokenizer localTokenizer = new NativeEmbeddingTokenizer(tokenizer, manifest.maximumLength());
            tokenizer = null;
            return new Resources(localTokenizer, inference);
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            Exception cleanupFailure = null;
            if (session != null) {
                try {
                    session.close();
                } catch (Exception exception) {
                    cleanupFailure = exception;
                }
            }
            if (tokenizer != null) {
                try {
                    tokenizer.close();
                } catch (Exception exception) {
                    if (cleanupFailure == null) cleanupFailure = exception;
                    else cleanupFailure.addSuppressed(exception);
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    static void requireDjlOffline() throws IOException {
        synchronized (OFFLINE_LOCK) {
            try {
                System.setProperty("ai.djl.offline", "true");
            } catch (SecurityException exception) {
                throw new IOException("Could not enforce DJL offline mode", exception);
            }
            if ("true".equalsIgnoreCase(System.getProperty("ai.djl.offline")) == false) {
                throw new IOException("Could not enforce DJL offline mode");
            }
        }
    }

    record Resources(NativeEmbeddingTokenizer tokenizer, NativeEmbeddingInferenceSession inference) { }
}
