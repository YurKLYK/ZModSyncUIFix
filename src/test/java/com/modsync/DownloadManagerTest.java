package com.modsync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadManagerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SyncIssueState.clear();
        RestartState.resetForTests();
    }

    @Test
    void describeExceptionMapsTimeoutToFriendlyMessage() {
        assertEquals("network timeout", DownloadManager.describeException(new SocketTimeoutException("Read timed out")));
    }

    @Test
    void describeExceptionFallsBackToExceptionMessage() {
        assertEquals("HTTP 404 while downloading mods/a.jar",
                DownloadManager.describeException(new IOException("HTTP 404 while downloading mods/a.jar")));
    }

    @Test
    void describeExceptionHandlesNull() {
        assertEquals("unknown error", DownloadManager.describeException(null));
    }

    @Test
    void buildSingleDownloadFailureMessageUsesRelativePath() {
        assertEquals(
                "Failed to download mods/example.jar: network timeout",
                DownloadManager.buildSingleDownloadFailureMessage("mods/example.jar", new SocketTimeoutException("timeout"))
        );
    }

    @Test
    void buildDownloadBatchFailureMessageIncludesCount() {
        assertEquals(
                "Download step failed for 3 file(s). Check the log panel for details.",
                DownloadManager.buildDownloadBatchFailureMessage(3)
        );
    }

    @Test
    void buildPostDownloadVerificationFailureMessageIncludesCount() {
        assertEquals(
                "Download completed, but post-download verification still failed for 2 file(s). Check the log panel for details.",
                DownloadManager.buildPostDownloadVerificationFailureMessage(2)
        );
    }

    @Test
    void hasSufficientDiskSpaceAllowsSmallRequiredSize() {
        ManifestEntry entry = new ManifestEntry(CategoryType.MOD, "mods/example.jar", "example.jar", 16L, "abc", true, true, "");

        assertTrue(DownloadManager.hasSufficientDiskSpace(List.of(entry), ignored -> tempDir.resolve("example.jar")));
    }

    @Test
    void hasSufficientDiskSpaceRejectsImpossiblyLargeRequiredSize() {
        ManifestEntry entry = new ManifestEntry(CategoryType.MOD, "mods/example.jar", "example.jar", Long.MAX_VALUE / 2, "abc", true, true, "");

        assertFalse(DownloadManager.hasSufficientDiskSpace(List.of(entry), ignored -> tempDir.resolve("example.jar")));
    }

    @Test
    void finalizeDownloadMovesVerifiedTempFileIntoTarget() throws Exception {
        Path downloadFile = tempDir.resolve("example.jar.modsync.tmp");
        Path targetFile = tempDir.resolve("example.jar");
        Files.writeString(downloadFile, "downloaded-binary");

        ManifestEntry entry = entry("mods/example.jar", downloadFile);

        DownloadManager.finalizeDownload(downloadFile, targetFile, entry, true, true, true);

        assertFalse(Files.exists(downloadFile));
        assertTrue(Files.exists(targetFile));
        assertEquals("downloaded-binary", Files.readString(targetFile));
    }

    @Test
    void finalizeDownloadDeletesInvalidTempFileWhenConfigured() throws Exception {
        Path downloadFile = tempDir.resolve("example.jar.modsync.tmp");
        Path targetFile = tempDir.resolve("example.jar");
        Files.writeString(downloadFile, "downloaded-binary");

        ManifestEntry entry = new ManifestEntry(CategoryType.MOD, "mods/example.jar", "example.jar", Files.size(downloadFile), "deadbeef", true, true, "");

        IOException exception = assertThrows(IOException.class,
                () -> DownloadManager.finalizeDownload(downloadFile, targetFile, entry, true, true, true));

        assertEquals("Invalid SHA-256 for mods/example.jar", exception.getMessage());
        assertFalse(Files.exists(downloadFile));
        assertFalse(Files.exists(targetFile));
    }

    @Test
    void finalizeDownloadKeepsInvalidTempFileWhenDeletionDisabled() throws Exception {
        Path downloadFile = tempDir.resolve("example.jar.modsync.tmp");
        Path targetFile = tempDir.resolve("example.jar");
        Files.writeString(downloadFile, "downloaded-binary");

        ManifestEntry entry = new ManifestEntry(CategoryType.MOD, "mods/example.jar", "example.jar", Files.size(downloadFile), "deadbeef", true, true, "");

        IOException exception = assertThrows(IOException.class,
                () -> DownloadManager.finalizeDownload(downloadFile, targetFile, entry, true, false, true));

        assertEquals("Invalid SHA-256 for mods/example.jar", exception.getMessage());
        assertTrue(Files.exists(downloadFile));
        assertFalse(Files.exists(targetFile));
    }

    @Test
    void startDownloadsCompletesSuccessfulQueueMarksRestartAndRunsCompletionAction() throws Exception {
        byte[] body = "downloaded-binary".getBytes();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mods/example.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            Path targetRoot = tempDir.resolve("downloads");
            Path targetFile = targetRoot.resolve("mods/example.jar");
            ManifestEntry entry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/example.jar",
                    "example.jar",
                    body.length,
                    HashUtils.sha256(writeTempFile(body)),
                    true,
                    true,
                    "http://127.0.0.1:" + port + "/mods/example.jar"
            );

            CountDownLatch completion = new CountDownLatch(1);
            DownloadManager manager = DownloadManager.getInstance();
            manager.startDownloads(List.of(entry), completion::countDown, item -> targetFile, 1, 0, true, true, true);

            assertTrue(completion.await(10, TimeUnit.SECONDS));
            awaitInactive(manager);

            assertEquals(1, manager.getTotalTasks());
            assertEquals(1, manager.getCompletedTasks());
            assertTrue(Files.exists(targetFile));
            assertEquals("downloaded-binary", Files.readString(targetFile));
            assertTrue(RestartState.isRestartRequired());
            assertEquals(List.of("DOWNLOADED:MOD:mods/example.jar"),
                    RestartState.snapshotChanges().stream()
                            .map(change -> change.type().name() + ":" + change.category().name() + ":" + change.relativePath())
                            .toList());
            assertFalse(SyncIssueState.hasIssue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startDownloadsFallsBackToCurrentServerHostWhenPrimaryDownloadUrlIsRefused() throws Exception {
        byte[] body = "fallback-download".getBytes();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/files/mod/mods/example.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            ClientSyncContext.setCurrentServerId("127.0.0.1:26683");
            ClientSyncContext.setCurrentServerHttpPort(port);

            Path targetFile = tempDir.resolve("downloads/mods/example.jar");
            ManifestEntry entry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/example.jar",
                    "example.jar",
                    body.length,
                    HashUtils.sha256(writeTempFile("fallback.bin", body)),
                    true,
                    true,
                    "http://127.0.0.1:1/files/mod/mods/example.jar"
            );

            CountDownLatch completion = new CountDownLatch(1);
            DownloadManager manager = DownloadManager.getInstance();
            manager.startDownloads(List.of(entry), completion::countDown, item -> targetFile, 1, 0, true, true, true);

            assertTrue(completion.await(10, TimeUnit.SECONDS));
            awaitInactive(manager);

            assertTrue(Files.exists(targetFile));
            assertEquals("fallback-download", Files.readString(targetFile));
            assertFalse(SyncIssueState.hasIssue());
        } finally {
            server.stop(0);
            ClientSyncContext.clear();
        }
    }

    @Test
    void startDownloadsSetsIssueStateAndSkipsCompletionOnFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mods/missing.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            Path targetFile = tempDir.resolve("downloads/mods/missing.jar");
            ManifestEntry entry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/missing.jar",
                    "missing.jar",
                    1L,
                    "deadbeef",
                    true,
                    true,
                    "http://127.0.0.1:" + port + "/mods/missing.jar"
            );

            AtomicBoolean completionCalled = new AtomicBoolean(false);
            DownloadManager manager = DownloadManager.getInstance();
            manager.startDownloads(List.of(entry), () -> completionCalled.set(true), item -> targetFile, 1, 0, true, true, true);

            awaitInactive(manager);

            assertFalse(completionCalled.get());
            assertEquals(1, manager.getTotalTasks());
            assertEquals(0, manager.getCompletedTasks());
            assertFalse(Files.exists(targetFile));
            assertEquals("Download step failed for 1 file(s). Check the log panel for details.", SyncIssueState.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startDownloadsHandlesMixedSuccessAndFailureQueue() throws Exception {
        byte[] okBody = "mixed-success".getBytes();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mods/ok.jar", exchange -> {
            exchange.sendResponseHeaders(200, okBody.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(okBody);
            }
        });
        server.createContext("/mods/fail.jar", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            Path successTarget = tempDir.resolve("downloads/mods/ok.jar");
            Path failedTarget = tempDir.resolve("downloads/mods/fail.jar");

            ManifestEntry successEntry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/ok.jar",
                    "ok.jar",
                    okBody.length,
                    HashUtils.sha256(writeTempFile("ok.bin", okBody)),
                    true,
                    true,
                    "http://127.0.0.1:" + port + "/mods/ok.jar"
            );
            ManifestEntry failedEntry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/fail.jar",
                    "fail.jar",
                    1L,
                    "deadbeef",
                    true,
                    true,
                    "http://127.0.0.1:" + port + "/mods/fail.jar"
            );

            AtomicBoolean completionCalled = new AtomicBoolean(false);
            DownloadManager manager = DownloadManager.getInstance();
            manager.startDownloads(
                    List.of(successEntry, failedEntry),
                    () -> completionCalled.set(true),
                    item -> item.getRelativePath().endsWith("ok.jar") ? successTarget : failedTarget,
                    1,
                    0,
                    true,
                    true,
                    true
            );

            awaitInactive(manager);

            assertFalse(completionCalled.get());
            assertEquals(2, manager.getTotalTasks());
            assertEquals(1, manager.getCompletedTasks());
            assertTrue(Files.exists(successTarget));
            assertFalse(Files.exists(failedTarget));
            assertEquals("mixed-success", Files.readString(successTarget));
            assertEquals("Download step failed for 1 file(s). Check the log panel for details.", SyncIssueState.getMessage());
            assertTrue(RestartState.isRestartRequired());
            assertEquals(List.of("DOWNLOADED:MOD:mods/ok.jar"),
                    RestartState.snapshotChanges().stream()
                            .map(change -> change.type().name() + ":" + change.category().name() + ":" + change.relativePath())
                            .toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startDownloadsDeletesTempFileWhenHttpSucceedsButHashIsInvalid() throws Exception {
        byte[] body = "corrupted-payload".getBytes();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mods/bad.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            Path targetFile = tempDir.resolve("downloads/mods/bad.jar");
            Path tempFile = targetFile.resolveSibling("bad.jar.modsync.tmp");

            ManifestEntry entry = new ManifestEntry(
                    CategoryType.MOD,
                    "mods/bad.jar",
                    "bad.jar",
                    body.length,
                    "deadbeef",
                    true,
                    true,
                    "http://127.0.0.1:" + port + "/mods/bad.jar"
            );

            AtomicBoolean completionCalled = new AtomicBoolean(false);
            DownloadManager manager = DownloadManager.getInstance();
            manager.startDownloads(
                    List.of(entry),
                    () -> completionCalled.set(true),
                    item -> targetFile,
                    1,
                    0,
                    true,
                    true,
                    true
            );

            awaitInactive(manager);

            assertFalse(completionCalled.get());
            assertEquals(1, manager.getTotalTasks());
            assertEquals(0, manager.getCompletedTasks());
            assertFalse(Files.exists(targetFile));
            assertFalse(Files.exists(tempFile));
            assertEquals("Download step failed for 1 file(s). Check the log panel for details.", SyncIssueState.getMessage());
            assertFalse(RestartState.isRestartRequired());
            assertEquals(List.of(), RestartState.snapshotChanges());
        } finally {
            server.stop(0);
        }
    }

    private Path writeTempFile(byte[] bytes) throws Exception {
        return writeTempFile("expected.bin", bytes);
    }

    private Path writeTempFile(String name, byte[] bytes) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static void awaitInactive(DownloadManager manager) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (manager.isActive() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        if (manager.isActive()) {
            throw new AssertionError("DownloadManager did not become inactive in time");
        }
    }

    private static ManifestEntry entry(String relativePath, Path file) throws Exception {
        String fileName = file.getFileName().toString().replace(".modsync.tmp", "");
        return new ManifestEntry(
                CategoryType.MOD,
                relativePath,
                fileName,
                Files.size(file),
                HashUtils.sha256(file),
                true,
                true,
                ""
        );
    }
}
