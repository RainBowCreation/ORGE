# Idea: Rain Mass Seeding (Top-Down Sampling)

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Introduce water mass into the simulation during rain in a performance-efficient way. Instead of ticking every block, the server "seeds" mass onto the world's surface based on active thermal sections.

## Proposed Logic (Server-side)
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

## Advantages
- **Performance:** Using the pre-calculated `Heightmap` avoids expensive block scanning.
- **Cave-Safe:** Naturally prevents rain from appearing indoors or underground.
- **Synchronization:** Seeding happens on the server, ensuring all clients see the same rain accumulation before they run their local advection simulations.
- **Dynamic Weather:** $N$ and $M$ can be scaled based on weather intensity (Drizzle vs. Thunderstorm).

## Physics Integration
- This logic provides the **Source** of mass.
- The **Native Engine (Advection)** handles the "Flow," creating puddles, filling holes, and causing rivers to rise.
