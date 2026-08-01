package com.modsync;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.concurrent.CompletableFuture;

@Mod(ModSync.MOD_ID)
public class ModSync {
    public static final String MOD_ID = "modsync";

    private static volatile ManifestData lastManifest;
    private static String visibleServerMotd = "";

    public ModSync() {
        ConfigManager.register();
        LoggerUtils.init(ConfigManager.logFile(), ConfigManager.logToFile());
        LoggerUtils.info("ModSync startup");
        NetworkHandler.register();
        MinecraftForge.EVENT_BUS.register(this);
        bootstrapClientHooks();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ManifestData getLastManifest() {
        return lastManifest;
    }

    public static void setLastManifest(ManifestData manifestData) {
        lastManifest = manifestData;
    }

    private static synchronized void refreshManifest(String reason) {
        try {
            ManifestData manifest = ManifestGenerator.generateManifest();
            setLastManifest(manifest);
            HttpFileServer.getInstance().updateManifest(manifest);
            LoggerUtils.info("Manifest cache refreshed: " + reason);
        } catch (Exception exception) {
            LoggerUtils.error("Failed to refresh manifest cache: " + reason, exception);
        }
    }

    public static synchronized void reloadServerRuntime(String reason) {
        try {
            ConfigManager.reloadFromDisk();
        } catch (Exception exception) {
            LoggerUtils.error("Failed to reload ModSync config from disk", exception);
            return;
        }

        HttpFileServer.getInstance().stop();
        updateServerMotdMetadata();
        refreshManifest(reason);
        HttpFileServer.getInstance().start();
        LoggerUtils.info("ModSync runtime reloaded: " + reason);
    }

    private static synchronized void updateServerMotdMetadata() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        if (visibleServerMotd.isBlank()) {
            visibleServerMotd = MotdMetadataCodec.stripHiddenMetadata(server.getMotd());
        }

        server.setMotd(MotdMetadataCodec.appendHiddenHttpPort(visibleServerMotd, ConfigManager.serverHttpPort()));
        LoggerUtils.info("Embedded hidden ModSync HTTP port metadata into server MOTD");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            FileUtils.ensureServerRepositoryDirectories();
            LoggerUtils.info("Ensured ModSync server repository directories");
        } catch (Exception exception) {
            LoggerUtils.error("Failed to create ModSync server repository directories", exception);
        }

        visibleServerMotd = MotdMetadataCodec.stripHiddenMetadata(event.getServer().getMotd());
        updateServerMotdMetadata();
        HttpFileServer.getInstance().start();
        // Manifest generation hashes every file in the repository — run async to avoid stalling server startup
        CompletableFuture.runAsync(() -> refreshManifest("server start"));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        HttpFileServer.getInstance().stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModSyncCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.registerPendingHandshake(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.clearPendingHandshake(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            NetworkHandler.tickPendingHandshakes(event.getServer());
        }
    }

    @SubscribeEvent
    public void onConfigReload(ModConfigEvent.Reloading event) {
        LoggerUtils.info("Configuration reloaded");
    }

    private static void bootstrapClientHooks() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class<?> bootstrapClass = Class.forName("com.modsync.ClientBootstrap");
            bootstrapClass.getMethod("init").invoke(null);
        } catch (Exception exception) {
            LoggerUtils.error("Failed to bootstrap client hooks", exception);
        }
    }
}
