package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void armature$render(float partialTick, PoseStack poses, SubmitNodeCollector collector,
                                 LocalPlayer player, int light, CallbackInfo callback) {
        ArmatureClient.render(poses, collector, light, partialTick);
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void armature$hideVanilla(AbstractClientPlayer player, float partialTick, float pitch,
                                      InteractionHand hand, float swing, ItemStack item, float equip,
                                      PoseStack poses, SubmitNodeCollector collector, int light, CallbackInfo callback) {
        if (ArmatureClient.hidesVanillaHand(hand)) callback.cancel();
    }
}
