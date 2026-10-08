package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.armaturemc.client.ClientArmor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Shadow @Final private EquipmentAssetManager equipmentAssets;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void armature$reloadArmor(ResourceManager resources, CallbackInfo callback) {
        ClientArmor.reload(equipmentAssets);
    }

    // Cull before extraction: owned virtual entities never enter a render state.
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void armature$hideReplaced(Entity entity, Frustum frustum, double x, double y, double z,
                                      CallbackInfoReturnable<Boolean> callback) {
        if (ArmatureClient.hidesEntity(entity.getId())) callback.setReturnValue(false);
    }
}
