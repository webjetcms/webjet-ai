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

    static Resources create(VerifiedBundleExtractor.Result<EmbeddingBundleManifest> bundle,
        Integer intraOpThreads) throws Exception {
        requireDjlOffline();
        Path directory = bundle.directory();
        EmbeddingBundleManifest manifest = bundle.manifest();
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(directory.resolve("tokenizer.json"))
            .optTokenizerConfigPath(directory.resolve("tokenizer_config.json").toString())
            .optAddSpecialTokens(true)
            .optTruncation(true)
            .optPadding(true)
            .optMaxLength(manifest.model().maximumLength())
            .build();
        return LocalProviderLifecycle.transfer(tokenizer, nativeTokenizer -> {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            environment.setTelemetry(false);
            OrtSession session = null;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (intraOpThreads != null) options.setIntraOpNumThreads(intraOpThreads);
                session = environment.createSession(directory.resolve("model.onnx").toAbsolutePath().toString(), options);
            } catch (Exception | Error failure) {
                LocalProviderLifecycle.cleanupAfterFailure(null, failure, session);
                throw failure;
            }
            return LocalProviderLifecycle.transfer(session, nativeSession -> {
                NativeEmbeddingInferenceSession inference = new NativeEmbeddingInferenceSession(
                    environment, nativeSession, manifest.model().inputNames(),
                    manifest.model().outputNames(), manifest.model().dimensions());
                return new Resources(new NativeEmbeddingTokenizer(
                    nativeTokenizer, manifest.model().maximumLength()), inference);
            });
        });
    }

    static void requireDjlOffline() throws IOException {
        synchronized (OFFLINE_LOCK) {
            try {
                System.setProperty("ai.djl.offline", "true");
            } catch (SecurityException exception) {
                throw new IOException("Could not enforce DJL offline mode", exception);
            }
            if ("true".equalsIgnoreCase(System.getProperty("ai.djl.offline")) == false)
                throw new IOException("Could not enforce DJL offline mode");
        }
    }

    record Resources(NativeEmbeddingTokenizer tokenizer, NativeEmbeddingInferenceSession inference) { }
}
