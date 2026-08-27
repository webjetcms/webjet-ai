package com.webjetcms.ai.local.tool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record LocalModelArguments(
    Action action,
    LocalModelRecipe recipe,
    String modelId,
    ModelVariant variant,
    int dimensions,
    Path output,
    boolean overwrite
) {
    enum Action {
        PREPARE,
        HELP,
        VERSION
    }

    static LocalModelArguments parse(String[] arguments, LocalModelRecipe recipe) throws CliException {
        return parse(arguments, List.of(recipe));
    }

    static LocalModelArguments parse(String[] arguments, List<LocalModelRecipe> recipes) throws CliException {
        if (arguments.length == 0) {
            throw new CliException("Missing command; expected prepare, --help, or --version");
        }
        if (arguments.length == 1 && isHelp(arguments[0])) {
            return result(Action.HELP);
        }
        if (arguments.length == 1 && "--version".equals(arguments[0])) {
            return result(Action.VERSION);
        }
        if ("prepare".equals(arguments[0]) == false) {
            throw new CliException("Unknown command: " + arguments[0]);
        }
        if (arguments.length == 2 && isHelp(arguments[1])) {
            return result(Action.HELP);
        }

        String modelId = null;
        String variantName = null;
        Integer dimensions = null;
        Path output = null;
        boolean overwrite = false;
        Set<String> seenOptions = new HashSet<>();

        for (int index = 1; index < arguments.length; index++) {
            String option = arguments[index];
            if ("--overwrite".equals(option)) {
                rejectDuplicate(seenOptions, option);
                overwrite = true;
                continue;
            }
            if (option.startsWith("--") == false) {
                throw new CliException("Unexpected argument: " + option);
            }
            rejectDuplicate(seenOptions, option);
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) {
                throw new CliException("Missing value for " + option);
            }
            String value = arguments[++index];
            try {
                switch (option) {
                    case "--model" -> modelId = value;
                    case "--variant" -> variantName = value;
                    case "--dimensions" -> dimensions = Integer.parseInt(value);
                    case "--output" -> output = Path.of(value);
                    default -> throw new CliException("Unknown option: " + option);
                }
            } catch (NumberFormatException exception) {
                throw new CliException("Invalid dimensions: " + value);
            } catch (InvalidPathException exception) {
                throw new CliException("Invalid output path: " + value);
            } catch (IllegalArgumentException exception) {
                throw new CliException(exception.getMessage());
            }
        }

        if (modelId == null) {
            throw new CliException("Missing required option: --model");
        }
        String requestedModelId = modelId;
        LocalModelRecipe recipe = recipes.stream()
            .filter(candidate -> candidate.acceptedIds().contains(requestedModelId))
            .findFirst()
            .orElse(null);
        if (recipe == null) {
            throw new CliException(
                "Unsupported model: " + modelId + "; expected one of "
                    + recipes.stream().map(LocalModelRecipe::canonicalId).sorted().toList()
            );
        }
        ModelVariant variant;
        try {
            variant = variantName == null ? recipe.defaultVariant() : ModelVariant.parse(variantName);
        } catch (IllegalArgumentException exception) {
            throw new CliException(exception.getMessage());
        }
        if (recipe.supportedVariants().contains(variant) == false) {
            throw new CliException(
                "Unsupported variant for " + recipe.canonicalId() + ": " + variant.cliName()
                    + "; expected " + recipe.supportedVariants().stream()
                        .map(ModelVariant::cliName)
                        .sorted()
                        .toList()
            );
        }
        Integer fixedDimensions = recipe.dimensions();
        if (dimensions != null && fixedDimensions == null) {
            throw new CliException("--dimensions is available only for embedding models");
        }
        if (dimensions != null && dimensions.equals(fixedDimensions) == false) {
            throw new CliException(
                "Unsupported dimensions: " + dimensions + "; " + recipe.canonicalId()
                    + " produces exactly " + fixedDimensions + " dimensions"
            );
        }
        if (output == null) {
            output = Path.of(recipe.defaultOutputName(variant));
        }
        return new LocalModelArguments(
            Action.PREPARE,
            recipe,
            modelId,
            variant,
            fixedDimensions == null ? 0 : fixedDimensions,
            output,
            overwrite
        );
    }

    private static LocalModelArguments result(Action action) {
        return new LocalModelArguments(action, null, null, null, 0, null, false);
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static void rejectDuplicate(Set<String> seenOptions, String option) throws CliException {
        if (seenOptions.add(option) == false) {
            throw new CliException("Option specified more than once: " + option);
        }
    }
}
