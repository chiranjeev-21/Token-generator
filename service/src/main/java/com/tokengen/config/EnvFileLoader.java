package com.tokengen.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads local .env files for developer workflows without overriding real
 * environment variables provided by Docker, CI, or production platforms.
 */
public final class EnvFileLoader {

    private static final List<Path> CANDIDATES = List.of(
            Path.of(".env"),
            Path.of("../.env")
    );

    private EnvFileLoader() {}

    public static void load() {
        for (Path candidate : CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                loadFile(candidate);
            }
        }
    }

    private static void loadFile(Path path) {
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                applyLine(rawLine);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read .env file: " + path.toAbsolutePath(), e);
        }
    }

    private static void applyLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).trim();
        }

        int equalsIndex = line.indexOf('=');
        if (equalsIndex <= 0) {
            return;
        }

        String key = line.substring(0, equalsIndex).trim().replace("\uFEFF", "");
        String value = line.substring(equalsIndex + 1).trim();

        if (key.isEmpty() || System.getenv().containsKey(key) || System.getProperty(key) != null) {
            return;
        }

        System.setProperty(key, stripMatchingQuotes(value));
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
