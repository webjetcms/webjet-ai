---
description: "Code review for WebJET AI library contributions. Use when reviewing pull requests, changed files, or code snippets for compliance with project conventions, security requirements, and quality standards."
---

# WebJET AI Code Review

Perform a thorough code review of the provided changes against the project's conventions and quality bar.

## Review Checklist

### 1. Framework Neutrality (Hard Boundary)

- **REJECT** any import from WebJET CMS (`sk.iway.*`), Spring (`org.springframework.*`), servlet APIs (`javax.servlet.*`, `jakarta.servlet.*`), or JPA (`javax.persistence.*`, `jakarta.persistence.*`).
- The library must remain usable without a container, database, or application server.
- Host-specific behaviour belongs in the consuming application, not here.

### 2. Security — Credentials and Sensitive Data

- API keys, tokens, and secrets must **never** appear in `toString()`, log output, exceptions, cache keys, or serialized forms.
- Verify that `AiProviderConfig.toString()` continues to redact the API key.
- Provider exceptions must redact API keys and trusted-header values.
- `AiRequest` must deny provider-side storage by default (`store(false)`).
- Custom production endpoints must require HTTPS; loopback HTTP is acceptable only when explicitly opted in for local tests.

### 3. Security — Prompt Injection and Input Handling

- Untrusted input (`inputText`, `userPrompt`) must not gain instruction authority.
- Template expansion must be single-pass and repeat-safe — placeholders inside untrusted values remain literal.
- Review `AiClient` request preparation, `AiRequest` detection metadata, and
  `PromptInjectionDefense` changes with extra scrutiny.

### 4. API Compatibility

- Public API in `com.webjetcms.ai` must remain backward-compatible where possible.
- Any breaking change must be explicitly called out while the project is on `0.x`.
- Immutable value types must stay immutable — no added setters or mutable fields.

### 5. Code Quality

- Java 17 source/target — use records, sealed interfaces, pattern matching where appropriate but do not require newer language features.
- Encoding must be UTF-8 (configured in `build.gradle`).
- Prefer immutable data structures and defensive copies at API boundaries.
- Avoid unnecessary dependencies — the current dependency set is intentionally small (Apache HttpClient 4.x, Jackson, Commons Text).
- No `var` for public/protected API return types or parameters.

### 6. Testing

- Every provider payload parsing change, error-handling path, and streaming change must have a corresponding test.
- Tests use JUnit 5 (Jupiter). Assertions should be clear and specific.
- Test code, comments, and assertions must be written in English.
- Verify edge cases: malformed JSON, partial SSE frames, network timeouts, empty responses.

### 7. Documentation and Style

- Code, comments, commit messages, and Javadoc must be in English.
- User-visible changes must be documented under `Unreleased` in `CHANGELOG.md`.
- Do not commit generated `build/` output or credentials.
- Keep Javadoc concise — one sentence for trivial methods, a short paragraph for complex behaviour.

### 8. Build Hygiene

- `./gradlew clean check javadoc` must pass without warnings or errors.
- No new dependencies without justification.
- Archive tasks remain reproducible (no timestamps, deterministic file order).

## Output Format

Structure the review as:

```
## Summary
One-paragraph overview of the change and its risk level (low / medium / high).

## Issues
- **[BLOCK]** — Must fix before merge. Cite rule and location.
- **[WARN]** — Should fix; not a hard blocker.
- **[NIT]** — Style or minor suggestion.

## Positive Notes
Briefly acknowledge well-done aspects.

## Verdict
APPROVE | REQUEST_CHANGES | COMMENT
```

## Loading Changes

When no code is explicitly provided, automatically load the uncommitted changes in the current branch by running:

```
git diff HEAD
```

If `git diff HEAD` produces no output (no uncommitted changes), fall back to staged changes:

```
git diff --cached
```

If both are empty, check for commits not yet pushed to the remote default branch:

```
git log origin/main..HEAD --oneline
```

and load those diffs with:

```
git diff origin/main..HEAD
```

If all of the above produce no output, ask the user to supply the diff, file, or PR reference to review.
