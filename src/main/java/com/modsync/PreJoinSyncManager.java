package com.modsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PreJoinSyncManager {
    record PreJoinSyncPlan(List<ManifestEntry> requiredEntries,
                           boolean alreadySynchronized,
                           boolean downloadRequiredButSkipped,
                           boolean continueImmediately,
                           boolean continueAfterDownloads) {
    }

    private PreJoinSyncManager() {
    }

    public static void startForServer(ServerData serverData, Screen returnScreen) {
        startForServer(serverData, returnScreen, true, true);
    }

    public static void startForServer(ServerData serverData, Screen returnScreen, boolean connectAfterSync) {
        startForServer(serverData, returnScreen, connectAfterSync, true);
    }

    public static void startForServer(ServerData serverData,
                                      Screen returnScreen,
                                      boolean connectAfterSync,
                                      boolean allowDownloads) {
        Minecraft minecraft = Minecraft.getInstance();
        SyncLogBuffer.clear();
        SyncIssueState.clear();
        ClientSyncContext.setCurrentServerId(serverData.ip);
        LoggerUtils.info("Selected server: " + serverData.name + " (" + serverData.ip + ")");
        minecraft.setScreen(new SyncProgressScreen(returnScreen, false,
                () -> continueConnecting(minecraft, returnScreen, serverData)));

        CompletableFuture.runAsync(() -> {
            try {
                LoggerUtils.info("Requesting manifest over HTTP");
                int discoveredPort = MotdMetadataCodec.extractHttpPort(serverData);
                ClientSyncContext.setCurrentServerHttpPort(discoveredPort);
                if (discoveredPort > 0) {
                    LoggerUtils.info("Discovered hidden ModSync HTTP port from MOTD: " + discoveredPort);
                }
                ManifestData manifest = ServerManifestHttpHandler.fetchManifest(serverData.ip, discoveredPort);
                ModSync.setLastManifest(manifest);
                LoggerUtils.info("Manifest received: " + manifest.getEntries().size() + " files");

                // Always rescan on the actual join decision (rather than reusing the up-to-120s-old
                // status-badge cache) so files changed/added outside ModSync are picked up immediately
                // and the player isn't incorrectly told to re-download mods that are already in place.
                List<ManifestEntry> localEntries = ClientFileScanner.scanLocalFiles(serverData.ip);
                ServerSyncStatusCache.cacheLocalEntries(serverData.ip, localEntries);
                SyncCleanupManager.cleanupObsoleteManagedFiles(serverData.ip, manifest, localEntries);
                PreJoinSyncPlan plan = buildSyncPlan(localEntries, manifest, connectAfterSync, allowDownloads);
                LoggerUtils.info("Pre-join sync found " + plan.requiredEntries().size() + " files to download");

                if (plan.alreadySynchronized()) {
                    LoggerUtils.info("Client is already synchronized");
                    SyncCleanupManager.saveManagedManifest(serverData.ip, manifest);
                    // localEntries already cached above; no need to re-scan
                    ServerSyncStatusCache.requestRefresh(serverData);
                    if (plan.continueImmediately()) {
                        continueConnecting(minecraft, returnScreen, serverData);
                    }
                    return;
                }

                if (plan.downloadRequiredButSkipped()) {
                    SyncIssueState.set(LanguageManager.get("modsync.error.update_required"));
                    LoggerUtils.info("Connect flow blocked because client mods are outdated and auto-download is disabled for this action");
                    ServerSyncStatusCache.requestRefresh(serverData);
                    return;
                }

                DownloadManager.getInstance().startDownloads(plan.requiredEntries(), () -> {
                    if (!verifyDownloadedEntries(serverData.ip, plan.requiredEntries())) {
                        ServerSyncStatusCache.requestRefresh(serverData);
                        return;
                    }
                    SyncCleanupManager.saveManagedManifest(serverData.ip, manifest);
                    cacheCurrentState(serverData.ip);
                    ServerSyncStatusCache.requestRefresh(serverData);
                    if (plan.continueAfterDownloads()) {
                        continueConnecting(minecraft, returnScreen, serverData);
                    }
                });
                ServerSyncStatusCache.markDirty(serverData);
            } catch (Exception exception) {
                SyncIssueState.set("Manifest step failed: " + exception.getMessage());
                LoggerUtils.error("Pre-join sync failed", exception);
                ServerSyncStatusCache.markDirty(serverData);
            }
        });
    }

    private static void continueConnecting(Minecraft minecraft, Screen returnScreen, ServerData serverData) {
        ClientSyncContext.markPreJoinReady(serverData.ip);
        minecraft.execute(() -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                returnScreen,
                minecraft,
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(serverData.ip),
                serverData,
                false));
    }

    private static void cacheCurrentState(String serverId) {
        List<ManifestEntry> currentEntries = ClientFileScanner.scanLocalFiles(serverId);
        ServerSyncStatusCache.cacheLocalEntries(serverId, currentEntries);
    }

    static boolean verifyDownloadedEntries(String serverId, List<ManifestEntry> requiredEntries) {
        List<ManifestEntry> currentEntries = ClientFileScanner.scanLocalFiles(serverId);
        List<ManifestEntry> mismatches = SyncComparator.findMissingOrOutdated(currentEntries, requiredEntries);
        if (mismatches.isEmpty()) {
            LoggerUtils.info("Post-download verification passed");
            return true;
        }

        LoggerUtils.warn("Post-download verification failed for " + mismatches.size() + " file(s)");
        for (ManifestEntry mismatch : mismatches) {
            LoggerUtils.warn("Post-download mismatch: "
                    + mismatch.getCategory().name()
                    + " / "
                    + mismatch.getRelativePath());
        }
        SyncIssueState.set(DownloadManager.buildPostDownloadVerificationFailureMessage(mismatches.size()));
        return false;
    }

    static PreJoinSyncPlan buildSyncPlan(List<ManifestEntry> localEntries,
                                         ManifestData manifest,
                                         boolean connectAfterSync,
                                         boolean allowDownloads) {
        List<ManifestEntry> requiredEntries = SyncComparator.findMissingOrOutdated(localEntries, manifest);
        boolean alreadySynchronized = requiredEntries.isEmpty();
        return new PreJoinSyncPlan(
                requiredEntries,
                alreadySynchronized,
                !alreadySynchronized && !allowDownloads,
                alreadySynchronized && connectAfterSync,
                !alreadySynchronized && connectAfterSync && allowDownloads
        );
    }
}
