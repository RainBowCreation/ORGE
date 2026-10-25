# Slice C3 — daytime biome-scaled evaporation of surface water into air (issue #34, PRD #24)

**Status:** implemented with this spec (issue #34). Subordinate to `../DESIGN-LAW.md` and
`../handoffs/00-MASTER-RULES.md`; the law wins on conflict. Source ideas:
`../notes/2026-05-31-rain-mass-seeding.md` (evaporation) and the evaporation-integration paragraph of
`../notes/2026-05-31-thermal-seeding-dimensions.md`. Builds on the sky pass (#32,
`2026-10-25-solar-radiational-forcing.md`), the vapor exchange (#28, proposal §17) and rain seeding (#33,
`2026-10-25-rain-mass-seeding.md`).

## 1. What it is

By day and while it is not raining, the once-per-second sky pass (#32) also evaporates surface water:
each `(x, z)` column's surface cell — the cell rain lands on (#33 §2) — gives water to the cell directly
above it, where it lands as vapor (a `steam` component of the air, as #28 does). Evaporation is the pass's
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

For the surface cell `a` (donor) and the cell above it `b` (receiver), with the phase pair (water `c`,
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

Clamps (all saturating `min`/`max`, never a branch on what a cell is):

- **Donor budget** (law #9, the #28 V1 rule): what stays above the component's cohesive floor,
  `m_c − (1−χ_c)·a_c·min_c`, `a_c = clamp(1 − (V_c − v_c)/V, 0, 1)` the volume fraction left to it.
- **No over-saturation** (discrete maximum principle): one pass never lifts `p_v,b` above `p_sat,a`:
  `F ≤ (p_sat,a − p_v,b)·V_free,b·M_h/(R·T_b)`.
- **Receiver room**: the vapor goes into `b`'s slot already holding `h`, else its first empty slot, else
  nowhere (four foreign species: room 0 — clamped, never dropped), and `m_h ≤ max_h`.

## 3. What a pass does to the two cells

```
x    = the clamped F, on the mass lattice (below)
m_c,a ← m_c,a − x            m_h,b ← m_h,b + x                (both float stores change by exactly x)
E_a  ← E_a − x·h_h(T_a)      E_b  ← E_b + x·h_h(T_a)          (the vapor carries h_steam(T_donor): latent heat)
p⃗_a  ← p⃗_a − x·u⃗_a           p⃗_b  ← p⃗_b + x·u⃗_a              (the vapor leaves at the donor's velocity)
```

`h_h` is the vapor's own chain-anchored curve, `h_steam(T) = h_water(373) + L + cp_steam·(T − 373)`, so
the donor pays the latent heat and cools; the receiver holds it until the vapor condenses. A water slot
evaporated to 0 is relabelled the empty slot `(orge:vacuum, 0)`, as the farmland drain does.

**Exactness at float32 storage.** One pass moves ~1e-4 kg, about the float spacing of a full water cell
(6.1e-5 kg at 1000 kg). As in #28 (`vap_quantize`), `x` is floored to a multiple of the face's lattice
`q = max(ulp(m_c), ulp(m_h + F))` with an unbiased dither `x = q·floor(F/q + u)`, `u ∈ [0, 1)` a
deterministic hash of the pass (the same `u` for every column of a pass, so a uniform lake stays uniform);
the commit then checks that both stored floats changed by the same `x` and steps `x` down a lattice step
until they do. Per-species H₂O (water + steam) is therefore exact to the bit. The energy transfer is done
in double; when the receiver's `E` crosses a binade its sum rounds by at most ½ ulp, and that residue is
booked in the energy ledger so `Σ E + ledgers` stays exact.

## 4. Leaving the domain

When the cell above the surface is not simulated — the surface is the top cell of the world, or the
section above is not stored — the vapor leaves the simulated domain: the receiver is the free atmosphere
(`w_gas = 1`, `p_v = 0`, no saturation cap), and the mass and its enthalpy `x·h_h(T_a)` are a **boundary
sink**, booked in the evaporation ledgers `mass(dim)` [kg, ≤ 0] and `energy(dim)` [J]. A stepped column
has all 24 of its sections stored (the write-back stores every one), so in practice only a surface at the
world top loses its vapor this way.

## 5. Data (real, cited in `docs/material-data-sources.md`)

No new data: the conductance is #28's Penman value, the saturation curve comes from water's and steam's
existing columns (IAPWS-95 check +5…7 % at 283–300 K), the film is the engine's 1 mm. A 309 K desert
puddle under dry air starts at `3.0e-8·5.9 kPa = 1.8e-4 kg/(m²·s)` (0.64 mm/h, the order of daytime
desert pan evaporation, ~10 mm/day); a 274 K taiga puddle at `3.0e-8·0.66 kPa = 2.0e-5 kg/(m²·s)`.

**Known limit (inherited from #28 / #41):** humid air is its own species set and does not yet mix with
dry air, so the air cell above a puddle saturates in place (`p_v → p_sat`, ~0.04 kg of vapor at 309 K)
and evaporation then slows to what the engine's dew-point condensation and the swap carry off. With #41
the vapor mixes upward and the same rule keeps evaporating at the Penman rate.

**Not in this slice:** proposal §17.2's P2 (in-cell sub-saturation re-evaporation of fog condensed inside
an air cell) is engine work and stays with #41; this slice is the Java surface process only, and the
engine's own face exchange (`g_vap`) stays at its shipped 0.

## 6. Wiring

- `SkyColumnPass.surface(SurfaceVisitor)`: the per-column "cell of highest exposure + `Σw`" tracking that
  #33 wrote inside `RainSeeding`, lifted so rain and evaporation share it (`RainSeeding` now uses it).
- `Evaporation` (new, `core/.../scheduler/`): `visitor(dim, climate, dt, store, lookup)` — a no-op
  conductance unless `climate.solar() > 0` and `climate.rainKgPerM2s() == 0` (time of day and weather, not
  a material) — plus the boundary ledgers `mass(dim)` / `energy(dim)` and the diagnostic `evaporated(dim)`
  (all water moved off surfaces). A receiver in another section is fetched from the store and re-`put`.
- `MinecraftThermalWorld.applySurfaceForcing`: the one walk per column runs
  `forcing.andThen(rain).andThen(evaporation)`; `evaporation()` exposes the ledgers.

## 7. Law check

- #0: no branch on what a cell is. The weights `w_liq`, `w_gas`, `χ_pair` are continuous; the donor /
  vapor species are found by stored ids and the water row's `maxTarget` (data); the day/dry gate is the
  climate, not a material.
- #6/#7: `E` stays the stored truth, `T` derived; the moved mass carries `h_steam(T_donor)`.
- #9: water only *moves* (term (3) of the face flux, across one face) inside the domain, with the donor
  budget and receiver room clamps; leaving the domain is a ledgered sink. Per-species H₂O exact.
- PRD #24: no new block, state, packet or per-cell storage; the vanilla client keeps seeing water (the
  reconciler lowers a drying puddle as it already does for any mass change).

## 8. Tests

- `EvaporationTest`: over a simulated day (1200 passes of 1 s, `dayTime` 0 → 24000) a hot-biome puddle
  shrinks and loses more than a cold-biome one, and the vapor appears in the air above; no evaporation at
  night or while it rains (or in the End); pore water of a sand surface evaporates, the sand untouched; a
  cave pool under a roof and the water below the surface cell are untouched; the donor cools by the
  latent heat the receiver gains; the rate matches `g_vap·p_sat(T)` into dry air; a saturated air cell
  takes nothing; a surface at the world top loses its vapor to the ledger; grand + per-species mass
  (water + steam) and grand energy exact including the ledgers with forcing and evaporation in one pass.
- `SkyColumnPassTest` / `RainSeedingTest`: unchanged behaviour of the shared surface tracking.
- `MinecraftThermalWorldTest`: one shared pass forces and evaporates on the last batch's columns of the
  right dimension only.
