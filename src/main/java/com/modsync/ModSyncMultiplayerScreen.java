package com.modsync;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cleaned Vanilla-style multiplayer screen with ModSync Integration.
 * Optimized rendering layers to prevent buttons from being darkened by overlays.
 */
public class ModSyncMultiplayerScreen extends Screen {
    private static final int LIST_TOP = 32;
    private static final int ROW_HEIGHT = 36;
    private static final int ICON_SIZE = 32;
    private static final int LIST_SIDE_PADDING = 6;
    private static final int LIST_SCROLLBAR_WIDTH = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;
    private static final int BOTTOM_MARGIN = 8;
    private static final int LIST_TO_BUTTONS_GAP = 18;
    private static final Map<String, IconTexture> ICON_CACHE = new ConcurrentHashMap<>();

    private final Screen parent;

    private int listLeft;
    private int listWidth;
    private int listRight;
    private int listBottom;

    private ServerList serverList;
    private ServerStatusPinger pinger;
    private ServerBrowserList listWidget;

    // ボタン配置
    private Button connectButton;
    private Button joinNoSyncButton;
    private Button downloadButton; 
    private Button directButton;
    private Button addButton;
    private Button editButton;
    private Button deleteButton;
    private Button refreshButton;
    private Button backButton;

    public ModSyncMultiplayerScreen(Screen parent) {
        super(Component.translatable("multiplayer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        boolean firstInit = serverList == null;
        if (firstInit) {
            serverList = new ServerList(minecraft);
            serverList.load();
        }
        if (pinger == null) {
            pinger = new ServerStatusPinger();
        }

        String selectedIp = null;
        if (listWidget != null && listWidget.getSelected() != null) {
            selectedIp = listWidget.getSelected().serverData.ip;
        }

        buildLayout();
        reloadEntries(selectedIp);
    }

    /**
     * ボタンレイアウトの構築(公式のサーバー一覧画面と同じく、各段がリスト幅いっぱいに広がる横並び)
     */
    private void buildLayout() {
        clearWidgets();

        listLeft = 24;
        listWidth = Math.max(260, width - 48);
        listRight = listLeft + listWidth;

        int row2Y = height - BOTTOM_MARGIN - BUTTON_HEIGHT;
        int row1Y = row2Y - BUTTON_SPACING - BUTTON_HEIGHT;

        listBottom = row1Y - LIST_TO_BUTTONS_GAP;

        int listHeight = Math.max(80, listBottom - LIST_TOP);
        listWidget = new ServerBrowserList(minecraft, listWidth, listHeight, LIST_TOP, listBottom, ROW_HEIGHT, listLeft);
        addRenderableWidget(listWidget);

        // --- 1段目: Select / Join(No Sync) / Download Mods(ModSync独自) / Direct / Add ---
        int row1ButtonCount = 5;
        int row1TotalSpacing = BUTTON_SPACING * (row1ButtonCount - 1);
        int row1BtnW = (listWidth - row1TotalSpacing) / row1ButtonCount;
        int row1StartX = (width - (row1BtnW * row1ButtonCount + row1TotalSpacing)) / 2;

        connectButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.select"), button -> connectSelected())
            .bounds(row1StartX, row1Y, row1BtnW, BUTTON_HEIGHT)
            .build());

        joinNoSyncButton = addRenderableWidget(Button.builder(LanguageManager.component("modsync.join_nosync"), button -> joinSkipSync())
            .bounds(row1StartX + (row1BtnW + BUTTON_SPACING), row1Y, row1BtnW, BUTTON_HEIGHT)
            .build());

        downloadButton = addRenderableWidget(Button.builder(Component.translatable("modsync.download_button").copy().withStyle(ChatFormatting.GOLD), button -> downloadSelected())
            .bounds(row1StartX + (row1BtnW + BUTTON_SPACING) * 2, row1Y, row1BtnW, BUTTON_HEIGHT)
            .build());

        directButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.direct"), button -> directConnect())
            .bounds(row1StartX + (row1BtnW + BUTTON_SPACING) * 3, row1Y, row1BtnW, BUTTON_HEIGHT)
            .build());

        addButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.add"), button -> addServer())
            .bounds(row1StartX + (row1BtnW + BUTTON_SPACING) * 4, row1Y, row1BtnW, BUTTON_HEIGHT)
            .build());

