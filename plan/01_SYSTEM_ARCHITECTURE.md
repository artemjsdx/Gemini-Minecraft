# 01. High-Level System Architecture & Topology

## 1. System Vision & Overview
The next-generation Web Minecraft engine is an ultra-performant, browser-based voxel multiplayer sandbox platform engineered with **React (UI/Overlay)**, **Three.js (Custom WebGL2/WebGPU Deferred Pipeline)**, **Web Workers (Meshing, Octree & Physics)**, and a **Node.js Fastify WebSocket Server** utilizing low-overhead custom binary protocols.

```
+---------------------------------------------------------------------------------------------------+
|                                      CLIENT BROWSER (V8 / Blink)                                 |
|                                                                                                   |
|  +---------------------------+  +--------------------------------------------------------------+  |
|  |     React 19 UI Layer     |  |                 Three.js Custom Engine Loop                  |  |
|  | (HUD, Inventory, Skills)  |  |  +--------------------+  +--------------------------------+  |  |
|  +-------------+-------------+  |  | Deferred Pipeline  |  |   Instanced Foliage/Entities   |  |  |
|                | React Context  |  | (GBuffer, PBR, CSM) |  |   (InstancedBufferGeometry)    |  |  |
|                v                |  +---------+----------+  +---------------+----------------+  |  |
|  +---------------------------+  |            | GBuffer                    |                    |  |
|  |   Client Game Director    +--+------------+                            |                    |  |
|  |  (Input, State, Camera)   |  +-----------------------------------------+-----------------+  |  |
|  +-------------+-------------+                                                                 |  |
|                |                                                                                  |  |
|  +-------------+-------------------------------------------------------------------------------+  |
|  |                                  Web Worker Ecosystem                                       |  |
|  |  +-------------------------+  +-------------------------+  +-----------------------------+  |  |
|  |  | Worker 1..N: Meshing    |  | Worker: Voxel Physics   |  | Worker: Spatial Octree Sync |  |  |
|  |  | (Greedy Voxel Mesher)   |  | (AABB & Swept Raycast)  |  | (Frustum & Occlusion Culling|  |  |
|  |  +-------------------------+  +-------------------------+  +-----------------------------+  |  |
|  +---------------------------------------------+-----------------------------------------------+  |
|                                                | Transferable ArrayBuffers                        |
|                                                v                                                  |
|                              +-----------------------------------+                                |
|                              |   Binary WebSocket Client (WS)    |                                |
|                              | (Typed ArrayBuffers / LZ4 Decomp) |                                |
|                              +-----------------+-----------------+                                |
+------------------------------------------------|--------------------------------------------------+
                                                 | Binary TCP/WebSocket
                                                 v
+------------------------------------------------+--------------------------------------------------+
|                                  SERVER (Node.js 22+ / Fastify)                                   |
|                                                                                                   |
|  +---------------------------------------------------------------------------------------------+  |
|  |                         Fastify Core + @fastify/websocket Gateway                           |  |
|  |             (uWebSockets.js / Node.js Engine with Binary Packet Multiplexer)                |  |
|  +---------------------------------------------+-----------------------------------------------+  |
|                                                | Zero-Copy Buffer Slices                          |
|                                                v                                                  |
|  +---------------------------------------------------------------------------------------------+  |
|  |                                20 TPS Fixed Tick World Loop                                 |  |
|  |  +---------------------+  +----------------------+  +------------------+  +--------------+  |  |
|  |  |  Packet Dispatcher  |  | Spatial Hash Grid    |  | Entity Management|  | Mob AI Engine|  |  |
|  |  |  & Anti-Cheat Val   |  | (Interest Area Mgmt) |  | (Players, Items) |  | (BT / GOAP)  |  |  |
|  |  +---------------------+  +----------------------+  +------------------+  +--------------+  |  |
|  +---------------------------------------------+-----------------------------------------------+  |
|                                                |                                                  |
|  +---------------------------------------------v-----------------------------------------------+  |
|  |                                World & Persistence Subsystem                                |  |
|  |  +-------------------------+  +-------------------------+  +-----------------------------+  |  |
|  |  | Worker: Simplex Terrains|  | Chunk Manager (SVO RAM) |  | LMDB / RocksDB Voxel Storage|  |  |
|  |  +-------------------------+  +-------------------------+  +-----------------------------+  |  |
|  +---------------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------------+
```

