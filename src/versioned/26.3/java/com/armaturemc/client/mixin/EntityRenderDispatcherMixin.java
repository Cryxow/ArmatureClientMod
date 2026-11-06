package com.armaturemc.client.mixin;

import com.armaturemc.client.ClientArmor;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Shadow @Final private EquipmentAssetManager equipmentAssets;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void armature$reloadArmor(ResourceManager resources, CallbackInfo callback) {
        ClientArmor.reload(equipmentAssets);
    }

}
