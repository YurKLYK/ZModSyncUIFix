package com.modsync;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    private static final int CHUNK_SIZE = 12000;
    private static final String REQUIRED_MOD_KICK_MESSAGE =
            "ModSync handshake timed out. Install/update ModSync on the client and try joining again.";
    private static final Map<UUID, ChunkedPayloadCodec.ChunkAccumulator> CLIENT_FILE_CHUNKS = new HashMap<>();
    private static final ChunkedPayloadCodec.ChunkAccumulator SERVER_MANIFEST_CHUNKS = new ChunkedPayloadCodec.ChunkAccumulator();
    private static final ChunkedPayloadCodec.ChunkAccumulator START_DOWNLOAD_CHUNKS = new ChunkedPayloadCodec.ChunkAccumulator();
    private static final HandshakeTracker HANDSHAKE_TRACKER = new HandshakeTracker();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ModSync.id("main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId;

    private NetworkHandler() {
    }

    public static void register() {
        CHANNEL.registerMessage(packetId++, PacketClientHello.class, PacketClientHello::encode, PacketClientHello::decode,
                PacketClientHello::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, PacketClientFileList.class, PacketClientFileList::encode, PacketClientFileList::decode,
                PacketClientFileList::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, PacketServerManifest.class, PacketServerManifest::encode, PacketServerManifest::decode,
                PacketServerManifest::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++, PacketStartDownload.class, PacketStartDownload::encode, PacketStartDownload::decode,
                PacketStartDownload::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendClientHello() {
        CHANNEL.sendToServer(new PacketClientHello());
    }

    public static void sendClientFileList(List<ManifestEntry> entries) {
        sendChunkedToServer(ManifestGenerator.entriesToJson(entries));
    }

    static void handleClientHello(NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        if (player != null) {
            synchronized (CLIENT_FILE_CHUNKS) {
                CLIENT_FILE_CHUNKS.remove(player.getUUID());
            }
            HANDSHAKE_TRACKER.acknowledge(player.getUUID());
            LoggerUtils.info("Client connected for sync handshake: " + player.getGameProfile().getName());
        }
    }

    static void handleClientFileList(NetworkEvent.Context context, PacketClientFileList packet) {
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }

        String json;
        synchronized (CLIENT_FILE_CHUNKS) {
            ChunkedPayloadCodec.ChunkAccumulator accumulator = CLIENT_FILE_CHUNKS.computeIfAbsent(player.getUUID(), ignored -> new ChunkedPayloadCodec.ChunkAccumulator());
            json = accumulator.accept(packet.getChunkIndex(), packet.getTotalChunks(), packet.getPayload());
            if (json != null) {
                CLIENT_FILE_CHUNKS.remove(player.getUUID());
            }
        }
        if (json == null) {
            return;
        }

        List<ManifestEntry> clientEntries = ManifestGenerator.entriesFromJson(json);
        ManifestData manifest = ModSync.getLastManifest();
        if (manifest == null) {
            LoggerUtils.warn("Sync handshake requested before cached manifest was available");
        }
        manifest = HandshakeManifestPlanner.ensureServerManifest(manifest, System.currentTimeMillis());

        String baseUrl = resolveBaseUrl(player);
        HandshakeManifestPlanner.ServerHandshakeResponse response =
                HandshakeManifestPlanner.buildServerHandshakeResponse(clientEntries, manifest, baseUrl);

        LoggerUtils.info("File comparison for " + player.getGameProfile().getName() + " found "
                + response.requiredEntries().size() + " downloads");

        sendChunkedToPlayer(player, ManifestGenerator.toJson(response.outboundManifest()), ChunkTarget.MANIFEST);
        sendChunkedToPlayer(player, ManifestGenerator.entriesToJson(response.requiredEntries()), ChunkTarget.START_DOWNLOAD);
    }

    static void handleServerManifest(PacketServerManifest packet) {
        String json = SERVER_MANIFEST_CHUNKS.accept(packet.getChunkIndex(), packet.getTotalChunks(), packet.getPayload());
        if (json == null) {
            return;
        }
        ManifestData manifestData = ManifestGenerator.fromJson(json);
        ModSync.setLastManifest(manifestData);
        LoggerUtils.info("Received server manifest with " + manifestData.getEntries().size() + " entries");
    }

    static void handleStartDownload(PacketStartDownload packet) {
        String json = START_DOWNLOAD_CHUNKS.accept(packet.getChunkIndex(), packet.getTotalChunks(), packet.getPayload());
        if (json == null) {
            return;
        }
        // Capture game-thread state before going async
        final ManifestData manifestData = ModSync.getLastManifest();
        final String serverId = ClientSyncContext.getCurrentServerId();

        // File scan, hashing, and cleanup are I/O-heavy — run off the game thread
        CompletableFuture.runAsync(() -> {
            List<ManifestEntry> serverSuggested = ManifestGenerator.entriesFromJson(json);
            List<ManifestEntry> localEntries = ServerSyncStatusCache.getCachedOrScanLocalEntries(serverId);
            if (manifestData != null) {
                SyncCleanupManager.cleanupObsoleteManagedFiles(serverId, manifestData, localEntries);
            }
            HandshakeManifestPlanner.StartDownloadPlan plan =
                    HandshakeManifestPlanner.buildStartDownloadPlan(serverSuggested, manifestData, localEntries);

            if (plan.alreadySynchronized()) {
                LoggerUtils.info("Client is already synchronized");
                if (plan.saveManagedManifest()) {
                    SyncCleanupManager.saveManagedManifest(serverId, manifestData);
                }
                return;
            }

            LoggerUtils.info("Client starting download of " + plan.requiredEntries().size() + " files");
            ensureProgressScreenVisible();
            DownloadManager.getInstance().startDownloads(plan.requiredEntries(),
                    () -> {
                        if (!PreJoinSyncManager.verifyDownloadedEntries(serverId, plan.requiredEntries())) {
                            return;
                        }
                        if (plan.saveManagedManifest()) {
                            SyncCleanupManager.saveManagedManifest(serverId, manifestData);
                        }
                    });
        });
    }

    private static void ensureProgressScreenVisible() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class<?> bootstrapClass = Class.forName("com.modsync.ClientBootstrap");
            bootstrapClass.getMethod("ensureProgressScreenVisible").invoke(null);
        } catch (Exception exception) {
            LoggerUtils.warn("Failed to open sync progress screen: " + exception.getMessage());
        }
    }

    public static void registerPendingHandshake(ServerPlayer player) {
        if (player == null || !requiresClientHandshake(player)) {
            return;
        }
        HANDSHAKE_TRACKER.registerPending(player.getUUID(), ConfigManager.handshakeTimeoutSeconds() * 20);
    }

    public static void clearPendingHandshake(ServerPlayer player) {
        if (player == null) {
            return;
        }
        HANDSHAKE_TRACKER.clear(player.getUUID());
        synchronized (CLIENT_FILE_CHUNKS) {
            CLIENT_FILE_CHUNKS.remove(player.getUUID());
        }
    }

    public static void tickPendingHandshakes(net.minecraft.server.MinecraftServer server) {
        if (HANDSHAKE_TRACKER.pendingView().isEmpty()) {
            return;
        }

        List<UUID> expired = new ArrayList<>();
        HANDSHAKE_TRACKER.pendingView().replaceAll((uuid, ticksLeft) -> {
            int next = ticksLeft - 1;
            if (next <= 0) {
                expired.add(uuid);
            }
            return next;
        });

        for (UUID uuid : expired) {
            HANDSHAKE_TRACKER.markAwaitingAdminDecision(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                handleExpiredHandshake(server, player);
            }
        }
    }

    public static boolean allowTimedOutHandshake(MinecraftServer server, String playerName) {
        ServerPlayer player = findOnlinePlayer(server, playerName);
        if (player == null || !HANDSHAKE_TRACKER.isAwaitingAdminDecision(player.getUUID())) {
            return false;
        }
        HANDSHAKE_TRACKER.clear(player.getUUID());
        LoggerUtils.warn("Admin allowed player without completed ModSync handshake: "
                + player.getGameProfile().getName() + " (" + player.getUUID() + ")");
        return true;
    }

    public static boolean kickTimedOutHandshake(MinecraftServer server, String playerName) {
        ServerPlayer player = findOnlinePlayer(server, playerName);
        if (player == null || !HANDSHAKE_TRACKER.isAwaitingAdminDecision(player.getUUID())) {
            return false;
        }
        HANDSHAKE_TRACKER.clear(player.getUUID());
        LoggerUtils.warn("Admin kicked player after ModSync handshake timeout: "
                + player.getGameProfile().getName() + " (" + player.getUUID() + ")");
        player.connection.disconnect(Component.literal(REQUIRED_MOD_KICK_MESSAGE));
        return true;
    }

    private static void handleExpiredHandshake(MinecraftServer server, ServerPlayer player) {
        String remoteAddress = player.connection == null ? "<unknown>" : String.valueOf(player.connection.getRemoteAddress());
        String timeoutMessage = "ModSync handshake timed out for player "
                + player.getGameProfile().getName()
                + " (" + player.getUUID() + ", " + remoteAddress + ") after "
                + ConfigManager.handshakeTimeoutSeconds() + "s.";

        if (ConfigManager.autoKickOnHandshakeTimeout()) {
            LoggerUtils.warn(timeoutMessage + " Auto-kicking is enabled.");
            HANDSHAKE_TRACKER.clear(player.getUUID());
            synchronized (CLIENT_FILE_CHUNKS) {
                CLIENT_FILE_CHUNKS.remove(player.getUUID());
            }
            player.connection.disconnect(Component.literal(REQUIRED_MOD_KICK_MESSAGE));
            return;
        }

        LoggerUtils.warn(timeoutMessage + " Awaiting admin decision.");
        notifyAdmins(server,
                "ModSync handshake timed out for " + player.getGameProfile().getName()
                        + ". Run /modsync handshake kick " + player.getGameProfile().getName()
                        + " or /modsync handshake allow " + player.getGameProfile().getName() + ".");
    }

    private static void notifyAdmins(MinecraftServer server, String message) {
        Component component = Component.literal(message);
        server.getPlayerList().getPlayers().stream()
                .filter(player -> player.hasPermissions(4))
                .forEach(player -> player.sendSystemMessage(component));
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String playerName) {
        if (server == null || playerName == null || playerName.isBlank()) {
            return null;
        }
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getGameProfile().getName().equalsIgnoreCase(playerName))
                .findFirst()
                .orElse(null);
    }

    private static String resolveBaseUrl(ServerPlayer player) {
        String host = player == null ? "127.0.0.1" : player.server.getLocalIp();
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        return ServerManifestHttpHandler.resolvePublicBaseUrl(host);
    }

    static boolean requiresClientHandshake(ServerPlayer player) {
        return player != null
                && player.server != null
                && HandshakePolicy.requiresClientHandshake(player.server.isDedicatedServer());
    }

    private static void sendChunkedToServer(String json) {
        List<String> chunks = ChunkedPayloadCodec.split(json, CHUNK_SIZE);
        for (int i = 0; i < chunks.size(); i++) {
            CHANNEL.sendToServer(new PacketClientFileList(i, chunks.size(), chunks.get(i)));
        }
    }

    private static void sendChunkedToPlayer(ServerPlayer player, String json, ChunkTarget target) {
        List<String> chunks = ChunkedPayloadCodec.split(json, CHUNK_SIZE);
        for (int i = 0; i < chunks.size(); i++) {
            switch (target) {
                case MANIFEST ->
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketServerManifest(i, chunks.size(), chunks.get(i)));
                case START_DOWNLOAD ->
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PacketStartDownload(i, chunks.size(), chunks.get(i)));
            }
        }
    }

    private enum ChunkTarget {
        MANIFEST,
        START_DOWNLOAD
    }
}
