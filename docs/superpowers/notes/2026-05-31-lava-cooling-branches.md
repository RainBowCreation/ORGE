# Idea: Data-Driven Cooling Branches (Lava -> Obsidian/Basalt/Stone)

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Replace the simple `freezing_target` with a dynamic selection based on the **cooling rate** ($\Delta T / \Delta t$) at the moment of phase transition. This avoids "magically" spawning blocks and uses the simulation's thermal history to decide the geological result.

## Proposed Logic (Java-side)
In the `Scheduler`, when a cell transitions from liquid to solid:
1. Calculate `deltaT = oldTemp - newTemp`.
2. Check the material's `cooling_branches` list (sorted by threshold descending).
3. If `deltaT > threshold`, pick that branch's `target`.
4. Fall back to `freezing_target` if no thresholds are met.

## Material JSON Structure
```json
{
  "id": "orge:lava",
  "freezing_point": 1000,
  "freezing_target": "minecraft:stone",
  "cooling_branches": [
    {
      "threshold": 400.0,
      "target": "minecraft:obsidian",
      "comment": "Extremely fast cooling (quenching)"
    },
    {
      "threshold": 100.0,
      "target": "minecraft:basalt",
      "comment": "Fast cooling"
    }
  ]
}
```

## Advantages
- **Stateless:** Doesn't require tracking history across multiple ticks; uses the current step's delta.
- **Data-Driven:** Players can add "Cooling Branches" to any material (e.g., Steam cooling into Snow vs. Water).
- **Performance:** Calculations are minimal and only run when a phase change actually occurs.
