package com.armaturemc.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the existing hand FOV; never modify the user's world projection. */
@Mixin(Camera.class)
public interface CameraFovAccessor {
    @Accessor("hudFov") float armature$getHudFov();
}
