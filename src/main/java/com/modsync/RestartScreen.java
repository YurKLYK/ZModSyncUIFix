package com.modsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;

import java.util.List;

public class RestartScreen extends ConfirmScreen {
    public RestartScreen() {
        super(
                confirmed -> {
                    if (confirmed) {
                        Minecraft.getInstance().stop();
                    } else {
                        Minecraft.getInstance().setScreen(null);
                    }
                },
                LanguageManager.component("modsync.restart.title"),
                LanguageManager.component("modsync.restart_required"),
                LanguageManager.component("modsync.restart_now"),
                LanguageManager.component("modsync.restart_later")
        );
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int boxLeft = 40;
        int boxTop = height - 90;
        int boxRight = width - 40;
        int boxBottom = height - 20;
        guiGraphics.fill(boxLeft, boxTop, boxRight, boxBottom, 0x99000000);
        guiGraphics.drawString(font, LanguageManager.component("modsync.restart.updated_header"), boxLeft + 8, boxTop + 8, 0xFFFFFF, false);

        List<String> lines = RestartDetailsFormatter.buildLines(font, boxRight - boxLeft - 16,
                Math.max(1, (boxBottom - boxTop - 22) / 10));
        int y = boxTop + 22;
        for (String line : lines) {
            guiGraphics.drawString(font, line, boxLeft + 8, y, 0xD7D7D7, false);
            y += 10;
        }
    }
}
