package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.webjetcms.ai.provider.local.ApprovedTranslationModelCatalog.TranslationArtifactDefinition;
import com.webjetcms.ai.provider.local.ApprovedTranslationModelCatalog.TranslationModelDefinition;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Specification;

/** Validates an approved local translation model bundle through the shared secure extractor. */
final class TranslationBundleValidator {
    private final ApprovedTranslationModelCatalog catalog;
    TranslationBundleValidator(ApprovedTranslationModelCatalog catalog) {
        this.catalog = catalog;
    }

    PreparedBundle validateAndExtract(Path bundle, Path temporaryParent) throws IOException {
        TranslationModelDefinition model = catalog.model();
        VerifiedBundleExtractor.Result<TranslationBundleManifest> result =
            VerifiedBundleExtractor.validateAndExtract(
                bundle,
                temporaryParent,
                "webjet-ai-local-translation-",
                new Specification<>() {
                    @Override
                    public List<String> entryOrder(TranslationBundleManifest manifest) {
                        return ApprovedTranslationModelCatalog.ENTRY_ORDER;
                    }

                    @Override
                    public TranslationBundleManifest parseManifest(byte[] content) throws IOException {
                        TranslationBundleManifest manifest = TranslationBundleManifest.parse(content, model);
                        PlatformSupport.requireSupported(
                            manifest.variant().name(),
                            manifest.variant().cpuTarget()
                        );
                        return manifest;
                    }

                    @Override
                    public Artifact artifact(TranslationBundleManifest manifest, String name)
                        throws IOException {
                        try {
                            TranslationArtifactDefinition approved = model.artifact(name, manifest.variant());
                            return new Artifact(approved.size(), approved.sha256());
                        } catch (IllegalArgumentException exception) {
                            throw new IOException(exception.getMessage(), exception);
                        }
                    }

                    @Override
                    public long maximumExtractedBytes(TranslationBundleManifest manifest) {
                        return model.maximumExtractedBytes(manifest.variant());
                    }
                }
            );
        return new PreparedBundle(result.directory(), result.manifest());
    }

    record PreparedBundle(Path directory, TranslationBundleManifest manifest) { }
}
