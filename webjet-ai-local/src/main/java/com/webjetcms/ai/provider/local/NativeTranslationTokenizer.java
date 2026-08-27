package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final int maximumLength;
    private final int eosTokenId;
    private final int unknownTokenId;
    private final Map<String, Integer> vocabulary;
    private final String[] tokensById;
    private final Set<Integer> specialTokenIds;
    private final Map<String, Long> languageTokenIds;

    NativeTranslationTokenizer(
        SpTokenizer tokenizer,
        Path vocabularyJson,
        Path specialTokensJson,
        int maximumLength,
        int eosTokenId,
        int vocabularySize
    ) throws IOException {
        this.tokenizer = tokenizer;
        this.maximumLength = maximumLength;
        this.eosTokenId = eosTokenId;
        VocabularyData vocabularyData = readVocabulary(vocabularyJson);
        this.vocabulary = vocabularyData.tokenIds();
        this.tokensById = vocabularyData.tokensById();
        Integer unknown = vocabulary.get("<unk>");
        if (unknown == null) throw new IOException("M2M100 vocabulary does not define <unk>");
        this.unknownTokenId = unknown;
        SpecialTokenData specialTokens = readSpecialTokens(
            specialTokensJson,
            vocabulary,
            tokensById.length,
            vocabularySize
        );
        this.specialTokenIds = specialTokens.tokenIds();
        this.languageTokenIds = specialTokens.languageTokenIds();
    }

    public synchronized Batch encode(String text, String sourceLanguage) throws IOException {
        long languageTokenId = languageTokenId(sourceLanguage);
        List<String> pieces;
        try {
            pieces = tokenizer.tokenize(text);
        } catch (RuntimeException exception) {
            throw new IOException("Could not tokenize local text input", exception);
        }
        int contentSize = Math.min(pieces.size(), maximumLength - 2);
        long[] inputIds = new long[contentSize + 2];
        long[] attentionMask = new long[inputIds.length];
        inputIds[0] = languageTokenId;
        for (int index = 0; index < contentSize; index++) {
            inputIds[index + 1] = vocabulary.getOrDefault(pieces.get(index), unknownTokenId);
        }
        inputIds[inputIds.length - 1] = eosTokenId;
        java.util.Arrays.fill(attentionMask, 1L);
        return new Batch(inputIds, attentionMask);
    }

    public synchronized String decode(long[] tokenIds) throws IOException {
        List<String> pieces = new ArrayList<>();
        for (long tokenId : tokenIds) {
            if (tokenId < 0 || tokenId >= tokensById.length) {
                if (languageTokenIds.containsValue(tokenId)) continue;
                throw new IOException("Generated token ID is outside the decodable vocabulary: " + tokenId);
            }
            int id = Math.toIntExact(tokenId);
            if (specialTokenIds.contains(id)) continue;
            pieces.add(tokensById[id]);
        }
        try {
            return tokenizer.buildSentence(pieces);
        } catch (RuntimeException exception) {
            throw new IOException("Could not decode generated text tokens", exception);
        }
    }

    public long languageTokenId(String language) throws IOException {
        String normalized = normalizeLanguage(language);
        Long tokenId = languageTokenIds.get(normalized);
        if (tokenId == null) throw new IOException("Unsupported local translation model language: " + language);
        return tokenId;
    }

    public Set<String> supportedLanguages() {
        return languageTokenIds.keySet();
    }

    @Override
    public synchronized void close() {
        tokenizer.close();
    }

    private static VocabularyData readVocabulary(Path vocabularyJson) throws IOException {
        JsonNode root = MAPPER.readTree(vocabularyJson.toFile());
        if (root == null || root.isObject() == false || root.isEmpty()) {
            throw new IOException("M2M100 vocabulary must be a non-empty JSON object");
        }
        Map<String, Integer> tokenIds = new LinkedHashMap<>();
        int maximumId = -1;
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (field.getValue().canConvertToInt() == false || field.getValue().intValue() < 0) {
                throw new IOException("M2M100 vocabulary contains an invalid token ID");
            }
            int id = field.getValue().intValue();
            if (tokenIds.putIfAbsent(field.getKey(), id) != null) {
                throw new IOException("M2M100 vocabulary contains a duplicate token");
            }
            maximumId = Math.max(maximumId, id);
        }
        String[] tokensById = new String[maximumId + 1];
        for (Map.Entry<String, Integer> token : tokenIds.entrySet()) {
            if (tokensById[token.getValue()] != null) {
                throw new IOException("M2M100 vocabulary contains a duplicate token ID");
            }
            tokensById[token.getValue()] = token.getKey();
        }
        for (String token : tokensById) {
            if (token == null) throw new IOException("M2M100 vocabulary token IDs must be contiguous");
        }
        return new VocabularyData(Map.copyOf(tokenIds), tokensById);
    }

    private static SpecialTokenData readSpecialTokens(
        Path specialTokensJson,
        Map<String, Integer> vocabulary,
        int languageOffset,
        int vocabularySize
    ) throws IOException {
        JsonNode root = MAPPER.readTree(specialTokensJson.toFile());
        if (root == null || root.isObject() == false) {
            throw new IOException("M2M100 special-token metadata must be a JSON object");
        }
        JsonNode languages = root.get("additional_special_tokens");
        if (languages == null || languages.isArray() == false || languages.isEmpty()) {
            throw new IOException("M2M100 special-token metadata does not define languages");
        }
        Map<String, Long> languageTokenIds = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode languageToken : languages) {
            if (languageToken.isTextual() == false) {
                throw new IOException("M2M100 language token must be text");
            }
            Matcher matcher = LANGUAGE_TOKEN.matcher(languageToken.textValue());
            if (matcher.matches() == false) {
                throw new IOException("Invalid M2M100 language token: " + languageToken.textValue());
            }
            long id = Math.addExact(languageOffset, index++);
            if (id >= vocabularySize) throw new IOException("M2M100 language token exceeds the approved vocabulary");
            String language = normalizeLanguage(matcher.group(1));
            if (languageTokenIds.putIfAbsent(language, id) != null) {
                throw new IOException("Duplicate M2M100 language token: " + language);
            }
        }
        Set<Integer> specialTokenIds = new LinkedHashSet<>();
        for (String field : List.of("bos_token", "eos_token", "pad_token", "sep_token", "unk_token")) {
            JsonNode value = root.get(field);
            if (value == null || value.isTextual() == false || vocabulary.containsKey(value.textValue()) == false) {
                throw new IOException("M2M100 special-token metadata is missing " + field);
            }
            specialTokenIds.add(vocabulary.get(value.textValue()));
        }
        return new SpecialTokenData(Set.copyOf(specialTokenIds), Map.copyOf(languageTokenIds));
    }

    private static String normalizeLanguage(String language) throws IOException {
        if (language == null || language.isBlank()) throw new IOException("Local translation model language must not be blank");
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private record VocabularyData(Map<String, Integer> tokenIds, String[] tokensById) { }

    private record SpecialTokenData(Set<Integer> tokenIds, Map<String, Long> languageTokenIds) { }

    record Batch(long[] inputIds, long[] attentionMask) {
        Batch {
            inputIds = inputIds.clone();
            attentionMask = attentionMask.clone();
            if (inputIds.length == 0 || inputIds.length != attentionMask.length) {
                throw new IllegalArgumentException(
                    "Text token IDs and attention mask must have equal non-zero length"
                );
            }
        }

        @Override
        public long[] inputIds() { return inputIds.clone(); }

        @Override
        public long[] attentionMask() { return attentionMask.clone(); }
    }
}
