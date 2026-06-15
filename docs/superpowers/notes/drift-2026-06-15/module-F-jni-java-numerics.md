# Drift Audit — Module F: JNI ABI + Java persistence + numerics / dt-invariance

**Scope:** §1.1 persisted round-trip, §9 numerics, law #7 (store extensive / derive intensive).
**Date:** 2026-06-15 · **Method:** executable code only; comments discarded; spec is source of truth.

Files audited:
- `ORGE-ENGINE/jni/orge_jni.cpp`
- `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- `core/src/main/java/net/rainbowcreation/orge/engine/LutArrays.java`
- `core/src/main/java/net/rainbowcreation/orge/engine/RegionMarshaller.java`
- `core/src/main/java/net/rainbowcreation/orge/engine/ColumnTask.java` / `ColumnResult.java`
- `core/src/main/java/net/rainbowcreation/orge/section/SectionData.java`
- `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`
- `core/src/main/java/net/rainbowcreation/orge/scheduler/ColumnAssembler.java`
- `core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialLut.java`
- `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- `ORGE-ENGINE/core/engine_b.hpp` (step_world_b top; resolve pass order; atomics grep)
- `ORGE-ENGINE/core/sim_engine.hpp` (Chunk persisted fields)

---

## Severity counts

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 2 |
| MINOR | 3 |
| Compliant items confirmed | 9 |

No law-#7 BLOCKER (no raw `v` or raw `T` is the *persisted source of truth* anywhere; the engine's
truth carriers are momentum `p` and enthalpy `E`). The two MAJORs are persistence-chain gaps that defeat
the *intent* of §1.1's loss-free round-trip for `E` and momentum, but do not store a forbidden intensive.

---

## MAJOR findings

### MAJOR-F1 — Enthalpy `E` is NOT persisted in Java; it is reconstructed each tick as `mass·cp·T` from the persisted *temperature*

- **Spec:** §1.1 "persisted per cell = { … enthalpy E [J] … }. Intensive derived every tick, never
  stored." §8.1 chain-anchored curves with latent **plateaus** — `T` is degenerate across a mushy band
  (the whole plateau shares one `T`), so `E` must be the carried quantity. Law #7: store EXTENSIVE
  (`E`), derive INTENSIVE (`T`). The JNI was explicitly grown (T10.x) to cross `jEin`/`jEout` as
  ABSOLUTE E precisely so the seam is loss-free.
- **What the code ACTUALLY does:**
  - The whole Java persistence chain has **no E channel**. `SectionData` stores only `temperature` +
    `mass` (+ vel/p/swapReady) — `SectionData.java:36-46`. `ColumnTask`/`ColumnResult`
    (`ColumnTask.java:5-6`, `ColumnResult.java:4-5`) and `RegionMarshaller.Flat`
    (`RegionMarshaller.java:12-15`) carry `temperature`, never `E`. `ColumnAssembler.SectionCells`
    (`ColumnAssembler.java:58-60`) has no E field.
  - `NativeEngine.stepWorld` **reconstructs** the input E from the persisted temperature each call:
    `NativeEngine.java:171-179` —
    ```java
    if (cp != null) {
        for (int i = 0; i < total; i++) {
            int mi = matIxFlat[i];
            float c = (mi < cp.length) ? cp[mi] : 0f;
            eIn[i] = massFlat[i] * c * tInFlat[i];     // E = m·cp·T  (single-slope, no plateau)
        }
    } ...
    ```
  - The engine's `eOut` is captured but **discarded**: `NativeEngine.java:189` allocates `eOut`, passes
    it (line 198), and the comment at lines 186-188 states it "is not yet consumed downstream"; the
    write-back uses only `ColumnResult.temperature()` (T-derived). So the authoritative within-tick E
    the engine produced is thrown away and re-synthesized from rounded T next tick.
