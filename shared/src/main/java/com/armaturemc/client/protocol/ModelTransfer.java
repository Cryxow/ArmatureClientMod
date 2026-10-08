package com.armaturemc.client.protocol;

import java.util.BitSet;
import java.util.Optional;

/** One bounded transfer per hand. Duplicate, conflicting and incomplete chunks are rejected. */
public final class ModelTransfer {
    private final String hash;
    private final int slot;
    private final byte[] bytes;
    private final BitSet received = new BitSet();

    public ModelTransfer(ClientProtocol.ModelChunk first) {
        if (first.totalBytes() <= 0 || first.totalBytes() > ClientProtocol.MAX_MODEL) {
            throw new IllegalArgumentException("Invalid transfer size");
        }
        hash = first.hash(); slot = first.slot(); bytes = new byte[first.totalBytes()];
    }
    public Optional<byte[]> accept(ClientProtocol.ModelChunk chunk) {
        if (!hash.equals(chunk.hash()) || slot != chunk.slot() || bytes.length != chunk.totalBytes()) {
            throw new IllegalArgumentException("Conflicting transfer");
        }
        int offset = chunk.index() * ClientProtocol.CHUNK_BYTES;
        byte[] data = chunk.bytes();
        if (chunk.index() < 0 || offset < 0 || offset >= bytes.length
            || data.length != Math.min(ClientProtocol.CHUNK_BYTES, bytes.length - offset)
            || received.get(chunk.index())) throw new IllegalArgumentException("Invalid or duplicate chunk");
        System.arraycopy(data, 0, bytes, offset, data.length); received.set(chunk.index());
        if (received.cardinality() != ClientProtocol.chunkCount(bytes.length)) return Optional.empty();
        if (!ClientProtocol.hash(bytes).equals(hash)) throw new IllegalArgumentException("Model hash mismatch");
        return Optional.of(bytes.clone());
    }
    public String hash() { return hash; }
}
