# Idea: Mass-Based Fluid Containers (Bucket & Glass Bottle)

**Date:** 2026-06-02
**Status:** Discussion / Idea

## Concept
Make buckets and glass bottles hold a **finite mass of flowable material** instead of the
vanilla "1 source block in / 1 source block out" abstraction. Filling and emptying are
**mass-conservative transfers** between the container's item NBT and the `SectionStore`,
carrying temperature with the mass — consistent with the finite-water/unified-fluid model
(no fluid created or destroyed).

## Containers & capacity

| Item | Capacity | Holds |
|---|---|---|
| **Bucket** | `1000 kg` | **any flowable material** (water, lava, steam, …) — capacity is by mass, regardless of the material's per-cell density |
| **Glass bottle** | `250 kg` | water (and other drinkable/flowable fluids — see open Qs) |
| **Cauldron** (block) | `1000 kg` | **any flowable material** — a placed *block* container, fed by pour/drip/rain (see below) |

`1000 kg` = exactly one full water cell (`default_mass`); `250 kg` = ¼ cell (= 2 × water
`min_mass` of 125 kg). Partial fills are allowed — a container can hold any amount up to
its capacity.

## Stored state (item NBT)
A non-empty container stores:
- `material` — the flowable material id it currently holds (a container holds **one**
  material at a time; mixing is not allowed).
- `mass_kg` — current contents (`0 … capacity`).
- `temperature` — K, carried with the contents (so a bucket of lava stays hot, cold water
  stays cold).

## Fill / empty logic (mass-conservative, enthalpy-carried)
- **Fill** (interact on a flowable cell): move up to `min(remaining capacity, cell mass)`
  from the `SectionStore` cell into the container, **draining the cell's mass**. The
  container adopts/keeps that material; temperature is enthalpy-mixed if it already held
  some of the same material. If the cell drains below cohesion it becomes air on
  reconciliation. Can't fill a different material than already stored (must empty first).
- **Empty / place** (interact on a target cell): deposit up to what fits into the target
  cell's `SectionStore` (respecting `max_mass`), **carrying temperature** (enthalpy mix).
  Leftover stays in the container if the cell can't take it all.
- Nothing is ever created or destroyed — every transfer is a conserved move, like
  [[2026-06-02-dripstone-conduit]] and the [[thirsty-farmland]]/[[rain-mass-seeding]]
  family.

## Fill indicator — reuse the vanilla durability bar
Show how full a container is by **repurposing the vanilla item durability bar** (no new
texture assets):
- Bar length renders the **fill fraction** `mass_kg / capacity` (full container → full
  bar; empty → no bar / empty).
- Could tint the bar by material later (blue water, orange lava) using the existing
  damage-bar color hook — optional.

## Block container — the Cauldron (1000 kg)
A cauldron is the **block** member of the family: model its interior as a **contained
`SectionStore` cell** — a cell with walls (no lateral or downward outflow) and an **open
top**, capacity `1000 kg`. It holds one flowable material at a time, carries temperature,
and never creates/destroys mass.

- **Inflows:**
  - **Dripstone drip** — a stalactite over a cauldron drips one `min_mass` quantum per
    event into it (the resolved receiver for [[2026-06-02-dripstone-conduit]]),
    accumulating toward 1000 kg.
  - **Pour from bucket/bottle** — deposit stored contents into the cauldron cell.
  - **Rain** — open cauldrons collect rain mass via [[2026-05-31-rain-mass-seeding]]
    (vanilla already fills cauldrons in rain; here it adds real water mass).
- **Outflows:**
  - **Fill a bucket/bottle** from the cauldron.
  - **Drink** directly (cup-from-cell) → refills thirst (see Integration).
- **Fill indicator:** reuse the **vanilla cauldron's own level rendering** (its 1/3·2/3·full
  visual states) mapped from `mass_kg / 1000` — no new assets. (Finer granularity is
  possible later, but the 3-step vanilla look is free.)
- Because the interior is just a walled cell, the engine's normal conduction still applies
  (a cauldron of lava radiates heat to neighbours; water in a cauldron over fire heats up
  and can phase-change per §7).

## Integration
- **Drinking / thirst:** a water-filled **glass bottle is the "water bottle item"** from
  [[2026-05-31-entity-homeostasis]] — drinking from it refills the thirst bar and spends
  some stored `mass_kg`.
- **Finite hydrology:** buckets/bottles become real mass sources & sinks the player can
  carry, complementing the world-driven [[rain-mass-seeding]] source/sink.
- **No engine change** — pure Java integration over `SectionStore` + item NBT.

## Advantages
- **Conserved & consistent:** containers obey the same finite-mass law as the rest of ORGE
  — carrying water actually moves mass out of the world and back.
- **Thermal carry:** temperature rides with the contents (haul lava, chill water).
- **Zero new assets:** fill level shown via the existing durability bar.

## Open design questions
- **Glass bottle contents:** water-only, or any flowable up to 250 kg (lava-in-a-bottle)?
- **Empty bucket stacking:** vanilla empty buckets stack to 16 but filled ones don't —
  keep that? Filled containers presumably unstackable (per-item NBT).
- **Partial-fill UX:** vanilla buckets are all-or-nothing; do we allow topping up a
  partially-full bucket, or only full fill / full empty?
- **Durability-bar conflict:** these items have no real durability, so repurposing the bar
  is safe — confirm no tooling/anvil interaction breaks.
- **Material mixing:** locked to one material per container (must empty to switch) — agreed?
- **Capacity vs vanilla cauldron / other fluid holders** — fold those in later or leave
  vanilla?
