package com.webjetcms.ai.local.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class LocalModelToolTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void productionCataloguesDescribeEveryBundleVariantConsistently() throws Exception {
        List<LocalModelRecipe> recipes = new ArrayList<>(CatalogRecipe.embeddings());
        recipes.add(CatalogRecipe.seq2seq(
            "META-INF/webjet-ai/local-translation-model-catalog-v1.properties",
            "translation-model"
        ));
        recipes.add(CatalogRecipe.seq2seq(
            "META-INF/webjet-ai/local-generation-model-catalog-v1.properties",
            "generation-model"
        ));

        assertEquals(4, recipes.size());
        for (LocalModelRecipe recipe : recipes) {
            assertTrue(recipe.acceptedIds().contains(recipe.canonicalId()), recipe.canonicalId());
            assertTrue(recipe.supportedVariants().contains(recipe.defaultVariant()), recipe.canonicalId());
            for (ModelVariant variant : recipe.supportedVariants()) {
                var manifest = new ObjectMapper().readTree(recipe.manifest(variant));
                List<ModelArtifact> artifacts = recipe.artifacts(variant);

                assertEquals(1, manifest.path("schemaVersion").intValue(), recipe.canonicalId());
                assertEquals(recipe.canonicalId(), manifest.path("model").path("id").textValue());
                assertEquals(variant.cliName(), manifest.path("model").path("variant").textValue());
                assertFalse(artifacts.isEmpty(), recipe.canonicalId());
                assertEquals(
                    artifacts.size(),
                    artifacts.stream().map(ModelArtifact::bundlePath).distinct().count(),
                    recipe.canonicalId()
                );
                assertTrue(artifacts.stream().allMatch(artifact ->
                    "https".equals(artifact.sourceUri().getScheme())
                        && "huggingface.co".equals(artifact.sourceUri().getHost())
                ));
            }
        }
    }

    @Test
    void prepareRetriesAndWritesAReproducibleVerifiedBundle() throws Exception {
        byte[] model = "small model fixture".getBytes(StandardCharsets.UTF_8);
        String sha256 = Hashes.sha256(model);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0
        );
        server.createContext("/model.onnx", exchange -> {
            try {
                if (requests.getAndIncrement() == 0) {
                    exchange.getResponseHeaders().add("Retry-After", "0");
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    exchange.sendResponseHeaders(200, model.length);
                    exchange.getResponseBody().write(model);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            URI source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.onnx");
            LocalModelRecipe recipe = new FixtureRecipe(source, model.length, sha256);
            List<Long> delays = new ArrayList<>();
            HttpDownloader downloader = new HttpDownloader(HttpClient.newHttpClient(), 2, delays::add);
            Path first = temporaryDirectory.resolve("first.zip");
            Path second = temporaryDirectory.resolve("second.zip");

            assertEquals(0, run(recipe, downloader, first));
            assertEquals(List.of(0L), delays);
            assertEquals(1, run(recipe, downloader, first));
            assertEquals(0, run(recipe, downloader, second));
            assertEquals(0, run(recipe, downloader, second, "--overwrite"));
            assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));

            Map<String, byte[]> entries = zipEntries(first);
            assertEquals(List.of("webjet-model.json", "model.onnx", "SHA256SUMS"),
                List.copyOf(entries.keySet()));
            assertArrayEquals(model, entries.get("model.onnx"));
            String sums = new String(entries.get("SHA256SUMS"), StandardCharsets.US_ASCII);
            assertTrue(sums.contains(sha256 + "  model.onnx\n"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bundleWriterDoesNotReplaceADanglingSymbolicLink() throws Exception {
        Path output = temporaryDirectory.resolve("dangling.zip");
        Path missingTarget = temporaryDirectory.resolve("missing.zip");
        try {
            Files.createSymbolicLink(output, missingTarget);
        } catch (UnsupportedOperationException | IOException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertFalse(Files.exists(output));
        assertTrue(Files.exists(output, LinkOption.NOFOLLOW_LINKS));
        LocalModelRecipe recipe = new FixtureRecipe(
            URI.create("https://example.invalid/model.onnx"), 1, "0".repeat(64)
        );
        assertThrows(IOException.class, () -> new LocalModelBundleWriter().write(
            output, false, recipe, ModelVariant.FP32, List.of(), List.of()
        ));

        assertTrue(Files.isSymbolicLink(output));
        assertEquals(missingTarget, Files.readSymbolicLink(output));
    }

    @Test
    void bundlePublicationDoesNotReplaceAnExistingDestination() throws Exception {
        Path source = temporaryDirectory.resolve("bundle.tmp");
        Path output = temporaryDirectory.resolve("late.zip");
        Files.writeString(source, "new bundle", StandardCharsets.UTF_8);
        Files.writeString(output, "existing output", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> LocalModelBundleWriter.move(source, output, false));

        assertEquals("existing output", Files.readString(output, StandardCharsets.UTF_8));
        assertTrue(Files.exists(source));
    }

    private static int run(LocalModelRecipe recipe, HttpDownloader downloader, Path output,
        String... additionalArguments) {
        List<String> arguments = new ArrayList<>(List.of(
            "prepare", "--model", recipe.canonicalId(), "--output", output.toString()
        ));
        arguments.addAll(List.of(additionalArguments));
        return LocalModelTool.run(
            arguments.toArray(String[]::new),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            recipe,
            downloader
        );
    }

    private static Map<String, byte[]> zipEntries(Path bundle) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private record FixtureRecipe(URI source, long size, String sha256) implements LocalModelRecipe {
        @Override public String canonicalId() { return "fixture/model"; }
        @Override public Set<String> acceptedIds() { return Set.of(canonicalId()); }
        @Override public String revision() { return "fixture-revision"; }
        @Override public Integer dimensions() { return 2; }
        @Override public int maximumLength() { return 8; }
        @Override public Set<ModelVariant> supportedVariants() { return Set.of(ModelVariant.FP32); }
        @Override public ModelVariant defaultVariant() { return ModelVariant.FP32; }
        @Override public String defaultOutputName(ModelVariant variant) { return "fixture.zip"; }
        @Override public String manifest(ModelVariant variant) {
            return "{\"schemaVersion\":1,\"model\":{\"id\":\"fixture/model\",\"variant\":\"fp32\"}}\n";
        }
        @Override public List<ModelArtifact> artifacts(ModelVariant variant) {
            return List.of(new ModelArtifact("model.onnx", "model.onnx", source, size, sha256));
        }
    }
}
