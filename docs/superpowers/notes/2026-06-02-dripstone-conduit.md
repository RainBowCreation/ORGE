# Idea: Dripstone as a Mass-Conservative Conduit

**Date:** 2026-06-02
**Status:** Discussion / Idea

## Concept
Vanilla pointed dripstone **creates fluid from nothing** — a water/lava source above a
stalactite drips into a cauldron (or feeds the infinite-source mechanic) on each random
tick, manufacturing mass. ORGE's whole point is finite, conserved fluid mass, so instead
of *spawning* fluid, dripstone should **move a small amount of existing mass from the cell
above it to the cell below it** — a slow, gravity-driven trickle through the stone.

A dripstone column is otherwise a solid block: the unified fluid engine would never flow
mass across it. Dripstone is the **one exception** — a narrow conduit that lets a heavy
fluid seep downward through an otherwise-blocking solid, exactly mirroring the visual of
a real drip, while conserving total mass.

## Gating rule (reuses the flowable sorting engine)
The transfer is governed by the **same molar-mass sort the unified fluid engine uses**
(`lava > water > air > steam > void`): a heavier fluid sinks below a lighter one.

- Let `top` = the fluid cell feeding the dripstone (above the stalactite base).
- Let `bottom` = the receiving cell below the stalactite tip.
- **Transfer only if `molarMass(bottom) < molarMass(top)`** — i.e. the top fluid is
  heavier than whatever currently occupies the bottom cell, so by the sort it *wants* to
  fall through. (Water drips through into air/steam/void; water will **not** drip into
  lava, because lava is heavier. This falls straight out of the existing sort order, no
  special cases per fluid pair.)

When the rule holds, move **exactly one quantum = the top fluid material's `min_mass`**
(the per-cell cohesion floor) from `top` → `bottom` per drip event, carrying its
temperature (enthalpy mix, same formula §6/engine). So a water drip moves `125 kg`, a
lava drip `400 kg`, a steam wisp `0.6 kg` — the transfer is data-driven off the same
field the engine already reads, no separate tuning constant. If `top` falls below its
own `min_mass` it can't sustain another drip (and becomes air during reconciliation when
it drains); nothing is ever created.

## Proposed Logic (Java integration layer)
Mirror the `thirsty-farmland` pattern — a mixin/listener on the vanilla dripstone drip
tick, but redirected to `SectionStore` mass moves instead of block spawns:
1. **Intercept** the vanilla pointed-dripstone drip random tick (suppress its
   fluid-creation / cauldron-fill side effect).
2. **Locate** the source cell above and the receiver cell below the column via the
   existing dripstone geometry (tip points down).
3. **Read** `molarMass`/mass/temp of both cells from `SectionStore`.
4. **Gate** on `molarMass(bottom) < molarMass(top)` (above).
5. **Transfer** `ΔM` top→bottom in `SectionStore` (temperature enthalpy-mixed), let the
   native advection engine handle any onward flow/pooling next step.
6. **Skip** if the top cell is dry (no source to drip) — never manufacture mass.

## Advantages
- **Conserves mass** — replaces the one remaining vanilla "fluid from nothing" path with
  a transfer, consistent with the finite-water model.
- **No engine change** — pure Java layer over `SectionStore`; the conduit decision reuses
  the engine's published molar-mass order rather than reimplementing physics.
- **Emergent correctness** — "water drips, lava doesn't drip up, won't drip into denser
  fluid" all fall out of the single molar-mass comparison.
- **Low overhead** — runs only on dripstone random ticks, like vanilla.

## Open design questions
- **Which block exactly?** Pointed dripstone stalactite (tip-down, fed by a source two
  above, as in vanilla) — confirm we target that, not the `dripstone_block`. How is the
  source/receiver pair located for multi-segment columns (tallest tip → cell below tip)?
- **Tick cadence** — match vanilla drip frequency, or decouple? (Transfer *size* is
  settled: one `min_mass` quantum of the top fluid.)
- **Source must hold a full quantum** — require `mass(top) ≥ min_mass(top)` before a drip
  (else partial/zero), so the cell can't drip itself below cohesion in a way that
  manufactures a fractional cell.
- **Cauldron interaction — RESOLVED:** a cauldron under the stalactite is the receiver;
  each drip deposits one `min_mass` quantum into it (a 1000 kg contained cell) per
  [[2026-06-02-fluid-containers]]. The cauldron is just another receiving cell with walls,
  so the molar-sort gate still applies.
- **Lava dripstone** — vanilla lava+pointed dripstone over a cauldron makes lava; here it
  becomes a lava trickle gated by the same rule. Desired?
- **Suppression** — like the vanilla-fluid suppression we already do (`FlowingFluid#tick`),
  this needs the vanilla drip's mass-creating effect disabled cleanly on both loaders.

## Relationship to other notes
- Same **source/sink family** as [[rain-mass-seeding]] and the mixin pattern of
  [[thirsty-farmland]] — but a **conduit (conservative transfer)**, not a source or sink.
- Depends on the **molar-mass sort** of the unified fluid engine (`orge-unified-fluid`):
  this idea is only coherent because "heavier sinks" is already the engine's law.
