package com.armaturemc.client;

import static org.junit.jupiter.api.Assertions.*;
import com.armaturemc.client.protocol.ClientProtocol;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class ClientHeldItemsTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test void paperCompatibleNbtPreservesOutgoingItemComponentsAndEmptyHands() throws Exception {
        var registries = VanillaRegistries.createLookup();
        var inventory = new ItemStack(Items.SHIELD);
        var model = ResourceLocation.parse("armature:held/states/outgoing");
        inventory.set(DataComponents.ITEM_MODEL, model);
        inventory.set(DataComponents.DAMAGE, 7);
        inventory.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var tag = (CompoundTag) ItemStack.CODEC.encodeStart(
            registries.createSerializationContext(NbtOps.INSTANCE), inventory).getOrThrow();
        // Paper serializeAsBytes adds DataVersion, then writes this vanilla compound as gzip NBT.
        tag.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        var session = UUID.randomUUID();
        var message = new ClientProtocol.HeldItems(2, session, bytes.toByteArray(), new byte[0]);
        var decoded = ClientHeldItems.decode(message, registries);
        inventory.set(DataComponents.ITEM_MODEL, ResourceLocation.parse("armature:held/states/incoming"));
        assertEquals(session, decoded.session());
        assertTrue(decoded.main().is(Items.SHIELD));
        assertEquals(model, decoded.main().get(DataComponents.ITEM_MODEL));
        assertEquals(7, decoded.main().get(DataComponents.DAMAGE));
        assertTrue(decoded.main().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(decoded.off().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ClientHeldItems.decode(new byte[]{1, 2, 3}, registries));
    }
}
