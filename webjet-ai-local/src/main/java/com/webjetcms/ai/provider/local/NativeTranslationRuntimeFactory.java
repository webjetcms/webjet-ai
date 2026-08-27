package com.webjetcms.ai.provider.local;

import java.nio.file.Path;

import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** Initializes offline DJL tokenization and paired ONNX encoder-decoder sessions. */
final class NativeTranslationRuntimeFactory {
    private NativeTranslationRuntimeFactory() { }

    static Resources create(
        TranslationBundleValidator.PreparedBundle bundle,
        Integer intraOpThreads
    )
        throws Exception {
        NativeEmbeddingRuntimeFactory.requireDjlOffline();
        Path directory = bundle.directory();
        TranslationBundleManifest manifest = bundle.manifest();
        SpTokenizer tokenizer = null;
        NativeTranslationTokenizer localTokenizer = null;
        OrtSession encoder = null;
        OrtSession decoder = null;
        Throwable failure = null;
        try {
            tokenizer = new SpTokenizer(directory.resolve(manifest.model().tokenizerModelFile()));
            localTokenizer = new NativeTranslationTokenizer(
                tokenizer,
                directory.resolve(manifest.model().vocabularyFile()),
                directory.resolve(manifest.model().specialTokensFile()),
                manifest.model().maximumLength(),
                manifest.model().eosTokenId(),
                manifest.model().vocabularySize()
            );
            tokenizer = null;

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            environment.setTelemetry(false);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (intraOpThreads != null) options.setIntraOpNumThreads(intraOpThreads);
                encoder = environment.createSession(
                    directory.resolve(manifest.model().encoderFile()).toAbsolutePath().toString(),
                    options
                );
                decoder = environment.createSession(
                    directory.resolve(manifest.model().decoderFile()).toAbsolutePath().toString(),
                    options
                );
            }
            NativeSeq2SeqGenerator generator = new NativeSeq2SeqGenerator(
                environment,
                encoder,
                decoder,
                manifest
            );
            encoder = null;
            decoder = null;
            Resources resources = new Resources(localTokenizer, generator);
            localTokenizer = null;
            return resources;
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            Throwable cleanupFailure = close(decoder, null);
            cleanupFailure = close(encoder, cleanupFailure);
            cleanupFailure = close(localTokenizer, cleanupFailure);
            cleanupFailure = close(tokenizer, cleanupFailure);
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception exception) throw exception;
                else if (cleanupFailure instanceof Error error) throw error;
            }
        }
    }

    private static Throwable close(AutoCloseable resource, Throwable failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Throwable exception) {
            if (failure == null) return exception;
            if (failure != exception) failure.addSuppressed(exception);
        }
        return failure;
    }

    record Resources(NativeTranslationTokenizer tokenizer, NativeSeq2SeqGenerator generator) { }
}
