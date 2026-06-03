> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.

# Design — ORGE Whole-Region Engine Step

**Date:** 2026-06-01
**Status:** Approved-in-conversation, pending written-spec review
**Repos:** MAIN `/home/claude/ORGE` (branch `rebuild`) · ENGINE submodule `ORGE-ENGINE` (branch `main`)
**Supersedes (orchestration only):** the per-section + halo + antisymmetric-seam-flux + cross-seam-reconciliation
machinery. **Keeps:** all §11 molar-mass gas physics (it already lives in `sim_engine.hpp`).

---

## 1. Problem & goal

Production physics currently steps the native engine **per 16³ section** with frozen read-only halos, then
reconciles cross-section flow in Java (antisymmetric seam flux, `SeamCoStep`, batch-§9 seam logic,
cross-seam reconciliation). This faked cross-chunk flow is the source of the live bugs — most visibly the
**1000→2000 cross-seam mass doubling** — and the §11 ship made the live game worse even though headless
tests passed.

The engine already has a **whole-region** stepping model (`sim_engine.hpp`'s `World` + double buffer, run by
`step_frame`, used by the SDL viewer) that does native cross-chunk flow. **Goal:** drive production from that
model — Java assembles the joined active region as one `World`, the engine steps it, Java writes it back —
and **delete** the per-section seam machinery. The §11 physics is unchanged.

## 2. Locked decisions (from brainstorming)

1. **Region representation:** sparse **column-keyed World** — reuse `sim_engine`'s `World` + `step_frame`
   directly (no new grid stepper, no re-derived physics).
2. **Region scope:** **active dormancy-gated sub-region** (not all loaded columns).
3. **Statefulness:** **stateless per-call** — Java owns canonical state (SectionStore); the engine builds a
   transient `World` each call and holds nothing between cycles.
4. **Per-section kernel fate:** **keep dormant** — `orge_kernel.hpp`, the old per-section `orgeStep` JNI
   symbol, and `advection_parity_test.cpp` stay compiled but are removed from the production path and the CI
   gate. Delete later once the World path is in-game-proven.
5. **Column extent:** **full-height columns** (all 384 cells per active column, ambient-air-filled from
   SectionStore). Matches the engine's fixed-height `Chunk` (`CHUNK_H=384`, `idx=x+y*16+z*256`); zero engine
   struct change. Y-band trimming is a deferred perf optimization.
6. **Java scope:** **single coherent pass** — replace the orchestration AND make the surviving Java layer
   air-conserving, so all four repro asserts flip to `== exactly 1000.0` in one landing.
7. **Region edge (loaded-but-dormant neighbour):** **loaded apron** — expand the active set by one ring of
   **loaded** neighbour columns, step them as full real columns in the same World, write back + wake any that
   gained mass. Genuinely MC-unloaded columns stay absent = correct wall. This is the X/Z-only, no-frozen-halo
   replacement for `SeamCoStep`.

**Sub-decisions:** (a) `orgeStepWorld` builds a **pre-advection `WorldSnapshot`** and passes it to every
`advect_chunk` so X/Z cross-column flow is consistent — confirmed-first in T1, not assumed. (b) The
`MaterialChangeReseed` mass-fabricating branches are **deleted outright** (no config kill-switch). (c)
Sequencing is ENGINE → .so rebuild → MAIN, tasks T1–T8.

## 3. Cell taxonomy — unloaded vs vacuum vs air (the core invariant)

The engine must distinguish three states. They are represented **structurally**, never by a single ambiguous
sentinel:

| State | Representation | Engine behaviour |
|---|---|---|
| **Unloaded** — MC hasn't loaded it, or we didn't send it | the column `(cx,cz)` is **absent from the World map** (`findChunk → nullptr`; absent from the pre-advection snapshot) | `exists=false` → **no-flow wall**; mass cannot cross into the unknown |
| **Vacuum** — real empty cell (e.g. §11 broken block) | a **present cell** in a **sent column**, `matIx == void_ix (0)`, `mass ≈ 0` | real **flow/fill target**; §11 adopts it |
| **Ambient air** — real finite gas | present cell, `matIx == orge:air` (nonzero LUT idx), `mass = 1.2 kg`, `airFlag=1` | real gas to **displace**, not absorb (§11) |

