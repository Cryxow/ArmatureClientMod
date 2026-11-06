package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cull owned virtual entities before the 26.3 level extractor creates render states. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void armature$hideReplaced(Entity entity, Frustum frustum, double x, double y, double z,
                                      float partialTick, long gameTime, CallbackInfoReturnable<Boolean> callback) {
        if (ArmatureClient.hidesEntity(entity.getId())) callback.setReturnValue(false);
    }
}
