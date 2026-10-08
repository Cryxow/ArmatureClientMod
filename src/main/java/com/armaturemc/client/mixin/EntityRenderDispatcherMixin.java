package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.armaturemc.client.ClientArmor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Shadow @Final private EquipmentAssetManager equipmentAssets;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void armature$reloadArmor(ResourceManager resources, CallbackInfo ci) {
        ClientArmor.reload(equipmentAssets);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), cancellable = true)
    private void armature$hideReplaced(Entity entity, double x, double y, double z, float partialTick,
                                      PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (ArmatureClient.hidesEntity(entity.getId())) ci.cancel();
    }
}
