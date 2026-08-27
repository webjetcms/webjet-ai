package com.webjetcms.ai.local.tool;

enum ModelVariant {
    FP32("fp32"),
    INT8("int8"),
    INT8_AVX512_VNNI("int8-avx512-vnni");

    private final String cliName;

    ModelVariant(String cliName) {
        this.cliName = cliName;
    }

    String cliName() { return cliName; }

    static ModelVariant parse(String value) {
        for (ModelVariant variant : values()) {
            if (variant.cliName.equals(value)) return variant;
        }
        throw new IllegalArgumentException(
            "Unsupported variant: " + value
        );
    }
}