Why this is unambiguous: in MC, chunk loading is **per column, full height** — a loaded column has all 384
cells well-defined (solid/fluid/vacuum/air, never "unknown"). There is no half-loaded column. Sending
**full-height columns** guarantees *present column ⇒ every cell defined*, so "unknown" can only ever mean
*absent column*. Vacuum (void cell **inside** a present column) and Unloaded (no column) can never collide.

**Consequence (the apron):** a loaded-but-dormant neighbour must not be misread as a wall. The active set is
therefore closed under one ring of **loaded** neighbour columns (decision 7). A genuinely MC-unloaded edge is
correctly an absent column = wall.

## 4. FFI contract — `orgeStepWorld`

New JNI entry in `orge_jni.cpp`; the old per-section `orgeStep` symbol stays (uncalled).

```
JNIEXPORT jdouble JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStepWorld(
    JNIEnv*, jclass,
    jint nCols,
    jintArray cx, jintArray cz,                 // per-column chunk coords (length nCols)
    jcharArray matIx,  jfloatArray mass, jfloatArray Tin,   // nCols*6144, engine idx=x+y*16+z*256
    // material LUT (UNCHANGED from orgeStep — reuse BatchMarshaller packing incl. §11 air-flag flip):
    jfloatArray cond, jfloatArray heatCap, jfloatArray visc, jfloatArray fullMass,
    jbyteArray fluid, jfloatArray minFlow, jfloatArray maxMass, jbyteArray gas, jbyteArray air,
    jfloatArray molar,
    jint passes, jdouble dt,
    jfloatArray Tout, jfloatArray massOut, jcharArray matOut);  // nCols*6144 each
```

**Y mapping:** Java maps MC-Y(−64..319) → engine 0-based Y (`y_engine = y_mc + 64`) when packing; the JNI sees
a raw 0..383 column. Out-of-range Y is naturally a wall (`exists=false`).

**`passes` semantics (honour the bitmask exactly as the kernel did — the scheduler runs conduction @1Hz and
advection @4Hz separately):**
- Build the transient `World`: one full-height `Chunk` per column, placed at its `(cx,cz)`.
- `if (passes & PASS_CONDUCTION)`: `compute_frame_to_backbuffers(world, dt)` then `swap_all_backbuffers(world)`
  (T only; mass/mat copied through).
- `if (passes & PASS_ADVECTION)`: build a **pre-advection `WorldSnapshot`** of the whole assembled World, then
  `advect_chunk(world, col, mats, &snap)` per column (mass/mat/T in place). The snapshot gives order-independent,
  antisymmetric **X/Z** cross-column flow; **Y is contiguous within a column** so vertical fall/level/buoyancy
  across the old 16³ boundaries needs no seam handling at all.
- Read each column's `T_curr / mass_kg / matIx` back into the output arrays. Return elapsed ms.

**Within-column = native, cross-column = engine snapshot, never Java.** No halo arrays, no per-section
batching, no antisymmetric-flux bookkeeping crosses the FFI.

## 5. Region selection & dormancy

- `ThermalWorld.snapshotColumns(range)` returns the awake column set near players (player-sphere `range`),
  then expands by **one ring of loaded neighbour columns** (the apron). MC-unloaded neighbours are excluded
  (→ absent → wall).
- Per-column `noteSettle(maxMassDelta, maxTempDelta)` drives dormancy (a column settles when its step is a
  no-op within ε).
- **Wake-on-cross:** when write-back shows mass crossed an X/Z column boundary into an apron column, that
  column is woken for next cycle. This replaces `SeamFluxWake`'s seam role at column granularity.
- `SeamCoStep` (per-section 6-face co-step) is **deleted** — the apron + full-height columns subsume it.

## 6. Components

### ENGINE (C++)
- **NEW** `orgeStepWorld` (§4) in `orge_jni.cpp`.
- **KEPT DORMANT:** `orge_kernel.hpp`, old `orgeStep` symbol, `advection_parity_test.cpp` (compiles, not gated).

