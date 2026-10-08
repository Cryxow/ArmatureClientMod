package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.armaturemc.client.ClientCameraEffect;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply the frame's presentation before world matrices, frustum and Iris uniforms are built. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private float xRot;
    @Shadow private float yRot;

    @Inject(method = "setup", at = @At("TAIL"))
    private void armature$presentation(BlockGetter level, Entity entity, boolean detached, boolean mirrored,
                                       float partialTick, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (detached || entity != client.player || (Object)this != client.gameRenderer.getMainCamera()) return;
        var effect = ArmatureClient.cameraEffect();
        if (!effect.rotates()) return;
        rotation.set(effect.orientation(rotation));
        forwards.set(0, 0, -1).rotate(rotation);
        up.set(0, 1, 0).rotate(rotation);
        left.set(-1, 0, 0).rotate(rotation);
        yRot = ClientCameraEffect.yaw(forwards, yRot);
        xRot = ClientCameraEffect.pitch(forwards);
    }
}
