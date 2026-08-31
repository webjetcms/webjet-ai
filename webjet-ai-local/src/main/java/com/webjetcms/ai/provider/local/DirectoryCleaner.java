package com.webjetcms.ai.provider.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Deletes only a provider-owned extraction tree without following symbolic links. */
final class DirectoryCleaner {
    private DirectoryCleaner() { }

    static void delete(Path directory) throws IOException {
        if (directory == null || Files.exists(directory) == false) return;
        IOException failure = null;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
