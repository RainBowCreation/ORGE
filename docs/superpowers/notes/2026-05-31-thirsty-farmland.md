# Idea: Thirsty Farmland (Mass-Draining Hydration)

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Make vanilla Farmland consume water mass from the simulation when it hydrates. This makes irrigation a finite resource and forces players to manage their water supplies for large-scale farming.

## Proposed Logic (Java-side)
Using a Mixin on `FarmlandBlock.randomTick()`:
1.  **Intercept the Hydration Check:** When vanilla scans the 9x9x9 area and finds a water block.
2.  **Identify the Source:** Get the `BlockPos` of the water block that triggered the hydration.
3.  **Drain Mass:** 
    - Subtract a small configurable amount of mass (e.g., `0.1 kg`) from the `SectionStore` at that position.
    - If the remaining mass is $\le 0$, immediately set the water block to `Air` (Option A).
4.  **Rain Handling:** If hydration is triggered by rain (checked via `Level.isRainingAt(pos.above())`), the mass-draining logic is **skipped**. Only discrete water blocks are consumed.

## Advantages
- **Low Overhead:** Reuses vanilla's existing search logic and random tick frequency.
- **Finite Resources:** Crops actually "use" the water in canals, leading to more interesting survival gameplay.
- **Performance:** No changes needed to the C++ physics engine; entirely handled on the Java integration layer.

## Implementation Notes
- Requires a Mixin to `net.minecraft.world.level.block.FarmlandBlock`.
- Needs access to `SectionStore` to modify mass directly outside of the native engine step.
