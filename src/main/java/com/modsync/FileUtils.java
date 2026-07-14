package com.modsync;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class FileUtils {
    private FileUtils() {
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize();
    }

    public static Path modSyncClientDir() {
        return gameDir().resolve(".modsync").normalize();
    }

    public static Path resolveServerSourceRoot(CategoryType category) {
        return switch (category) {
            case MOD -> gameDir().resolve("mods").normalize();
            case TACZ -> gameDir().resolve("tacz").normalize();
            case RESOURCEPACK, SHADERPACK, CONFIG, OPTIONAL_CLIENT ->
                    gameDir().resolve("sync_repo").resolve(category.getRepositoryFolder()).normalize();
        };
    }

    public static void ensureServerRepositoryDirectories() throws IOException {
        Path syncRoot = gameDir().resolve("sync_repo").normalize();
        Files.createDirectories(syncRoot);
        for (CategoryType category : CategoryType.values()) {
            if (!ConfigManager.isCategoryEnabled(category) || category == CategoryType.MOD || category == CategoryType.TACZ) {
                continue;
            }
            Files.createDirectories(resolveServerSourceRoot(category));
        }
    }

    public static Path resolveClientTargetRoot(CategoryType category) {
        return resolveClientTargetRoot(category, ClientSyncContext.getCurrentServerId());
    }

    public static Path resolveClientTargetRoot(CategoryType category, String serverId) {
        return switch (category) {
            case CONFIG -> gameDir().resolve("config").normalize();
            case RESOURCEPACK -> gameDir().resolve("resourcepacks").normalize();
            case SHADERPACK -> gameDir().resolve("shaderpacks").normalize();
            case MOD -> rootModsDir();
            case TACZ -> gameDir().resolve("tacz").normalize();
            case OPTIONAL_CLIENT -> resolveSafeChild(gameDir(), ConfigManager.optionalClientTarget());
        };
    }

    public static Path rootModsDir() {
        return gameDir().resolve("mods").normalize();
    }

    public static boolean isProtectedModFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        return fileName.toLowerCase(Locale.ROOT).contains(ModSync.MOD_ID);
    }

    public static boolean isProtectedModPath(Path file) {
        return file != null && isProtectedModFileName(file.getFileName().toString());
    }

    public static String sanitizeServerId(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static Path resolveSafeChild(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Blocked path traversal: " + relativePath);
        }
        return resolved;
    }

    public static String toRelativeUnixPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static boolean isSkippedFile(Path file, Set<String> skippedExtensions) {
        return isSkippedFile(null, file, skippedExtensions);
    }

    public static boolean isSkippedFile(CategoryType category, Path file, Set<String> skippedExtensions) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : skippedExtensions) {
            String normalizedExtension = extension.toLowerCase(Locale.ROOT);
            if (category == CategoryType.CONFIG && ".txt".equals(normalizedExtension)) {
                continue;
            }
            if (fileName.endsWith(normalizedExtension)) {
                return true;
            }
        }
        return false;
    }

    public static void ensureParentExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
