# Phase change blocked by mass window: design proposal (2026-09-30, rev 2)

Status: **PROPOSED, awaiting review.** Nothing implemented.
Rev 2 replaces rev 1's designs A (gather/merge pre-pass) and B (phase flux) with the user's **soft-wall force curve + Bingham yield** model (designs D1/D2, adversarial review D3).

---

## 1. The bug (verified)

Observed: lava cools 1300 → 1275 K, holds at 1275 (latent plateau), becomes stone, stone reads **290 K** instantly.

1. The engine relabel lava → stone is guarded by `mNew >= minMass(stone)` (`engine_b.hpp` ~3533 fluid, ~3368 terrain). `lava.max_mass 2650 < stone.min_mass 2700`, so the relabel is **blocked forever**. The cell stays lava and cools below 1275 as undercooled lava (energy correct, species wrong).
2. Java `PhasePlanner` has no such guard. It sees lava < 1275 and plans `orge:stone`.
3. `MinecraftPhaseChanger:109` does `level.setBlock(minecraft:stone)` and never writes species/E to the store.
4. Fabric `WakeSetBlockMixin` fires on every `LevelChunk.setBlockState` and calls `BlockChangeCapture.captureBlockChange`. With live = stone and incumbent = lava it sees a displacement, and injects fresh stone at 2700 kg / **290 K**. Latent and sensible heat are deleted and 50 kg of mass is fabricated.

**Root cause.** min/max are enforced as **hard gates**. The engine handles "too much mass for target" (freeze-evict) but not "too little". Java then overrides the engine, and its block write is captured as a player placement.

### Other bugs found during the audit

| # | Bug | Where |
|---|---|---|
| B1 | Terrain melt checks target `minMass` but not `maxMass`: stone 2700 → lava 2700 (> 2650). INV-NOOVERMAX violated. | `engine_b.hpp` ~3368 |
| B2 | Steam's `representative_block` is `minecraft:steam`, which doesn't exist. It falls back to air and is read back as `orge:air`; the capture treats that as a removal, so **boiled steam is stomped to vacuum**. | steam.json, BlockChangeCapture |
| B3 | NeoForge `NeighborNotifyEvent` likely doesn't fire for `UPDATE_CLIENTS` writes, so the 290 K reset is probably Fabric-only (unverified). | WakePlatformImpl (neoforge) |

### Phase pairs today

| Pair | Source [min,max] | Target [min,max] | Today |
|---|---|---|---|
| lava → stone | [330, 2650] | [2700, 2700] | never |
| stone → lava | [2700, 2700] | [330, 2650] | fires, over max (B1) |
| water → ice | [125, 1000] | [917, 917] | only m ≥ 917; a puddle never freezes |
| steam → water | ~0.6 typical | [125, 1000] | ~never |

---

## 2. The model (user-authored)

### 2.1 Soft-wall force curve (replaces hard min/max gates)
Pressure (force) vs cell mass is one curve per material, built from `{min_mass, default_mass, max_mass}`:
- **Inside [min, max]:** today's pressure, unchanged. Rest = default.
- **Above max:** a steep push wall drives the surplus out.
- **Below min:** a steep pull wall draws mass in / merges.
- min/max are **soft**: a cell may be briefly outside, and the wall drives it back.

Chosen form (D3 F1: **hinge**, not softplus; softplus put −110 kPa suction on every partly-filled water cell):
```
Wh(m) = K · max(0, m − max)                 // push, every cell
Wl(m) = K · (1 − χ) · max(0, min − m)       // pull, cohesion only: gases have none (D3 M4)
p(m)  = today's anchor(m) + Wh(m) − Wl(m)   // exactly today's value inside the window ⇒ bit-parity
dp/dm = today's stiffness + K·[m>max] + K·(1−χ)·[m<min]   // via max/min, branchless
```
- Uses the existing **implicit θ** relaxation (`pressure_stiffness_v4` ~444, θ ~472). Backward Euler cannot overshoot, so any K is stable.
- The gas anchor floor and sub-min overrides (~402/419/1080) are **kept**. Gas behaviour at rest is unchanged.
- K calibrated from yield (D3 M2): `K = τ_y / (w · max)`, so the wall reaches a material's yield at a fractional overfill `w`. No new LUT column.

