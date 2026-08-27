package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingArtifactDefinition;
import com.webjetcms.ai.provider.local.ApprovedEmbeddingModelCatalog.EmbeddingModelDefinition;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Specification;

/** Validates an approved embedding bundle through the shared secure extractor. */
final class EmbeddingBundleValidator {
    private final ApprovedEmbeddingModelCatalog catalog;
    EmbeddingBundleValidator(ApprovedEmbeddingModelCatalog catalog) {
        this.catalog = catalog;
    }

    PreparedBundle validateAndExtract(Path bundle, Path temporaryParent) throws IOException {
        EmbeddingModelDefinition model = catalog.model();
        VerifiedBundleExtractor.Result<EmbeddingBundleManifest> result =
            VerifiedBundleExtractor.validateAndExtract(
                bundle,
                temporaryParent,
                "webjet-ai-local-",
                new Specification<>() {
                    @Override
                    public List<String> entryOrder() {
                        return ApprovedEmbeddingModelCatalog.ENTRY_ORDER;
                    }

                    @Override
                    public EmbeddingBundleManifest parseManifest(byte[] content) throws IOException {
                        EmbeddingBundleManifest manifest = EmbeddingBundleManifest.parse(content, model);
                        PlatformSupport.requireSupported(manifest.variant());
                        return manifest;
                    }

                    @Override
                    public Artifact artifact(EmbeddingBundleManifest manifest, String name)
                        throws IOException {
                        try {
                            EmbeddingArtifactDefinition approved = model.artifact(name, manifest.variant());
                            return new Artifact(approved.size(), approved.sha256());
                        } catch (IllegalArgumentException exception) {
                            throw new IOException(exception.getMessage(), exception);
                        }
                    }

                    @Override
                    public long maximumExtractedBytes(EmbeddingBundleManifest manifest) {
                        return model.maximumExtractedBytes(manifest.variant());
                    }
                }
            );
        return new PreparedBundle(result.directory(), result.manifest());
    }

    record PreparedBundle(Path directory, EmbeddingBundleManifest manifest) { }
}
