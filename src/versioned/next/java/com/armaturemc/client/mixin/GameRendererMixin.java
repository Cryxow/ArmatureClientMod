package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.2 removes the update method's render-level argument. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void armature$frame(DeltaTracker deltaTracker, CallbackInfo callback) {
        ArmatureClient.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
    }
}
