package com.armaturemc.client;

import java.io.IOException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ArmatureConfigScreen extends Screen {
    private final Screen parent;
    private boolean saveFailed;

    ArmatureConfigScreen(Screen parent) {
        super(Component.translatable("armature_client.config.title")); this.parent = parent;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(260, width - 32);
        addRenderableWidget(Button.builder(toggleLabel(), button -> {
            try {
                ArmatureClient.setRenderingEnabled(!ArmatureClient.isRenderingEnabled());
                saveFailed = false;
            } catch (IOException failure) { saveFailed = true; }
            button.setMessage(toggleLabel());
        }).bounds((width - buttonWidth) / 2, height / 2 - 12, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
            .bounds((width - buttonWidth) / 2, height / 2 + 65, buttonWidth, 20).build());
    }

    private Component toggleLabel() {
        return Component.translatable("armature_client.config.rendering",
            Component.translatable(ArmatureClient.isRenderingEnabled() ? "options.on" : "options.off"));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 55, 0xffffffff);
        var description = Component.translatable(ArmatureClient.isRendererSwitchPending()
            ? "armature_client.config.switch_pending"
            : ArmatureClient.isRendererActive() != ArmatureClient.isRenderingEnabled()
            ? "armature_client.config.switch_failed" : ArmatureClient.isRenderingEnabled()
            ? "armature_client.config.enabled_description" : "armature_client.config.disabled_description");
        int y = height / 2 + 20;
        for (var line : font.split(description, Math.min(300, width - 32))) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xffaaaaaa); y += font.lineHeight + 2;
        }
        if (saveFailed) graphics.drawCenteredString(font, Component.translatable("armature_client.config.save_failed"),
            width / 2, height / 2 + 50, 0xffff5555);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
