package com.modsync;

import java.util.Locale;

public enum CategoryType {
    MOD("mods", "mods", true),
    RESOURCEPACK("resourcepacks", "resourcepacks", false),
    SHADERPACK("shaderpacks", "shaderpacks", false),
    CONFIG("configs", "config", false),
    OPTIONAL_CLIENT("optional_client", "mods", true),
    TACZ("tacz", "tacz", false);

    private final String repositoryFolder;
    private final String clientFolder;
    private final boolean defaultRestartRequired;

    CategoryType(String repositoryFolder, String clientFolder, boolean defaultRestartRequired) {
        this.repositoryFolder = repositoryFolder;
        this.clientFolder = clientFolder;
        this.defaultRestartRequired = defaultRestartRequired;
    }

    public String getRepositoryFolder() {
        return repositoryFolder;
    }

    public String getClientFolder() {
        return clientFolder;
    }

    public boolean isDefaultRestartRequired() {
        return defaultRestartRequired;
    }

    public String getHttpSegment() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CategoryType fromHttpSegment(String value) {
        for (CategoryType type : values()) {
            if (type.getHttpSegment().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown category segment: " + value);
    }

    public static CategoryType fromRepositoryFolder(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return null;
        }
        String normalized = folderName.toLowerCase(Locale.ROOT).trim();
        for (CategoryType type : values()) {
            if (type.repositoryFolder.equalsIgnoreCase(normalized) || type.clientFolder.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }
}
