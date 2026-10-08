package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sample once per frame; leave Minecraft's world projection and player FOV unchanged. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void armature$frame(DeltaTracker deltaTracker, CallbackInfo callback) {
        ArmatureClient.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
    }
}
