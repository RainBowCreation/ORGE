# Slice C1 — solar / radiational thermal forcing (issue #32, PRD #24)

**Status:** implemented with this spec (issue #32). Subordinate to `../DESIGN-LAW.md` and
`../handoffs/00-MASTER-RULES.md`; the law wins on conflict. Source idea:
`../notes/2026-05-31-thermal-seeding-dimensions.md`.

## 1. What it is

Once per simulated second the server runs ONE heightmap pass over the columns the scheduler is
stepping (the last batch's active + apron columns). The pass walks each of the 256 `(x, z)` cell
columns top-down through the stored `SectionStore` sections and hands every sky-exposed cell, with
its exposure weight, to a consumer. The first consumer is the surface forcing:

- **Overworld** — absorbed sunlight by day, long-wave loss to the sky at every hour; rain blocks
  the sun, so a rainy day and every night lose heat.
- **The End** — no sun, the sky is deep space: every exposed cell always loses heat.
- **The Nether** (and any other dimension) — no forcing at all (bedrock ceiling).

Energy goes into the cell's stored enthalpy `E` (law #6/#7: `T` stays derived). Every joule added or
removed is booked in the forcing's boundary energy ledger, so stored energy + ledger is exact.

No engine change, no per-block ticking, no new block/item/state, nothing sent to the client.

## 2. The heightmap pass (`SkyColumnPass`) — the reusable component

For each `(x, z)` of a column, from the top cell (section `MAX_SECTION_Y`, `sy = 15`) down:

```
ε_cell = Σ_k θ_k·ε_k,   θ_k = v_k / (Σ_j v_j + ε_v),   v_k = m_k / (ρ0_k + ε_v)     (v5 proposal §4.8)
w      = τ·ε_cell                                   exposure of this cell
τ      = τ·(1 − ε_cell)                             transmission to the next cell down (starts at 1)
```

`ρ0_k` is the slot material's `defaultMass` (1 m³ cell); `ε_v` a tiny regularizer so an empty cell
(`Σv = 0`) is `ε_cell = 0` with no special case. The exposure is Kirchhoff's grey body: a cell absorbs
what it emits, `ε`, of whatever reaches it. Air and steam carry `ε = 0` in the data, so they pass the
sky through untouched; the first condensed cell (stone 0.9, water 0.96, leaves 0.97, snow 0.98) takes
almost all of it. Nothing classifies a cell: "the top-most solid/liquid block" is simply where `τ`
collapses. The walk stops once `τ < 1e-6` (numerical truncation: the remaining ≤ 1 ppm of the flux is
not absorbed anywhere and is not booked, so the ledger stays exact). A section that is not stored is
skipped (it holds no state to force; every column the scheduler has stepped stores all 24 sections).

The pass is a visitor: `SkyColumnPass.walk(store, cx, cz, lookup, visitor)` calls
`visitor.exposed(key, data, cell, exposure)` per exposed cell. Rain seeding (#33) and evaporation
(#34) plug in as further visitors of the same walk (rain lands on the highest-exposure cell, evaporation
reads the exposed water cells), so the scan runs once per second for all three.

## 3. The forcing (`SurfaceForcing`)

Per exposed cell, per pass of `dt` seconds (top face `A = 1 m²`):

```
q   = w·(S(t) − σ·(T⁴ − T_sky⁴))                    [W]
ΔE  = max(q·dt, −C·max(0, T − T_sky)),   C = Σ_k m_k·cp_k
E  ← E + ΔE        ledger += (E_after − E_before)    (the float delta actually stored)
```

- `T` is the cell's derived temperature: the material's own curve `h⁻¹(E/m)` (`EnthalpyCurve.deriveT`,
  latent plateaus included) for a one-component cell; for a multi-component cell the linear mixture
  `T = (E − Σ m_k·h_k(0)) / Σ m_k·cp_k` (it ignores latent bands inside a mixture — only the emission
  temperature uses it).
- The `−C·(T − T_sky)` floor is the discrete maximum principle for the sink: one pass can never cool
  a cell below its sky. On a latent plateau it is conservative (the plateau holds `T`).
- The ledger books the stored float difference, so `Σ E_after − Σ E_before = ledger` exactly.
- Each forced section is re-`put` into the store, marking its column dirty so the next save keeps the
  forced `E`.

### Climate (real data, cited in `docs/material-data-sources.md`)

| Dimension | `S(t)` [W/m²] | `T_sky` [K] |
|---|---|---|
| `minecraft:overworld` | `π·161·max(0, sin(2π·dayTime/24000))·(1 − rain)` | 276.8 |
| `minecraft:the_end` | 0 | 2.725 |
| anything else (Nether) | no forcing | — |

- **161 W/m²** — global annual mean solar radiation absorbed at the surface (Trenberth, Fasullo &
  Kiehl 2009, *Earth's global energy budget*, BAMS 90:311, Fig. 1). The `π·max(0, sin)` day profile
  (sunrise at `dayTime` 0, noon 6000, sunset 12000) makes the 24000-tick daily mean exactly 161;
  the noon peak is 506 W/m².
- **276.8 K** — the same budget's 333 W/m² back radiation as a black-body sky, `(333/σ)^¼`.
- **2.725 K** — the cosmic microwave background (Fixsen 2009, ApJ 707:916).
- **rain** — `Level.getRainLevel(1)` in [0, 1]: an overcast, raining sky passes no direct sun.

**No runaway.** The loss grows with `T⁴`, so each exposed cell has one stable equilibrium:
`∫S = ∫σ(T⁴ − T_sky⁴)` over the day — about 305 K for an isolated Overworld surface, lower once
conduction into the air and ground (and #34's evaporation) share the sun. The End relaxes toward
2.725 K and never below it (the floor).

## 4. Wiring

- `Scheduler` counts real ticks; at a step boundary, while IDLE (no step in flight, so no write-back
  can overwrite the forced `E`), once ≥ 20 ticks (1 s) have passed since the last pass it calls
  `ThermalWorld.applySurfaceForcing(ticks / 20.0)` and then snapshots. The tick count is real game
  time (frozen ticks do not count), so a late pass forces the full elapsed time.
- `MinecraftThermalWorld` remembers the last batch's columns, and per server level picks the climate
  from the dimension id, `getDayTime()` and `getRainLevel(1)`, then runs the pass over that
  dimension's columns. `SurfaceForcing.ledger()` holds the cumulative energy per dimension.

## 5. Law check

- #0: no branch selects behavior by what a cell is; `ε`, `cp`, `m` enter as continuous weights; the
  floor is a saturating `max`. The only dimension switch is the climate table (data, not cells).
- #6/#7: `E` is the stored truth; `T` is derived for the emission term only.
- Conservation: no mass moves; every energy change is ledgered (boundary source/sink).

## 6. Tests

- `SurfaceForcingTest`: Overworld surface `E` rises over a simulated day and falls over the night;
  `Σ E` change over all stored sections equals the ledger exactly; rain stops the gain; End monotonically
  loses, never below `T_sky`; Nether has no climate; a mixed cell uses its mixture `T`; forced `E`
  survives save + reload.
- `SkyColumnPassTest`: exposure goes to the top condensed cell through air, a shaded cell gets only
  the transmitted remainder, an empty column gets nothing, a mixed cell uses the §4.8 volume mix.
- `SchedulerTest`: the pass fires once per second while IDLE, before the snapshot, never while a
  step is in flight, and forces the full elapsed time.
- `MinecraftThermalWorldTest`: forcing reaches the last batch's columns of the right dimension only.