### 2.2 Nothing is immovable except bedrock/barrier (user rule)
Solids get a **finite, very high viscosity** plus a **yield stress**. They rest because ordinary forces are below yield; a large enough force (e.g. the push wall of over-full ice) makes them flow. μ = ∞ is reserved for truly immovable blocks.

Continuous Bingham weight (D3 F2: no cached `rigid` flag, law #0):
```
σ_i = own wall term only (own over/under-fill), computed in ENCODE      // D3 M3: never overburden P
yf  = max(0, σ_i − τ_y) / (σ_i + ε)                                      // 0 at rest ⇒ bit-parity
wall_weight = present · wallcap · (1 − mobile · yf)                     // ~275
frozen (relax, ~651) = present · (1 − mobile · yf)
DECODE momentum scaled by mobile · yf                                   // D3 M1: no gravity→heat leak
Nusselt: Ra · yf before the cube root                                   // rigid solid doesn't convect
```
- Swap pre-gate `mob<=0 → continue` (~1611) is **deleted**: the existing `drive > swap_resistance` already includes `max(τ_y)`.
- Every `mob<=0` / terrain site (inventory: ~1168, 1246, 1609, 1984, 2154, 2357, 2720, 2914, 2995, 3053, 3335) moves to the `yf` weight. There is one DECODE path.

### 2.3 Phase change becomes trivial
Relabel **always** happens once the latent band is fully crossed, **keeping mass and E**. Only the VOID guard (`heatCapacity > 0`) remains. Any out-of-window result is fixed by the wall force + normal flow.

### 2.4 Rain: existing swap logic, unchanged (user decision)
The swap happens only if `drive > swap_resistance`, which is the existing code (~1609-1667). Verified:

| Scene | Drive vs resistance | Result |
|---|---|---|
| 0.6 kg water over 0.6 kg steam | negative (thermal expansion) | stays |
| ~0.8 kg water over steam | > 1.8 N | falls, 1 block / 0.5 s |
| 0.6 kg water under 1.2 kg air | 6 N < 30 N | stays (does **not** rise) |

A condensing cloud rains: neighbouring cells condense into water cells, touch, and merge (same-species flux + pull wall) until heavy enough to fall. A single isolated droplet stays forever (accepted limit). Rejected: D2's material-density buoyancy, which would break same-species leveling.

---

## 3. Case walkthrough

| Case | Result |
|---|---|
| lava 2650 → stone | relabels at 2650, T continuous (~1275 K). Stone is rigid (pull wall far below τ_y), so it stays **porous stone**. No 290 K reset. |
| water 1000 → ice (max 917) | freeze_evict (kept as primary over-max path) pushes 83 kg water out, else relabels at 1000; if the push wall exceeds ice τ_y, the ice extrudes. |
| water 500 → ice | 500 kg porous ice. |
| stone 2700 → lava (max 2650) | relabels, push wall sheds 50 kg (**fixes B1**); enclosed, stays within the ±H cap. |
| steam 0.6 → water | relabels to a 0.6 kg water cell; merges when it touches water; cloud rains per §2.4. |
| resting stone world | yf = 0 ⇒ bit-identical to today; sections still sleep; cost +2 `max` per cell. |

---

## 4. Java side
1. **Java never decides species.** Delete `PhasePlanner`, `PhaseChangeDecider`, and the placement loop in `MinecraftPhaseChanger`.
2. **Paint engine species** in `FluidReconcileDecider.decide`: when out-species id ≠ the world block's first-touch id, PLACE the out-species' `representative_block` (fluid level if movable, else default state), bypassing the contact whitelist for this rule only.
3. **Keep the source re-pin**, since the engine has no `pinned` concept: `SourcePinPlanner.plan(cellMaterial)` re-pins every cell whose out-species is `pinned`.
4. **Self-write guard:** a server-thread depth counter around ORGE's own `setBlock`. `captureBlockChange` returns early when it's > 0, and wakes still run. The live == incumbent filter is not enough (B2).
5. **Redefine `Material.movable()`** as `isFinite(μ) && yieldStress < defaultMass·g` ("flows under own weight"). Otherwise finite-μ stone gets painted as fluid and enters the `StepValidator` ledger. Identical results on current data.
6. **Tests:**
   - delete `PhasePlannerTest`, `PhaseChangeTargetTest`;
   - update `SourcePinPlannerTest`, `AuditScenarioTest`;
   - extend `FluidReconcileDeciderTest` and `MaterialTest.movable`;
   - add a test that a guarded setBlock enqueues nothing.

## 5. Material data (D2)

| Material | viscosity (Pa·s) | yield_stress (N) |
|---|---|---|
| stone | 1e9 | 1e8 |
| ice | 1e7 | 5e6 (or ~1e5; see K calibration) |
| generic_solid | 1e9 | 1e7 |
| bedrock | ∞ | 1e30 |
| tinted_glass | ∞ (radiation mirror is keyed on μ=∞) | — |
| pinned blocks (fire, torch, lanterns, magma_block, blue_ice, glowstone, end_rod, redstone_block, nether_portal) | ∞ (Java re-pins E; must not trade mass) | — |
| future sand | 1e3 | ~5e3 |

These μ are game-scale post-yield rates, not physical. Plus a load-time validator in `ActiveMaterials.buildState`: WARN on a missing phase-target id, and on finite μ > 1e30 (denormal risk).

---

## 6. DESIGN-LAW amendments required first (D3 M5)
- **#0:** the continuous Bingham weight `yf` is sanctioned; the rigid limit is `yf → 0`, never a flag.
- **#8:** natural solids get finite μ + τ_y; ∞ is reserved for bedrock/barrier/pinned/mirror.
- **#9:** INV-NOSUBMIN / INV-NOOVERMAX ("not even one tick") are replaced by **INV-SOFTWIN**: `min − H ≤ m ≤ max + H` always (hard safety cap), and the excursion is non-increasing and < 1% within N ticks whenever a mobile same-species/vacuum partner exists.

## 7. Rollout

| Step | Scope | Fixes | Risk |
|---|---|---|---|
| 0 | DESIGN-LAW amendments #0, #8, #9 | — | doc |
| 1 | Java §4 (painter, guard, delete planner) + always-relabel keep-mass-keep-E + hinge walls (pull χ-weighted) + ±H caps; freeze_evict kept | reported 290 K bug, lava→stone, puddle→ice, B1, B2 | low–med |
| 2 | Bingham `yf` (wall_weight, relax frozen, DECODE momentum, Nusselt) + μ/τ_y data + K calibration + `movable()` redefinition | ice burst, finite solids | medium |
| 3 | Validator + steam render block | hygiene | low |

Step 1 must ship Java + engine together: dropping the Java planner alone leaves lava as lava forever.

## 8. Tests

**Native (new):**
- `softwall_curve` — p is exactly today's inside the window; monotone; dp/dm matches finite difference; no NaN.
- `softwin_invariant` — Σm, ΣE + boundaryE and Σp are exact; excursions decay.
- `softwall_relabel` — lava 2650 → stone with ΔT < 1 K; stone 2700 → lava sheds 50 kg; puddle → ice.
- `gas_rest_parity` — air and steam at rest match today.
- `leveling` — water leveling unchanged.
- `drain` — displaced air still clears.
- `yield_rigid` — resting stone/ice with finite μ is bit-identical to the μ=∞ baseline.
- `yield_burst` — over-full ice extrudes until P ≤ τ_y.
- `bingham_nusselt` — Nu == 1 for stone.
- `droplet_hold` — 0.6 kg stays, 0.8 kg falls.
- `melt_overmax` — B1 regression.

**Native (convert to INV-SOFTWIN):** `engine_b_inv_nosubmin_test`, `engine_b_relabel_submin_test`, `engine_b_freeze_evict_test`, and the INV-NOOVERMAX asserts.

**Parity:** `EnthalpyRestoreParityIT` and `ResidentLutParityIT` must stay green. No JNI change: `yf` is recomputed each tick, and K comes from existing data.

## 9. Open decisions for reviewer
1. Approve DESIGN-LAW amendments #0/#8/#9 (INV-SOFTWIN replacing NOSUBMIN/NOOVERMAX)?
2. K: tie it to yield (`K = τ_y/(w·max)`, no new data) or a global knob? And the value of `w` (fractional overfill that reaches yield)?
3. Ice τ_y: realistic 5e6 (bursts only at a large overfill) or game-scale ~1e5 (bursts readily)?
4. Accept that a single isolated droplet / an isolated out-of-window cell stays forever?
5. What should steam render as (no vanilla steam block)?
6. Keep freeze_evict as the over-max path for immobile targets, or rely on the push wall + yield alone (the burst cannot push into a cross-species neighbour; D2 risk 1)?

---

## 10. Future work (out of scope for this fix; recorded 2026-09-30)

1. **Biome temperature = worldgen seed only (user decision).** Biome ambient applies a higher/lower cell temperature only to a cell's *world-generation* state. A block placed later keeps its own block temperature (`default_temperature`), not the biome's. Deferred until ORGE has a safe way to persist per-cell mass/temperature for generated terrain. Today `BlockChangeCapture.capturePlacement` falls back to biome ambient only when a material lacks `default_temperature`, and `BiomeTemperature` uses `getBaseTemperature()` (no altitude adjustment).
2. **Mixtures without breaking one-species-per-cell ("carrier + passenger").** Every cell keeps ONE carrier species plus a uniform passenger slot (species id + mass), carried by the carrier's flux like E. Face exchange is by a saturation law from material data. A single mechanism covers humidity (air+vapour), cloud/fog (air+suspended water, rains out past a threshold), mud (water+sediment), wet porous stone (stone+water, cf. porous stone from §3), and fizz (water+dissolved gas). One T per cell. Cost: per-cell state, JNI, save format, every flux carries a second channel. Its own epic.
3. **Gas thermal buoyancy: VERIFIED working.** Probe (sealed 16×30×16 air box, 2×2×2 parcel at 600 K, dt 0.25): the parcel rose y 12 → 29 in 80 ticks (20 s). Mechanism: the EOS sheds mass from the hot cell (air β = 0, so not via `rho_eff`'s β term). **Finding:** the hot cell's mass stops at air's `min_mass` 1.0 kg instead of the ideal 0.58 kg (1.2·288/600), so air's cohesion floor caps hot-air expansion. Consider lowering air/steam `min_mass`, or the χ-weighted pull wall in §2.1 plus relaxing the gas canDrain floor.
4. **No expansion/compression heating.** The atmosphere stratifies (~12 Pa/cell, bottom cells ~1.2033 kg vs 1.2; `engine_b_gas_test` INV-AL), but the energy update has no pressure-work term: E moves per kg with no p·dV. Sinking air doesn't warm and rising air doesn't cool. The real dry-adiabatic effect over 384 m is only ~3.8 K, so this is low priority but physically correct to add.
5. **Droplets and wind.** Cross-species faces block flux (`crossOccluded` ~2366), so air flow doesn't carry a water cell sideways; droplets move only by vertical buoyancy swaps. Unverified whether momentum-driven cross-species swaps exist. The carrier+passenger model (item 2) would make cloud water ride the wind naturally.
6. **Attraction (cohesion-at-distance) force: not recommended.** Surface tension is irrelevant at 1 m³ cells, and attracting a cell 2 hops away needs a non-local read (law #4: 1-hop only). A lone thin cloud not raining is realistic.
