package com.modsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManifestGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ManifestGenerator() {
    }

    public static ManifestData generateManifest() {
        Set<String> skipped = ConfigManager.skipFileExtensions();
        List<String> nameOnlyPaths = ConfigManager.nameOnlyMatchPaths();
        Map<CategoryType, Path> roots = new EnumMap<>(CategoryType.class);

        for (CategoryType category : CategoryType.values()) {
            if (!ConfigManager.isCategoryEnabled(category)) {
                continue;
            }
            roots.put(category, FileUtils.resolveServerSourceRoot(category));
        }

        ManifestData data = generateManifest(roots, skipped, nameOnlyPaths, FileUtils.configDir().resolve("modsync-manifest.json").normalize());
        LoggerUtils.info("Generated manifest with " + data.getEntries().size() + " entries");
        return data;
    }

    static ManifestData generateManifest(Map<CategoryType, Path> roots, Set<String> skippedExtensions, Path manifestCopyPath) {
        return generateManifest(roots, skippedExtensions, List.of(), manifestCopyPath);
    }

    static ManifestData generateManifest(Map<CategoryType, Path> roots, Set<String> skippedExtensions, List<String> nameOnlyMatchPaths, Path manifestCopyPath) {
        ManifestData data = new ManifestData();
        data.setGeneratedAt(System.currentTimeMillis());

        List<ManifestEntry> entries = new ArrayList<>();
        for (Map.Entry<CategoryType, Path> rootEntry : roots.entrySet()) {
            CategoryType category = rootEntry.getKey();
            Path root = rootEntry.getValue();
            if (!Files.exists(root)) {
                continue;
            }

            try (FileHashCache.ScopeSession hashCache = FileHashCache.openServerScope(category)) {
                Files.walk(root)
                        .filter(Files::isRegularFile)
                        .filter(path -> shouldIncludeInManifest(category, path))
                        .filter(path -> !FileUtils.isSkippedFile(category, path, skippedExtensions))
                        .forEach(path -> entries.add(createEntry(category, root, path, hashCache, nameOnlyMatchPaths)));
            } catch (IOException exception) {
                LoggerUtils.error("Failed generating manifest for " + category, exception);
            }
        }

        data.setEntries(entries);
        writeManifestCopy(data, manifestCopyPath);
        return data;
    }

    public static String toJson(ManifestData data) {
        return GSON.toJson(data);
    }

    public static ManifestData fromJson(String json) {
        return normalizeManifestData(GSON.fromJson(json, ManifestData.class));
    }

    public static List<ManifestEntry> entriesFromJson(String json) {
        ManifestEntry[] entries = GSON.fromJson(json, ManifestEntry[].class);
        List<ManifestEntry> list = new ArrayList<>();
        if (entries != null) {
            for (ManifestEntry entry : entries) {
                if (isUsableEntry(entry)) {
                    list.add(entry);
                }
            }
        }
        return list;
    }

    public static String entriesToJson(List<ManifestEntry> entries) {
        return GSON.toJson(entries);
    }

    static ManifestData normalizeManifestData(ManifestData data) {
        ManifestData normalized = data == null ? new ManifestData() : data;
        if (normalized.getEntries() == null) {
            normalized.setEntries(new ArrayList<>());
        } else {
            normalized.setEntries(normalized.getEntries().stream()
                    .filter(ManifestGenerator::isUsableEntry)
                    .toList());
        }
        return normalized;
    }

    private static boolean isUsableEntry(ManifestEntry entry) {
        return entry != null
                && entry.getCategory() != null
                && entry.getRelativePath() != null
                && !entry.getRelativePath().isBlank()
                && entry.getSha256() != null
                && !entry.getSha256().isBlank();
    }

    private static boolean shouldIncludeInManifest(CategoryType category, Path file) {
        return true;
    }

    private static ManifestEntry createEntry(CategoryType category, Path root, Path file, FileHashCache.ScopeSession hashCache, List<String> nameOnlyMatchPaths) {
        try {
            String relativePath = FileUtils.toRelativeUnixPath(root, file);
            FileHashCache.FileFingerprint fingerprint = hashCache.describe(file, relativePath);
            ManifestEntry entry = new ManifestEntry(
                    category,
                    relativePath,
                    file.getFileName().toString(),
                    fingerprint.size(),
                    fingerprint.sha256(),
                    true,
                    category.isDefaultRestartRequired(),
                    "",
                    fingerprint.sha1()
            );
            entry.setMatchByNameOnly(isNameOnlyPath(category, relativePath, nameOnlyMatchPaths));
            return entry;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build manifest entry for " + file, exception);
        }
    }

    private static boolean isNameOnlyPath(CategoryType category, String relativePath, List<String> nameOnlyMatchPaths) {
        String categoryLower = category.name().toLowerCase();
        String pathLower = relativePath.toLowerCase();
        for (String pattern : nameOnlyMatchPaths) {
            String p = pattern.trim().toLowerCase();
            if (p.isEmpty()) continue;
            if (categoryLower.equals(p)) return true;
            if (pathLower.equals(p) || pathLower.startsWith(p + "/")) return true;
        }
        return false;
    }

    private static void writeManifestCopy(ManifestData data, Path file) {
        try {
            FileUtils.ensureParentExists(file);
            Files.writeString(file, toJson(data), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LoggerUtils.error("Failed to write generated manifest copy", exception);
        }
    }
}
