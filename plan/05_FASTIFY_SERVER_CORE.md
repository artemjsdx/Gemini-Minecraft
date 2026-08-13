# 05. Fastify Server Architecture & World Management

## 1. Fastify Server Core Structure

The server is built using **Node.js 22+ ESM** with **Fastify** and `@fastify/websocket` for ultra-low latency event handling.

```
/server
 ├── index.ts                     // Server entrypoint & Fastify initialization
 ├── /config                      // Server tuning & rate limits
 ├── /network
 │    ├── WebSocketGateway.ts     // Binary packet deserializer & router
 │    ├── BinaryPacker.ts         // High-performance typed buffer serializer
 │    └── SpatialInterestGrid.ts  // Grid-based AOI packet broadcast
 ├── /world
 │    ├── WorldManager.ts         // Authoritative world orchestrator
 │    ├── ChunkManager.ts         // LRU memory cache & SVO representation
 │    ├── TerrainGenerator.ts     // 3D Simplex noise generator worker pool
 │    └── StorageDriver.ts        // Embedded RocksDB/LMDB persistent storage
 ├── /entities
 │    ├── EntityManager.ts        // Player & mob spatial indexing
 │    ├── PlayerSession.ts        // Player state, client prediction validation
 │    └── MobController.ts        // Mob AI Behavior Tree execution
 └── /gameplay
      ├── InventoryEngine.ts      // Anti-cheat slot & item logic
      ├── CraftingRegistry.ts     // 3x3 shaped/shapeless recipes
      └── RPGSkillSystem.ts       // XP, leveling curves, talent validation
```

---

## 2. Fastify WebSocket Setup Code Plan

```typescript
import Fastify from 'fastify';
import websocket from '@fastify/websocket';
import { WebSocketGateway } from './network/WebSocketGateway';
import { WorldManager } from './world/WorldManager';

export async function createServer() {
  const app = Fastify({
    logger: { level: 'info' },
    disableRequestLogging: true
  });

  await app.register(websocket, {
    options: {
      maxPayload: 1024 * 1024 * 4, // 4MB maximum payload
      perMessageDeflate: false     // Disabled to eliminate CPU deflate latency
    }
  });

  const worldManager = new WorldManager();
  const wsGateway = new WebSocketGateway(worldManager);

  app.get('/ws/game', { websocket: true }, (socket, req) => {
    wsGateway.handleConnection(socket, req);
  });

  // Fixed 20 TPS Server Tick Loop (50ms delta)
  const TICK_RATE_MS = 50;
  setInterval(() => {
    worldManager.tick(TICK_RATE_MS / 1000);
  }, TICK_RATE_MS);

  return app;
}
```

---

## 3. Spatial Interest Grid & AOI Broadcasting

Broadcasting every event to all clients degrades performance at $O(N^2)$. The server implements a **Spatial Hash Grid (Area of Interest - AOI)**:
- World is divided into horizontal sectors of 64x64 blocks.
- When an entity moves or a block is destroyed, the server queries the Spatial Grid to obtain only player sockets within viewing radius (typically 8-12 chunks).
- Direct buffer broadcast bypasses JSON stringify, writing raw `ArrayBuffer` directly to uWebSocket sockets.

---

## 4. Anti-Cheat & Authoritative Physics

1. **Movement Validation**:
   - Client sends proposed position and input bitmask.
   - Server performs swept-AABB test against local chunk SVO.
   - Limits max speed: $v_{max} = v_{base} \times (1.0 + \text{AgilityLevel} \times 0.05) + \epsilon$.
   - Prevents fly-hacks, wall-phasing, and high-frequency teleportation.
2. **Mining Reach & Cooldowns**:
   - Raycast distance strictly validated $\le 5.5\text{m}$.
   - Break duration validated against tool mining power + block hardness + Mining skill haste bonus.
