package com.modsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ServerManifestHttpHandler {
    private ServerManifestHttpHandler() {
    }

    public static ManifestData buildPublicManifest(String serverHost) {
        ManifestData manifest = ModSync.getLastManifest();
        if (manifest == null) {
            LoggerUtils.warn("Requested public manifest before cached manifest was ready");
            manifest = new ManifestData();
            manifest.setGeneratedAt(System.currentTimeMillis());
            manifest.setEntries(List.of());
        }
        return buildPublicManifest(manifest, resolvePublicBaseUrl(serverHost));
    }

    static ManifestData buildPublicManifest(ManifestData manifest, String publicBaseUrl) {
        ManifestData source = manifest == null ? new ManifestData() : manifest;
        ManifestData publicManifest = new ManifestData();
        publicManifest.setGeneratedAt(source.getGeneratedAt());

        List<ManifestEntry> entries = source.getEntries().stream().map(entry -> {
            ManifestEntry copy = entry.copy();
            copy.setDownloadUrl(publicBaseUrl + "/files/" + entry.getCategory().getHttpSegment() + "/"
                    + HttpPathCodec.encodeRelativePath(entry.getRelativePath()));
            return copy;
        }).toList();
        publicManifest.setEntries(entries);
        return publicManifest;
    }

    public static byte[] manifestBytes(String serverHost) {
        return ManifestGenerator.toJson(buildPublicManifest(serverHost)).getBytes(StandardCharsets.UTF_8);
    }

    public static ManifestData fetchManifest(String serverAddress) throws IOException {
        return fetchManifest(serverAddress, -1);
    }

    public static ManifestData fetchManifest(String serverAddress, int discoveredPort) throws IOException {
        return fetchManifest(serverAddress, discoveredPort, 10_000, 30_000);
    }

    public static ManifestData fetchManifest(String serverAddress, int discoveredPort, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return fetchManifest(serverAddress, discoveredPort, connectTimeoutMs, readTimeoutMs, ConfigManager.serverHttpPort());
    }

    static ManifestData fetchManifest(String serverAddress,
                                      int discoveredPort,
                                      int connectTimeoutMs,
                                      int readTimeoutMs,
                                      int serverHttpPort) throws IOException {
        String host = ManifestUrlResolver.extractHost(serverAddress);
        return fetchManifestFromCandidates(
                ManifestUrlResolver.buildManifestCandidateUrls(host, discoveredPort, serverHttpPort),
                connectTimeoutMs,
                readTimeoutMs
        );
    }

    static ManifestData fetchManifestFromCandidates(List<String> candidateUrls, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        IOException lastError = null;
        List<String> attemptedUrls = new ArrayList<>();

        for (String url : candidateUrls) {
            attemptedUrls.add(url);
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestMethod("GET");

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("HTTP " + responseCode);
                }

                try (InputStream stream = connection.getInputStream()) {
                    String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    LoggerUtils.info("Manifest fetched from " + url);
                    return ManifestGenerator.fromJson(json);
                }
            } catch (IOException exception) {
                lastError = exception;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        String suffix = lastError == null ? "no response details" : lastError.getMessage();
        throw new IOException("Unable to fetch ModSync manifest from " + String.join(", ", attemptedUrls) + " (" + suffix + ")", lastError);
    }

    public static String extractHost(String serverAddress) {
        return ManifestUrlResolver.extractHost(serverAddress);
    }

    public static String normalizeHost(String host) {
        return ManifestUrlResolver.normalizeHost(host);
    }

    public static String resolvePublicBaseUrl(String serverHost) {
        return ManifestUrlResolver.resolvePublicBaseUrl(ConfigManager.publicHttpBaseUrl(), serverHost, ConfigManager.serverHttpPort());
    }
}
