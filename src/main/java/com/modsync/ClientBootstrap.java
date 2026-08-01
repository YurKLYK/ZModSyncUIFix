package com.modsync;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ClientBootstrap {
    record PostLoginSyncPlan(boolean skipHandshake,
                             boolean acknowledgeOnly,
                             boolean startHandshake,
                             String reason) {
    }

    private static boolean initialized;

    private ClientBootstrap() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        MinecraftForge.EVENT_BUS.register(ServerListDownloadHooks.class);
        initialized = true;
    }

    public static void ensureProgressScreenVisible() {
        Minecraft minecraft = Minecraft.getInstance();
        // Safe to call from any thread: execute() dispatches to game thread if needed
        minecraft.execute(() -> {
            if (minecraft.screen instanceof SyncProgressScreen || minecraft.screen instanceof RestartScreen) {
                return;
            }
            minecraft.setScreen(new SyncProgressScreen(minecraft.screen));
        });
    }

    public static final class ClientEvents {
        @SubscribeEvent
        public void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            Minecraft minecraft = Minecraft.getInstance();
            String serverId = minecraft.getCurrentServer() == null ? "" : minecraft.getCurrentServer().ip;
            PostLoginSyncPlan plan = buildPostLoginSyncPlan(
                    minecraft.hasSingleplayerServer(),
                    serverId,
                    ClientSyncContext::consumePreJoinReady
            );
            if (plan.skipHandshake()) {
                LoggerUtils.info(plan.reason());
                return;
            }

            ClientSyncContext.setCurrentServerId(serverId);
            if (plan.acknowledgeOnly()) {
                LoggerUtils.info(plan.reason());
                NetworkHandler.sendClientHello();
                return;
            }
            if (plan.startHandshake()) {
                LoggerUtils.info("Client connection established, starting sync handshake");
                NetworkHandler.sendClientHello();
                // Scan + hash all mod files off the game thread to avoid freezing the game on join.
                final String serverIdCopy = serverId;
                CompletableFuture.runAsync(() -> {
                    List<ManifestEntry> localFiles = ClientFileScanner.scanLocalFiles();
                    // Guard: if the player switched servers while we were scanning, discard results.
                    if (!serverIdCopy.equals(ClientSyncContext.getCurrentServerId())) {
                        LoggerUtils.info("Server changed during file scan, discarding stale file list for " + serverIdCopy);
                        return;
                    }
                    ServerSyncStatusCache.cacheLocalEntries(serverIdCopy, localFiles);
                    NetworkHandler.sendClientFileList(localFiles);
                });
            }
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (!DownloadManager.getInstance().isActive() && RestartState.consumePromptPending()) {
                minecraft.setScreen(new RestartScreen());
            }
        }
    }

    static PostLoginSyncPlan buildPostLoginSyncPlan(boolean singleplayerSession,
                                                    String serverId,
                                                    java.util.function.Predicate<String> preJoinReadyConsumer) {
        String normalizedServerId = serverId == null ? "" : serverId.trim();
        if (singleplayerSession || normalizedServerId.isEmpty()) {
            return new PostLoginSyncPlan(true, false, false, "Skipping ModSync handshake in singleplayer/local session");
        }
        if (preJoinReadyConsumer.test(normalizedServerId)) {
            return new PostLoginSyncPlan(false, true, false,
                    "Sending ModSync handshake acknowledgement after pre-join sync");
        }
        return new PostLoginSyncPlan(false, false, true, "Client connection established, starting sync handshake");
    }
}
