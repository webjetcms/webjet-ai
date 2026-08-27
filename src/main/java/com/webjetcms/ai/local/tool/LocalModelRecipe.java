package com.webjetcms.ai.local.tool;

import java.util.List;
import java.util.Set;

interface LocalModelRecipe {
    String canonicalId();

    Set<String> acceptedIds();

    String revision();

    Integer dimensions();

    int maximumLength();

    Set<ModelVariant> supportedVariants();

    ModelVariant defaultVariant();

    String defaultOutputName(ModelVariant variant);

    String manifest(ModelVariant variant);

    List<ModelArtifact> artifacts(ModelVariant variant);
}
