package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.djl.sentencepiece.SpTokenizer;

/** Serializes access to SentencePiece with an approved external M2M100 vocabulary. */
final class NativeTranslationTokenizer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LANGUAGE_TOKEN = Pattern.compile("^__([A-Za-z0-9_-]+)__$");
    private final SpTokenizer tokenizer;
    private final int maximumLength, eosTokenId, vocabularySize;
    private final Metadata metadata;
    NativeTranslationTokenizer(SpTokenizer tokenizer, Path vocabularyJson, Path specialTokensJson,
        int maximumLength, int eosTokenId, int vocabularySize) throws IOException {
        this.tokenizer = tokenizer; this.maximumLength = maximumLength; this.eosTokenId = eosTokenId;
        this.vocabularySize = vocabularySize;
        this.metadata = readMetadata(vocabularyJson, specialTokensJson, vocabularySize);
    }
    public synchronized NativeTokenizerBatch encode(String text, String sourceLanguage) throws IOException {
        long languageTokenId = languageTokenId(sourceLanguage);
        List<String> pieces = nativeCall(() -> tokenizer.tokenize(text), "Could not tokenize local text input");
        int contentSize = Math.min(pieces.size(), maximumLength - 2);
        long[] inputIds = new long[contentSize + 2], attentionMask = new long[inputIds.length];
        inputIds[0] = languageTokenId;
        for (int index = 0; index < contentSize; index++)
            inputIds[index + 1] = metadata.tokenIds().getOrDefault(pieces.get(index), metadata.unknownTokenId());
        inputIds[inputIds.length - 1] = eosTokenId;
        java.util.Arrays.fill(attentionMask, 1L);
        return new NativeTokenizerBatch(inputIds, attentionMask);
    }
    public synchronized String decode(long[] tokenIds) throws IOException {
        List<String> pieces = decodePieces(tokenIds, metadata.tokensById(), vocabularySize);
        return nativeCall(() -> tokenizer.buildSentence(pieces), "Could not decode generated text tokens");
    }
    static List<String> decodePieces(long[] tokenIds, String[] tokensById, int vocabularySize)
        throws IOException {
        List<String> pieces = new ArrayList<>();
        for (long tokenId : tokenIds) {
            if (tokenId < 0 || tokenId >= vocabularySize) {
                throw new IOException("Generated token ID is outside the decodable vocabulary: " + tokenId);
            }
            if (tokenId >= tokensById.length) continue;
            int id = Math.toIntExact(tokenId); String piece = tokensById[id];
            if (piece != null) pieces.add(piece);
        }
        return pieces;
    }
    public long languageTokenId(String language) throws IOException {
        String normalized = normalizeLanguage(language);
        Long tokenId = metadata.languageTokenIds().get(normalized);
        if (tokenId == null) throw new IOException("Unsupported local translation model language: " + language);
        return tokenId;
    }
    public Set<String> supportedLanguages() { return metadata.languageTokenIds().keySet(); }
    @Override
    public synchronized void close() { tokenizer.close(); }

    private static Metadata readMetadata(Path vocabularyJson, Path specialTokensJson,
        int vocabularySize) throws IOException {
        JsonNode root = MAPPER.readTree(vocabularyJson.toFile());
        require(root != null && root.isObject() && root.isEmpty() == false,
            "M2M100 vocabulary must be a non-empty JSON object");
        Map<String, Integer> tokenIds = new LinkedHashMap<>();
        String[] tokensById = new String[root.size()];
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            JsonNode value = field.getValue();
            require(value.canConvertToInt() && value.intValue() >= 0,
                "M2M100 vocabulary contains an invalid token ID");
            int id = value.intValue();
            require(id < tokensById.length, "M2M100 vocabulary token IDs must be contiguous");
            require(tokensById[id] == null, "M2M100 vocabulary contains a duplicate token ID");
            tokenIds.put(field.getKey(), id); tokensById[id] = field.getKey();
        }
        for (String token : tokensById)
            require(token != null, "M2M100 vocabulary token IDs must be contiguous");
        Integer unknownTokenId = tokenIds.get("<unk>");
        require(unknownTokenId != null, "M2M100 vocabulary does not define <unk>");
        root = MAPPER.readTree(specialTokensJson.toFile());
        require(root != null && root.isObject(), "M2M100 special-token metadata must be a JSON object");
        JsonNode languages = root.get("additional_special_tokens");
        require(languages != null && languages.isArray() && languages.isEmpty() == false,
            "M2M100 special-token metadata does not define languages");
        Map<String, Long> languageTokenIds = new LinkedHashMap<>(); int index = 0;
        for (JsonNode languageToken : languages) {
            require(languageToken.isTextual(), "M2M100 language token must be text");
            Matcher matcher = LANGUAGE_TOKEN.matcher(languageToken.textValue());
            require(matcher.matches(), "Invalid M2M100 language token: " + languageToken.textValue());
            long id = Math.addExact(tokensById.length, index++);
            require(id < vocabularySize, "M2M100 language token exceeds the approved vocabulary");
            String language = normalizeLanguage(matcher.group(1));
            require(languageTokenIds.putIfAbsent(language, id) == null,
                "Duplicate M2M100 language token: " + language);
        }
        for (String field : List.of("bos_token", "eos_token", "pad_token", "sep_token", "unk_token")) {
            JsonNode value = root.get(field);
            require(value != null && value.isTextual() && tokenIds.containsKey(value.textValue()),
                "M2M100 special-token metadata is missing " + field);
            tokensById[tokenIds.get(value.textValue())] = null;
        }
        return new Metadata(Map.copyOf(tokenIds), tokensById, Map.copyOf(languageTokenIds), unknownTokenId);
    }
    private static String normalizeLanguage(String language) throws IOException {
        require(language != null && language.isBlank() == false,
            "Local translation model language must not be blank");
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
    private static <T> T nativeCall(Supplier<T> call, String message) throws IOException {
        try { return call.get(); } catch (RuntimeException exception) {
            throw new IOException(message, exception); }
    }
    private static void require(boolean valid, String message) throws IOException { if (valid == false) throw new IOException(message); }
    private record Metadata(Map<String, Integer> tokenIds, String[] tokensById,
        Map<String, Long> languageTokenIds, int unknownTokenId) { }
}

/** Defensive token IDs and matching encoder attention mask shared by local tokenizers. */
record NativeTokenizerBatch(long[] inputIds, long[] attentionMask) {
    NativeTokenizerBatch {
        inputIds = inputIds.clone(); attentionMask = attentionMask.clone();
        if (inputIds.length == 0 || inputIds.length != attentionMask.length)
            throw new IllegalArgumentException("Token IDs and attention mask must have equal non-zero length");
    }
    @Override public long[] inputIds() { return inputIds.clone(); }
    @Override public long[] attentionMask() { return attentionMask.clone(); }
}
