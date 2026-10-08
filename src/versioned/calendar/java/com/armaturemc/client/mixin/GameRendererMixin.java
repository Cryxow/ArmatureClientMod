package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sample before Camera.update builds the world view and culling frustum. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void armature$frame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
        ArmatureClient.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
    }
}
