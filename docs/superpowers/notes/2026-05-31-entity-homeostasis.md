# Idea: Entity Homeostasis Simulation

**Date:** 2026-05-31
**Status:** Discussion / Idea

## Concept
Transform entities from static objects into active thermal agents. Entities have an internal body temperature, thermal mass, and a metabolic engine that attempts to maintain homeostasis by generating heat, while simultaneously exchanging heat with the surrounding world cells.

## Entity Thermal Registry (`JSON`)
New registry: `data/orge/orge/entity_thermal/<entity_id>.json`
- `default_temp`: Target body temperature (K).
- `max_temp` / `low_temp`: Thresholds for taking heat/freeze damage.
- `metabolic_rate`: Maximum energy (Joules/s) the entity can generate to warm itself.
- `thermal_conductivity`: Rate of heat transfer between the entity and its environment.
- `thermal_capacity` / `mass`: Defines the entity's thermal inertia.

## Proposed Logic (Java-side)
Each simulation second, for each entity in an active section:
1.  **Voxelize Hitbox:** Identify all cells $(x, y, z)$ the entity's bounding box currently occupies.
2.  **External Exchange ($Q_{ext}$):** Calculate conduction between the entity's current temperature (stored in NBT) and the average temperature of the occupied cells.
3.  **Metabolism ($Q_{met}$):** 
    - If $T_{body} < T_{default}$, generate heat up to `metabolic_rate` to reach target.
    - If $T_{body} \ge T_{default}$, generation is $0$.
4.  **Update Entity:** $\Delta T = \frac{Q_{met} - Q_{ext}}{\text{mass} \times \text{capacity}}$. Update internal NBT temperature.
5.  **Inject into World:** Apply the equal and opposite heat exchange ($-Q_{ext}$) back into the world cells in `SectionData`.

## Gameplay & Survival
- **Homeostasis:** In moderate environments, the entity maintains $T_{default}$ easily.
- **Exposure:** In extreme cold, if $Q_{ext} > Q_{met}$, the body temperature will drop.
- **Damage:** Taking continuous damage when $T_{body}$ is outside the `low_temp` to `max_temp` safety window.
- **Death (Option A):** On death, metabolic heat generation stops immediately. The entity's remaining thermal energy is transferred to its dropped items (which will have their own thermal properties).

## Weighted Thermal Exchange (Mixed Mass/Fluids)
To handle entities standing in partial fluids (puddles, steam, or half-full water blocks), the conduction uses a **Fill Fraction ($f$)** weighted approach:
1.  **Fill Fraction ($f$):** $f = \frac{\text{Current Cell Mass}}{\text{Material Default Mass}}$ (clamped 0 to 1).
2.  **Effective Conductivity ($k_{eff}$):** $k_{eff} = (f \times k_{material}) + ((1-f) \times k_{air})$.
3.  **Result:** Being fully submerged in water ($f=1$) transfers heat much faster than standing in a shallow puddle ($f=0.1$) or dry air ($f=0$), even if the temperature is the same. This naturally integrates with the Phase 2a finite-mass system.

## Advantages
- **Realistic Survival:** Standing in a blizzard or lava has a measurable, progressive effect on body temperature.
- **World Interaction:** A group of players in a small, insulated room will actually warm the air up over time.
- **Performance:** Calculations are limited to entities within active thermal sections and handled in Java.
