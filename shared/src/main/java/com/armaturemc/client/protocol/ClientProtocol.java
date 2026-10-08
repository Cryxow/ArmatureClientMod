package com.armaturemc.client.protocol;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Platform-free, bounded binary payload carried verbatim by Bukkit and Fabric. */
public final class ClientProtocol {
    public static final String CHANNEL = "armature:client";
    // Version 5 sends local additive offsets, never sampled server animation matrices.
    // Version 6 offers Options/Frame before chunks and permits Ready from the uploaded-model cache.
    // Version 7 coordinates resource reload completion before switching renderer ownership.
    // Version 8 sends session-owned held-item snapshots before the visibility frame.
    // Version 9 retains an outgoing channel for every hotbar presentation in either hand.
    public static final int VERSION = 9;
    public static final int MAX_HELD_ITEM = 12_000;
    public static final int MODEL_CHANNELS = 20;
    public static final int MAX_PACKET = 30_000;
    public static final int CHUNK_BYTES = 24_000;
    public static final int MAX_MODEL = 8 * 1024 * 1024;
    public static final int MAX_BONES = 256;
    public static final int MAX_ENTITIES = 512;
    public static final String BONE_ID_PATTERN = "[a-zA-Z0-9._-]{1,36}";
    public static final long FRAME_TIMEOUT_NANOS = 1_000_000_000L;

