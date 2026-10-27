# Slice C3 — daytime biome-scaled evaporation of surface water into air (issue #34, PRD #24)

**Status:** implemented with this spec (issue #34); since issue #80 the pass owns only the world-top face
(§0) — every other surface face is the engine's vapor exchange's. Subordinate to `../DESIGN-LAW.md` and
`../handoffs/00-MASTER-RULES.md`; the law wins on conflict. Source ideas:
`../notes/2026-05-31-rain-mass-seeding.md` (evaporation) and the evaporation-integration paragraph of
`../notes/2026-05-31-thermal-seeding-dimensions.md`. Builds on the sky pass (#32,
`2026-10-25-solar-radiational-forcing.md`), the vapor exchange (#28, proposal §17) and rain seeding (#33,
`2026-10-25-rain-mass-seeding.md`).

## 0. One owner per face (issue #80)

Since #41 (PR #75) the engine's own face exchange is on by default (`engine_b.hpp` `Globals::g_vap =
G_VAP_PENMAN`): it evaporates every liquid|gas face inside the simulated domain — day and night, in rain or
not — at exactly the rate of §2. A sky pass that also evaporated those faces evaporated the world at **2×**
the Penman rate. The engine exchange is law-native, branchless and runs everywhere, so it **owns every face
inside the domain**, the surface faces included. This pass keeps only the face the engine has no cell for:
a surface cell whose top face is the top of the world (the top cell of section `MAX_SECTION_Y`), where the
receiver is the free atmosphere outside the domain and the vapor is a boundary sink (§4). A surface under a
section that is not stored is inside the world — the engine assembles that section — so it is the engine's
too. Each face therefore evaporates once per cycle, at one Penman flux.

The day / no-rain gate below applies to the world-top face only; inside the domain the engine evaporates at
night and in rain as well (physically fine: night evaporation is slower through the colder water's `p_sat`,
and rain humidifies the air above).

## 1. What it is

By day and while it is not raining, the once-per-second sky pass (#32) also evaporates surface water off
the top of the world: each `(x, z)` column whose surface cell — the cell rain lands on (#33 §2) — is the
world's top cell gives water to the free atmosphere above it, as vapor (`steam`) that leaves the domain. Evaporation is the pass's
third visitor: it rides the same walk as the forcing and the rain, so there is **no extra scan**.

- Free water (a water cell, or a water component on stone) and pore water of a sand / dirt surface
  evaporate by the same rule; only the surface cell does, so underground water — a cave pool, the water
  below the top soil cell, anything under a roof — is never touched (heightmap-only).
- The rate is the #28 face exchange, evaluated across the surface cell's top face: it depends on the
  water's own derived `T` through its saturation pressure. That is how the biome enters — a desert puddle
  sits at the desert's ambient (`BiomeTemperature`, then the sun), a taiga puddle at the taiga's, and
  `p_sat` grows ~7 % per kelvin. No biome multiplier is invented on top of the physics.
- At night (no sun), while it rains, in the End (no sun) and in the Nether (no sky) nothing evaporates.

## 2. The rate (the #28 exchange, one face)

The engine's exchange, written for a surface cell `a` (donor) and the cell above it `b` (receiver), with the
phase pair (water `c`,
its `maxTarget` vapor `h` = `orge:steam`), exactly the engine's definitions (`engine_b.hpp` vapor
exchange, `sim_engine.hpp` `phase_pair` / `p_sat`):

```
w_liq,a = clamp(v_c / V_film, 0, 1)             v_c = (1−χ_c)·m_c/ρ_p,c,  V_film = 1e-3 m³ (1 mm film)
w_gas,b = clamp(n_gas / n_film, 0, 1)           n_gas = Σ_k χ_k·m_k/M_k,  n_film = P_atm·V_film/(R·288 K)
p_sat,a = p_sat(pair, T_a)                      integrated Clausius–Clapeyron + Kirchhoff from the pair's columns
p_v,b   = n_h·R·T_b / max(1e-6·V, V − (V_c,b − v_h,b))    the vapor's Dalton partial pressure in b
F       = χ_pair·g_vap·A·dt·max(0, w_liq,a·w_gas,b·(p_sat,a − p_v,b))      χ_pair = max(χ_c, χ_h)
```

with `g_vap = G_VAP_PENMAN = 3.0e-8 kg·m⁻²·s⁻¹·Pa⁻¹` (Penman's still-air wind function, #28) and
`A = 1 m²`. `T_a`, `T_b` are the cells' derived temperatures (the `SurfaceForcing` rule: the curve for a
one-component cell, the linear mixture otherwise). Only the evaporation piece is applied — condensation
(dew) stays the engine's.

At the world-top face the receiver is the free atmosphere: `w_gas,b = 1`, `p_v,b = 0`, so
`F = χ_pair·g_vap·A·dt·w_liq,a·p_sat,a`.

Clamp (saturating, never a branch on what a cell is): the **donor budget** (law #9, the #28 V1 rule) —
what stays above the component's cohesive floor, `m_c − (1−χ_c)·a_c·min_c`,
`a_c = clamp(1 − (V_c − v_c)/V, 0, 1)` the volume fraction left to it. The free atmosphere has no
saturation cap and no slot limit.

## 3. What a pass does to the donor

```
x    = the clamped F, on the mass lattice (below)
m_c,a ← m_c,a − x            ledger mass   += −x
E_a  ← E_a − x·h_h(T_a)      ledger energy += the change of E_a      (the vapor carries h_steam(T_donor))
p⃗_a  ← p⃗_a·m_a'/m_a                                                  (the vapor leaves at the donor's velocity)
```

`h_h` is the vapor's own chain-anchored curve, `h_steam(T) = h_water(373) + L + cp_steam·(T − 373)`, so
the donor pays the latent heat and cools. A water slot evaporated to 0 is relabelled the empty slot
`(orge:vacuum, 0)`, as the farmland drain does.

**Exactness at float32 storage.** One pass moves ~1e-4 kg, about the float spacing of a full water cell
(6.1e-5 kg at 1000 kg). As in #28 (`vap_quantize`), `x` is floored to a multiple of the water's lattice
`q = ulp(m_c)` with an unbiased dither `x = q·floor(F/q + u)`, `u ∈ [0, 1)` a deterministic hash of the pass
(the same `u` for every column of a pass, so a uniform lake stays uniform); `m_c − x` is then exact (a lower
binade is finer). Per-species H₂O (water + steam + the sink ledger) is therefore exact to the bit, and the
ledger books the donor's actual `E` change, so `Σ E + ledgers` stays exact.

## 4. Leaving the domain

The vapor of the world-top face leaves the simulated domain: the mass and its enthalpy `x·h_h(T_a)` are a
**boundary sink**, booked in the evaporation ledgers `mass(dim)` [kg, ≤ 0] and `energy(dim)` [J]. These
are the only source/sink this pass has; the vapor the engine exchange makes inside the domain stays in it
and needs no ledger.

## 5. Data (real, cited in `docs/material-data-sources.md`)

No new data: the conductance is #28's Penman value, the saturation curve comes from water's and steam's
existing columns (IAPWS-95 check +5…7 % at 283–300 K), the film is the engine's 1 mm. A 309 K desert
puddle under dry air starts at `3.0e-8·5.9 kPa = 1.8e-4 kg/(m²·s)` (0.64 mm/h, the order of daytime
desert pan evaporation, ~10 mm/day); a 274 K taiga puddle at `3.0e-8·0.66 kPa = 2.0e-5 kg/(m²·s)`.

Since #41 humid air mixes with dry air, so the vapor the engine's exchange makes above a puddle moves on
and the face keeps evaporating at the Penman rate instead of saturating the air cell in place.

**Not in this slice:** proposal §17.2's P2 (in-cell sub-saturation re-evaporation of fog condensed inside
an air cell) and the face exchange inside the domain are engine work (#41, on by default: `g_vap =
G_VAP_PENMAN`); this slice is the Java world-top face only (§0).

## 6. Wiring

- `SkyColumnPass.surface(SurfaceVisitor)`: the per-column "cell of highest exposure + `Σw`" tracking that
  #33 wrote inside `RainSeeding`, lifted so rain and evaporation share it (`RainSeeding` now uses it).
- `Evaporation` (new, `core/.../scheduler/`): `visitor(dim, climate, dt, lookup)` — a no-op conductance
  unless `climate.solar() > 0` and `climate.rainKgPerM2s() == 0` (time of day and weather, not a material),
  and a no-op on any surface below the world top (§0, by position, not by material) — plus the boundary
  ledgers `mass(dim)` / `energy(dim)` and the diagnostic `evaporated(dim)` (all water moved off world-top
  surfaces). It writes only the donor, a cell of the walk.
- `MinecraftThermalWorld.applySurfaceForcing`: the one walk per column runs
  `forcing.andThen(rain).andThen(evaporation)`; `evaporation()` exposes the ledgers.

## 7. Law check

- #0: no branch on what a cell is. The weights `w_liq`, `w_gas`, `χ_pair` are continuous; the donor /
  vapor species are found by stored ids and the water row's `maxTarget` (data); the day/dry gate is the
  climate, not a material.
- #6/#7: `E` stays the stored truth, `T` derived; the moved mass carries `h_steam(T_donor)`.
- #9: inside the domain water only *moves*, by the engine's face flux term (3) — once per face (§0);
  leaving the domain at the world top is this pass's ledgered sink, with the donor budget clamp.
  Per-species H₂O exact.
- PRD #24: no new block, state, packet or per-cell storage; the vanilla client keeps seeing water (the
  reconciler lowers a drying puddle as it already does for any mass change).

## 8. Tests

- `EvaporationTest` (world-top surfaces): over a simulated day (1200 passes of 1 s, `dayTime` 0 → 24000)
  a hot-biome puddle shrinks and loses more than a cold-biome one, every kilogram booked; no evaporation
  at night or while it rains (or in the End); pore water of a sand surface evaporates, the sand untouched;
  a capped pool and the water below the surface cell are untouched; the donor cools by the latent heat;
  the rate matches `g_vap·p_sat(T)` into the free atmosphere; the vapor's enthalpy is booked; the drained
  section is saved; **a surface under air, or under an unstored section, is never touched (the engine's,
  §0)**; grand + per-species mass (water + steam) and grand energy exact including the ledgers with
  forcing and evaporation in one pass.
- `EvaporationIT` (issue #80, native engine): one combined cycle — the step-thread sky pass
  (`MinecraftThermalWorld.forceCaptured`) and then one engine step, both 1 s — over a full-height column
  with a daytime water surface under dry air moves the **single** Penman flux `χ_pair·g_vap·A·dt·p_sat(T)`
  per surface cell into the air, not twice it; water + steam, air and stone mass and the grand `E` balance
  against the sky pass's ledgers.
- `SkyColumnPassTest` / `RainSeedingTest`: unchanged behaviour of the shared surface tracking.
- `MinecraftThermalWorldTest`: one shared pass forces and evaporates (world-top water only) on the last
  batch's columns of the right dimension only; the step-thread pass matches the store pass.
