# Plan — Slice C3 daytime evaporation (issue #34)

Spec: `../specs/2026-10-25-daytime-evaporation.md`. Java only (`core`), no engine change.
Test loop: `rtdd run --base origin/rebuild` (static selection `Foo` → `FooTest`).

1. **Shared surface tracking** — `SkyColumnPass.SurfaceVisitor` + `SkyColumnPass.surface(...)`: per
   `(x, z)` column the highest-`w` cell (top-most on a tie), its section key, and `Σw`, handed over at
   `columnDone`. `RainSeeding.visitor` uses it (behaviour unchanged; `RainSeedingTest` stays green).
2. **Saturation curve** — `Evaporation.pSat(water, steam, T)`: the engine's `phase_pair` / `p_sat` ported
   (Clausius–Clapeyron + Kirchhoff from the pair's columns, finite clamps). Test first: `p_sat(373) = P_atm`,
   283/293/300 K within the engine's +5…7 % of IAPWS-95.
3. **`Evaporation`** — `visitor(dim, climate, dt, store, lookup)`: by day and dry, at `columnDone`, the #28
   exchange from the surface cell into the cell above (same section or the stored section above; none →
   free atmosphere, a ledgered sink); donor budget above the cohesive floor, no over-saturation, vapor slot
   (existing, else first empty, else 0); mass lattice + dither + exact-commit check; `E` moves
   `x·h_steam(T_donor)`; momentum `x·u_donor`; drained slot → `(orge:vacuum, 0)`. Ledgers `mass`, `energy`,
   diagnostic `evaporated`. Test first: `EvaporationTest` (hot vs cold over a day, night / rain / End none,
   pore water, cave + below-surface untouched, latent heat, rate, saturated air, world-top sink, exact
   grand / per-species mass + energy with forcing).
4. **`MinecraftThermalWorld.applySurfaceForcing`** — `forcing.andThen(rain).andThen(evaporation)`; expose
   `evaporation()`. Test: `MinecraftThermalWorldTest`.
5. **Docs** — `docs/material-data-sources.md` "Daytime evaporation (issue #34)": Penman g_vap, the
   saturation curve, the 309 K / 274 K rates, the saturate-in-place limit until #41.
6. Red check: set the source change aside, keep the tests, watch them fail; restore. Full `:core:check`
   before merge (no hosted CI).
