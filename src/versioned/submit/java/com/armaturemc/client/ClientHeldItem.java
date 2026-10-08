package com.armaturemc.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.PoseStack;

/** Neutral item extraction followed by submission in the existing hand collector. */
public final class ClientHeldItem {
    public static boolean suppressMarkerTint;
    private ClientHeldItem() { }
    static ItemStack visual(ItemStack original, String model) {
        if (model == null) return original;
        ResourceLocation location = ResourceLocation.tryParse(model);
        if (location == null) throw new IllegalArgumentException("Invalid resolved held-item model");
        ItemStack result = original.copy(); result.set(DataComponents.ITEM_MODEL, location); return result;
    }

    static void render(ItemModelResolver resolver, ItemStack original, String model, boolean offhand,
                       PoseStack poses, SubmitNodeCollector collector, Level level, int light, int seed) {
        ItemStack item = visual(original, model);
        boolean previousMarkerTint = suppressMarkerTint;
        suppressMarkerTint = model != null && model.startsWith("armature:");
        try {
            var state = new ItemStackRenderState();
            // Do not supply the player: tridents/shields remain neutral unless the
            // server's held-item policy explicitly resolved a different model.
            resolver.updateForTopItem(state, item, offhand ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, level, null, seed);
            state.submit(poses, collector, light, OverlayTexture.NO_OVERLAY, 0);
        } finally { suppressMarkerTint = previousMarkerTint; }
    }
}
