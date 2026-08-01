package com.modsync;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

import java.util.List;

public class SyncProgressScreen extends Screen {
    private static final int LOG_LINE_HEIGHT = 10;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int STATUS_BOX_HEIGHT = 44;
    private final Screen returnScreen;
    /** When false, suppress auto-close so ConnectScreen can take over cleanly (pre-join flow). */
    private final boolean autoClose;
    /** Non-null in pre-join flow; shown when manifest fetch fails so the user can still connect. */
    private final Runnable connectAnywayAction;
    private int logScroll;
    private Button backButton;
    private Button connectAnywayButton;
    private boolean autoCloseHandled;

    public SyncProgressScreen(Screen returnScreen) {
        this(returnScreen, true, null);
    }

    public SyncProgressScreen(Screen returnScreen, boolean autoClose) {
        this(returnScreen, autoClose, null);
    }

    public SyncProgressScreen(Screen returnScreen, boolean autoClose, Runnable connectAnywayAction) {
        super(LanguageManager.component("modsync.progress.title"));
        this.returnScreen = returnScreen;
        this.autoClose = autoClose;
        this.connectAnywayAction = connectAnywayAction;
    }

    @Override
    protected void init() {
        if (connectAnywayAction != null) {
            backButton = addRenderableWidget(Button.builder(LanguageManager.component("modsync.back"), button -> onClose())
                    .bounds(width / 2 - 104, height - 40, 100, 20)
                    .build());
            connectAnywayButton = addRenderableWidget(Button.builder(LanguageManager.component("modsync.connect_anyway"), button -> connectAnywayAction.run())
                    .bounds(width / 2 + 4, height - 40, 100, 20)
                    .build());
            connectAnywayButton.visible = false;
        } else {
            backButton = addRenderableWidget(Button.builder(LanguageManager.component("modsync.back"), button -> onClose())
                    .bounds(width / 2 - 50, height - 40, 100, 20)
                    .build());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(font, title, width / 2, 30, 0xFFFFFF);

        DownloadManager manager = DownloadManager.getInstance();
        List<String> lines = SyncLogBuffer.snapshot();
        SyncProgressStateResolver.SyncProgressVisualState visualState = SyncProgressStateResolver.resolve(
                SyncIssueState.hasIssue(),
                manager.isActive(),
                RestartState.isRestartRequired(),
                !lines.isEmpty()
        );
        if (backButton != null) {
            backButton.active = !manager.isActive();
        }
        if (connectAnywayButton != null) {
            connectAnywayButton.visible = (visualState == SyncProgressStateResolver.SyncProgressVisualState.FAILED);
        }

        // Nothing left to show the player: close automatically instead of leaving this
        // screen sitting on top of the game (e.g. after a mid-game sync completes silently).
        if (autoClose && !autoCloseHandled && !manager.isActive()
                && visualState == SyncProgressStateResolver.SyncProgressVisualState.COMPLETE) {
            autoCloseHandled = true;
            onClose();
            return;
        }

        int boxLeft = 40;
        int boxTop = 132;
        int boxRight = width - 40;
        int boxBottom = height - 60;

        renderStatusBox(guiGraphics, manager, visualState, boxLeft, 74, boxRight);
        guiGraphics.fill(boxLeft, boxTop, boxRight, boxBottom, 0x99000000);
        guiGraphics.drawString(font, LanguageManager.component("modsync.logs"), boxLeft + 8, boxTop + 8, 0xFFFFFF, false);

        int contentLeft = boxLeft + 8;
        int contentRight = boxRight - 8 - SCROLLBAR_WIDTH - 4;
        int visibleLines = getVisibleLines(boxTop, boxBottom);
        int maxScroll = Math.max(0, lines.size() - visibleLines);
        logScroll = Math.max(0, Math.min(maxScroll, logScroll));
        int start = Math.max(0, lines.size() - visibleLines - logScroll);
        int end = Math.min(lines.size(), start + visibleLines);
        int y = boxTop + 22;
        for (int i = start; i < end; i++) {
            guiGraphics.drawString(font, font.plainSubstrByWidth(lines.get(i), Math.max(20, contentRight - contentLeft)), contentLeft, y, 0xCFCFCF, false);
            y += LOG_LINE_HEIGHT;
        }
        renderScrollBar(guiGraphics, boxRight - 8 - SCROLLBAR_WIDTH, boxTop + 22, boxBottom - 8, lines.size(), visibleLines, logScroll);

        if (!manager.isActive() && visualState.showsRestartSummary()) {
            renderRestartSummary(guiGraphics, boxLeft, boxTop, boxRight, boxBottom);
        }
        if (!manager.isActive() && visualState.showsIssueSummary()) {
            renderIssueSummary(guiGraphics, boxLeft, boxTop, boxRight);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderStatusBox(GuiGraphics guiGraphics, DownloadManager manager, SyncProgressStateResolver.SyncProgressVisualState visualState, int left, int top, int right) {
        int bottom = top + STATUS_BOX_HEIGHT;
        int color = visualState.accentColor();
        guiGraphics.fill(left, top, right, bottom, 0x990F0F0F);
        guiGraphics.fill(left, top, left + 4, bottom, color);

        String titleText = statusTitle(visualState);
        guiGraphics.drawString(font, titleText, left + 12, top + 9, 0xFFFFFF, false);

        if (visualState == SyncProgressStateResolver.SyncProgressVisualState.DOWNLOADING && manager.getTotalTasks() > 0) {
            renderProgressBar(guiGraphics, manager, left + 12, top + 23, right - 12);
        } else {
            String detailText = statusDetail(manager, visualState);
            guiGraphics.drawString(font, font.plainSubstrByWidth(detailText, right - left - 24), left + 12, top + 23, 0xD0D0D0, false);
        }
    }

    // Vanilla's own loading/download screens use this same bordered-bar convention
    // (dark outline, gray track, filled portion) instead of plain text percentages.
    private void renderProgressBar(GuiGraphics guiGraphics, DownloadManager manager, int left, int top, int right) {
        int total = Math.max(1, manager.getTotalTasks());
        float progress = Mth.clamp(manager.getCompletedTasks() / (float) total, 0.0F, 1.0F);
        int barHeight = 9;
        int filledRight = left + Math.round((right - left) * progress);

        guiGraphics.fill(left - 1, top - 1, right + 1, top + barHeight + 1, 0xFF000000);
        guiGraphics.fill(left, top, right, top + barHeight, 0xFF555555);
        guiGraphics.fill(left, top, filledRight, top + barHeight, 0xFF80C080);
        guiGraphics.drawCenteredString(font, manager.getCompletedTasks() + "/" + manager.getTotalTasks(), (left + right) / 2, top, 0xFFFFFF);
    }

    private String statusTitle(SyncProgressStateResolver.SyncProgressVisualState visualState) {
        return switch (visualState) {
            case FAILED -> LanguageManager.get("modsync.progress.state_failed");
            case DOWNLOADING -> LanguageManager.get("modsync.progress.state_downloading");
            case RESTART_REQUIRED -> LanguageManager.get("modsync.progress.state_restart");
            case WAITING -> LanguageManager.get("modsync.progress.state_waiting");
            case COMPLETE -> LanguageManager.get("modsync.progress.state_complete");
        };
    }

    private String statusDetail(DownloadManager manager, SyncProgressStateResolver.SyncProgressVisualState visualState) {
        return switch (visualState) {
            case FAILED -> SyncIssueState.getMessage();
            case DOWNLOADING -> LanguageManager.get("modsync.progress.active")
                    .formatted(manager.getCompletedTasks(), manager.getTotalTasks());
            case RESTART_REQUIRED -> LanguageManager.get("modsync.restart_required");
            case WAITING -> LanguageManager.get("modsync.checking");
            case COMPLETE -> LanguageManager.get("modsync.download_complete");
        };
    }

    private void renderRestartSummary(GuiGraphics guiGraphics, int boxLeft, int boxTop, int boxRight, int boxBottom) {
        int summaryLeft = boxLeft + 20;
        int summaryTop = boxTop + 28;
        int summaryRight = boxRight - 20;
        int summaryBottom = Math.min(boxBottom - 20, summaryTop + 90);
        guiGraphics.fill(summaryLeft, summaryTop, summaryRight, summaryBottom, 0xCC101010);
        guiGraphics.drawString(font, LanguageManager.component("modsync.restart.updated_header"), summaryLeft + 8, summaryTop + 8, 0xFFFFFF, false);

        List<String> lines = RestartDetailsFormatter.buildLines(font, summaryRight - summaryLeft - 16,
                Math.max(1, (summaryBottom - summaryTop - 22) / 10));
        int y = summaryTop + 22;
        for (String line : lines) {
            guiGraphics.drawString(font, line, summaryLeft + 8, y, 0xD7D7D7, false);
            y += 10;
        }
    }

    private void renderIssueSummary(GuiGraphics guiGraphics, int boxLeft, int boxTop, int boxRight) {
        int summaryLeft = boxLeft + 20;
        int summaryTop = boxTop + 28;
        int summaryRight = boxRight - 20;
        int summaryBottom = summaryTop + 56;
        guiGraphics.fill(summaryLeft, summaryTop, summaryRight, summaryBottom, 0xCC301010);
        guiGraphics.drawString(font, LanguageManager.component("modsync.error.title"), summaryLeft + 8, summaryTop + 8, 0xFFFFFF, false);
        guiGraphics.drawString(font,
                font.plainSubstrByWidth(SyncIssueState.getMessage(), summaryRight - summaryLeft - 16),
                summaryLeft + 8,
                summaryTop + 22,
                0xFFD4D4,
                false);
        guiGraphics.drawString(font, LanguageManager.component("modsync.error.check_logs"), summaryLeft + 8, summaryTop + 36, 0xF0DE84, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int totalLines = SyncLogBuffer.snapshot().size();
        int maxScroll = Math.max(0, totalLines - getVisibleLines(132, height - 60));
        logScroll = Math.max(0, Math.min(maxScroll, logScroll + (delta < 0 ? 1 : -1)));
        return true;
    }

    private int getVisibleLines(int boxTop, int boxBottom) {
        return Math.max(1, (boxBottom - boxTop - 30) / LOG_LINE_HEIGHT);
    }

    private void renderScrollBar(GuiGraphics guiGraphics, int x, int top, int bottom, int totalLines, int visibleLines, int scroll) {
        guiGraphics.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0x55303030);
        if (totalLines <= visibleLines) {
            guiGraphics.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0xAA707070);
            return;
        }

        int trackHeight = Math.max(1, bottom - top);
        int thumbHeight = Math.max(18, (int) ((visibleLines / (double) totalLines) * trackHeight));
        int maxThumbOffset = Math.max(0, trackHeight - thumbHeight);
        int maxScroll = Math.max(1, totalLines - visibleLines);
        int thumbOffset = (int) Math.round((scroll / (double) maxScroll) * maxThumbOffset);
        guiGraphics.fill(x, top + thumbOffset, x + SCROLLBAR_WIDTH, top + thumbOffset + thumbHeight, 0xFFC8C8C8);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(returnScreen);
        }
    }
}
