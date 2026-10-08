package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.*;
import net.minecraft.client.renderer.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void armature$render(float partialTick, PoseStack poses, MultiBufferSource.BufferSource buffers,
                                 LocalPlayer player, int light, CallbackInfo ci) {
        ArmatureClient.render(poses, buffers, light, partialTick);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void armature$hideVanilla(AbstractClientPlayer player, float partialTick, float pitch,
                                      InteractionHand hand, float swing, ItemStack item, float equip,
                                      PoseStack poses, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (ArmatureClient.hidesVanillaHand(hand)) ci.cancel();
    }
}
