package com.armaturemc.client.mixin;

import com.armaturemc.client.ClientHeldItem;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Constant.class)
abstract class ConstantItemTintMixin {
    @Inject(method = "calculate", at = @At("RETURN"), cancellable = true)
    private void armature$removeServerMarker(ItemStack item, ClientLevel level, LivingEntity entity,
                                            CallbackInfoReturnable<Integer> ci) {
        if (ClientHeldItem.suppressMarkerTint && ci.getReturnValue() == 0xff2bd9ff) ci.setReturnValue(0xffffffff);
    }
}