- **Consequence (real bug, not cosmetic):** for any cell sitting on a latent plateau (boiling water at
  373 K, freezing water at 273 K, lava→stone at 1275 K) the reconstruction `E = m·cp·T` collapses the
  cell to the band-edge enthalpy and silently loses (or fabricates) the latent-heat fraction every tick.
  This is exactly the corruption the JNI in-code comment (`orge_jni.cpp:215-220`) warns against — the
  warning is correct, but the *Java caller violates it*. INV-LAT ("heated water pins at 373 K until
  +2.256e6 J/kg then relabels with ΔE ≡ 0") cannot hold across a JNI round-trip while E is rebuilt from
  T. Within a single tick E is loss-free (the C++ side honors it); the loss is at the tick boundary,
  which is where §1.1 demands persistence.
- **Classification:** CODE-BUG against §1.1 / law #7 / §8.1. The C++/JNI half is built correctly
  (`jEin`/`jEout` cross as absolute E); the **Java persistence layer never grew the E channel**, so the
  loss-free seam terminates at a `mass·cp·T` reconstruction. NativeEngine's own comments
  (`NativeEngine.java:90-92`, `163-165`) acknowledge it: "true cross-tick absolute-E persistence is the
  Subtask 9 disk decision" — i.e. **known, tracked, deferred DEBT** (Subtask 9), but the spec §1.1 text
  is unconditional, so it is filed here as an open drift, not a satisfied requirement.
- **Should do:** add an `E[]` channel to `SectionData` + `SectionCells` + `ColumnTask`/`ColumnResult` +
  `RegionMarshaller`, source `eIn` from the persisted E (not `mass·cp·T`), and write `eOut` back into
  `SectionData.E` in `writeBackColumn` — mirroring exactly how momentum/velocity and `P` are threaded.

### MAJOR-F2 — Persisted momentum is round-tripped as *velocity* (`p` reconstructed `v·m` in, re-derived `p/m` out); a relabel/flux that changes a cell's mass between store and reload silently rescales its momentum

- **Spec:** §1.1 / law #7: momentum `(px,py,pz)` is the persisted extensive carrier; `velocity = p/m`
  is derived every tick, never stored. §1.3 retired-symbols list: "persisted vx/vy/vz" must not reappear.
- **What the code ACTUALLY does:** The persisted Java channel is **velocity**, not momentum.
  `SectionData` stores `velX/velY/velZ` (`SectionData.java:42-44`); `ColumnTask`/`ColumnResult`/
  `RegionMarshaller`/`SectionCells` all carry `velX/velY/velZ`. The JNI reconstructs momentum on
  feed-in (`orge_jni.cpp:207-209` `C->px[i] = vxIn·mass`) and re-derives velocity on read-out
  (`orge_jni.cpp:314-320` `vxOut = px·(1/m)`).
- **Consequence:** within one call this is consistent (mass + velocity arrive paired, as the JNI comment
  at 204-206 notes). But across the tick boundary the *velocity* is what survives in `SectionData`, and
  next tick momentum is rebuilt from `v·m` using **whatever mass the cell now holds**. If the cell's mass
  changed (advection flux, a relabel, a swap recipient) between write-back and the next snapshot, the
  re-derived momentum `v·m_new ≠ p_old` — momentum is silently rescaled, not conserved. Storing the
  extensive `p` (as §1.1 requires) would make the round-trip exact regardless of mass changes; storing
  `v` re-introduces the velocity-ghost the law forbids at the *persistence* layer.
- **Classification:** CODE-BUG against §1.1 / law #7. It does not store a raw velocity *inside the engine*
  (the engine truth is `px/py/pz`), so it is **not** the §11 INV-7 / drift-test-(c) BLOCKER class — but
  the Java SoT that crosses ticks is velocity, which §1.3 explicitly retired. The JNI in-code comment
  ("there is no decoupled-velocity ghost at the boundary") is true only *within* a call, not across one.
- **Should do:** persist `px/py/pz` in `SectionData`/`SectionCells`/`ColumnTask`/`ColumnResult`/
  `RegionMarshaller` and cross them as momentum (the JNI's `jEin`/`jEout` precedent), deriving velocity
  only at the render/observability boundary.

---

## MINOR findings

### MINOR-F3 — `swapReady` is in-memory only, never serialized; resets to 0 on world reload
- `SectionData.swapReady` is documented IN-MEMORY ONLY, NOT serialized (`SectionData.java:46`,
  `305-307`, `763-766` in MinecraftThermalWorld). §1.1 lists `swapReady` as a persisted bookkeeping
  field. The cadence accumulator (≤1 of a fire interval) resetting on reload only delays a swap by up to
  `t_swap` once — spec-acceptable (the comment says so), so MINOR. The §1.1 frozen-manifest text still
  enumerates it as persisted; the divergence is a documented acceptance, file as spec-acknowledged DEBT.

### MINOR-F4 — `void_ix` does not cross the JNI ABI (not fed in / not read out)
- §1.1 lists `void_ix` (engine free-list index) among the seven persisted fields. The JNI ABI
  (`orge_jni.cpp` orgeStepWorld signature) has no void_ix array in or out; the engine resets it to its
  constructed default (0) on every per-call World rebuild (`sim_engine.hpp:276`). Because `void_ix` is
  pure engine bookkeeping with "no physical meaning" (law #7) and slot-0 is the stable vacuum sentinel
  in this build, a per-call default of 0 is currently correct, so this is MINOR. Strictly, §1.1 requires
  it to persist; classify as spec-acknowledged DEBT (engine-internal, harmless under the current LUT).

### MINOR-F5 — Stale comments contradict the executable code (sub-cycling; swapReady persistence)
- `Scheduler.java:184-185`, `216-217` and `orge_jni.cpp:282-284` describe "sub-cycles n=round(dt/0.25)"
  but the executable code does **ONE** `step_world_b` per call with no sub-cycle loop
  (`orge_jni.cpp:285-293`) — the FORK-4 full-step-sub-cycling ban is in fact honored. `sim_engine.hpp:271`
  has a `TODO(T10): swapReady persistence across JNI` that is already implemented (`orge_jni.cpp:228,311`).
  No behavior impact (comments are discarded per the audit contract); flagged so a future reader is not
  misled. CODE-comment drift, not code drift.

---

## Compliant items (confirmed against executable code)

1. **Pass order (§6.1 / §4 normative):** `step_world_b` (`engine_b.hpp:3126-3152`) runs
   `derive_world_T → snapshot → encrypt(ENCODE) → relax_pressure_world (2·N_relax half-sweeps) →
   resolve_world (R0→R1→R1.5→R2) → freeze_evict → ledger-fold → decrypt(DECODE)`. Matches ENCODE →
   pressure sweeps → R0..R2 → DECODE. (R1/R1.5 σ/ρ′ are ST3 placeholders=1 per the comments — that is a
   §6 module concern, not Module F; the *ordering* is compliant.)
2. **No atomics in hot passes (§9):** grep of `engine_b.hpp` finds no `std::atomic`, no
   `#pragma omp`, no `fetch_add`. Boundary/ledger reduction is done OUTSIDE the resolve pass
   (`engine_b.hpp:3143-3147` folds per-chunk `boundaryE_sky` partials after RESOLVE). Compliant.
3. **No raw `T` persisted as SoT in the engine:** `Chunk::E` is the truth carrier; `T_curr` is
   re-derived from `E` at the top of every step (`engine_b.hpp:3130` → `derive_world_T`,
   `sim_engine.hpp:357-370`). A scribbled T cannot survive a tick. Law #7 satisfied engine-side.
4. **No raw `v` persisted as SoT in the engine:** `Chunk::px/py/pz` is the truth; `ChunkSnapshot.vx`
   is a transient derived view (`sim_engine.hpp:554-562`). Law #7 satisfied engine-side.
5. **Momentum, E, P, swapReady all cross the JNI in BOTH directions:** feed-in
   `orge_jni.cpp:207-228` (px/py/pz from v·m, E from eIn, P from pIn, swapReady from swapRdyIn);
   read-out `orge_jni.cpp:299-321` (px→vxOut, E→eOut, P→pOut, swapReady→swapRdyOut). None defaulted to
   zero on read. (Caveat: see MAJOR-F1/F2 — the *Java* side terminates E in a reconstruction and
   persists velocity not momentum; the ABI itself carries them correctly.)
6. **`P` persisted round-trip:** `pIn` loaded directly into `C->P[i]` (`orge_jni.cpp:214`), relaxed,
   stored back to `pOut` (`orge_jni.cpp:321`); Java threads it through `SectionData.p` →
   `SectionCells.p` → `ColumnTask.p` → `Flat.pIn` → `ColumnResult.p` → write-back
   (`MinecraftThermalWorld.java:709,760-762`). The one persisted intensive `P` survives across ticks
   (law #1). Compliant.
7. **MaterialLUT full schema (§1.2 / law #8):** all 17 columns cross the register ABI —
   `cond, heatCap, molar, minMass, maxMass, visc, defaultMass, yieldStress, minTemp, maxTemp,
   minTarget, maxTarget, emissivity, thermalExpansion, latentHeatMin, latentHeatMax, T_ref_gas`
   (`LutArrays.java:33-87`, `NativeEngine.java:53-103`, `orge_jni.cpp:24-104`). No column missing;
   `χ` is derived in-engine from min/default/max (not a stored column — correct per §1.2). Compliant.
8. **dt-invariance / cadence in seconds (§9):** `Scheduler.nextDt()` (`Scheduler.java:238-243`)
   computes `secs = ticksSinceLastDispatch/20`, clamped to `[ADVECTION_DT_SECONDS=0.25,
   MAX_CATCHUP_SECONDS=0.5]` — a real-seconds dt, not a tick count. Passed straight to the engine which
   scales amounts by dt. Compliant.
9. **No full-step sub-cycling (FORK-4 ban, §3.2/§9):** `orge_jni.cpp:285-293` calls `step_world_b`
   exactly once per JNI call; the `dt` scales the amount moved, not iterated distance. The "sub-cycle"
   comments are stale (MINOR-F5). FORK-4 ban honored. Compliant.

---

## Bottom line for Module F

The **native/JNI ABI is spec-faithful**: momentum, E, P, swapReady all cross in both directions, the
full 17-column LUT is carried, pass order is ENCODE→relax→R0..R2→DECODE, and there are no atomics in the
hot passes. The drift lives entirely in the **Java persistence layer**, which never grew an `E` channel
(MAJOR-F1: E is rebuilt as `mass·cp·T` each tick, corrupting latent-plateau cells across ticks) and
persists **velocity instead of momentum** (MAJOR-F2: momentum is silently rescaled when a cell's mass
changes between ticks). Both are tracked DEBT in NativeEngine's comments ("Subtask 9") but are open
divergences against §1.1's unconditional persisted-extensive-set requirement. `swapReady` (MINOR-F3)
and `void_ix` (MINOR-F4) are documented/acceptable non-persistence. No law-#7 BLOCKER: nothing stores a
raw intensive as the engine's source of truth.
