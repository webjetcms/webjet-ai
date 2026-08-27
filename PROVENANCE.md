# Provenance

WebJET AI was extracted from
[`webjetcms/webjetcms`](https://github.com/webjetcms/webjetcms) as part of
issue 58670.

Relevant earlier implementation history includes:

- [`600020a9e2d91bd23a21313f793eccb7789eaf02`](https://github.com/webjetcms/webjetcms/commit/600020a9e2d91bd23a21313f793eccb7789eaf02) — initial AI provider implementation.
- [`9cd9117922b6cf4aa458ca37e5f73ca54ee738eb`](https://github.com/webjetcms/webjetcms/commit/9cd9117922b6cf4aa458ca37e5f73ca54ee738eb) — prompt-injection protection.

The repository starts with a clean history because the reusable module was
created during the extraction and had no independent commit history. The source
and its subsequent changes are licensed under Apache License 2.0.

Local M2M100 bundles are prepared from the
[`Xenova/m2m100_418M`](https://huggingface.co/Xenova/m2m100_418M) ONNX conversion
at revision `9c374f0b7aca709787cea97b047bfbbd1559d177`. The preparation catalogue pins
the encoder, merged decoder, tokenizer, configuration, vocabulary, SentencePiece,
and model-card artifacts by exact byte size and SHA-256. The upstream model identity
remains [`facebook/m2m100_418M`](https://huggingface.co/facebook/m2m100_418M).
