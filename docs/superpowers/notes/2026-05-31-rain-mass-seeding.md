# Idea: Mass Seeding (Rain & Evaporation)

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Introduce and remove water mass into the simulation in a performance-efficient way. Instead of ticking every block, the server "seeds" or "harvests" mass onto/from the world's surface based on active thermal sections and weather conditions.

## Proposed Logic: Rain Seeding (Server-side)
1.  **Frequency:** Runs once per second (every 20 ticks).
2.  **Scope:** Only processes horizontal $(x, z)$ columns that are part of currently active sections in the `SectionStore`.
3.  **Sampling:** For each active 16x16 area, pick $N$ random horizontal coordinates (e.g., $N=4$).
4.  **Heightmap Lookup:**
    - Use `level.getHeight(Heightmap.Type.MOTION_BLOCKING, x, z)` to find the top-most surface block instantly.
5.  **Mass Injection:**
    - Target the `SectionData` containing the surface $Y$ coordinate.
    - Add a small amount of mass $+M$ (e.g., $0.5\text{ kg}$) to the cell.
    - If the surface is Air, the mass is added to that cell (it will become a Water block during reconciliation).
    - If the surface is Water, the mass is added to the existing pool.

## Proposed Logic: Evaporation (Daytime Mass Loss)
To simulate the drying of the world, surface water loses mass during the day.
1. **Condition:** Only runs when it is **Daytime** and **not raining**.
2. **Logic:** Reuses the "Top-Down Sampling" and `Heightmap` lookup pass from the Rain logic.
3. **Action:** 
    - If the top-most block is `Water`, subtract a small amount of mass $-M_{evap}$.
    - $M_{evap}$ is scaled by the **Biome Temperature** at the $(x, z)$ coordinate.
    - Hotter biomes (Deserts, Badlands) lose water significantly faster than cold biomes (Ice Plains, Taiga).
4. **Optimization:** This pass can be merged with the "Solar Thermal Seeding" pass to avoid redundant Heightmap lookups.

## Advantages
- **Performance:** Using the pre-calculated `Heightmap` avoids expensive block scanning.
- **Dynamic Hydrology:** Rivers and lakes will naturally recede during hot days and fill during storms.
- **Cave-Safe:** Naturally prevents rain from appearing indoors and keeps underground reservoirs from evaporating.
- **Synchronization:** Seeding/Harvesting happens on the server, ensuring all clients see the same mass changes.

## Physics Integration
- This logic provides the **Source** and **Sink** of mass.
- The **Native Engine (Advection)** handles the "Flow," creating puddles, filling holes, and causing rivers to rise or fall.