        // --- 2段目: Edit / Delete / Refresh / Back(公式と同じ4つ) ---
        int row2ButtonCount = 4;
        int row2TotalSpacing = BUTTON_SPACING * (row2ButtonCount - 1);
        int row2BtnW = (listWidth - row2TotalSpacing) / row2ButtonCount;
        int row2StartX = (width - (row2BtnW * row2ButtonCount + row2TotalSpacing)) / 2;

        editButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.edit"), button -> editSelected())
            .bounds(row2StartX, row2Y, row2BtnW, BUTTON_HEIGHT)
            .build());

        deleteButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.delete"), button -> deleteSelected())
            .bounds(row2StartX + (row2BtnW + BUTTON_SPACING), row2Y, row2BtnW, BUTTON_HEIGHT)
            .build());

        refreshButton = addRenderableWidget(Button.builder(Component.translatable("selectServer.refresh"), button -> refreshServers())
            .bounds(row2StartX + (row2BtnW + BUTTON_SPACING) * 2, row2Y, row2BtnW, BUTTON_HEIGHT)
            .build());

        backButton = addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
            .bounds(row2StartX + (row2BtnW + BUTTON_SPACING) * 3, row2Y, row2BtnW, BUTTON_HEIGHT)
            .build());

        updateButtons();
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        this.width = width;
        this.height = height;
        this.init(minecraft, width, height);
    }

    private void reloadEntries(String selectedIp) {
        if (listWidget == null) {
            return;
        }

        listWidget.clearAllEntries();
        ServerEntry selectedEntry = null;
        for (int i = 0; i < serverList.size(); i++) {
            ServerData serverData = serverList.get(i);
            ServerEntry entry = listWidget.addServer(serverData, i);
            if (selectedIp != null && selectedIp.equals(serverData.ip)) {
                selectedEntry = entry;
            }
        }

        if (selectedEntry != null) {
            listWidget.setSelected(selectedEntry);
        } else if (serverList.size() > 0 && !listWidget.children().isEmpty()) {
            listWidget.setSelected(listWidget.children().get(0));
        }
        updateButtons();
    }

    private void refreshServers() {
        if (pinger != null) {
            pinger.removeAll();
        }
        for (int i = 0; i < serverList.size(); i++) {
            ServerData serverData = serverList.get(i);
            ServerSyncStatusCache.markDirty(serverData);
            ServerSyncStatusCache.markChecking(serverData);
            serverData.pinged = false;
            serverData.ping = -1L;
            serverData.version = Component.literal("...");
            serverData.motd = Component.empty();
            try {
                pinger.pingServer(serverData, () -> {
                    ServerSyncStatusCache.requestRefresh(serverData);
                });
            } catch (Exception exception) {
                LoggerUtils.warn("Failed to ping server " + serverData.ip + ": " + exception.getMessage());
            }
        }
    }

    private void connectSelected() {
        if (listWidget == null) return;
        ServerEntry entry = listWidget.getSelected();
        if (entry == null) {
            return;
        }
        ServerSyncStatusCache.markDirty(entry.serverData);
        PreJoinSyncManager.startForServer(entry.serverData, this, true, true);
    }

    private void joinSkipSync() {
        if (listWidget == null) return;
        ServerEntry entry = listWidget.getSelected();
        if (entry == null) return;
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                this, minecraft,
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(entry.serverData.ip),
                entry.serverData, false);
    }

    private void downloadSelected() {
        if (listWidget == null) return;
        ServerEntry entry = listWidget.getSelected();
        if (entry == null) {
            return;
        }
        ServerSyncStatusCache.markDirty(entry.serverData);
        PreJoinSyncManager.startForServer(entry.serverData, this, false);
    }

    private void addServer() {
        ServerData serverData = new ServerData(LanguageManager.get("modsync.server_name_default"), "", false);
        openEditor(serverData, accepted -> {
            if (accepted) {
                serverList.add(serverData, false);
                serverList.save();
                reloadEntries(serverData.ip);
            }
            minecraft.setScreen(this);
        });
    }

    private void directConnect() {
        ServerData serverData = new ServerData(LanguageManager.get("modsync.direct_server_default"), "", false);
        minecraft.setScreen(new DirectJoinServerScreen(this, accepted -> {
            if (accepted) {
                ServerSyncStatusCache.markDirty(serverData);
                PreJoinSyncManager.startForServer(serverData, this, true, true);
            } else {
                minecraft.setScreen(this);
            }
        }, serverData));
    }

    private void editSelected() {
        if (listWidget == null) return;
        ServerEntry entry = listWidget.getSelected();
        if (entry == null) {
            return;
        }

        ServerData edited = new ServerData(entry.serverData.name, entry.serverData.ip, entry.serverData.isLan());
        edited.copyFrom(entry.serverData);
        int selectedIndex = entry.index;
        openEditor(edited, accepted -> {
            if (accepted) {
                serverList.replace(selectedIndex, edited);
                serverList.save();
                reloadEntries(edited.ip);
            }
            minecraft.setScreen(this);
        });
    }

    private void deleteSelected() {
        if (listWidget == null) return;
        ServerEntry entry = listWidget.getSelected();
        if (entry == null) {
            return;
        }

        serverList.remove(entry.serverData);
        serverList.save();
        reloadEntries(null);
    }

    private void openEditor(ServerData serverData, BooleanConsumer consumer) {
        minecraft.setScreen(new EditServerScreen(this, consumer, serverData));
    }

    private void updateButtons() {
        if (connectButton == null) {
            return;
        }

        ServerEntry entry = listWidget == null ? null : listWidget.getSelected();
        boolean hasSelection = entry != null;
        connectButton.active = hasSelection;
        joinNoSyncButton.active = hasSelection;
        downloadButton.active = hasSelection;
        editButton.active = hasSelection;
        deleteButton.active = hasSelection;
    }

    @Override
    public void tick() {
        super.tick();
        if (pinger != null) {
            pinger.tick();
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (pinger != null) {
            pinger.removeAll();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. 背景(Dirt)
        renderBackground(guiGraphics);

        // 2. 中央タイトル(公式のサーバー一覧画面と同じ位置)
        guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);

        if (serverList.size() == 0) {
            guiGraphics.drawCenteredString(font, LanguageManager.component("modsync.multiplayer.empty"), width / 2, LIST_TOP + (listBottom - LIST_TOP) / 2 - 10, 0xCFCFCF);
        }

        // 3. 先にリスト単体の中身（スクロールされるサーバー項目群）を描画
        if (listWidget != null) {
            listWidget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 4. 【ココが重要】上下の暗転グラデーションを「ボタンより奥」に描画(公式と同じ、リストを囲う箱は描かない)
        guiGraphics.fillGradient(0, 0, width, LIST_TOP, 0xFF000000, 0x00000000);
        guiGraphics.fillGradient(0, listBottom, width, height, 0x00000000, 0xFF000000);

        // 5. 最後に super.render を呼ぶことで、すべての登録ボタンが一番手前のレイヤーに「明るく」描画されます
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            if (connectButton != null && connectButton.active) {
                connectSelected();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private String trim(String text, int maxWidth) {
        return font.plainSubstrByWidth(text == null ? "" : text, Math.max(8, maxWidth));
    }

    private static String formatPing(ServerData serverData) {
        if (!serverData.pinged || serverData.ping < 0L) {
            return "--";
        }
        return serverData.ping + " ms";
    }

    private static String formatVersion(ServerData serverData) {
        if (serverData.version == null) {
            return "--";
        }
        String text = serverData.version.getString();
        return text == null || text.isBlank() ? "--" : text;
    }

    private static int statusColor(ServerSyncStatusCache.SyncState state) {
        return switch (state) {
            case UNKNOWN -> 0xFF3A3A3A;
            case SYNCED -> 0xFF275D37; 
            case OUTDATED, ERROR -> 0xFF7A2626; 
            case CHECKING -> 0xFF6B612D; 
        };
    }

    private static String statusBadge(ServerSyncStatusCache.SyncState state) {
        return switch (state) {
            case UNKNOWN -> "--";
            case SYNCED -> "OK";
            case OUTDATED -> "DL";
            case ERROR -> "ERR";
            case CHECKING -> "...";
        };
    }

    private final class ServerBrowserList extends ObjectSelectionList<ServerEntry> {
        private final int left;

        private ServerBrowserList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight, int left) {
            super(minecraft, width, height, top, bottom, itemHeight);
            this.left = left;
            setLeftPos(left);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        @Override
        public int getRowLeft() {
            return left + LIST_SIDE_PADDING;
        }

        @Override
        public int getRowWidth() {
            return width - LIST_SIDE_PADDING * 2 - LIST_SCROLLBAR_WIDTH - 8;
        }

        @Override
        protected int getScrollbarPosition() {
            return left + width - LIST_SCROLLBAR_WIDTH - 6;
        }

        private ServerEntry addServer(ServerData serverData, int index) {
            ServerEntry entry = new ServerEntry(serverData, index);
            addEntry(entry);
            return entry;
        }

        private void clearAllEntries() {
            clearEntries();
        }
    }

    private final class ServerEntry extends ObjectSelectionList.Entry<ServerEntry> {
        private final ServerData serverData;
        private final int index;

        private ServerEntry(ServerData serverData, int index) {
            this.serverData = serverData;
            this.index = index;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            if (listWidget == null) return;
            
            int iconX = left + 2;
            int iconY = top + (rowHeight - ICON_SIZE) / 2 - 1;
            drawServerIcon(guiGraphics, serverData, iconX, iconY);

            int textLeft = iconX + ICON_SIZE + 8;
            int rightAlignWidth = 80;
            int textWidth = rowWidth - ICON_SIZE - rightAlignWidth - 16;

            guiGraphics.drawString(font, trim(serverData.name, textWidth), textLeft, top + 3, 0xFFFFFF, false);
            
            String motd = serverData.motd != null ? serverData.motd.getString() : "";
            if (motd.isBlank()) {
                motd = serverData.ip;
            }
            guiGraphics.drawString(font, trim(motd, textWidth), textLeft, top + 15, 0x808080, false);

            ServerSyncStatusCache.SyncState syncState = ServerSyncStatusCache.getStatus(serverData);
            int badgeWidth = 24;
            int badgeHeight = 11;
            int badgeX = left + rowWidth - badgeWidth - 6;
            int badgeY = top + 4;
            
            guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, statusColor(syncState));
            guiGraphics.drawCenteredString(font, statusBadge(syncState), badgeX + badgeWidth / 2, badgeY + 2, 0xFFFFFF);

            String pingText = formatPing(serverData);
            String versionText = trim(formatVersion(serverData), 60);

            int pingX = badgeX - font.width(pingText) - 6;
            guiGraphics.drawString(font, pingText, pingX, top + 4, 0x808080, false);
            
            int versionX = left + rowWidth - font.width(versionText) - 6;
            guiGraphics.drawString(font, versionText, versionX, top + 15, 0x606060, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (listWidget != null) {
                listWidget.setSelected(this);
            }
            updateButtons();
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public Component getNarration() {
            return Component.literal(serverData.name).withStyle(ChatFormatting.WHITE);
        }
    }

    private void drawServerIcon(GuiGraphics guiGraphics, ServerData serverData, int x, int y) {
        ResourceLocation texture = getServerIcon(serverData);
        if (texture != null) {
            guiGraphics.blit(texture, x, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            return;
        }

        guiGraphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, 0xFF3C3C3C);
        String initials = initials(serverData.name);
        guiGraphics.drawCenteredString(font, initials, x + ICON_SIZE / 2, y + (ICON_SIZE / 2 - 4), 0xCCCCCC);
    }

    private ResourceLocation getServerIcon(ServerData serverData) {
        byte[] iconBytes = serverData.getIconBytes();
        if (iconBytes == null || iconBytes.length == 0) {
            return null;
        }

        String key = serverData.ip;
        int hash = java.util.Arrays.hashCode(iconBytes);
        IconTexture cached = ICON_CACHE.get(key);
        if (cached != null && cached.hash == hash) {
            return cached.location;
        }

        if (cached != null) {
            minecraft.getTextureManager().release(cached.location);
        }

        try {
            NativeImage image = NativeImage.read(iconBytes);
            ResourceLocation location = minecraft.getTextureManager().register("modsync_server_" + Math.abs(key.hashCode()), new DynamicTexture(image));
            ICON_CACHE.put(key, new IconTexture(hash, location));
            return location;
        } catch (IOException exception) {
            return null;
        }
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String trimmed = name.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
    }

    private record IconTexture(int hash, ResourceLocation location) {
    }
}