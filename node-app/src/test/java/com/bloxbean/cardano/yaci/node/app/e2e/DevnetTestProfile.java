package com.bloxbean.cardano.yaci.node.app.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public class DevnetTestProfile implements QuarkusTestProfile {

    static final Path TEMP_STORAGE_DIR;

    static {
        try {
            TEMP_STORAGE_DIR = Files.createTempDirectory("yaci-e2etest-chainstate");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Use Files.walk + stream instead of anonymous SimpleFileVisitor subclass
        // to avoid cross-classloader IllegalAccessError with Quarkus's ParentLastURLClassLoader
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(TEMP_STORAGE_DIR)) {
                    try (var paths = Files.walk(TEMP_STORAGE_DIR)) {
                        paths.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                }
            } catch (IOException ignored) {
            }
        }));
    }

    @Override
    public String getConfigProfile() {
        if (System.getProperty("yaci.e2e.baseUrl") != null) {
            return "test";   // lightweight — no devnet infrastructure
        }
        return "devnet";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        if (System.getProperty("yaci.e2e.baseUrl") != null) {
            return Map.of();  // %test defaults are fine
        }
        return Map.of(
                "yaci.node.storage.rocksdb", "true",
                "yaci.node.storage.path", TEMP_STORAGE_DIR.toString(),
                "yaci.plugins.enabled", "false",
                "yaci.node.server.port", "23337"
        );
    }
}
