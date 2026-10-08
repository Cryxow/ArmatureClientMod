package com.armaturemc.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientHeldItemTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }
    @Test void resolvedStaticSourceOrRuleModelOnlyChangesTheRenderedCopy() {
        ItemStack inventory = new ItemStack(Items.BOW);
        var original = inventory.get(DataComponents.ITEM_MODEL);
        var copy = ClientHeldItem.visual(inventory, "armature:held/states/test");
        assertNotSame(inventory, copy);
        assertEquals(ResourceLocation.parse("armature:held/states/test"), copy.get(DataComponents.ITEM_MODEL));
        assertEquals(original, inventory.get(DataComponents.ITEM_MODEL));
        assertSame(inventory, ClientHeldItem.visual(inventory, null));
    }

    @Test void vanillaUseConditionsStayNeutralAndServerPolicyAliasesSurviveBothHandContexts() {
        int[] calls = {0};
        var renderer = new net.minecraft.client.renderer.item.ItemModelResolver(
                new net.minecraft.client.resources.model.ModelManager(null, null, null)) {
            @Override public void updateForTopItem(net.minecraft.client.renderer.item.ItemStackRenderState state, ItemStack item,
                    ItemDisplayContext context, net.minecraft.world.level.Level level,
                    net.minecraft.world.entity.ItemOwner owner, int seed) {
                assertNull(owner, "Armature items must resolve as ItemDisplays, not living-player held items");
                assertFalse(new net.minecraft.client.renderer.item.properties.conditional.IsUsingItem()
                    .get(item, null, null, seed, context));
                assertEquals(0, new net.minecraft.client.renderer.item.properties.numeric.UseDuration(false)
                    .get(item, null, owner, seed));
                assertTrue(context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
                if (ClientHeldItem.suppressMarkerTint)
                    assertEquals(ResourceLocation.parse("armature:held/states/selected"), item.get(DataComponents.ITEM_MODEL));
                calls[0]++;
            }
        };
        for (var type : new Item[]{Items.TRIDENT, Items.SHIELD, Items.BOW}) for (boolean offhand : new boolean[]{false, true}) {
            var inventory = new ItemStack(type); var original = inventory.get(DataComponents.ITEM_MODEL);
            for (String model : new String[]{null, "armature:held/states/selected"}) {
                ClientHeldItem.render(renderer, inventory, model, offhand, new com.mojang.blaze3d.vertex.PoseStack(),
                    null, null, 0, 17);
                assertEquals(original, inventory.get(DataComponents.ITEM_MODEL));
                assertFalse(ClientHeldItem.suppressMarkerTint);
            }
        }
        assertEquals(12, calls[0]);
    }
}
