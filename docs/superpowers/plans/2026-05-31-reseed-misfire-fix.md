# Fix: mass created from nothing — MaterialChangeReseed misfires on the reconciler's own placements

## Problem (root cause, code-traced)

`MaterialChangeReseed` refreshes a cell's stale stored mass to the new material's
`default_mass` when the cell's **block** changed material since last cycle — the legitimate
entry point for "place 1 water bucket = 1000 kg". The §5 store persists only temp+mass (never
material identity), so `CellMaterialTracker` remembers the per-cell material the stored mass
belongs to, and `MinecraftThermalWorld.snapshot()` records that signature **from the pre-step
world block** (`geo.matIx()`).

The bug: the reconciler's OWN placements are indistinguishable from an external edit.

1. Cycle N snapshot records cell D = `orge:air` (pre-step world).
2. Engine wets D: `matOut[D]=water`, deposits e.g. 50 kg; write-back persists 50 kg.
3. Reconciler places a `minecraft:water` block at D.
4. Cycle N+1 snapshot: D's world block is now water ≠ recorded `orge:air`
   → `MaterialChangeReseed.reseeds(air, water)` = true → **overwrites D's 50 kg with 1000 kg.**

Every engine-wetted cell is pumped back toward 1000 kg the next cycle, fighting the spread that
drains it — settling at the observed ~700–800 kg/tile instead of the conserved ~4 kg/tile.
1000 kg of placed water becomes 16×16×~750 kg. The air-sink fix (last session) unmasked this:
before it, fluids never flowed, so the reconciler never placed new blocks and this never fired.

## Fix

The change-detector's signature must record **what the persisted mass belongs to = the engine's
OUTPUT species (`matOut`), captured at write-back** — not the pre-step world block. Then:

- A reconciler-placed cell (`matOut[D]=water`, signature=water) matches next snapshot's world
  block → **no reseed** (its engine-deposited mass is preserved → conservation holds).
- A genuine external edit (player bucket / `/setblock` / piston — the engine never produced that
  species there, signature=air) → still **reseeds to 1000** (entry point preserved).

This is a pure-Java scheduler change. No kernel/JNI/`.so`/material change. No gitlink bump.

## Constraints (non-negotiable)

- MAIN repo only: `/home/claude/ORGE` on `rebuild`. No ENGINE submodule changes, no `.so` rebuild,
  no gitlink bump. NEVER `git add` across the repo boundary.
- `JAVA_HOME=/home/claude/jdk21` on every gradle call.
- Push `origin/rebuild` after the commit (standing authorization).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- The fix is ATOMIC (one commit): removing the snapshot-time `record()` without adding the
  write-back-time recording in the same change would leave buckets un-reseeded. Do both together.

## Task (single, atomic) — move the material signature from pre-step snapshot to post-step write-back

### Production changes

1. **`scheduler/ThermalWorld.java`** — add a no-op default (mirroring `noteSettle`/`wakeNeighbourFlow`):
   ```java
   /**
    * Record the material each cell's just-persisted mass now belongs to (DESIGN §10 follow-on).
    * Called from the writeback loop AFTER a successful advection writeBack with the engine's output
    * species ({@code outMat}, may be null for stub/back-compat) and the batch {@code lut}. The live
    * impl updates its CellMaterialTracker so the NEXT snapshot's MaterialChangeReseed treats
    * engine-driven fluid placements (the reconciler turning a wetted air cell into water) as
    * already-known and reseeds only genuine external edits. Default no-op for headless test worlds.
    */
   default void recordCellMaterials(BatchEntry entry, char[] outMat, java.util.List<Material> lut) { }
   ```

2. **`scheduler/Scheduler.java`** — in `writeBackResults`, on the advection path, immediately AFTER
   the successful `world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()));` (the
   current ~line 313), add:
   ```java
   world.recordCellMaterials(entry, r.material(), pendingMaterials);
   ```
   It must NOT run for a held (non-conserving) section — the existing `continue` above already skips
   it. Do NOT add it to the conduction-only `else` branch.