---

## 2. Process & Thread Model
To achieve a locked **60-144 FPS** on the client and a stable **20 TPS** on the server:

### 2.1 Client Main Thread
- **Three.js Render Pipeline**: Renders G-Buffer passes, executes light clustering, shadow mapping, deferred composition, and post-processing (SSAO, TAA, Bloom).
- **Input Sampling & Camera Control**: Raw pointer-lock input sampled at display refresh rate.
- **Client Prediction**: Immediate local motion prediction and immediate voxel placement/break speculative rendering.
- **React 19 Fiber UI**: Rendered on top of the Three.js Canvas via lightweight zero-cost reactive bindings (Zustand / NanoStores with selector subscriptions to avoid React re-renders during high-frequency camera motion).

### 2.2 Client Web Workers (Offscreen Execution)
- **Meshing Worker Pool (3-7 Workers)**: Converts raw voxel chunk palettes into optimized 3D geometry using the **Greedy Meshing Algorithm**, calculating vertex ambient occlusion (AO) and light levels. Transfers raw `Float32Array` and `Uint32Array` buffers back to the main thread via **Transferable Objects** (0ms copy overhead).
- **Voxel Physics Worker (1 Worker)**: Runs client-side swept AABB collision tests against the Sparse Voxel Octree.

### 2.3 Server Multi-Threaded Architecture
- **Main Event Loop**: Fastify HTTP/WS connection handshakes, high-level game state dispatch.
- **Tick Worker Thread (20 TPS)**: Fixed-step delta physics, mob AI decision trees, combat calculations, and player state validation.
- **Chunk Generator & IO Thread Pool (`piscina` / Node `worker_threads`)**: Asynchronous 3D noise generation (OpenSimplex2 + Fractal Brownian Motion) and asynchronous disk read/writes into high-speed embedded key-value storage (LMDB / RocksDB).

---

## 3. High-Level Data Flow Lifecycle

### 3.1 Voxel Modification Lifecycle (Real-Time Block Mutation)
1. **User Input**: Player clicks left mouse button to destroy a block at coordinate `(x, y, z)`.
2. **Client Prediction**:
   - Voxel immediately marked as `AIR` in the local Sparse Voxel Octree.
   - Neighboring chunk boundaries flagged for re-mesh.
   - Meshing worker immediately dispatches a high-priority sub-chunk remesh.
   - Client emits an encrypted/validated binary packet `C2S_BLOCK_ACTION` to the server with `(x, y, z, actionType: BREAK, clientTick)`.
3. **Server Validation**:
   - Fastify WebSocket receiver unpacks the binary packet in < 0.1ms.
   - Server checks distance limit (e.g., raycast reach <= 5.5 blocks), tool efficiency, cooldowns, and collision integrity.
   - Server applies change to authoritative in-memory SVO chunk data and enqueues dirty chunk to persistence queue.
4. **Broadcast & Reconciliation**:
   - Server writes `S2C_BLOCK_DELTA` into the spatial broadcast packet for all clients within the chunk's interest grid (64-128 block radius).
   - If server rejects (anti-cheat violation), it dispatches `S2C_BLOCK_REVERT` forcing the client to re-insert the authoritative voxel.

### 3.2 Chunk Streaming & Area of Interest (AOI)
- Server divides world into 16x16x16 sub-chunks (or 16x256x16 columns).
- Fastify maintains a per-player **Spatial Hash Grid** tracking subscribed chunks (e.g., radius 12 chunks = 25x25 grid).
- Chunks are prioritized based on:
  1. Distance from player camera.
  2. Viewing frustum dot product (chunks directly in front of the player receive priority 1).
- Outgoing chunk payloads are compressed with **RLE (Run-Length Encoding) + LZ4** and streamed in chunks under 1400 bytes (or single MTU binary payloads).
