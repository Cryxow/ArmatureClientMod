package com.armaturemc.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exact 1.21.8 FOV including fluid/death effects, for the hand and world projections. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("getFov")
    float armature$getFov(Camera camera, float partialTick, boolean world);
}
