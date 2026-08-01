package com.modsync;

import net.minecraft.client.multiplayer.ServerData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class ServerSyncStatusCache {
    private static final long REFRESH_INTERVAL_MS = 120_000L;
    private static final long LOCAL_SCAN_CACHE_MS = 120_000L;
    private static final int STATUS_CONNECT_TIMEOUT_MS = 1_500;
    private static final int STATUS_READ_TIMEOUT_MS = 2_500;
    private static final Map<String, StatusSnapshot> STATUS_BY_IP = new ConcurrentHashMap<>();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "modsync-status-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, LocalScanSnapshot> LOCAL_SCAN_BY_SERVER = new ConcurrentHashMap<>();

    private ServerSyncStatusCache() {
    }

    public static SyncState getStatus(ServerData serverData) {
        StatusSnapshot snapshot = STATUS_BY_IP.get(serverData.ip);
        if (snapshot == null) {
            return SyncState.UNKNOWN;
        }
        return snapshot.state;
    }

    public static void requestRefresh(ServerData serverData) {
        STATUS_BY_IP.compute(serverData.ip, (ip, existing) -> {
            REFRESH_EXECUTOR.execute(() -> refreshNow(serverData));
            if (existing != null && existing.state == SyncState.CHECKING) {
                return existing;
            }
            return new StatusSnapshot(SyncState.CHECKING, System.currentTimeMillis());
        });
    }

    public static void markChecking(ServerData serverData) {
        STATUS_BY_IP.put(serverData.ip, new StatusSnapshot(SyncState.CHECKING, System.currentTimeMillis()));
    }

    public static void markDirty(ServerData serverData) {
        markDirty(serverData.ip);
    }

    private static void refreshNow(ServerData serverData) {
        try {
            int discoveredPort = MotdMetadataCodec.extractHttpPort(serverData);
            ManifestData manifest = ServerManifestHttpHandler.fetchManifest(serverData.ip, discoveredPort, STATUS_CONNECT_TIMEOUT_MS, STATUS_READ_TIMEOUT_MS);
            boolean synced = SyncComparator.findMissingOrOutdated(getCachedOrScanLocalEntries(serverData.ip), manifest).isEmpty();
            STATUS_BY_IP.put(serverData.ip, new StatusSnapshot(synced ? SyncState.SYNCED : SyncState.OUTDATED, System.currentTimeMillis()));
        } catch (Exception exception) {
            STATUS_BY_IP.put(serverData.ip, new StatusSnapshot(SyncState.ERROR, System.currentTimeMillis()));
        }
    }

    public static void cacheLocalEntries(String serverId, List<ManifestEntry> entries) {
        LOCAL_SCAN_BY_SERVER.put(normalizeServerId(serverId), new LocalScanSnapshot(List.copyOf(entries), System.currentTimeMillis()));
    }

    public static List<ManifestEntry> getCachedOrScanLocalEntries(String serverId) {
        return getCachedOrScanLocalEntries(serverId, ClientFileScanner::scanLocalFiles, System::currentTimeMillis);
    }

    static List<ManifestEntry> getCachedOrScanLocalEntries(String serverId,
                                                           Function<String, List<ManifestEntry>> scanner,
                                                           LongSupplier nowSupplier) {
        String normalizedServerId = normalizeServerId(serverId);
        LocalScanSnapshot snapshot = LOCAL_SCAN_BY_SERVER.get(normalizedServerId);
        long now = nowSupplier.getAsLong();
        if (snapshot != null && now - snapshot.timestamp <= LOCAL_SCAN_CACHE_MS) {
            return snapshot.entries;
        }

        // Perform the scan outside any lock so the game thread isn't blocked by file I/O
        List<ManifestEntry> entries = scanner.apply(normalizedServerId);
        now = nowSupplier.getAsLong();

        synchronized (ServerSyncStatusCache.class) {
            // Another thread may have cached a fresh result while we were scanning; prefer theirs
            snapshot = LOCAL_SCAN_BY_SERVER.get(normalizedServerId);
            if (snapshot != null && now - snapshot.timestamp <= LOCAL_SCAN_CACHE_MS) {
                return snapshot.entries;
            }
            LOCAL_SCAN_BY_SERVER.put(normalizedServerId, new LocalScanSnapshot(List.copyOf(entries), now));
        }
        return entries;
    }

    private static String normalizeServerId(String serverId) {
        return serverId == null ? "" : serverId;
    }

    static void markDirty(String serverId) {
        STATUS_BY_IP.remove(serverId);
        LOCAL_SCAN_BY_SERVER.remove(normalizeServerId(serverId));
    }

    static void putStatusForTests(String serverIp, SyncState state, long timestamp) {
        STATUS_BY_IP.put(serverIp, new StatusSnapshot(state, timestamp));
    }

    static void putLocalScanForTests(String serverId, List<ManifestEntry> entries, long timestamp) {
        LOCAL_SCAN_BY_SERVER.put(normalizeServerId(serverId), new LocalScanSnapshot(List.copyOf(entries), timestamp));
    }

    static void resetForTests() {
        STATUS_BY_IP.clear();
        LOCAL_SCAN_BY_SERVER.clear();
    }

    public enum SyncState {
        UNKNOWN,
        CHECKING,
        SYNCED,
        OUTDATED,
        ERROR
    }

    private record StatusSnapshot(SyncState state, long timestamp) {
    }

    private record LocalScanSnapshot(List<ManifestEntry> entries, long timestamp) {
    }

    public static boolean shouldRefresh(ServerData serverData) {
        return shouldRefresh(serverData.ip);
    }

    static boolean shouldRefresh(String serverId) {
        StatusSnapshot snapshot = STATUS_BY_IP.get(serverId);
        if (snapshot == null) {
            return true;
        }
        if (snapshot.state == SyncState.UNKNOWN) {
            return true;
        }
        if (snapshot.state == SyncState.CHECKING) {
            return false;
        }
        return System.currentTimeMillis() - snapshot.timestamp > REFRESH_INTERVAL_MS;
    }
}