### JAVA (`core/`)
- **NEW `ColumnAssembler`** — replaces `HaloAssembler` + per-section batching. Per active `(cx,cz)`, reads
  SectionStore `sy ∈ [−4, 19]` → full-height `matIx[6144]/mass[6144]/T[6144]`:
  - `matIx`: live block via `MaterialBindings` → LUT index; unstored/empty → `orge:air`.
  - `mass`: stored mass where present; ambient air `1.2 kg` for air cells; **player-freshly-placed fluid**
    (stored ≤ 0) seeded once to `defaultMass` (the air-aware snapshot rule, applied column-wide — the *only*
    surviving seed).
  - `T`: stored T or biome ambient.
- **NEW `RegionStep`** — marshals the column list into the `orgeStepWorld` arrays and slices results back per
  column. Reuses `BatchMarshaller`'s LUT packing **with the §11 air-flag flip unchanged**.
- **MODIFIED `OrgeEngine` / `NativeEngine`** — add `stepWorld(columns, lut, dt, passes)` + native binding;
  `StubEngine` gains a World-equivalent for lib-less tests.
- **MODIFIED `Scheduler`** — snapshot phase calls `ColumnAssembler` over the active+apron column set; one
  whole-region submit; per-column write-back. Deadline/health throttle and conduction/advection cadences
  preserved.
- **MODIFIED `StepValidator`** — per-species `SpeciesMassLedger` scoped to the **whole region**; air tracked
  as its own species (already supported); clamp retained; non-conservation → atomic **HOLD** of the region for
  that cycle (existing advection-batch semantics).
- **SLIMMED, air-aware `MinecraftFluidReconciler`** — when engine `matOut` ≠ live block, update the MC block
  to the engine species, **conserving air** (water moved out ⇒ becomes air/vacuum per engine output; air
  displaced ⇒ tracked, not dropped). This is what makes physics visible.
- **DELETED:** `HaloAssembler`, `SeamCoStep`, the Y-seam `SeamFluxWake` role, cross-seam reconciliation, the
  mass-fabricating `MaterialChangeReseed` branches, `NeighborHalo` in production.

## 7. Data flow (per cycle)

```
cadence boundary (conduction @1Hz / advection @4Hz)
  → ThermalWorld.snapshotColumns(range)  [awake set + loaded apron]
  → ColumnAssembler  [blocks→matIx, store→mass/T, ambient-air fill, fresh-fluid seed]
  → RegionStep.marshal → NativeEngine.stepWorld → orgeStepWorld
        [build transient World → (PASS_CONDUCTION: compute+swap) and/or
         (PASS_ADVECTION: snapshot + advect each column) → read back]
  → StepValidator  [clamp + region-wide per-species conservation;  fail ⇒ HOLD whole region]
  → per-column writeBack
        [persist T/mass to SectionStore; air-aware block reconcile to matOut;
         record output species as next signature; noteSettle; wake X/Z neighbours that gained mass;
         phase change]
```

## 8. Error handling & trust model

- Existing clamps: `StepValidator.clean` (non-finite T → fallback, clamp `[0,6000] K`), `cleanMass`
  (clamp `[0, fullMassBound]`, non-finite → 0).
- Region-wide per-species conservation gate (`SpeciesMassLedger.conserved()` within `ε·totalCells`);
  failure ⇒ **atomic HOLD** — write nothing this cycle, keep previous state.
- Scheduler deadline/health throttle preserved (snapshot → bg step → validate → write).
- Native/stub fallback preserved (no `liborge` ⇒ `StubEngine`).

## 9. Testing & acceptance oracle

- **Adapt `Section11LivePipelineReproTest`** (untracked; the validation oracle) to drive the NEW pipeline
  (`ColumnAssembler → stepWorld → StepValidator → air-aware writeBack`) and flip **all four** asserts to
  `== exactly 1000.0`:
  1. place water into air (no spill loss),
  2. spread into 2 cells (500+500, no loss),
  3. cross-(old-)section drop within a column (no doubling),
  4. wetting trough (no +1.2/cell growth).
