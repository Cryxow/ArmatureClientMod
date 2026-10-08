package com.armaturemc.client;

import com.armaturemc.client.protocol.ClientProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** No extra byte-array length prefix: Bukkit's plugin message is the entire payload. */
public record ArmaturePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<ArmaturePayload> TYPE = new Type<>(ResourceLocation.parse(ClientProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmaturePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeBytes(payload.bytes),
        buffer -> {
            if (buffer.readableBytes() > ClientProtocol.MAX_PACKET) throw new IllegalArgumentException("Armature packet too large");
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return new ArmaturePayload(bytes);
        });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
