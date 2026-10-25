# Plan — Slice C4 thirsty farmland (issue #35)

Spec: `../specs/2026-10-25-thirsty-farmland.md`. Java only (`core` + one mixin per loader), no engine change.
Test loop: `rtdd run --base origin/rebuild` (static selection `Foo` → `FooTest`).

1. **`HydrationDrain`** (`core/.../scheduler/`): server-thread queue of per-cell requests (`drainKg`
   each, accumulated); `apply(storeFor, lookup, wake)` drains the water slot (`min`), removes `Δm·h(T)`
   of `E`, scales momentum, relabels an exhausted slot `orge:vacuum`, re-`put`s and wakes the section;
   per-dimension per-species mass ledger + energy ledger of the stored float deltas. Test first:
   `HydrationDrainTest`.
2. **`ThirstyFarmland`** (`core/.../block/`): pure `hydratingWater(x, y, z, probe)` in vanilla's scan
   order and `drainSource(...)` (rain → none); `onRandomTick(ServerLevel, BlockPos)` glue; a static sink
   installed by `Orge`. Test first: `ThirstyFarmlandTest`.
3. **`ThermalWorld.applyHydrationDrain()`** default no-op; **`Scheduler.submit`** calls it while IDLE,
   before `snapshotColumns`. Test: `SchedulerTest`.
4. **`MinecraftThermalWorld`** owns the `HydrationDrain`, applies it over its stores with the active
   materials and wakes through the `ActiveSet`. Test: `MinecraftThermalWorldTest`.
5. **Mixins**: `FarmBlockMixin` (fabric + neoforge) `@Inject(HEAD)` on
   `randomTick(BlockState, ServerLevel, BlockPos, RandomSource)` → `ThirstyFarmland.onRandomTick`.
   `Orge` installs the sink and clears the queue on server stop.
6. **Docs**: FAO-56 ET₀ and the random-tick interval in `docs/material-data-sources.md`.
7. Red check: set the source change aside, keep the tests, watch them fail; restore. Full
   `./gradlew :core:check` + both loader builds before merge (no hosted CI).
