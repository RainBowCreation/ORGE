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

## Player Thermoregulation — Hunger (heat fuel) & Thirst (cool fuel)

**Date added:** 2026-06-02

Players get **active** homeostasis driven by two resource bars. The metabolic engine above
(`Q_met`) is no longer free — it must be *paid for* out of a bar, and the bar chosen depends
on which way the body is off-target:

- **Hunger = heating fuel.** When `T_body < T_default` (too cold), the body burns **hunger**
  to run `Q_met` and generate heat, warming back toward `T_default`. Burns faster the colder
  the deficit. This is the only way to actively fight cold.
- **Thirst (NEW bar) = cooling fuel.** When `T_body > T_default` (too hot), the body burns
  **thirst** (water) to shed heat *beyond* passive conduction — evaporative/sweat cooling,
  an active `-Q_cool` term — pulling back toward `T_default`. This is the only way to
  actively fight heat.
- **Both auto-regulate to a stable body temp.** Each second the controller measures
  `ΔT = T_body − T_default` and spends from the matching bar to drive `ΔT → 0`:
  - `ΔT < 0` → spend hunger → `+Q_met`
  - `ΔT > 0` → spend thirst → `−Q_cool`
  - `ΔT ≈ 0` → spend nothing (idle).
  Spend rate scales with `|ΔT|` (bigger gap → faster drain), capped by `metabolic_rate`
  (heating) and a matching `evaporative_rate` (cooling).

### Thirst bar & drinking water (new feature)
- New player HUD bar, persisted in player NBT, **synced to the client and rendered by
  reusing vanilla HUD assets** (recolor/reuse existing bar sprites — **no new texture
  assets**).
- **Drinking refills thirst, two ways:**
  - **Water bottle item** — drink a water-filled **glass bottle** (the 250 kg container in
    [[2026-06-02-fluid-containers]]); spends some of its stored `mass_kg`.
  - **Cup from a world water cell** — interact on a water cell to drink directly, which
    **consumes mass from the `SectionStore`** at that cell (ties to [[rain-mass-seeding]]
    as a sink; keeps finite water consistent).
- Cold water drunk can also slightly lower `T_body` directly (enthalpy of the ingested
  mass).
- **Empty thirst** → cannot cool actively → in heat, `T_body` climbs past `max_temp` →
  heat damage. **Empty hunger** → cannot heat actively → in cold, `T_body` falls below
  `low_temp` → freeze damage. (Reuses the parent damage window.)

### Exercise heat (vanilla hunger actions generate heat)
Any vanilla action that drains the hunger bar — **sprinting, jumping, attacking, etc.** —
also adds an **exercise heat term** `+Q_exercise` to body-heat generation that second,
proportional to the hunger drained. Consequences fall out naturally:
- Sprinting in the cold helps you **warm up** (and burns hunger for it).
- Sprinting in the heat makes you **overheat faster**, forcing more thirst/water spend.
- Standing still minimizes both drains.

### Oxygen bar (reworked from vanilla air/bubbles — mass-based breathing)
Replace vanilla's underwater-only bubble meter with a **mass-based oxygen bar** driven by
the finite-air model:
- Each breath (per second) the player **consumes `200 g` (0.2 kg) of air mass from the
  cell at the player's head position** in the `SectionStore`.
- If the head cell has enough air mass → oxygen bar stays full (refills). If it does not
  (head submerged in a water cell, vacuum/void, or the local air has been *depleted*) →
  the oxygen bar drains.
- **Empty oxygen → slow damage, exactly like vanilla drowning** (no heat coupling —
  oxygen is independent of body temperature).
- **Does not affect heat** at all; it's a parallel survival meter that simply makes
  *air a finite, consumable resource*.
- Rendered by **reusing the vanilla air/bubble HUD asset** (no new texture).

Emergent consequence (**intended**): breathing is a real **air sink**. In the open world,
air flows back via the engine; in a **sealed room** the total air mass is finite, so a
closed space genuinely suffocates its occupants until ventilated. This works for free with
the engine's existing physics — the engine **sorts each Y column by molar mass**, so air
(the light gas) redistributes upward through the room as it's consumed, and the head cell
keeps drawing from the room's shrinking finite air budget until it runs out. No special
sealed-room bookkeeping is needed; the molar-sort + finite mass already produce it.

### Net player loop
Cold biome → hunger drains to keep warm (eat more). Hot biome → thirst drains to keep cool
(drink more). Exertion accelerates whichever way you're already stressed. Deserts/Nether
punish thirst; snow/End punish hunger; sealed/underwater spaces punish oxygen.

## Open design questions (player layer)
- **Failure = damage only** (decided) — no debuffs; reuse the parent `low_temp`/`max_temp`
  window for temp, vanilla-style drown damage for oxygen.
- **Sealed-room suffocation = YES (decided).** Breathing permanently depletes a closed
  volume's finite air; the engine's per-Y-column molar-mass sort redistributes the
  remaining air so the head cell drains the room's budget naturally. No extra bookkeeping.
- **Drink rates / bar sizes:** thirst drain per `|ΔT|`, refill per drink; `200 g`/s
  oxygen rate — all need tuning constants (config).
- **Bar sync:** thirst + oxygen are server-authoritative (NBT + `SectionStore` reads) and
  pushed to the client HUD; how does this ride the §3 networking layer?

## Advantages
- **Two-resource survival:** hunger and thirst become a coupled thermoregulation system, not
  just a food timer — heat is the thing both bars actually manage.
- **Realistic Survival:** Standing in a blizzard or lava has a measurable, progressive effect on body temperature.
- **World Interaction:** A group of players in a small, insulated room will actually warm the air up over time.
- **Performance:** Calculations are limited to entities within active thermal sections and handled in Java.
