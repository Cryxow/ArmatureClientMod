package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void armature$render(float partialTick, PoseStack poses, SubmitNodeCollector collector,
                                 PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, CallbackInfo callback) {
        if (player.avatarRenderState == null || hands.handRenderSelection == null) return;
        ArmatureClient.render(poses, collector, player.avatarRenderState.lightCoords, partialTick);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void armature$hideVanilla(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, float partialTick, float pitch,
                                      InteractionHand hand, float swing, ItemStack item, float equip,
                                      PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo callback) {
        if (ArmatureClient.hidesVanillaHand(hand)) callback.cancel();
    }
}
