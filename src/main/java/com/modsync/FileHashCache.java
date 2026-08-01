package com.modsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class FileHashCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CACHE_TYPE = new TypeToken<Map<String, Map<String, CacheEntry>>>() { }.getType();
    private static final Object LOCK = new Object();

    private static Map<String, Map<String, CacheEntry>> scopes = new HashMap<>();
    private static boolean loaded;
    private static Path cachePathOverride;

    private FileHashCache() {
    }

    public static ScopeSession openServerScope(CategoryType category) {
        return new ScopeSession("server:" + category.name());
    }

    public static ScopeSession openClientScope(String serverId, CategoryType category) {
        return new ScopeSession("client:" + FileUtils.sanitizeServerId(serverId) + ":" + category.name());
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }

            Path path = cachePath();
            if (!Files.exists(path)) {
                loaded = true;
                scopes = new HashMap<>();
                return;
            }

            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Map<String, Map<String, CacheEntry>> persisted = GSON.fromJson(reader, CACHE_TYPE);
                scopes = persisted == null ? new HashMap<>() : new HashMap<>(persisted);
            } catch (Exception exception) {
                scopes = new HashMap<>();
                LoggerUtils.warn("Failed to load ModSync file hash cache: " + exception.getMessage());
            }
            loaded = true;
        }
    }

    private static Path cachePath() {
        if (cachePathOverride != null) {
            return cachePathOverride;
        }
        return FileUtils.configDir().resolve("modsync-hash-cache.json").normalize();
    }

    static void resetForTests() {
        synchronized (LOCK) {
            scopes = new HashMap<>();
            loaded = false;
            cachePathOverride = null;
        }
    }

    static void overrideCachePathForTests(Path path) {
        synchronized (LOCK) {
            scopes = new HashMap<>();
            loaded = false;
            cachePathOverride = path == null ? null : path.toAbsolutePath().normalize();
        }
    }

    public static final class ScopeSession implements AutoCloseable {
        private final String scopeKey;
        private final Set<String> seenPaths = new HashSet<>();
        private boolean changed;

        private ScopeSession(String scopeKey) {
            this.scopeKey = scopeKey;
            ensureLoaded();
        }

        public String sha256(Path file, String relativePath) throws IOException {
            return describe(file, relativePath).sha256();
        }

        public FileFingerprint describe(Path file, String relativePath) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            long size = attributes.size();
            long lastModified = attributes.lastModifiedTime().toMillis();

            CacheEntry cached;
            synchronized (LOCK) {
                cached = scopes
                        .getOrDefault(scopeKey, Map.of())
                        .get(relativePath);
                if (cached != null && cached.size == size && cached.lastModified == lastModified
                        && cached.sha256 != null && !cached.sha256.isBlank()
                        && cached.sha1 != null && !cached.sha1.isBlank()) {
                    seenPaths.add(relativePath);
                    return new FileFingerprint(size, cached.sha256, cached.sha1);
                }
            }

            String sha256 = HashUtils.sha256(file);
            String sha1 = HashUtils.sha1(file);
            synchronized (LOCK) {
                scopes.computeIfAbsent(scopeKey, ignored -> new HashMap<>())
                        .put(relativePath, new CacheEntry(size, lastModified, sha256, sha1));
            }
            seenPaths.add(relativePath);
            changed = true;
            return new FileFingerprint(size, sha256, sha1);
        }

        @Override
        public void close() {
            ensureLoaded();

            boolean removedAny = false;
            synchronized (LOCK) {
                Map<String, CacheEntry> scopeEntries = scopes.get(scopeKey);
                if (scopeEntries == null) {
                    return;
                }

                if (scopeEntries.keySet().removeIf(path -> !seenPaths.contains(path))) {
                    removedAny = true;
                }
                if (scopeEntries.isEmpty()) {
                    scopes.remove(scopeKey);
                    removedAny = true;
                }
            }

            if (changed || removedAny) {
                persist();
            }
        }
    }

    private static void persist() {
        // Serialize under lock (fast), then write to disk outside lock (slow I/O)
        String json;
        synchronized (LOCK) {
            json = GSON.toJson(scopes, CACHE_TYPE);
        }
        try {
            Path path = cachePath();
            FileUtils.ensureParentExists(path);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LoggerUtils.warn("Failed to persist ModSync file hash cache: " + exception.getMessage());
        }
    }

    private static final class CacheEntry {
        private long size;
        private long lastModified;
        private String sha256;
        private String sha1;

        private CacheEntry(long size, long lastModified, String sha256, String sha1) {
            this.size = size;
            this.lastModified = lastModified;
            this.sha256 = sha256;
            this.sha1 = sha1;
        }
    }

    public record FileFingerprint(long size, String sha256, String sha1) {
    }
}
