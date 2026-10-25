# Slice C2 — rain mass seeding into multi-material surface cells (issue #33, PRD #24)

**Status:** implemented with this spec (issue #33). Subordinate to `../DESIGN-LAW.md` and
`../handoffs/00-MASTER-RULES.md`; the law wins on conflict. Source idea:
`../notes/2026-05-31-rain-mass-seeding.md`. Builds on the sky pass of
`2026-10-25-solar-radiational-forcing.md` (#32), the soil-water columns (#29) and the representative
vanilla state (#30).

## 1. What it is

While it rains, the once-per-second sky pass (#32) also seeds water mass into the cells it hands out.
Rain is the pass's second visitor, so the same walk serves the forcing and the rain — **no second
heightmap scan**. The water joins the receiving cell's composition (law v5 #7):

- a **sand / dirt** surface takes it as **pore water** — the cell's skeleton (`flowResistance r > 0`)
  holds it, the engine's Darcy / capillary terms (#29) move it;
- a **stone** surface takes it as a **free-water component** — stone has no pore volume, so the per-cell
  volume wall (law #9) pushes it out onto the cell above: a puddle;
- a **water** surface takes it into the existing pool's water slot.

Nothing classifies the receiving cell: the same rule ("add `dm` of water to this cell's water slot") runs
for all three, and the difference is emergent from the receiving cell's own data in the engine.
Covered and cave columns receive nothing, because the pass hands out only sky-exposed cells.

Every kilogram added and its enthalpy are booked in a per-dimension **boundary ledger** (law #9: the caller's
place at the boundary is a ledgered source), so grand mass, per-species mass and grand energy stay exact
with the ledger included.

## 2. Where the rain lands

The sky pass visits each sky-exposed cell with its exposure `w = τ·ε_cell` (#32 §2); over one `(x, z)`
cell column the exposures sum to `Σw = 1 − τ_end ≤ 1`. Each `(x, z)` column's rain lands on its cell of
**highest exposure**, as #32 §2 planned:

```
dm = I·dt·Σw·A                A = 1 m² (top face),  I = I_rain·rainLevel [kg/(m²·s)]
target = argmax_cells w        (the top-most on a tie)
```

Air (`ε = 0`) is never the target; the first condensed cell is (stone `w = 0.9`, water `0.96`, the cell
below it at most `0.1·ε`). Spreading the rain by `w` itself was rejected: the grey-body walk transmits
`1 − ε` through a cell, so a one-block roof would let 10 % of the rain onto the floor beneath it — rain
indoors. The argmax keeps a roofed floor dry while the amount, `Σw`, stays the continuous weight of the
walk (an open column gets `I·dt` to within the walk's 1 ppm truncation, which is never added and never
booked). The selection compares exposures with each other, never a material against a threshold (law #0).
The walk tells the visitor when an `(x, z)` column is done (`Visitor.columnDone`), so the rain is placed
in the same single walk.

This deterministically seeds every one of the 256 `(x, z)` columns of each stepped 16 × 16 area rather
than `N` random samples (the note's suggestion): the pass already visits all 256, so sampling saves nothing
and would only add noise to the rate.

**Known limit (inherited from #32's data):** materials that declare no `emissivity` read `ε = 0` and are
transparent to the sky walk — metal blocks (copper, gold), bedrock, glass-like and torch/lantern blocks —
so the rain (and the sun) passes them. A cave or a house under stone, wood, dirt or leaves is dry.

## 3. What a seed does to a cell

```
k   = the slot holding orge:water with m > 0, else the first empty slot (m = 0), else none
m_k ← m_k + dm                 (float store; booked = the stored float delta)
E   ← E + booked·h_water(T_rain)
```

- `h_water` is the water material's own enthalpy curve (`EnthalpyCurve.hOf`), so the rain enters at
  `T_rain` exactly: a water cell already at `T_rain` stays at `T_rain`, a warmer or colder cell mixes.
- A cell with all `K = 4` slots taken by other species has **no room** for water: the seed is clamped to
  0 (law #9 — "clamped, never dropped": nothing is created, nothing is booked).
- Slot bookkeeping (water present / slot empty) is a test on stored slot ids, not on a material category
  (law #0); the receiving cell's material never selects a code path.
- The section is re-`put` into the store (column dirty), exactly as the forcing does, so the next save
  keeps it. The pass runs at an IDLE step boundary, before the snapshot (#32 §4), so no in-flight
  write-back can overwrite the seeded mass.

## 4. Data (real, cited in `docs/material-data-sources.md`)

| Quantity | Value | Source |
|---|---|---|
| Rain intensity `I_rain` | 5.1 mm/h = 1.4167·10⁻³ kg/(m²·s) — the middle of the "moderate rain" band 2.6–7.6 mm/h | AMS *Glossary of Meteorology*, "Rain" (intensity classes) |
| Rain level | `Level.getRainLevel(1)` ∈ [0, 1] scales `I` | vanilla weather |
| Rain temperature `T_rain` | 287.15 K (14.0 °C) | raindrops fall at the wet-bulb temperature (Gosnell, Fairall & Webster 1995, JGR 100(C9):18437); under rain the near-surface air is close to saturation, so wet bulb ≈ air temperature = the 1961–90 global mean surface air temperature 14.0 °C (Jones et al. 1999, Rev. Geophys. 37(2):173) |

Only the Overworld rains: the climate table (#32 §3) carries the rain rate, `I_rain·rainLevel` for
`minecraft:overworld` and `0` for the End; the Nether has no climate and therefore no pass.

## 5. Wiring

- `SurfaceForcing.Climate` gains `rainKgPerM2s` (the data above; 0 outside the Overworld).
- `RainSeeding` (new, `core/.../scheduler/`): a `SkyColumnPass.Visitor` factory per
  `(dimension, climate, dt)` (it tracks each column's best cell and `Σw`, and lands the rain on
  `columnDone`), plus the boundary ledgers `mass(dim)` [kg of water] and `energy(dim)` [J]. It has no walk
  of its own.
- `SurfaceForcing.visitor(...)` is the forcing visitor that `applyColumn` already ran; `SkyColumnPass.Visitor`
  gains `columnDone` and `andThen`, `SkyColumnPass.walkAndPut` re-puts every section a walk handed out, and `MinecraftThermalWorld.applySurfaceForcing` runs **one walk per column** with
  `forcing.andThen(rain)`, then re-puts the touched sections once.

## 6. Law check

- #0: no branch on what a cell is; the amount is the continuous `Σw`, the target the cell of highest `w`,
  the seed a plain add.
- #7: water joins the fixed-K composition; `T` stays derived from `E`.
- #9: mass only enters at the boundary, ledgered per species; a full composition clamps the seed to 0.
- #6/conservation: `ΔE = booked·h_water(T_rain)` is ledgered; `Σ E + forcing ledger + rain energy ledger`
  is exact.
- PRD #24: vanilla client unchanged — the cell keeps its live block (#30 host rule) or, once the engine
  moves the water into an open cell, the existing reconciler draws vanilla water; no new block, state or
  packet. Fixed-size SoA: no new per-cell storage.

## 7. Tests

- `RainSeedingTest`: rain over sand / dirt raises the surface cell's pore-water mass (skeleton mass
  unchanged); over stone a free-water component appears (stone unchanged); over water the pool's slot
  grows; the floor under a one-block roof and the air above receive nothing; no rain → nothing; a cell with four
  foreign slots takes nothing; an empty first slot takes the water; a table without water makes no rain;
  the received mass is `I·dt·Σw`; rain into water at `T_rain` keeps `T_rain`;
  grand mass, per-species mass and grand energy (with forcing on, in the shared pass) are exact including
  both ledgers.
- `SurfaceForcingTest`: the climate's rain rate (Overworld `I·rainLevel`, End 0).
- `MinecraftThermalWorldTest`: one shared pass forces and seeds the last batch's columns of the right
  dimension only.
