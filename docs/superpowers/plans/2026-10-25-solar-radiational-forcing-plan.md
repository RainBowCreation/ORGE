# Plan — Slice C1 solar / radiational forcing (issue #32)

Spec: `../specs/2026-10-25-solar-radiational-forcing.md`. Java only (`core`), no engine change.
Test loop: `rtdd run --base origin/rebuild` (static selection `Foo` → `FooTest`).

1. **`SkyColumnPass`** (`core/.../scheduler/`): `walk(store, cx, cz, lookup, visitor)` — top-down per
   `(x, z)`, §4.8 volume-mixed `ε_cell`, exposure `τ·ε`, transmission `τ·(1−ε)`, stop at `τ < 1e-6`,
   skip unstored sections. Visitor gets `(data, cell, exposure)`. Test first: `SkyColumnPassTest`.
2. **`SurfaceForcing`** (`core/.../scheduler/`): `Climate` record + `forDimension(dim, dayTime, rain)`
   (Overworld / End / none); `applyColumn(dim, store, cx, cz, climate, dt, lookup)` runs the pass with the
   forcing visitor — `ΔE = max(q·dt, −C·max(0, T−T_sky))`, stored-float-delta ledger per dimension.
   Test first: `SurfaceForcingTest` (day up / night down / ledger exact / rain / End / Nether).
3. **`ThermalWorld.applySurfaceForcing(dt)`** default no-op; **`Scheduler`** calls it once ≥ 20 ticks
   have elapsed, at a boundary while IDLE, before `snapshotColumns`. Test: `SchedulerTest`.
4. **`MinecraftThermalWorld`**: remember the last batch columns; `applySurfaceForcing` loops the bound
   server's levels → `SurfaceForcing.applyColumn`. Test: `MinecraftThermalWorldTest` (package seam taking
   dim/dayTime/rain/lookup).
5. **Docs**: cite 161 W/m², 333 W/m² → 276.8 K, 2.725 K in `docs/material-data-sources.md`.
6. Red check: stash the source change, keep the tests, watch them fail; pop. Full `:core:check` before
   merge (no hosted CI).
