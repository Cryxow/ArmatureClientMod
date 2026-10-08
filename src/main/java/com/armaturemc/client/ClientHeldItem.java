package com.armaturemc.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.PoseStack;

/** Profile resolution stays on the server; only this visual copy receives the resolved model. */
public final class ClientHeldItem {
    public static boolean suppressMarkerTint;
    private ClientHeldItem() { }
    static ItemStack visual(ItemStack original, String model) {
        if (model == null) return original;
        ResourceLocation location = ResourceLocation.tryParse(model);
        if (location == null) throw new IllegalArgumentException("Invalid resolved held-item model");
        ItemStack result = original.copy(); result.set(DataComponents.ITEM_MODEL, location); return result;
    }

    static void render(ItemRenderer renderer, ItemStack original, String model, boolean offhand,
                       PoseStack poses, MultiBufferSource buffers, Level level, int light, int seed) {
        ItemStack item = visual(original, model);
        boolean previousMarkerTint = suppressMarkerTint;
        suppressMarkerTint = model != null && model.startsWith("armature:");
        try {
            // The entity-free overload matches ItemDisplay. Source/rules aliases
            // already encode Armature's selected state; vanilla use stays neutral.
            renderer.renderStatic(item, offhand ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, light, OverlayTexture.NO_OVERLAY,
                poses, buffers, level, seed);
        } finally { suppressMarkerTint = previousMarkerTint; }
    }
}
