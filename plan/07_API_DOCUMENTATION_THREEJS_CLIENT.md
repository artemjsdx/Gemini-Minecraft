# 07. Client-Server API & Three.js Integration Guide

## 1. Three.js Engine Lifecycle & Architecture

The client application integrates Three.js imperatively for raw performance, exposing reactive state to React via lightweight event listeners.

```typescript
// Core Engine Interface
export interface VoxelEngineConfig {
  canvas: HTMLCanvasElement;
  renderDistance: number;       // e.g. 12 Chunks
  fov: number;                  // Default 75
  enableShadows: boolean;       // CSM Cascaded Shadows
  enableSSAO: boolean;          // Screen-Space Ambient Occlusion
  enableTAA: boolean;           // Temporal Anti-Aliasing
  serverUrl: string;            // ws://localhost:8080/ws/game
}

export class VoxelEngine {
  private renderer: THREE.WebGLRenderer;
  private deferredPipeline: DeferredPipeline;
  private chunkManager: ClientChunkManager;
  private networkClient: NetworkClient;
  private inputController: InputController;
  private playerEntity: LocalPlayer;

  constructor(config: VoxelEngineConfig) {
    this.initRenderer(config);
    this.initWorkers();
    this.initNetwork(config.serverUrl);
    this.startRenderLoop();
  }

  public setBlock(x: number, y: number, z: number, blockId: number): void;
  public getBlock(x: number, y: number, z: number): number;
  public raycastBlock(maxDistance: number): RaycastHit | null;
  public destroy(): void;
}
```

---

## 2. React UI Bridge & Custom Hooks

React 19 sits atop the canvas as a non-blocking HUD layer.

```tsx
import React, { useEffect, useState } from 'react';
import { useGameStore } from '../state/gameStore';

export const GameHUD: React.FC = () => {
  const health = useGameStore(state => state.player.health);
  const mana = useGameStore(state => state.player.mana);
  const hotbar = useGameStore(state => state.inventory.hotbar);
  const activeSlot = useGameStore(state => state.inventory.activeSlot);

  return (
    <div className="hud-overlay pointer-events-none fixed inset-0">
      {/* Reticle */}
      <div className="crosshair absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2" />

      {/* Health & Mana Bars */}
      <div className="status-bars absolute bottom-24 left-1/2 -translate-x-1/2 flex gap-8">
        <HealthBar current={health} max={100} />
        <ManaBar current={mana} max={100} />
      </div>

      {/* 9-Slot Hotbar */}
      <div className="hotbar absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-1 pointer-events-auto">
        {hotbar.map((item, idx) => (
          <HotbarSlot key={idx} item={item} isActive={idx === activeSlot} />
        ))}
      </div>
    </div>
  );
};
```

---

## 3. Network Client SDK & Event Emitter Specs

```typescript
export class NetworkClient extends EventEmitter {
  private ws: WebSocket;
  private packetSequencer: number = 0;

  public connect(url: string, authToken: string): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(url);
      this.ws.binaryType = 'arraybuffer';

      this.ws.onopen = () => {
        this.sendHandshake(authToken);
        resolve();
      };

      this.ws.onmessage = (event: MessageEvent) => {
        this.decodePacket(new Uint8Array(event.data as ArrayBuffer));
      };
    });
  }

  public sendInput(input: PlayerInputState): void {
    const buffer = new ArrayBuffer(26);
    const view = new DataView(buffer);
    view.setUint8(0, 0xMC);             // Magic
    view.setUint8(1, 0x02);             // Opcode: C2S_PLAYER_INPUT
    view.setUint16(2, this.packetSequencer++, false);
    view.setFloat32(4, input.x, false);
    view.setFloat32(8, input.y, false);
    view.setFloat32(12, input.z, false);
    view.setFloat32(16, input.yaw, false);
    view.setFloat32(20, input.pitch, false);
    view.setUint16(24, input.bitmask, false);
    this.ws.send(buffer);
  }
}
```
