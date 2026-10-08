package com.armaturemc.client.mixin;

import com.armaturemc.client.ArmatureClient;
import com.armaturemc.client.ClientCameraEffect;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply after entity alignment, before FOV/frustum/projection extraction. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private float xRot;
    @Shadow private float yRot;

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void armature$presentation(float partialTick, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        Camera camera = (Camera)(Object)this;
        if (!client.options.getCameraType().isFirstPerson() || camera.entity() != client.player
            || camera != client.gameRenderer.getMainCamera()) return;
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
