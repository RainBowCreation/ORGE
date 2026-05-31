# Idea: Solar & Radiational Thermal Seeding

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Drive the world's thermal cycle by injecting heat during the day and removing it at night. This simulates solar radiation and radiational cooling into space, making the surface temperature dynamic and physically grounded.

## Dimension Rules (Java-side)

### 1. The Overworld (Day/Night Cycle)
- **Daytime:** Inject heat energy into the top-most solid/liquid block (identified via Heightmap).
- **Nighttime or Raining:** Remove an equal amount of heat energy from the top-most block (simulating heat escaping into the atmosphere/space).
- **Formula:** $\Delta T = \frac{\text{Energy flux} \times \text{Time Step}}{\text{Mass} \times \text{Heat Capacity}}$
- **Result:** Ground heats up in the sun and cools down under the stars.

### 2. The End (The Eternal Cold)
- **Always Removing Heat:** The End has no sun and is exposed to the void. It acts as a permanent heat sink, removing energy from all surface blocks 24/7.
- **Result:** The End becomes a frozen wasteland unless players provide artificial heat sources.

### 3. The Nether (Insulated)
- **No Seeding:** Because the Nether has a bedrock ceiling, it does not receive solar heat or lose heat to space. Its temperature is driven entirely by internal sources (lava, fire) and conduction.

## Implementation (Java Scheduler)
- **Efficiency:** Reuses the "Top-Down Sampling" logic from the Mass Seeding design.
- **Evaporation Integration:** During the Daytime Solar Seeding pass, the server can simultaneously check for surface water and apply **Evaporation** (mass loss) based on the local temperature. This merges two physical processes into a single efficient heightmap scan.
- **Authority:** The Server performs the $\Delta T$ and mass loss calculations once per second and updates the `SectionData` before the simulation step.
- **Physics:** Only the top-most solid/liquid block is affected (Option A). Heat then moves to the air or deeper ground naturally via the C++ conduction engine.

## Advantages
- **Balanced:** Using equal amounts for Day/Night in the Overworld prevents thermal "runaway" (the world getting infinitely hot or cold).
- **Atmospheric:** Naturally creates cold nights in deserts and warm days on plains.
- **Dimension-Specific:** Gives each dimension a unique thermal "identity" with very little code.