- **NEW live-path integration test:** multi-column region where water falls across an old section boundary
  *within* a column **and** flows across an **X/Z column seam**; total mass **and** air conserved exactly over
  N cycles; apron column wakes and persists.
- **Engine C++ test:** 2-column `World` via `orgeStepWorld` — conserves mass, flows water across the X/Z seam,
  falls across the 16-cell boundary inside a column (the case the kernel needed seam-flux for), and treats an
  absent neighbour column as a wall (no flow into the unknown). `advection_parity_test` stays compiling, not
  gated.
- **Discipline (the lesson that caused this mess):** every fix is validated against a test that drives the
  **real live pipeline** (the repro test is the template); hand-seeding the engine and bypassing
  snapshot/assemble/reconcile is forbidden as an acceptance gate. **In-game re-test by the user is the final
  gate.**

## 10. Sequencing (subagent-driven; ENGINE → .so → MAIN; TDD live-path first)

| # | Repo | Task |
|---|---|---|
| **T1** | ENGINE | Verify `advect_chunk` cross-column behaviour (snapshot ⇒ flow, absent ⇒ wall). Add `orgeStepWorld` (build World, `passes`-gated conduct+swap / snapshot+advect, read back). 2-column C++ conservation+seam+wall test green. Commit ENGINE `main`. |
| **T2** | ENGINE→.so | Rebuild `liborge.so` (`JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`), confirm md5 build==bundled, copy to `core/src/main/resources/natives/linux-x64/liborge.so`. |
| **T3** | JAVA | `OrgeEngine.stepWorld` + `NativeEngine.orgeStepWorld` binding + `StubEngine` World-equivalent + `RegionStep` marshaller (reuse LUT/air-flag packing). Unit tests. |
| **T4** | JAVA | `ColumnAssembler` (full-height, ambient-air fill, fresh-fluid seed). Unit tests. |
| **T5** | JAVA | Rewire `Scheduler` to whole-region (active+apron); delete `SeamCoStep`/`HaloAssembler`/Y-seam wake; region-scoped `StepValidator`. |
| **T6** | JAVA | Air-aware `MinecraftFluidReconciler`; delete mass-fabricating `MaterialChangeReseed` branches. |
| **T7** | TEST | Adapt repro test → all 4 `== 1000.0`; new multi-column live integration test green. |
| **T8** | INT/MAIN | One MAIN commit bundling `.so` + gitlink + Java; push `origin/rebuild`; in-game re-test gate. |

Each task: fresh implementer + spec-then-code-quality review (subagent-driven-development), live-path test
first.

## 11. Hard constraints (non-negotiable)

- **Two separate git repos.** NEVER `git add` ENGINE source from MAIN — only the gitlink (`git add ORGE-ENGINE`).
  Commit ENGINE on `main` (push `origin/main`) **before** bumping the MAIN gitlink. One MAIN commit bundles
  `.so` + gitlink + Java.
- `JAVA_HOME=/home/claude/jdk21` on **every** gradle/native call.
- After any `.so` rebuild: `md5(build) == md5(bundled)`.
- **Push `origin/rebuild` after every MAIN commit** (the user tests from `origin/rebuild`).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## 12. Risks & verify-first items

- **R1 (verify-first, T1):** `step_frame` calls `advect_chunk` with no `preStep`, and the kernel notes say
  X/Z seam flow is a no-flow wall when `preStep` is null. `orgeStepWorld` MUST build and pass a pre-advection
  `WorldSnapshot`, and T1 must prove cross-column flow + absent-column wall before anything downstream.
- **R2 (perf):** full-height columns (6144 cells/col) × active+apron set per cycle. Bounded by the active set;
  Y-band trimming is the documented later optimization if the per-second deadline is threatened.
- **R3 (air-aware reconcile):** the block↔cell reconciler is the remaining place mass can be fabricated/lost
  on write-back; it must conserve air consistently with the engine LUT. The repro test pins this.
- **R4 (dormant kernel drift):** `orge_kernel.hpp` / `advection_parity_test` are no longer gated; note in the
  ENGINE that they are dormant so a future reader doesn't trust them as production truth.
