# Plan — Slice C2 rain mass seeding (issue #33)

Spec: `../specs/2026-10-25-rain-mass-seeding.md`. Java only (`core`), no engine change.
Test loop: `rtdd run --base origin/rebuild` (static selection `Foo` → `FooTest`).

1. **Climate rain rate** — `SurfaceForcing.Climate(solar, skyK, rainKgPerM2s)` (2-arg constructor keeps
   rain 0); `forDimension` sets `RAIN_INTENSITY·rainLevel` for the Overworld, 0 for the End.
   Test first: `SurfaceForcingTest`.
2. **Shared visitor** — `SkyColumnPass.Visitor.columnDone` + `andThen`, `SkyColumnPass.walkAndPut`; `SurfaceForcing.visitor(dim, climate, dt, lookup)`
   books into the ledger; `applyColumn` becomes walk(visitor) + re-put.
3. **`RainSeeding`** — `visitor(dim, climate, dt, lookup)`: per `(x, z)` column `dm = rain·dt·Σw` onto the
   highest-`w` cell at `columnDone`, water slot (existing water,
   else first empty, else clamp 0), `E += booked·h_water(T_rain)`, mass + energy ledgers per dimension.
   Test first: `RainSeedingTest` (sand/dirt pore water, stone free water, water pool, roof/cave, no rain,
   full composition, rate, `T_rain`, exact grand / per-species mass + energy with forcing).
4. **`MinecraftThermalWorld.applySurfaceForcing`** — one walk per column with `forcing.andThen(rain)`,
   re-put touched sections once; expose `rainSeeding()`. Test: `MinecraftThermalWorldTest`.
5. **Docs** — cite 5.1 mm/h (AMS moderate rain) and 287.15 K (wet bulb ≈ 14.0 °C global mean) in
   `docs/material-data-sources.md`.
6. Red check: set the source change aside, keep the tests, watch them fail; restore. Full `:core:check`
   before merge (no hosted CI).
