# 06. Gameplay Systems, Mobs AI, Inventory & RPG Progression

## 1. Slot-Based Inventory & NBT Item Stacking System

```
+-----------------------------------------------------------------------------------------------+
|                                      PLAYER INVENTORY                                         |
+-----------------------------------------------------------------------------------------------+
| Main Inventory: 27 Slots (3 rows of 9) | Hotbar: 9 Slots (Slots 0..8)                         |
| Armor Slots: 4 (Helmet, Chestplate, Leggings, Boots) | Offhand: 1 Slot                        |
| Crafting Matrix: 3x3 Grid (9 Slots) + 1 Result Slot                                           |
+-----------------------------------------------------------------------------------------------+
```

### 1.1 Item Data Structure
```typescript
export interface ItemStack {
  id: number;              // Unique item identifier (Uint16)
  count: number;           // Current stack amount (1..64)
  maxStack: number;        // Maximum stack size (1, 16, 64)
  durability?: number;     // Current item durability
  maxDurability?: number;  // Max durability before breakage
  rarity: ItemRarity;      // COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHIC
  nbt: {
    enchantments?: Array<{ id: number; level: number }>;
    socketGems?: Array<{ type: string; statBonus: number }>;
    customName?: string;
    lore?: string[];
  };
}
```

---

## 2. 3x3 Shaped & Shapeless Crafting Engine

The crafting manager matches recipe templates:
- **Shaped Recipes**: Exact 2D matrix matching with spatial translation tolerance (e.g. 1x2 wooden sticks recipe valid in any 2 consecutive vertical slots).
- **Shapeless Recipes**: Ingredient frequency count matching (e.g. 1 Log -> 4 Planks, or Herb + Flask -> Health Potion).
- **RPG Workstations**:
  - *Forge*: Unlocks advanced steel, mithril, and titan weapons requiring higher Mining/Smithing levels.
  - *Arcane Enchanter*: Consumes XP and gemstones to bestow elemental affixes.

---

## 3. Mob AI Engine: Behavior Trees + 3D Voxel A* Pathfinding

```
                          [Selector: Root AI]
                                   |
         +-------------------------+-------------------------+
         |                                                   |
 [Sequence: Combat Mode]                             [Sequence: Idle/Wander]
         |                                                   |
   +-----+-----+-----+                                 +-----+-----+
   |           |     |                                 |           |
(Has Target) (In Range) (Execute Attack)            (Pick Random) (Walk To Node)
   |           |
 (True)     (False) -> [A* 3D Voxel Pathfind to Target]
```

### 3.1 3D Voxel Navigation Grid
- Mobs sample step heights (up to 1 block jump height, or 2 blocks for spiders).
- Fall damage evaluation prevents hostile/passive mobs from pathfinding off dangerous cliffs (> 3 blocks).
- Flocking behaviors (Boids algorithm) for passive herds (cows, sheep, birds).

---

## 4. RPG Skill Progression & 6-Branch Talent Trees

Players earn experience points (XP) in 6 distinct disciplines:

```
                                    [RPG MASTER TALENT TREE]
                                               |
     +------------+------------+---------------+------------+------------+------------+
     |            |            |               |            |            |            |
 [MINING]      [COMBAT]    [AGILITY]      [BUILDING]   [ALCHEMY]    [SORCERY]
     |            |            |               |            |            |
- Vein Miner  - Critical Strike - Double Jump - Mega Placer - Potency Boost - Mana Pool
- Ore Sense   - Berserk Rage    - Wall Run    - Blueprint   - Auto Brewer   - Teleport
- Smelt Touch - Parry & Riposte - Soft Fall   - Reach +2m   - Elixir Trans  - Meteor Voxel
```

### 4.1 Talent Tree Mechanics
1. **Mining**:
   - Tier 1: *Vein Miner* (Breaking an ore automatically mines all connected matching ores up to 16 blocks).
   - Tier 2: *Smelt Touch* (Mined iron/gold ores drop refined ingots directly).
   - Tier 3: *Seismic Tremor* (Active ability: Pulverizes a 3x3x3 cube of stone in front of the player).
2. **Combat**:
   - Tier 1: *Executioner* (+15% Critical strike damage with swords).
   - Tier 2: *Shield Breaker* (Axes disable target blocking for 3 seconds).
   - Tier 3: *Bloodlust* (Killing an enemy grants +25% attack speed and life leech for 6 seconds).
3. **Agility**:
   - Tier 1: *Parkour Momentum* (Continuous sprinting builds up to +30% move speed).
   - Tier 2: *Air Step* (Mid-air double jump).
   - Tier 3: *Feather Weight* (Negates fall damage up to 12 blocks).
4. **Building**:
   - Tier 1: *Architect's Reach* (Increases block placement range by +3 blocks).
   - Tier 2: *Line Placer* (Hold shift to place a straight row of 5 blocks simultaneously).
5. **Alchemy & Sorcery**:
   - Elemental spell casting (Fireball with voxel explosive destruction, Ice Wall creating temporary solid blocks).
