# Armature client protocol

The source of truth is `shared/src/main/java/com/armaturemc/client/protocol/ClientProtocol.java`.
This snapshot uses **version 9** on the play-phase channel `armature:client`.
Messages begin with the protocol version as a big-endian 32-bit integer, followed
by an unsigned-byte opcode. Strings use the codec's Java DataInput/DataOutput
encoding, not Minecraft's generic packet string encoding. Use the shared codec
rather than maintaining a second implementation.

| Opcode | Message | Direction | Purpose |
| --- | --- | --- | --- |
| 0 | Hello | Client → server | Requested protocol, or 0 to request server rendering |
| 1 | ModelChunk | Server → client | Ordered bounded chunks of a hash-identified model bundle |
| 2 | Ready | Client → server | Model hash uploaded and ready in the addressed channel |
| 3 | Frame | Server → client | Session identity, sequence, ownership, visibility and animation control |
| 4 | Clear | Server → client | Release a channel's current presentation |
| 5 | Options | Server → client | Model basis, motion, physics and camera configuration |
| 6 | RendererSwitch | Server → client | Prepare renderer ownership and fallback pack transition |
| 7 | RendererReady | Client → server | Matching transition token, after resource reload completion |
| 8 | RendererCommit | Server → client | Commit the prepared renderer transition |
| 9 | HeldItems | Server → client | Session-owned compressed vanilla item NBT snapshots |

## Invariants

- The server owns profile selection, action arbitration, configured `held-item`
  policy, visibility and additive adjustments. The client samples authored clips
  at render-frame time; frames carry no baked server animation matrices.
- Model hashes bind uploaded/cached bundles. Session UUIDs isolate playback and
  held-item snapshots. Reject stale sequences and mismatched bundles.
- Options and ownership frames can arrive before model chunks. Ownership hides
  server geometry during transfer; `Ready` follows parsing and texture upload.
  The plugin waits for the incoming bundle before activating its equip animation.
- Renderer ownership changes must preserve the pack/reload token handshake.
  A UI preference change alone does not complete a switch.
- Protocol 9 exposes 20 model channels for active/outgoing hand presentations.
  Obtain channel assignments from the matching server implementation; they are
  not interchangeable with the older four-channel protocol 8.
- Unkeyed bones/channels use their neutral animated transform: translation and
  rotation 0, scale 1. Model rest transforms and hierarchy still apply. Preserve
  authored blend-out and incoming pose transitions without adding a second fade.

## Bounds in this snapshot

Maximum packet size: 30,000 bytes. Model bundle: 8 MiB. Chunk: 24,000 bytes.
Frame: up to 256 bones and 512 server entity IDs. Control JSON: 1,024 UTF-8 bytes.
Options JSON: 8,192 UTF-8 bytes. Each hand's compressed item NBT: 12,000 bytes;
empty bytes mean an empty hand. The client also bounds decompressed item NBT.
Frame timeout: one second, with resource reload handling in the renderer handoff.

Retain all validation and budget checks in forks. A protocol change requires a
matching server implementation and version bump; accepting different data under
the same version can break existing clients.
