# Web Minecraft Engine: Master Architecture Plan & Implementation Roadmap

## Overview & Index of Architecture Documents
This directory contains the complete technical architecture and implementation blueprints for a next-generation Web Minecraft engine built with **React**, **Three.js (Custom Deferred Pipeline)**, **Node.js Fastify**, **WebSockets with Binary Packets**, **Sparse Voxel Octree (SVO)**, **Greedy Meshing**, **PBR Shaders**, and **Deep RPG Gameplay Mechanics**.

### Table of Contents
1. [`01_SYSTEM_ARCHITECTURE.md`](./01_SYSTEM_ARCHITECTURE.md) - System overview, client-server topology, multi-threading (Web Workers & Node Worker Threads), and tick execution loops.
2. [`02_NETWORKING_BINARY_PROTOCOL.md`](./02_NETWORKING_BINARY_PROTOCOL.md) - Custom binary WebSocket protocol, opcodes registry, delta chunk compression (RLE + LZ4), client prediction & reconciliation.
3. [`03_CHUNK_OCTREE_VOXEL_ENGINE.md`](./03_CHUNK_OCTREE_VOXEL_ENGINE.md) - Sparse Voxel Octrees (SVO), multi-threaded Greedy Meshing with ambient occlusion, 8-byte bit-packed vertex formats, and WebGL `InstancedBufferGeometry`.
4. [`04_RENDERING_PIPELINE_SHADERS.md`](./04_RENDERING_PIPELINE_SHADERS.md) - Custom G-Buffer Deferred Renderer, GLSL PBR Shaders, Cascaded Shadow Maps (CSM), SSAO, TAA, Volumetric Fog, and dynamic point lights.
5. [`05_FASTIFY_SERVER_CORE.md`](./05_FASTIFY_SERVER_CORE.md) - High-throughput Fastify server with `@fastify/websocket`, 20 TPS authoritative tick loop, spatial hash grid broadcasting, and anti-cheat validation.
6. [`06_GAMEPLAY_SYSTEMS_RPG.md`](./06_GAMEPLAY_SYSTEMS_RPG.md) - NBT-based slot inventory, 3x3 shaped/shapeless crafting matrix, Behavior Tree + 3D Voxel A* Mob AI, and 6-branch RPG talent trees.
7. [`07_API_DOCUMENTATION_THREEJS_CLIENT.md`](./07_API_DOCUMENTATION_THREEJS_CLIENT.md) - Client SDK, Three.js engine lifecycle, React 19 UI bridge hooks, and binary packet dispatchers.

---

## 4-Phase Implementation Roadmap

### Phase 1: Voxel Core & Multi-Threaded Mesher
- Initialize Three.js WebGL2 context with fallback checks.
- Build Sparse Voxel Octree (SVO) data structure in memory.
- Implement Greedy Mesher in Web Workers using Transferable ArrayBuffers with bit-packed 8-byte vertex formats.
- Integrate WebGL `InstancedBufferGeometry` for foliage, animated crops, and item entities.

### Phase 2: Deferred Rendering & Next-Gen Shader Pipeline
- Setup Multi-Render-Target (MRT) G-Buffer (Position, Normals, Albedo/Metal, Emissive/Light).
- Implement Cook-Torrance PBR Deferred Lighting pass with support for 128+ dynamic point lights.
- Add 4-Cascade Shadow Maps (CSM) with Poisson disk PCF filtering.
- Implement Screen-Space Ambient Occlusion (SSAO) and Temporal Anti-Aliasing (TAA).

### Phase 3: Fastify Binary WebSocket Server & Sync
- Implement Fastify WebSocket gateway with binary packet multiplexer.
- Develop 20 TPS authoritative server tick loop with client prediction reconciliation.
- Construct Spatial Hash Grid for distance-based delta broadcasts.
- Implement chunk streaming pipeline with RLE + LZ4 compression.

### Phase 4: RPG Gameplay, Mobs AI & UI Overlay
- Integrate React 19 UI overlay (HUD, inventory grid, crafting matrix, talent tree screen).
- Build Mob AI engine (Behavior Trees + 3D Voxel A* pathfinding).
- Implement 6-Branch RPG Progression system (Mining, Combat, Agility, Building, Alchemy, Sorcery).
