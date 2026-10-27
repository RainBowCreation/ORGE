# Plan — Slice C3 daytime evaporation (issue #34)

Spec: `../specs/2026-10-25-daytime-evaporation.md`. Java only (`core`), no engine change.

**Issue #80 amendment.** The engine's vapor exchange is on by default since #41 (`g_vap = G_VAP_PENMAN`, not
0) and owns every face inside the domain, so step 3 now evaporates only a world-top surface into the free
atmosphere (a ledgered sink); every other surface is left to the engine (spec §0). Tests: `EvaporationTest`
(world-top behaviour + "the engine owns every in-domain face") and `EvaporationIT` (one sky pass + one native
engine step move a single Penman flux).
Test loop: `rtdd run --base origin/rebuild` (static selection `Foo` → `FooTest`).

1. **Shared surface tracking** — `SkyColumnPass.SurfaceVisitor` + `SkyColumnPass.surface(...)`: per
   `(x, z)` column the highest-`w` cell (top-most on a tie), its section key, and `Σw`, handed over at
   `columnDone`. `RainSeeding.visitor` uses it (behaviour unchanged; `RainSeedingTest` stays green).
2. **Saturation curve** — `Evaporation.pSat(water, steam, T)`: the engine's `phase_pair` / `p_sat` ported
   (Clausius–Clapeyron + Kirchhoff from the pair's columns, finite clamps). Test first: `p_sat(373) = P_atm`,
   283/293/300 K within the engine's +5…7 % of IAPWS-95.
3. **`Evaporation`** — `visitor(dim, climate, dt, lookup)`: by day and dry, at `columnDone`, the #28
   exchange from a world-top surface cell into the free atmosphere, a ledgered sink (any other surface: the
   engine's, #80); donor budget above the cohesive floor; mass lattice + dither; `E` moves
   `x·h_steam(T_donor)`; momentum scales with the donor's mass; drained slot → `(orge:vacuum, 0)`. Ledgers
   `mass`, `energy`, diagnostic `evaporated`. Test first: `EvaporationTest` (hot vs cold over a day,
   night / rain / End none, pore water, capped + below-surface untouched, in-domain surfaces untouched,
   latent heat, rate, world-top sink, exact grand / per-species mass + energy with forcing).
4. **`MinecraftThermalWorld.applySurfaceForcing`** — `forcing.andThen(rain).andThen(evaporation)`; expose
   `evaporation()`. Test: `MinecraftThermalWorldTest`.
5. **Docs** — `docs/material-data-sources.md` "Daytime evaporation (issue #34)": Penman g_vap, the
   saturation curve, the 309 K / 274 K rates, and (#80) that the engine owns every in-domain face.
6. Red check: set the source change aside, keep the tests, watch them fail; restore. Full `:core:check`
   before merge (no hosted CI).
