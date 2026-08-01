package com.modsync;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ModConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConfigManager {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.ConfigValue<String> LANGUAGE;
    private static final ForgeConfigSpec.BooleanValue ENABLE_HTTP_SERVER;
    private static final ForgeConfigSpec.IntValue SERVER_HTTP_PORT;
    private static final ForgeConfigSpec.ConfigValue<String> SERVER_HTTP_BIND;
    private static final ForgeConfigSpec.ConfigValue<String> PUBLIC_HTTP_BASE_URL;
    private static final ForgeConfigSpec.BooleanValue ENABLE_MODS_SYNC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_RESOURCEPACKS_SYNC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_SHADERPACKS_SYNC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_CONFIG_SYNC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_OPTIONAL_CLIENT_SYNC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_TACZ_SYNC;
    private static final ForgeConfigSpec.ConfigValue<String> OPTIONAL_CLIENT_TARGET;
    private static final ForgeConfigSpec.IntValue DOWNLOAD_THREADS;
    private static final ForgeConfigSpec.IntValue RETRY_COUNT;
    private static final ForgeConfigSpec.BooleanValue ENABLE_MODRINTH_CDN_FALLBACK;
    private static final ForgeConfigSpec.IntValue HANDSHAKE_TIMEOUT_SECONDS;
    private static final ForgeConfigSpec.BooleanValue AUTO_KICK_ON_HANDSHAKE_TIMEOUT;
    private static final ForgeConfigSpec.BooleanValue VERIFY_HASH_AFTER_DOWNLOAD;
    private static final ForgeConfigSpec.BooleanValue DELETE_INVALID_FILES;
    private static final ForgeConfigSpec.BooleanValue USE_TEMP_FILES;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SKIP_FILE_EXTENSIONS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SYNC_FOLDERS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> NAME_ONLY_MATCH_PATHS;
    private static final ForgeConfigSpec.BooleanValue LOG_TO_FILE;
    private static final ForgeConfigSpec.ConfigValue<String> LOG_FILE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        LANGUAGE = builder.define("language", "en");
        ENABLE_HTTP_SERVER = builder.define("enable_http_server", true);
        SERVER_HTTP_PORT = builder.defineInRange("server_http_port", 8080, 1, 65535);
        SERVER_HTTP_BIND = builder.define("server_http_bind", "0.0.0.0");
        PUBLIC_HTTP_BASE_URL = builder.define("public_http_base_url", "");

        ENABLE_MODS_SYNC = builder.define("enable_mods_sync", true);
        ENABLE_RESOURCEPACKS_SYNC = builder.define("enable_resourcepacks_sync", true);
        ENABLE_SHADERPACKS_SYNC = builder.define("enable_shaderpacks_sync", true);
        ENABLE_CONFIG_SYNC = builder.define("enable_config_sync", true);
        ENABLE_OPTIONAL_CLIENT_SYNC = builder.define("enable_optional_client_sync", true);
        ENABLE_TACZ_SYNC = builder.define("enable_tacz_sync", true);
        OPTIONAL_CLIENT_TARGET = builder.define("optional_client_target", "mods");

        DOWNLOAD_THREADS = builder.defineInRange("download_threads", 4, 1, 16);
        RETRY_COUNT = builder.defineInRange("retry_count", 3, 0, 10);
        ENABLE_MODRINTH_CDN_FALLBACK = builder.define("enable_modrinth_cdn_fallback", true);
        HANDSHAKE_TIMEOUT_SECONDS = builder.defineInRange("handshake_timeout_seconds", 120, 5, 600);
        AUTO_KICK_ON_HANDSHAKE_TIMEOUT = builder.define("auto_kick_on_handshake_timeout", false);
        VERIFY_HASH_AFTER_DOWNLOAD = builder.define("verify_hash_after_download", true);
        DELETE_INVALID_FILES = builder.define("delete_invalid_files", true);
        USE_TEMP_FILES = builder.define("use_temp_files", true);

        SKIP_FILE_EXTENSIONS = builder.defineList("skip_file_extensions",
                List.of(".txt", ".log", ".tmp", ".bak"), value -> value instanceof String);

        SYNC_FOLDERS = builder.defineList("sync_folders",
                List.of(
                        "sync_repo/mods",
                        "sync_repo/resourcepacks",
                        "sync_repo/shaderpacks",
                        "sync_repo/configs",
                    "sync_repo/optional_client",
                    "tacz"
                ), value -> value instanceof String);

        NAME_ONLY_MATCH_PATHS = builder.defineList("name_only_match_paths",
                List.of("tacz"), value -> value instanceof String);

        LOG_TO_FILE = builder.define("log_to_file", true);
        LOG_FILE = builder.define("log_file", "logs/modsync.log");
        SPEC = builder.build();
    }

    private ConfigManager() {
    }

    public static void register() {
        ModContainer container = ModList.get()
                .getModContainerById(ModSync.MOD_ID)
                .orElseThrow(() -> new IllegalStateException("Unable to resolve ModSync mod container during config registration"));
        container.addConfig(new ModConfig(ModConfig.Type.COMMON, SPEC, container, "modsync.toml"));
    }

    public static String language() {
        return LANGUAGE.get();
    }

    public static boolean enableHttpServer() {
        return ENABLE_HTTP_SERVER.get();
    }

    public static int serverHttpPort() {
        return SERVER_HTTP_PORT.get();
    }

    public static String serverHttpBind() {
        return SERVER_HTTP_BIND.get();
    }

    public static String publicHttpBaseUrl() {
        return PUBLIC_HTTP_BASE_URL.get().trim();
    }

    public static boolean isCategoryEnabled(CategoryType category) {
        return switch (category) {
            case MOD -> ENABLE_MODS_SYNC.get();
            case RESOURCEPACK -> ENABLE_RESOURCEPACKS_SYNC.get();
            case SHADERPACK -> ENABLE_SHADERPACKS_SYNC.get();
            case CONFIG -> ENABLE_CONFIG_SYNC.get();
            case OPTIONAL_CLIENT -> ENABLE_OPTIONAL_CLIENT_SYNC.get();
            case TACZ -> ENABLE_TACZ_SYNC.get();
        };
    }

    public static int downloadThreads() {
        return DOWNLOAD_THREADS.get();
    }

    public static String optionalClientTarget() {
        return OPTIONAL_CLIENT_TARGET.get();
    }

    public static int retryCount() {
        return RETRY_COUNT.get();
    }

    public static boolean enableModrinthCdnFallback() {
        return ENABLE_MODRINTH_CDN_FALLBACK.get();
    }

    public static boolean enableModrinthCdnFallbackOrDefault() {
        try {
            return ENABLE_MODRINTH_CDN_FALLBACK.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public static int handshakeTimeoutSeconds() {
        return HANDSHAKE_TIMEOUT_SECONDS.get();
    }

    public static boolean autoKickOnHandshakeTimeout() {
        return AUTO_KICK_ON_HANDSHAKE_TIMEOUT.get();
    }

    public static boolean verifyHashAfterDownload() {
        return VERIFY_HASH_AFTER_DOWNLOAD.get();
    }

    public static boolean deleteInvalidFiles() {
        return DELETE_INVALID_FILES.get();
    }

    public static boolean useTempFiles() {
        return USE_TEMP_FILES.get();
    }

    public static Set<String> skipFileExtensions() {
        return new HashSet<>(SKIP_FILE_EXTENSIONS.get().stream().map(String::valueOf).toList());
    }

    public static List<String> syncFolders() {
        return SYNC_FOLDERS.get().stream().map(String::valueOf).toList();
    }

    public static List<String> nameOnlyMatchPaths() {
        return NAME_ONLY_MATCH_PATHS.get().stream().map(String::valueOf).toList();
    }

    public static boolean logToFile() {
        return LOG_TO_FILE.get();
    }

    public static Path logFile() {
        return FileUtils.gameDir().resolve(LOG_FILE.get()).normalize();
    }

    public static Path configPath() {
        return FileUtils.configDir().resolve("modsync.toml").normalize();
    }

    public static synchronized void reloadFromDisk() throws IOException {
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            LANGUAGE.set(fileConfig.getOrElse("language", LANGUAGE.get()));
            ENABLE_HTTP_SERVER.set(fileConfig.getOrElse("enable_http_server", ENABLE_HTTP_SERVER.get()));
            SERVER_HTTP_PORT.set(fileConfig.getOrElse("server_http_port", SERVER_HTTP_PORT.get()));
            SERVER_HTTP_BIND.set(fileConfig.getOrElse("server_http_bind", SERVER_HTTP_BIND.get()));
            PUBLIC_HTTP_BASE_URL.set(fileConfig.getOrElse("public_http_base_url", PUBLIC_HTTP_BASE_URL.get()));
            ENABLE_MODS_SYNC.set(fileConfig.getOrElse("enable_mods_sync", ENABLE_MODS_SYNC.get()));
            ENABLE_RESOURCEPACKS_SYNC.set(fileConfig.getOrElse("enable_resourcepacks_sync", ENABLE_RESOURCEPACKS_SYNC.get()));
            ENABLE_SHADERPACKS_SYNC.set(fileConfig.getOrElse("enable_shaderpacks_sync", ENABLE_SHADERPACKS_SYNC.get()));
            ENABLE_CONFIG_SYNC.set(fileConfig.getOrElse("enable_config_sync", ENABLE_CONFIG_SYNC.get()));
            ENABLE_OPTIONAL_CLIENT_SYNC.set(fileConfig.getOrElse("enable_optional_client_sync", ENABLE_OPTIONAL_CLIENT_SYNC.get()));
            ENABLE_TACZ_SYNC.set(fileConfig.getOrElse("enable_tacz_sync", ENABLE_TACZ_SYNC.get()));
            OPTIONAL_CLIENT_TARGET.set(fileConfig.getOrElse("optional_client_target", OPTIONAL_CLIENT_TARGET.get()));
            DOWNLOAD_THREADS.set(fileConfig.getOrElse("download_threads", DOWNLOAD_THREADS.get()));
            RETRY_COUNT.set(fileConfig.getOrElse("retry_count", RETRY_COUNT.get()));
            ENABLE_MODRINTH_CDN_FALLBACK.set(fileConfig.getOrElse("enable_modrinth_cdn_fallback", ENABLE_MODRINTH_CDN_FALLBACK.get()));
            HANDSHAKE_TIMEOUT_SECONDS.set(fileConfig.getOrElse("handshake_timeout_seconds", HANDSHAKE_TIMEOUT_SECONDS.get()));
            AUTO_KICK_ON_HANDSHAKE_TIMEOUT.set(fileConfig.getOrElse("auto_kick_on_handshake_timeout", AUTO_KICK_ON_HANDSHAKE_TIMEOUT.get()));
            VERIFY_HASH_AFTER_DOWNLOAD.set(fileConfig.getOrElse("verify_hash_after_download", VERIFY_HASH_AFTER_DOWNLOAD.get()));
            DELETE_INVALID_FILES.set(fileConfig.getOrElse("delete_invalid_files", DELETE_INVALID_FILES.get()));
            USE_TEMP_FILES.set(fileConfig.getOrElse("use_temp_files", USE_TEMP_FILES.get()));
            SKIP_FILE_EXTENSIONS.set(asStringList(fileConfig.getOrElse("skip_file_extensions", SKIP_FILE_EXTENSIONS.get())));
            SYNC_FOLDERS.set(asStringList(fileConfig.getOrElse("sync_folders", SYNC_FOLDERS.get())));
            NAME_ONLY_MATCH_PATHS.set(asStringList(fileConfig.getOrElse("name_only_match_paths", NAME_ONLY_MATCH_PATHS.get())));
            LOG_TO_FILE.set(fileConfig.getOrElse("log_to_file", LOG_TO_FILE.get()));
            LOG_FILE.set(fileConfig.getOrElse("log_file", LOG_FILE.get()));
        }
    }

    public static synchronized void setServerHttpPortAndSave(int port) throws IOException {
        SERVER_HTTP_PORT.set(port);
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            fileConfig.set("server_http_port", port);
            fileConfig.save();
        }
    }

    public static synchronized void setPublicHttpBaseUrlAndSave(String baseUrl) throws IOException {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        PUBLIC_HTTP_BASE_URL.set(normalized);
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            fileConfig.set("public_http_base_url", normalized);
            fileConfig.save();
        }
    }

    public static synchronized void setHandshakeTimeoutSecondsAndSave(int seconds) throws IOException {
        HANDSHAKE_TIMEOUT_SECONDS.set(seconds);
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            fileConfig.set("handshake_timeout_seconds", seconds);
            fileConfig.save();
        }
    }

    public static synchronized void setEnableModrinthCdnFallbackAndSave(boolean enabled) throws IOException {
        ENABLE_MODRINTH_CDN_FALLBACK.set(enabled);
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            fileConfig.set("enable_modrinth_cdn_fallback", enabled);
            fileConfig.save();
        }
    }

    public static synchronized void setAutoKickOnHandshakeTimeoutAndSave(boolean enabled) throws IOException {
        AUTO_KICK_ON_HANDSHAKE_TIMEOUT.set(enabled);
        Path path = configPath();
        try (CommentedFileConfig fileConfig = CommentedFileConfig.builder(path).sync().autosave().build()) {
            fileConfig.load();
            fileConfig.set("auto_kick_on_handshake_timeout", enabled);
            fileConfig.save();
        }
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
