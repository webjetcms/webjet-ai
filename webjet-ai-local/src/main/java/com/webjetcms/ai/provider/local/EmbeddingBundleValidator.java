package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;

import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingArtifactDefinition;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Result;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Specification;

/** Validates an approved embedding bundle through the shared secure extractor. */
final class EmbeddingBundleValidator {
    private final ApprovedEmbeddingModelCatalog catalog;
    EmbeddingBundleValidator(ApprovedEmbeddingModelCatalog catalog) { this.catalog = catalog; }

    Result<EmbeddingBundleManifest> validateAndExtract(Path bundle, Path temporaryParent) throws IOException {
        return VerifiedBundleExtractor.validateAndExtract(bundle, temporaryParent, "webjet-ai-local-", new Specification<>(
                manifest -> manifest.model().entryOrder(), this::parseManifest, this::artifact,
                manifest -> manifest.model().maximumExtractedBytes(manifest.variant())));
    }

    private EmbeddingBundleManifest parseManifest(byte[] content) throws IOException {
        try {
            EmbeddingBundleManifest manifest = EmbeddingBundleManifest.parse(content, catalog);
            PlatformSupport.requireSupported(manifest.variant());
            return manifest;
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private Specification.Artifact artifact(EmbeddingBundleManifest manifest, String name) throws IOException {
        try {
            EmbeddingArtifactDefinition artifact = manifest.model().artifact(name, manifest.variant());
            return new Specification.Artifact(artifact.size(), artifact.sha256());
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }
}
