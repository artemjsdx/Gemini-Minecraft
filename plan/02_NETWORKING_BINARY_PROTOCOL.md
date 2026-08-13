# 02. Networking & Binary Protocol Specification

## 1. Network Protocol Architecture
To achieve ultra-low packet overhead, minimal latency, and zero JSON serialization bottlenecks, all communication between the Fastify server and Three.js client is conducted via **Typed Binary Packets** over WebSockets (`ArrayBuffer` & `DataView`).

```
+------------------------------------------------------------------------------------+
|                             BINARY PACKET STRUCTURE                                |
+---------------+------------------+-------------------+-----------------------------+
| Magic (1 Byte)| Opcode (1 Byte)  | Sequence (2 Bytes)| Payload (0 .. N Bytes)      |
|     0xMC      |    0x00 .. 0xFF  |   Uint16 (BE)     | Typed binary data / Delta   |
+---------------+------------------+-------------------+-----------------------------+
```

---

## 2. Opcode Registry

### 2.1 Client to Server (C2S) Opcodes
| Opcode | Identifier | Size (Bytes) | Description |
| :--- | :--- | :--- | :--- |
| `0x01` | `C2S_HANDSHAKE` | 36 | Protocol version (Uint16), Auth Token (32 Bytes UUID), Client Config |
| `0x02` | `C2S_PLAYER_INPUT` | 26 | Sequence (Uint16), X, Y, Z (3x Float32), Yaw, Pitch (2x Float32), Input Bitmask (Uint16) |
| `0x03` | `C2S_BLOCK_ACTION` | 14 | Action (Uint8: Break/Place/Interact), X (Int32), Y (Int16), Z (Int32), BlockType (Uint16), Face (Uint8) |
| `0x04` | `C2S_INVENTORY_ACTION` | 10 | ActionType (Uint8), SourceSlot (Uint16), DestSlot (Uint16), Quantity (Uint16), CraftRecipeId (Uint16) |
| `0x05` | `C2S_CHUNK_REQUEST` | 9 | ChunkX (Int32), ChunkZ (Int32), LODLevel (Uint8) |
| `0x06` | `C2S_SKILL_UPGRADE` | 4 | SkillBranchId (Uint8), NodeId (Uint16), TargetTier (Uint8) |
| `0x07` | `C2S_CHAT_COMMAND` | Variable | String length (Uint8) + UTF-8 payload |
| `0x08` | `C2S_PING` | 8 | Client timestamp (Float64) |

### 2.2 Server to Client (S2C) Opcodes
| Opcode | Identifier | Size (Bytes) | Description |
| :--- | :--- | :--- | :--- |
| `0x80` | `S2C_HANDSHAKE_ACK` | 28 | Player Entity ID (Uint32), World Seed (Uint64), Spawn X,Y,Z (3x Float32), World Time (Uint32) |
| `0x81` | `S2C_WORLD_SNAPSHOT` | Variable | Server Tick (Uint32), Entity Count (Uint16), Array of Entity States (Interpolation stream) |
| `0x82` | `S2C_CHUNK_DATA` | Variable | ChunkX (Int32), ChunkZ (Int32), CompressionType (Uint8), UncompressedSize (Uint32), Compressed SVO Payload |
| `0x83` | `S2C_BLOCK_DELTA` | 12 | X (Int32), Y (Int16), Z (Int32), NewBlockId (Uint16), Metadata/Light (Uint16) |
| `0x84` | `S2C_ENTITY_SPAWN` | 24 | EntityID (Uint32), Type (Uint16), X, Y, Z (3x Float32), Yaw, Pitch (2x Float32), Metadata |
| `0x85` | `S2C_ENTITY_DESPAWN` | 4 | EntityID (Uint32) |
| `0x86` | `S2C_INVENTORY_SYNC` | Variable | SlotCount (Uint16), Array of [SlotIdx (Uint16), ItemId (Uint16), Count (Uint8), Durability (Uint16), NBT Hash (Uint32)] |
| `0x87` | `S2C_SKILL_DATA_SYNC` | Variable | Player XP (Uint32), Skill Levels (Array of 6x Uint16), Unlocked Talents Bitset (64 bits) |
| `0x88` | `S2C_VOXEL_LIGHT_UPDATE`| Variable| ChunkX (Int32), ChunkZ (Int32), Array of updated light nibbles |
| `0x89` | `S2C_PONG` | 16 | Client timestamp (Float64) + Server timestamp (Float64) for precise clock sync |

---

## 3. High-Performance Binary Serialization Schemas

### 3.1 Input Bitmask Bitflags (Uint16)
```ts
export enum PlayerInputFlags {
  FORWARD      = 1 << 0,  // 0b0000000000000001
  BACKWARD     = 1 << 1,  // 0b0000000000000010
  LEFT         = 1 << 2,  // 0b0000000000000100
  RIGHT        = 1 << 3,  // 0b0000000000001000
  JUMP         = 1 << 4,  // 0b0000000000010000
  SNEAK        = 1 << 5,  // 0b0000000000100000
  SPRINT       = 1 << 6,  // 0b0000000001000000
  ATTACK       = 1 << 7,  // 0b0000000010000000
  USE_ITEM     = 1 << 8,  // 0b0000000100000000
  SKILL_ACTIVE = 1 << 9   // 0b0000001000000000
}
```

### 3.2 Chunk Data Compression (Palette + SVO + RLE + LZ4)
Instead of streaming 16x256x16 = 65,536 raw 16-bit block IDs (131 KB uncompressed per chunk column):
1. **Global/Local Palette**: Map 65,536 voxels to a compact palette (e.g. 8-16 unique block IDs per chunk = 3 to 4 bits per voxel).
2. **Sub-chunk SVO Serialization**: Break the chunk into 16 sub-chunks (16x16x16 = 4096 voxels each). Uniform sub-chunks (e.g., solid stone or pure air) are encoded as **1 byte** (Leaf Node).
3. **RLE (Run-Length Encoding)**: Consecutive identical voxels are stored as `[Count, PaletteIndex]`.
4. **LZ4 / Snappy Compression**: Compressed using WebAssembly LZ4 compressor on server and decompressed in < 0.2ms inside the Client Web Worker.
5. **Typical Network Footprint**: **300 Bytes to 4.2 KB** per chunk column (97%+ reduction).

---

## 4. Latency Compensation, Prediction & Interpolation

### 4.1 Client-Side Prediction & Reconciliation
- The client executes local kinematic movement at 60/120/144 Hz based on player input flags and local SVO collision boxes.
- Maintains an **Input Ring Buffer** of size 128: `[{ sequence: 104, input, position, timestamp }]`.
- When server sends `S2C_WORLD_SNAPSHOT` with authoritative position for `sequence: 100`, the client compares server position with stored prediction at sequence 100.
- If delta > 0.05 units: snaps to server position and **re-simulates inputs from sequence 101 to 104** in a single frame (error-free reconciliation).

### 4.2 Hermite / Cubic Entity Interpolation
- Remote entities (other players, hostile mobs, arrows, item drops) are rendered using a 100ms interpolation buffer.
- Positions are interpolated using Catmull-Rom or Cubic Hermite splines to guarantee silky smooth visual tracking even over variable jitter networks:
$$\vec{P}(t) = (2t^3 - 3t^2 + 1)\vec{P}_0 + (t^3 - 2t^2 + t)\vec{M}_0 + (-2t^3 + 3t^2)\vec{P}_1 + (t^3 - t^2)\vec{M}_1$$
