package com.modsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ActiveModProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object SAVE_LOCK = new Object();

    private ActiveModProfileStore() {
    }

    public static ActiveProfile load() {
        Path file = stateFile();
        if (!Files.exists(file)) {
            return ActiveProfile.empty();
        }

        try {
            ActiveProfile profile = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ActiveProfile.class);
            return profile == null ? ActiveProfile.empty() : profile.normalized();
        } catch (Exception exception) {
            LoggerUtils.warn("Failed to load active mod profile: " + exception.getMessage());
            return ActiveProfile.empty();
        }
    }

    public static void save(String serverId, List<ManifestEntry> entries) {
        ActiveProfile profile = new ActiveProfile(serverId == null ? "" : serverId.trim(), entries == null ? List.of() : List.copyOf(entries));
        synchronized (SAVE_LOCK) {
            Path file = stateFile();
            try {
                FileUtils.ensureParentExists(file);
                Files.writeString(file, GSON.toJson(profile), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                LoggerUtils.error("Failed to save active mod profile", exception);
            }
        }
    }

    public static boolean isActiveServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        return serverId.equals(load().serverId());
    }

    private static Path stateFile() {
        return FileUtils.configDir().resolve("modsync-active-mods.json").normalize();
    }

    public record ActiveProfile(String serverId, List<ManifestEntry> entries) {
        public static ActiveProfile empty() {
            return new ActiveProfile("", List.of());
        }

        public ActiveProfile normalized() {
            return new ActiveProfile(serverId == null ? "" : serverId, entries == null ? List.of() : List.copyOf(entries));
        }
    }
}