3. **`scheduler/MinecraftThermalWorld.java`**:
   - In `snapshot()`, **remove** the `cellMaterials.record(dim, key, liveMaterialIds(...))` call
     (current ~line 122). KEEP the two lines above it: `cellMaterials.prior(dim, key)` and
     `MaterialChangeReseed.apply(priorMat, geo.matIx(), ...)` — the snapshot still reseeds using
     whatever signature the last write-back recorded.
   - Implement `recordCellMaterials(BatchEntry entry, char[] outMat, List<Material> lut)`:
     build the per-cell `Identifier[]` signature and call `cellMaterials.record(entry.dimension(),
     entry.key(), ids)`. Per cell `i`, the recorded species is the engine output when present,
     else the cell's input/world material (so unchanged air cells record `orge:air`, never the
     index-0 `orge:void` sentinel):
     ```java
     char[] inMat = entry.task().matIx();
     int s = (outMat != null && i < outMat.length && outMat[i] != 0) ? outMat[i] : inMat[i];
     ids[i] = lut.get(s).id();
     ```
     Reuse the existing `liveMaterialIds(...)`/`idsUnchanged(...)` allocation-avoidance against the
     prior signature where practical (fetch `cellMaterials.prior(dim, key)` and reuse it when the
     new ids are identical), so a static section costs only a comparison pass.
   - `liveMaterialIds` was previously fed `geo.matIx()` in snapshot; it is now used only from
     `recordCellMaterials`, fed the effective (output-or-input) species. Keep it `private static`.

### Tests (TDD — write first, watch them fail, then implement)

All in `core/src/test/.../scheduler/`. `MaterialChangeReseedTest` and `CellMaterialTrackerTest`
(pure units) stay UNCHANGED and green.

- **`MinecraftThermalWorldTest`** (inject a `CellMaterialTracker` via the
  `(stores, cellMaterials, activeSet)` constructor so the recorded signature is observable):
  - `recordCellMaterialsRecordsEngineOutputSpeciesNotInputBlock`: a section whose task `matIx` has
    cell D = `orge:air`; call `recordCellMaterials(entry, outMat[D]=water, lut)`; assert
    `tracker.prior(dim,key)[D]` is `orge:water` (NOT air). This is the core of the fix — the
    reconciler's placement of water at D will match next snapshot, so it is not reseeded.
  - `recordCellMaterialsFallsBackToWorldMaterialForUntouchedAirCells`: a cell E with `outMat[E]=0`
    (engine deposited nothing) records `orge:air` (from input `matIx`), never `orge:void`.
- **`SchedulerMassTest`** (fake `ThermalWorld` capturing `recordCellMaterials` calls):
  - `conservedAdvectionRecordsCellMaterialsWithEngineOutput`: a conserved advection cycle calls
    `recordCellMaterials` once for the written section with the result's `material()` array.
  - `heldAdvectionDoesNotRecordCellMaterials`: a non-conserving advection result (held, no
    write-back) does NOT call `recordCellMaterials`.
- **`MaterialChangeReseedConservationTest`** (new; encodes the symptom at the tracker+reseed seam,
  no live server): given a tracker populated by `recordCellMaterials` with `matOut=water` at the
  spread cells (cycle N), a cycle-N+1 `MaterialChangeReseed.apply(prior=that signature, live=world
  where those cells are now water)` leaves their mass UNCHANGED (no reseed → conservation). Contrast:
  a cell that became water WITHOUT being in `matOut` (external bucket: prior=air) IS reseeded to
  `water.defaultMass()` (1000). This is the regression guard for "mass from nothing".

### Verify

- `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` green (full suite, incl. the new tests and the
  untouched `MaterialChangeReseedTest`/`CellMaterialTrackerTest`).
- `JAVA_HOME=/home/claude/jdk21 ./gradlew :fabric-1.21:build :neoforge-1.21:build` green (both loaders).
- ONE MAIN commit, push `origin/rebuild`. No gitlink bump, no `.so` change.

## In-game gate (user)

Pour 1 water bucket (1000 kg) on a floating block: it spreads/falls into a thin bottom layer and
the **combined mass of all water tiles ≈ 1000 kg** (per-tile mass is now small, not ~700–800).
`/orge get-live` on a spread tile reads a small mass, not ~1000.
