package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public abstract class MinecraftReloadMixin {
    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void armature$reload(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        ArmatureClient.resourceReload(callback.getReturnValue());
    }
}
