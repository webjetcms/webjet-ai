package com.webjetcms.ai.provider.local;

import java.nio.file.Path;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.sentencepiece.SpTokenizer;

/** Initializes model-specific tokenization around the shared ONNX encoder-decoder generator. */
final class NativeSeq2SeqRuntimeFactory {
    private NativeSeq2SeqRuntimeFactory() { }
    static Resources<NativeTranslationTokenizer> createTranslation(
        Seq2SeqBundleValidator.PreparedBundle bundle, Integer intraOpThreads) throws Exception {
        return create(bundle, intraOpThreads, (directory, model) -> LocalProviderLifecycle.transfer(
            new SpTokenizer(directory.resolve(model.tokenizerModelFile())),
            tokenizer -> new NativeTranslationTokenizer(tokenizer,
                directory.resolve(model.vocabularyFile()), directory.resolve(model.specialTokensFile()),
                model.maximumLength(), model.eosTokenId(), model.vocabularySize())
        ));
    }
    static Resources<NativeGenerationTokenizer> createGeneration(
        Seq2SeqBundleValidator.PreparedBundle bundle, Integer intraOpThreads) throws Exception {
        return create(bundle, intraOpThreads, (directory, model) -> LocalProviderLifecycle.transfer(
            HuggingFaceTokenizer.builder()
                .optTokenizerPath(directory.resolve(model.tokenizerFile()))
                .optTokenizerConfigPath(directory.resolve(model.tokenizerConfigFile()).toString())
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optPadding(false)
                .optMaxLength(model.maximumLength())
                .build(),
            tokenizer -> new NativeGenerationTokenizer(
                tokenizer, model.maximumLength(), model.vocabularySize())
        ));
    }
    private static <T extends AutoCloseable> Resources<T> create(
        Seq2SeqBundleValidator.PreparedBundle bundle, Integer intraOpThreads,
        TokenizerLoader<T> tokenizerLoader) throws Exception {
        NativeEmbeddingRuntimeFactory.requireDjlOffline();
        Path directory = bundle.directory();
        T tokenizer = tokenizerLoader.open(directory, bundle.manifest().model());
        return LocalProviderLifecycle.transfer(tokenizer, ownedTokenizer -> {
            NativeSeq2SeqGenerator generator = NativeSeq2SeqGenerator.open(
                directory, bundle.manifest().model(), intraOpThreads);
            return LocalProviderLifecycle.transfer(generator, ownedGenerator ->
                new Resources<>(ownedTokenizer, ownedGenerator));
        });
    }
    private interface TokenizerLoader<T extends AutoCloseable> {
        T open(Path directory, ApprovedSeq2SeqModelCatalog.ModelDefinition model) throws Exception; }
    record Resources<T extends AutoCloseable>(T tokenizer, NativeSeq2SeqGenerator generator) { }
}
