package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Path;

import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.ArtifactDefinition;
import com.webjetcms.ai.provider.local.ApprovedSeq2SeqModelCatalog.ModelDefinition;
import com.webjetcms.ai.provider.local.VerifiedBundleExtractor.Specification;

/** Validates an approved encoder-decoder bundle through the shared secure extractor. */
final class Seq2SeqBundleValidator {
    private Seq2SeqBundleValidator() { }

    static PreparedBundle validateAndExtract(ModelDefinition model, String temporaryPrefix,
        Path bundle, Path temporaryParent) throws IOException {
        VerifiedBundleExtractor.Result<Seq2SeqBundleManifest> result = VerifiedBundleExtractor.validateAndExtract(
            bundle, temporaryParent, temporaryPrefix, new Specification<>(
                manifest -> model.entryOrder(),
                content -> {
                    Seq2SeqBundleManifest manifest = Seq2SeqBundleManifest.parse(content, model);
                    PlatformSupport.requireSupported(
                        manifest.variant().name(), manifest.variant().cpuTarget());
                    return manifest;
                },
                (manifest, name) -> {
                    ArtifactDefinition approved = model.artifact(name, manifest.variant());
                    return new Specification.Artifact(approved.size(), approved.sha256());
                },
                manifest -> model.maximumExtractedBytes(manifest.variant())
            ));
        return new PreparedBundle(result.directory(), result.manifest());
    }

    record PreparedBundle(Path directory, Seq2SeqBundleManifest manifest) { }
}
