# Slice C4 — thirsty farmland (issue #35, PRD #24)

**Status:** implemented with this spec (issue #35). Subordinate to `../DESIGN-LAW.md` and
`../handoffs/00-MASTER-RULES.md`; the law wins on conflict. Source idea:
`../notes/2026-05-31-thirsty-farmland.md`.

## 1. What it is

Vanilla farmland stays moist while a water block lies within its 9×2×9 search box. With this slice
the moisture costs water: every random tick on which vanilla's water search hydrates the farmland,
a small configured mass of water leaves the simulation from the cell vanilla found. A canal that
waters a field slowly empties; a source that runs dry becomes air (or, if the water was in a
waterlogged block, that block stays and dries). Rain hydrates for free.

No engine change, no new block / item / state / packet: a vanilla client sees ordinary farmland and
ordinary water that the server's reconciler lowers and removes.

## 2. The hook (`FarmBlockMixin` → `ThirstyFarmland`)

A server-side mixin on `FarmBlock#randomTick` (Mojang name of farmland in 1.21.11) injects at `HEAD`
and calls `ThirstyFarmland.onRandomTick(level, pos)`, then lets vanilla run unchanged:

1. **Rain.** If `level.isRainingAt(pos.above())`, nothing is drained — the farmland is hydrated by
   rain, whether or not water is also nearby.
2. **The source.** Otherwise the water block vanilla would find: the first position, in vanilla's
   `BlockPos.betweenClosed(pos + (−4, 0, −4), pos + (4, 1, 4))` order (x fastest, then y, then z),
   whose fluid state is in `FluidTags.WATER` (free water or a waterlogged block). None found → nothing
   drained (vanilla dries the farmland). NeoForge's `FarmlandWaterManager` tickets hydrate without a
   water block, so they drain nothing either.
3. **Request.** A request `(dim, x, y, z)` is queued on the server thread. Nothing touches the
   `SectionStore` here, because an engine step may be in flight whose write-back would overwrite it.

## 3. The drain (`HydrationDrain`)

The scheduler applies the queued requests at the same safe point as the sky forcing: at a step
boundary, while IDLE (no step in flight), before the snapshot — so the step simulates the drained
water. Per request, against the cell's stored composition (law v5 #7):

```
k      = the first slot holding water mass (a movable material drawn as minecraft:water)
Δm     = min(drainKg, m_k)               m_k ← m_k − Δm
ΔE     = Δm·h_k(T)                       (T derived before the drain; E ← E − ΔE; E ← 0 if the cell is now empty)
p⃗      ← p⃗·(M − Δm)/M                     (the water leaves at the cell's velocity: v = p⃗/M unchanged)
ledger: mass[dim][water] += −Δm_stored,  energy[dim] += −ΔE_stored   (the float deltas actually stored)
```

- **The water's identity.** Water is the component vanilla draws as water: `movable` and
  `representativeBlock == minecraft:water` (the same rule `RepresentativeState` uses for waterlogging).
  Free water is slot 0 of a one-component cell; pore / waterlogged water is whichever slot holds it.
- **`T`** is the cell's derived temperature: `EnthalpyCurve.deriveT` for a one-component cell, the
  linear mixture `(E − Σ m_j·h_j(0)) / Σ m_j·cp_j` otherwise (the `SurfaceForcing` rule). `h_k(T)` is
  water's enthalpy curve — the drained water carries exactly its own enthalpy, so the remaining
  components keep their `T`.
- **Exhaustion.** A slot drained to `m_k = 0` is relabelled the empty slot `(orge:vacuum, 0)`. The
  drained cell is then an engine-drained cell: the reconciler (#30) clears a free-water block to air
  (`FluidReconcileDecider` → `CLEAR`), and keeps a waterlogged host, drawing it dry (pore fill
  `< WATERLOGGED_OFF`). The host's own mass is never touched. An empty slot 0 is `orge:vacuum`, so a
  later reload cannot re-seed the drained cell from its old identity.
- **Requests in one window** accumulate per cell; the clamp `Δm ≤ m_k` holds for their sum, so a cell
  never goes negative and nothing is fabricated.
- **Not drained:** a request whose column is not loaded or whose section is not stored (its water is
  not in the simulation yet — it is seeded from the block the first time the section is stepped), or a
  cell with no water component (the block changed since the tick).
- Each drained section is re-`put` (column dirty, the drain survives a save) and its flow pass woken,
  so a dormant section steps and reconciles the lowered water.

### The configured amount

`drainKg` per hydrating random tick, JVM property `orge.farmland.drainKg` (kg, default below; a
negative or unparsable value falls back to the default).

```
drainKg = ET₀ · A · Δt = (5 kg/m²/day / 86 400 s) · 1 m² · (4096 / 3 / 20 s) = 3.95 × 10⁻³ kg
```

- **ET₀ = 5 mm/day** — FAO-56 grass-reference evapotranspiration of a temperate summer day (Allen,
  Pereira, Raes & Smith 1998, *Crop evapotranspiration*, FAO Irrigation and Drainage Paper 56): 1 mm
  of water over 1 m² is 1 kg.
- **Δt = 68.3 s** — the mean time between random ticks of one block at the default
  `randomTickSpeed` 3 (each tick a section picks 3 of its 4096 blocks; 20 ticks/s). Simulated seconds are
  game seconds, as for the sky forcing.

So one hydrated farmland drinks ET₀ on average. Vanilla's search box lets one water block hydrate up
to 80 farmland: about 400 kg/day, so a 1 m³ source under a full field lasts ~2.5 days —
60 hours of play at 20 tps, 180 Minecraft day cycles.

## 4. Law check

- #0: no branch selects behavior by what a cell is. The water slot is found by data
  (`movable`, `representativeBlock`), the same lookup the reconciler draws with; the drain is a
  saturating `min`.
- #6/#7: `E` stays the stored truth; `T` is derived; the removed enthalpy is the drained mass's own
  `Δm·h(T)`.
- #9: the drain is a boundary sink (the issue's "drained mass is a boundary-ledger sink"), booked
  per species with its enthalpy. `Σ m_species + ledger_species` and `Σ E + ledger_E` are exact.
- PRD #24 constraint 1: server-side only; vanilla clients see vanilla farmland and water.

## 5. Tests

- `HydrationDrainTest`: hydration lowers a free source by exactly `drainKg`, `T` unchanged; an exhausted
  free source is empty and the reconciler decides `CLEAR` (air); an exhausted waterlogged stair keeps
  its host and draws dry, in either slot order; grand + per-species mass and energy exact including the
  ledger over many requests (over-drains, repeats); unstored / dry cells drain nothing; the property
  parses.
- `ThirstyFarmlandTest`: the source is vanilla's first water in scan order; no water → no request;
  rain → no request.
- `SchedulerTest`: the drain applies while IDLE, before the snapshot, never while a step is in flight.
- `MinecraftThermalWorldTest`: queued requests reach the store of their dimension and wake the section.