    private ClientProtocol() { }
    public sealed interface Message permits Hello, ModelChunk, Ready, Frame, Clear, Options, RendererSwitch, RendererReady, RendererCommit, HeldItems { }
    /** Compressed vanilla item NBT; zero bytes represent an empty hand. */
    public record HeldItems(int slot, UUID session, byte[] main, byte[] off) implements Message {
        public HeldItems {
            check(main.length <= MAX_HELD_ITEM && off.length <= MAX_HELD_ITEM, "Held-item budget");
            main = main.clone(); off = off.clone();
        }
        @Override public byte[] main() { return main.clone(); }
        @Override public byte[] off() { return off.clone(); }
    }
    public record Hello(int version) implements Message { }
    public static final UUID NO_PACK = new UUID(0, 0);
    public record RendererSwitch(UUID token, UUID pack, boolean client) implements Message { }
    public record RendererReady(UUID token) implements Message { }
    public record RendererCommit(UUID token, boolean client) implements Message { }
    public record ModelChunk(int slot, String hash, int totalBytes, int index, byte[] bytes) implements Message {
        public ModelChunk { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Ready(int slot, String hash) implements Message { }
    /** Local additive translation (blocks) and quaternion, independent of authored playback. */
    public record Bone(String id, boolean visible, float[] offset) {
        public Bone {
            check(id.matches(BONE_ID_PATTERN), "Bone id");
            check(offset.length == 7, "Offset size");
            for (float value : offset) check(Float.isFinite(value), "Finite offset");
            double norm = 0;
            for (int i = 3; i < 7; i++) norm += (double)offset[i] * offset[i];
            check(norm > 1e-12 && Math.abs(norm - 1) < .001, "Offset quaternion");
            offset = offset.clone();
        }
        public Bone(String id, boolean visible) { this(id, visible, new float[]{0, 0, 0, 0, 0, 0, 1}); }
        @Override public float[] offset() { return offset.clone(); }
    }
    public record View(float fov, boolean yLock, float zoom, boolean mountedOrigin) {
        public View {
            check(Float.isFinite(fov) && (fov == 0 || fov >= 1 && fov <= 179), "View FOV");
            check(Float.isFinite(zoom) && zoom >= 1 && zoom <= 4, "View zoom");
        }
        public static View defaults() { return new View(70, true, 1, false); }
    }
    public record Frame(int slot, UUID session, String hash, long sequence, boolean replaceHand,
                        int[] entityIds, List<Bone> bones, View view, String control) implements Message {
        public Frame {
            entityIds = entityIds.clone(); bones = List.copyOf(bones);
            check(control != null && control.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024, "Control budget");
        }
        public Frame(int slot, UUID session, String hash, long sequence, boolean replaceHand,
                     int[] entityIds, List<Bone> bones, View view) {
            this(slot, session, hash, sequence, replaceHand, entityIds, bones, view, "{}");
        }
        public Frame(int slot, UUID session, String hash, long sequence, boolean replaceHand,
                     int[] entityIds, List<Bone> bones) {
            this(slot, session, hash, sequence, replaceHand, entityIds, bones, View.defaults());
        }
        @Override public int[] entityIds() { return entityIds.clone(); }
    }
    public record Clear(int slot) implements Message { }
    /** Session configuration sent separately from the bounded state packet, only when changed. */
    public record Options(int slot, String json) implements Message {
        public Options { check(json != null && json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8192, "Options budget"); }
    }

    public static byte[] encode(Message message) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            if (message instanceof Hello m) { out.writeByte(0); out.writeInt(m.version()); }
            else if (message instanceof ModelChunk m) {
                out.writeByte(1); out.writeByte(slot(m.slot())); writeHash(out, m.hash());
                check(m.totalBytes() > 0 && m.totalBytes() <= MAX_MODEL, "Model size");
                check(m.index() >= 0 && m.index() < chunkCount(m.totalBytes()), "Chunk index");
                byte[] chunk = m.bytes();
                check(chunk.length == Math.min(CHUNK_BYTES, m.totalBytes() - m.index() * CHUNK_BYTES), "Chunk size");
                out.writeInt(m.totalBytes()); out.writeInt(m.index()); out.writeInt(chunk.length); out.write(chunk);
            } else if (message instanceof Ready m) {
                out.writeByte(2); out.writeByte(slot(m.slot())); writeHash(out, m.hash());
            } else if (message instanceof Frame m) {
                out.writeByte(3); out.writeByte(slot(m.slot()));
                out.writeLong(m.session().getMostSignificantBits()); out.writeLong(m.session().getLeastSignificantBits());
                writeHash(out, m.hash()); out.writeLong(m.sequence()); out.writeBoolean(m.replaceHand());
                out.writeFloat(m.view().fov()); out.writeBoolean(m.view().yLock());
                out.writeFloat(m.view().zoom()); out.writeBoolean(m.view().mountedOrigin());
                out.writeUTF(m.control());
                check(m.entityIds().length <= MAX_ENTITIES, "Entity count");
                out.writeInt(m.entityIds().length); for (int id : m.entityIds()) out.writeInt(id);
                check(m.bones().size() <= MAX_BONES, "Bone count");
                out.writeInt(m.bones().size());
                for (Bone bone : m.bones()) {
                    check(bone.id().matches(BONE_ID_PATTERN), "Bone id"); out.writeUTF(bone.id());
                    out.writeBoolean(bone.visible()); for (float value : bone.offset()) out.writeFloat(value);
                }
            } else if (message instanceof Clear m) { out.writeByte(4); out.writeByte(slot(m.slot())); }
            else if (message instanceof Options m) { out.writeByte(5); out.writeByte(slot(m.slot())); out.writeUTF(m.json()); }
            else if (message instanceof RendererSwitch m) {
                out.writeByte(6); writeUuid(out, m.token()); writeUuid(out, m.pack()); out.writeBoolean(m.client());
            } else if (message instanceof RendererReady m) { out.writeByte(7); writeUuid(out, m.token()); }
            else if (message instanceof RendererCommit m) { out.writeByte(8); writeUuid(out, m.token()); out.writeBoolean(m.client()); }
            else if (message instanceof HeldItems m) {
                out.writeByte(9); out.writeByte(slot(m.slot())); writeUuid(out, m.session());
                out.writeInt(m.main.length); out.write(m.main);
                out.writeInt(m.off.length); out.write(m.off);
            }
            else throw new IllegalArgumentException("Unknown message");
            out.flush(); check(bytes.size() <= MAX_PACKET, "Packet size"); return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static Message decode(byte[] bytes) throws IOException {
        check(bytes.length <= MAX_PACKET, "Packet size");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        check(in.readInt() == VERSION, "Protocol version");
        Message result = switch (in.readUnsignedByte()) {
            case 9 -> new HeldItems(slot(in.readUnsignedByte()), readUuid(in), readItem(in), readItem(in));
            case 0 -> new Hello(in.readInt());
            case 1 -> {
                int slot = slot(in.readUnsignedByte()); String hash = readHash(in);
                int total = in.readInt(), index = in.readInt(), size = in.readInt();
                check(total > 0 && total <= MAX_MODEL, "Model size");
                check(index >= 0 && index < chunkCount(total), "Chunk index");
                check(size == Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES), "Chunk size");
                check(size <= in.available(), "Truncated chunk");
                yield new ModelChunk(slot, hash, total, index, in.readNBytes(size));
            }
            case 2 -> new Ready(slot(in.readUnsignedByte()), readHash(in));
            case 3 -> {
                int slot = slot(in.readUnsignedByte()); UUID session = new UUID(in.readLong(), in.readLong());
                String hash = readHash(in); long sequence = in.readLong(); boolean replace = in.readBoolean();
                View view = new View(in.readFloat(), in.readBoolean(), in.readFloat(), in.readBoolean());
                String control = in.readUTF();
                int entities = in.readInt(); check(entities >= 0 && entities <= MAX_ENTITIES, "Entity count");
                int[] ids = new int[entities]; for (int i = 0; i < entities; i++) ids[i] = in.readInt();
                int count = in.readInt(); check(count >= 0 && count <= MAX_BONES, "Bone count");
                var bones = new ArrayList<Bone>(count); Set<String> unique = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    String id = in.readUTF(); check(id.matches(BONE_ID_PATTERN) && unique.add(id), "Bone id");
                    boolean visible = in.readBoolean(); float[] offset = new float[7];
                    for (int j = 0; j < 7; j++) offset[j] = in.readFloat();
                    bones.add(new Bone(id, visible, offset));
                }
                yield new Frame(slot, session, hash, sequence, replace, ids, bones, view, control);
            }
            case 4 -> new Clear(slot(in.readUnsignedByte()));
            case 5 -> new Options(slot(in.readUnsignedByte()), in.readUTF());
            case 6 -> new RendererSwitch(readUuid(in), readUuid(in), in.readBoolean());
            case 7 -> new RendererReady(readUuid(in));
            case 8 -> new RendererCommit(readUuid(in), in.readBoolean());
            default -> throw new IOException("Unknown message type");
        };
        check(in.available() == 0, "Trailing payload bytes"); return result;
    }

    private static byte[] readItem(DataInputStream in) throws IOException {
        int length = in.readInt();
        check(length >= 0 && length <= MAX_HELD_ITEM && length <= in.available(), "Held-item size");
        return in.readNBytes(length);
    }

    public static int chunkCount(int size) { return (size + CHUNK_BYTES - 1) / CHUNK_BYTES; }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static int slot(int value) { check(value >= 0 && value < MODEL_CHANNELS, "Model channel"); return value; }
    private static void writeHash(DataOutputStream out, String hash) throws IOException {
        check(hash.matches("[a-f0-9]{64}"), "Hash"); out.writeUTF(hash);
    }
    private static String readHash(DataInputStream in) throws IOException {
        String hash = in.readUTF(); check(hash.matches("[a-f0-9]{64}"), "Hash"); return hash;
    }
    private static void check(boolean valid, String field) {
        if (!valid) throw new IllegalArgumentException("Invalid " + field);
    }
}
