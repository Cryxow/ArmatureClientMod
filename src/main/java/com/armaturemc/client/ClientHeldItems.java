package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Visual equipment belongs to a presentation session, independently of the physical hotbar. */
record ClientHeldItems(UUID session, ItemStack main, ItemStack off) {
    static ClientHeldItems decode(ClientProtocol.HeldItems items, HolderLookup.Provider registries) {
        return new ClientHeldItems(items.session(), decode(items.main(), registries), decode(items.off(), registries));
    }

    static ItemStack decode(byte[] bytes, HolderLookup.Provider registries) {
        if (bytes.length == 0) return ItemStack.EMPTY;
        try {
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(2 * 1024 * 1024));
            return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid Armature held-item NBT", failure);
        }
    }
}
