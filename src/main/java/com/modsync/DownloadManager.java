package com.modsync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class DownloadManager {
    private static final DownloadManager INSTANCE = new DownloadManager();
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 20_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 120_000;
    private static final int DOWNLOAD_BUFFER_SIZE = 65536;
    private static final long MIN_FREE_DISK_SPACE_BUFFER_BYTES = 200L * 1024 * 1024;

    private volatile ExecutorService executorService;
    private volatile int totalTasks;
    private final AtomicInteger completedTasks = new AtomicInteger();
    private volatile boolean active;

    private DownloadManager() {
    }

    public static DownloadManager getInstance() {
        return INSTANCE;
    }

    public synchronized void startDownloads(List<ManifestEntry> entries) {
        startDownloads(entries, null);
    }

    public synchronized void startDownloads(List<ManifestEntry> entries, Runnable completionAction) {
        startDownloads(
                entries,
                completionAction,
                entry -> FileUtils.resolveSafeChild(
                        FileUtils.resolveClientTargetRoot(entry.getCategory(), ClientSyncContext.getCurrentServerId()),
                        entry.getRelativePath()),
                ConfigManager.downloadThreads(),
                ConfigManager.retryCount(),
                ConfigManager.verifyHashAfterDownload(),
                ConfigManager.deleteInvalidFiles(),
                ConfigManager.useTempFiles()
        );
    }

    synchronized void startDownloads(List<ManifestEntry> entries,
                                     Runnable completionAction,
                                     Function<ManifestEntry, Path> targetResolver,
                                     int downloadThreads,
                                     int retryCount,
                                     boolean verifyHashAfterDownload,
                                     boolean deleteInvalidFiles,
                                     boolean useTempFiles) {
        if (entries.isEmpty()) {
            LoggerUtils.info("No downloads required");
            if (completionAction != null) {
                completionAction.run();
            }
            return;
        }

        if (!hasSufficientDiskSpace(entries, targetResolver)) {
            String message = buildInsufficientDiskSpaceMessage();
            LoggerUtils.error(message, null);
            SyncIssueState.set(message);
            return;
        }

        shutdownExecutor();
        this.executorService = Executors.newFixedThreadPool(downloadThreads);
        this.totalTasks = entries.size();
        this.completedTasks.set(0);
        this.active = true;

        AtomicBoolean restartRequired = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failedDownloads = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (ManifestEntry entry : entries) {
            LoggerUtils.info("Queued: " + entry.getCategory().name() + " / " + entry.getRelativePath());
            Path defaultTarget = targetResolver.apply(entry);
            SelfUpdateCoordinator.SelfUpdatePlan selfUpdatePlan = SelfUpdateCoordinator.planForEntry(entry, defaultTarget);
            DownloadTask task = new DownloadTask(entry, selfUpdatePlan.downloadTargetPath());

            futures.add(CompletableFuture.runAsync(() -> {
                if (downloadWithRetry(task, retryCount, verifyHashAfterDownload, deleteInvalidFiles, useTempFiles)) {
                    SelfUpdateCoordinator.registerDownloadedUpdate(selfUpdatePlan);
                    if (entry.isRestartRequired()) {
                        restartRequired.set(true);
                        RestartState.recordDownloaded(entry);
                    }
                    completedTasks.incrementAndGet();
                } else {
                    failed.set(true);
                    failedDownloads.incrementAndGet();
                }
            }, executorService));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> {
                    active = false;
                    shutdownExecutor();
                    if (throwable != null) {
                        LoggerUtils.error("Download queue completed with failures", throwable);
                    } else {
                        LoggerUtils.info("Download queue complete");
                    }
                    if (failed.get()) {
                        SyncIssueState.set(buildDownloadBatchFailureMessage(failedDownloads.get()));
                    }
                    if (restartRequired.get()) {
                        RestartState.markRestartRequired();
                        LoggerUtils.info("Restart requested after synchronized downloads");
                    }
                    if (throwable == null && !failed.get() && completionAction != null) {
                        completionAction.run();
                    }
                });
    }

    public boolean isActive() {
        return active;
    }

    public int getCompletedTasks() {
        return completedTasks.get();
    }

    public int getTotalTasks() {
        return totalTasks;
    }

    private boolean downloadWithRetry(DownloadTask task,
                                      int retryCount,
                                      boolean verifyHashAfterDownload,
                                      boolean deleteInvalidFiles,
                                      boolean useTempFiles) {
        Exception lastFailure = null;
        // Local/direct URLs are tried first (fast LAN transfer). Modrinth's CDN is only
        // consulted afterwards, as a genuine fallback, to avoid paying its network lookup
        // cost on every file when the local server is already serving fine.
        List<String> candidateUrls = ManifestUrlResolver.buildDownloadCandidateUrls(
                task.getEntry(),
                task.getEntry().getDownloadUrl(),
                ClientSyncContext.getCurrentServerId(),
                ClientSyncContext.getCurrentServerHttpPort()
        );
        for (int i = 0; i <= retryCount; i++) {
            task.incrementAttempts();
            for (String candidateUrl : candidateUrls) {
                try {
                    download(task, candidateUrl, verifyHashAfterDownload, deleteInvalidFiles, useTempFiles);
                    task.markCompleted();
                    LoggerUtils.info("Downloaded " + task.getEntry().getRelativePath());
                    return true;
                } catch (Exception exception) {
                    lastFailure = exception;
                }
            }
            LoggerUtils.warn("Download attempt " + task.getAttempts() + " failed for "
                    + task.getEntry().getRelativePath() + ": " + describeException(lastFailure));
        }

        String modrinthUrl = ModrinthApiClient.resolveDownloadUrlIfEnabled(task.getEntry());
        if (modrinthUrl != null && !modrinthUrl.isBlank()) {
            task.incrementAttempts();
            try {
                download(task, modrinthUrl, verifyHashAfterDownload, deleteInvalidFiles, useTempFiles);
                task.markCompleted();
                LoggerUtils.info("Downloaded " + task.getEntry().getRelativePath() + " via Modrinth CDN fallback");
                return true;
            } catch (Exception exception) {
                lastFailure = exception;
                LoggerUtils.warn("Modrinth CDN fallback failed for " + task.getEntry().getRelativePath()
                        + ": " + describeException(lastFailure));
            }
        }

        SyncIssueState.set(buildSingleDownloadFailureMessage(task.getEntry().getRelativePath(), lastFailure));
        return false;
    }

    private void download(DownloadTask task,
                          String downloadUrl,
                          boolean verifyHashAfterDownload,
                          boolean deleteInvalidFiles,
                          boolean useTempFiles) throws IOException {
        ManifestEntry entry = task.getEntry();
        Path targetFile = task.getTargetFile();
        FileUtils.ensureParentExists(targetFile);
        LoggerUtils.info("Downloading: " + downloadUrl);

        Path downloadFile = useTempFiles
                ? targetFile.resolveSibling(targetFile.getFileName() + ".modsync.tmp")
                : targetFile;

        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            // Drain/close the error body so the underlying connection isn't left dangling
            // when many candidate URLs are probed and rejected before a download succeeds.
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    errorStream.readAllBytes();
                }
            } catch (IOException ignored) {
                // best-effort drain only
            } finally {
                connection.disconnect();
            }
            throw new IOException("HTTP " + responseCode + " while downloading " + entry.getRelativePath());
        }

        try (InputStream inputStream = connection.getInputStream();
             OutputStream outputStream = Files.newOutputStream(downloadFile)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
                task.addDownloadedBytes(read);
            }
        }

        finalizeDownload(downloadFile, targetFile, entry, verifyHashAfterDownload, deleteInvalidFiles, useTempFiles);
    }

    private synchronized void shutdownExecutor() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    static String buildDownloadBatchFailureMessage(int failedDownloads) {
        return "Download step failed for " + failedDownloads + " file(s). Check the log panel for details.";
    }

    static String buildInsufficientDiskSpaceMessage() {
        return "Not enough free disk space to download the required files. Free up space and try again.";
    }

    // Guards against a manifest (malicious, misconfigured, or just very large) demanding more
    // storage than is actually available, so a sync attempt fails fast instead of filling the disk.
    static boolean hasSufficientDiskSpace(List<ManifestEntry> entries, Function<ManifestEntry, Path> targetResolver) {
        Map<Path, Long> requiredBytesByStoreRoot = new HashMap<>();
        for (ManifestEntry entry : entries) {
            if (entry.getFileSize() <= 0) {
                continue;
            }
            Path target = targetResolver.apply(entry);
            Path storeRoot = nearestExistingAncestor(target.getParent());
            requiredBytesByStoreRoot.merge(storeRoot, entry.getFileSize(), Long::sum);
        }

        for (Map.Entry<Path, Long> requirement : requiredBytesByStoreRoot.entrySet()) {
            try {
                long usable = Files.getFileStore(requirement.getKey()).getUsableSpace();
                if (usable - MIN_FREE_DISK_SPACE_BUFFER_BYTES < requirement.getValue()) {
                    LoggerUtils.warn("Insufficient disk space near " + requirement.getKey()
                            + ": need " + requirement.getValue() + " bytes, have " + usable + " usable");
                    return false;
                }
            } catch (IOException exception) {
                LoggerUtils.warn("Unable to determine free disk space near " + requirement.getKey()
                        + ": " + exception.getMessage());
            }
        }
        return true;
    }

    private static Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? FileUtils.gameDir() : current;
    }

    static String buildSingleDownloadFailureMessage(String relativePath, Exception exception) {
        return "Failed to download " + relativePath + ": " + describeException(exception);
    }

    static String buildPostDownloadVerificationFailureMessage(int failedEntries) {
        return "Download completed, but post-download verification still failed for "
                + failedEntries
                + " file(s). Check the log panel for details.";
    }

    static List<String> resolveDownloadCandidateUrls(ManifestEntry entry,
                                                     String primaryDownloadUrl,
                                                     String serverAddress,
                                                     int discoveredPort,
                                                     Function<ManifestEntry, String> modrinthResolver) {
        Set<String> urls = new LinkedHashSet<>();
        urls.addAll(ManifestUrlResolver.buildDownloadCandidateUrls(
                entry,
                primaryDownloadUrl,
                serverAddress,
                discoveredPort
        ));
        if (modrinthResolver != null) {
            String modrinthUrl = modrinthResolver.apply(entry);
            if (modrinthUrl != null && !modrinthUrl.isBlank()) {
                urls.add(modrinthUrl);
            }
        }
        return new ArrayList<>(urls);
    }

    static String describeException(Exception exception) {
        if (exception == null) {
            return "unknown error";
        }
        if (exception instanceof SocketTimeoutException) {
            return "network timeout";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static void finalizeDownload(Path downloadFile,
                                 Path targetFile,
                                 ManifestEntry entry,
                                 boolean verifyHashAfterDownload,
                                 boolean deleteInvalidFiles,
                                 boolean useTempFiles) throws IOException {
        if (verifyHashAfterDownload) {
            String hash = HashUtils.sha256(downloadFile);
            if (!hash.equalsIgnoreCase(entry.getSha256())) {
                if (deleteInvalidFiles) {
                    Files.deleteIfExists(downloadFile);
                }
                LoggerUtils.warn("Hash verification failed for " + entry.getRelativePath());
                throw new IOException("Invalid SHA-256 for " + entry.getRelativePath());
            }
        }

        if (useTempFiles) {
            try {
                Files.move(downloadFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(downloadFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
