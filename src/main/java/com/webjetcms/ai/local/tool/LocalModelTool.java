package com.webjetcms.ai.local.tool;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Command-line tool that prepares verified, deterministic local-model bundles for WebJET AI.
 *
 * <p>The tool downloads pinned model artifacts but does not load or execute the model. Run it
 * with {@code --help} to see the supported model, variants, and preparation options.</p>
 */
public final class LocalModelTool {
    private static final int SUCCESS = 0;
    private static final int OPERATIONAL_FAILURE = 1;
    private static final int INVALID_ARGUMENTS = 2;

    private LocalModelTool() { }

    /**
     * Runs the local-model preparation command and exits with a documented CLI exit code.
     *
     * @param arguments command-line arguments
     */
    public static void main(String[] arguments) {
        int exitCode = run(
            arguments,
            System.out,
            System.err,
            List.of(new MultilingualE5BaseRecipe(), new M2m100Recipe()),
            HttpDownloader.production()
        );
        if (exitCode != SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int run(
        String[] arguments,
        PrintStream standardOutput,
        PrintStream standardError,
        LocalModelRecipe recipe,
        HttpDownloader downloader
    ) {
        return run(arguments, standardOutput, standardError, List.of(recipe), downloader);
    }

    static int run(
        String[] arguments,
        PrintStream standardOutput,
        PrintStream standardError,
        List<LocalModelRecipe> recipes,
        HttpDownloader downloader
    ) {
        LocalModelArguments options;
        try {
            options = LocalModelArguments.parse(arguments, recipes);
        } catch (CliException exception) {
            standardError.println("Error: " + exception.getMessage());
            standardError.println();
            standardError.print(help());
            return INVALID_ARGUMENTS;
        }

        if (options.action() == LocalModelArguments.Action.HELP) {
            standardOutput.print(help());
            return SUCCESS;
        }
        if (options.action() == LocalModelArguments.Action.VERSION) {
            standardOutput.println(version());
            return SUCCESS;
        }

        Path output = options.output().toAbsolutePath().normalize();
        if (Files.exists(output) && options.overwrite() == false) {
            standardError.println("Error: Output already exists; use --overwrite to replace it: " + output);
            return OPERATIONAL_FAILURE;
        }

        Path downloadDirectory = null;
        try {
            Path parent = output.getParent();
            if (parent == null) {
                throw new IOException("Output path has no parent directory: " + output);
            }
            Files.createDirectories(parent);
            downloadDirectory = Files.createTempDirectory(parent, ".webjet-model-download-");
            LocalModelRecipe recipe = options.recipe();
            List<ModelArtifact> artifacts = recipe.artifacts(options.variant());
            List<DownloadResult> downloads = new ArrayList<>(artifacts.size());
            for (int index = 0; index < artifacts.size(); index++) {
                ModelArtifact artifact = artifacts.get(index);
                standardError.println(
                    "Downloading " + artifact.sourcePath() + " (" + (index + 1) + "/" + artifacts.size() + ")"
                );
                Path temporaryFile = downloadDirectory.resolve(index + "-" + artifact.bundlePath());
                downloads.add(downloader.download(
                    new DownloadRequest(
                        artifact.sourceUri(),
                        temporaryFile,
                        artifact.expectedSize(),
                        artifact.expectedSha256()
                    ),
                    standardError::println
                ));
            }
            standardError.println("Creating reproducible bundle");
            Path prepared = new LocalModelBundleWriter().write(
                output,
                options.overwrite(),
                recipe,
                options.variant(),
                artifacts,
                downloads
            );
            standardOutput.println(prepared);
            return SUCCESS;
        } catch (IOException | RuntimeException exception) {
            standardError.println("Error: " + message(exception));
            return OPERATIONAL_FAILURE;
        } finally {
            if (downloadDirectory != null) {
                try {
                    deleteTree(downloadDirectory);
                } catch (IOException exception) {
                    standardError.println("Warning: Unable to remove temporary files: " + message(exception));
                }
            }
        }
    }

    private static String version() {
        String implementationVersion = LocalModelTool.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
            ? "development"
            : implementationVersion;
    }

    private static String help() {
        return """
            webjet-ai-local-model-tool

            Prepare a verified local-model ZIP without loading or executing the model.

            Usage:
              java -jar webjet-ai-VERSION.jar prepare --model MODEL [options]
              java -jar webjet-ai-VERSION.jar --help
              java -jar webjet-ai-VERSION.jar --version

            Required:
              --model MODEL       intfloat/multilingual-e5-base or facebook/m2m100_418M

            Options:
              --variant VARIANT   Model-specific fp32, int8, or int8-avx512-vnni variant
              --dimensions SIZE   Validate an embedding model's fixed dimensions
              --output PATH       Destination ZIP (uses a model-specific default)
              --overwrite         Replace an existing destination after preparation succeeds
              -h, --help          Show this help
              --version           Show the JAR implementation version
            """;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
