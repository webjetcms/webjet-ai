package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line tool that prepares verified, deterministic local-model bundles for WebJET AI.
 *
 * <p>The tool downloads pinned model artifacts but does not load or execute the model. Run it
 * with {@code --help} to see the supported models, variants, and preparation options.</p>
 */
public final class LocalModelTool {
    private static final int SUCCESS = 0, OPERATIONAL_FAILURE = 1, INVALID_ARGUMENTS = 2;
    private static final Set<String> VALUE_OPTIONS = Set.of("--model", "--variant", "--dimensions", "--output");

    private LocalModelTool() { }

    /**
     * Runs the local-model preparation command and exits with a documented CLI exit code.
     *
     * @param arguments command-line arguments
     */
    public static void main(String[] arguments) {
        int exitCode = run(arguments, System.out, System.err, productionRecipes(), HttpDownloader.production());
        if (exitCode != SUCCESS) System.exit(exitCode);
    }

    static int run(String[] arguments, PrintStream out, PrintStream error, LocalModelRecipe recipe,
        HttpDownloader downloader) {
        return run(arguments, out, error, List.of(recipe), downloader);
    }

    static int run(String[] arguments, PrintStream out, PrintStream error, List<LocalModelRecipe> recipes,
        HttpDownloader downloader) {
        Options options;
        try {
            options = parse(arguments, recipes);
        } catch (CliFailure failure) {
            error.println("Error: " + failure.getMessage()); error.println();
            error.print(help(recipes));
            return INVALID_ARGUMENTS;
        }
        if (options.action == Action.HELP) { out.print(help(recipes)); return SUCCESS; }
        if (options.action == Action.VERSION) { out.println(version()); return SUCCESS; }
        return prepare(options, out, error, downloader);
    }

    private static int prepare(Options options, PrintStream out, PrintStream error, HttpDownloader downloader) {
        Path output = options.output.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && options.overwrite == false) {
            error.println("Error: Output already exists; use --overwrite to replace it: " + output);
            return OPERATIONAL_FAILURE;
        }
        Path directory = null;
        try {
            Path parent = output.getParent();
            if (parent == null) throw new IOException("Output path has no parent directory: " + output);
            Files.createDirectories(parent);
            directory = Files.createTempDirectory(parent, ".webjet-model-download-");
            List<ModelArtifact> artifacts = options.recipe.artifacts(options.variant);
            List<DownloadResult> downloads = new ArrayList<>(artifacts.size());
            for (int index = 0; index < artifacts.size(); index++) {
                ModelArtifact artifact = artifacts.get(index);
                error.println("Downloading " + artifact.sourcePath() + " (" + (index + 1) + "/" + artifacts.size() + ")");
                downloads.add(downloader.download(new DownloadRequest(artifact.sourceUri(),
                    directory.resolve(index + "-" + artifact.bundlePath()), artifact.expectedSize(),
                    artifact.expectedSha256()), error::println));
            }
            error.println("Creating reproducible bundle");
            out.println(new LocalModelBundleWriter().write(output, options.overwrite, options.recipe,
                options.variant, artifacts, downloads));
            return SUCCESS;
        } catch (IOException | RuntimeException exception) {
            error.println("Error: " + message(exception));
            return OPERATIONAL_FAILURE;
        } finally {
            if (directory != null) try { deleteTree(directory); }
            catch (IOException exception) {
                error.println("Warning: Unable to remove temporary files: " + message(exception));
            }
        }
    }

    private static Options parse(String[] arguments, List<LocalModelRecipe> recipes) throws CliFailure {
        if (arguments.length == 0) throw fail("Missing command; expected prepare, --help, or --version");
        if (arguments.length == 1 && isHelp(arguments[0])) return Options.action(Action.HELP);
        if (arguments.length == 1 && "--version".equals(arguments[0])) return Options.action(Action.VERSION);
        if ("prepare".equals(arguments[0]) == false) throw fail("Unknown command: " + arguments[0]);
        if (arguments.length == 2 && isHelp(arguments[1])) return Options.action(Action.HELP);

        Map<String, String> values = new HashMap<>();
        boolean overwrite = false;
        for (int index = 1; index < arguments.length; index++) {
            String option = arguments[index];
            if ("--overwrite".equals(option)) {
                if (overwrite) throw fail("Option specified more than once: " + option); overwrite = true; continue;
            }
            if (option.startsWith("--") == false) throw fail("Unexpected argument: " + option);
            if (values.containsKey(option)) throw fail("Option specified more than once: " + option);
            if (++index >= arguments.length || arguments[index].startsWith("--")) throw fail("Missing value for " + option);
            if (VALUE_OPTIONS.contains(option) == false) throw fail("Unknown option: " + option);
            values.put(option, arguments[index]);
        }
        String modelId = values.get("--model");
        if (modelId == null) throw fail("Missing required option: --model");
        LocalModelRecipe recipe = recipes.stream().filter(item -> item.acceptedIds().contains(modelId)).findFirst().orElse(null);
        if (recipe == null) throw fail("Unsupported model: " + modelId + "; expected one of "
            + recipes.stream().map(LocalModelRecipe::canonicalId).sorted().toList());
        ModelVariant variant;
        try {
            variant = values.containsKey("--variant") ? ModelVariant.parse(values.get("--variant")) : recipe.defaultVariant();
        } catch (IllegalArgumentException exception) {
            throw fail(exception.getMessage());
        }
        if (recipe.supportedVariants().contains(variant) == false) throw fail("Unsupported variant for "
            + recipe.canonicalId() + ": " + variant.cliName() + "; expected "
            + recipe.supportedVariants().stream().map(ModelVariant::cliName).sorted().toList());

        Integer requestedDimensions;
        try { requestedDimensions = values.containsKey("--dimensions") ? Integer.valueOf(values.get("--dimensions")) : null; }
        catch (NumberFormatException exception) { throw fail("Invalid dimensions: " + values.get("--dimensions")); }
        Integer dimensions = recipe.dimensions();
        if (requestedDimensions != null && dimensions == null) throw fail("--dimensions is available only for embedding models");
        if (requestedDimensions != null && requestedDimensions.equals(dimensions) == false) throw fail("Unsupported dimensions: "
            + requestedDimensions + "; " + recipe.canonicalId() + " produces exactly " + dimensions + " dimensions");
        Path output;
        try { output = Path.of(values.getOrDefault("--output", recipe.defaultOutputName(variant))); }
        catch (InvalidPathException exception) { throw fail("Invalid output path: " + values.get("--output")); }
        return new Options(Action.PREPARE, recipe, variant, output, overwrite);
    }

    private static List<LocalModelRecipe> productionRecipes() {
        List<LocalModelRecipe> recipes = new ArrayList<>(CatalogRecipe.embeddings());
        recipes.add(CatalogRecipe.seq2seq("META-INF/webjet-ai/local-translation-model-catalog-v1.properties", "translation-model"));
        recipes.add(CatalogRecipe.seq2seq("META-INF/webjet-ai/local-generation-model-catalog-v1.properties", "generation-model"));
        return List.copyOf(recipes);
    }

    private static String help(List<LocalModelRecipe> recipes) {
        String models = String.join(" or ", recipes.stream().map(LocalModelRecipe::canonicalId).sorted().toList());
        return ("webjet-ai-local-model-tool\n\nPrepare a verified local-model ZIP without loading or executing the model.\n\n"
            + "Usage:\n  java -jar webjet-ai-VERSION.jar prepare --model MODEL [options]\n"
            + "  java -jar webjet-ai-VERSION.jar --help\n  java -jar webjet-ai-VERSION.jar --version\n\n"
            + "Source checkout:\n  ./gradlew localModelTool --args='prepare --model MODEL [options]'\n\n"
            + "Required:\n  --model MODEL       %s\n\nOptions:\n"
            + "  --variant VARIANT   Model-specific quantization variant\n"
            + "  --dimensions SIZE   Validate an embedding model's fixed dimensions\n"
            + "  --output PATH       Destination ZIP (uses a model-specific default)\n"
            + "  --overwrite         Replace an existing destination after preparation succeeds\n"
            + "  -h, --help          Show this help\n  --version           Show the JAR implementation version\n").formatted(models);
    }

    private static String version() {
        String version = LocalModelTool.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }
    private static boolean isHelp(String value) { return "--help".equals(value) || "-h".equals(value); }
    private static CliFailure fail(String message) { return new CliFailure(message); }
    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private enum Action { PREPARE, HELP, VERSION }
    private record Options(Action action, LocalModelRecipe recipe, ModelVariant variant, Path output, boolean overwrite) {
        private static Options action(Action action) { return new Options(action, null, null, null, false); }
    }
    private static final class CliFailure extends Exception {
        private CliFailure(String message) { super(message); }
    }
}
