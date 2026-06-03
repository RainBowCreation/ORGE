# Engine-resident material table (register-once LUT + stable global ids)

Date 2026-06-03 · Branch `rebuild` · Status: SPEC (no code yet) · Author handoff: lava/water debug session

## 1. Motivation

Today every `orgeStepWorld` JNI call re-ships the full 6-array material LUT
(`cond, heatCap, molar, minMass, maxMass, visc`) and the native shim rebuilds a
`MaterialLUT` from scratch each call (`orge_jni.cpp:63-72`). The material physics only
changes on `/reload`, so re-marshalling + re-building it every tick is wasted work and
muddies the boundary.

**Goal (user directive 2026-06-03):** the engine holds the material id→physics table
**once, from mod load until `/reload`**; `stepWorld` then sends only per-cell
`{id, mass, temp}`. The World itself stays **stateless** — rebuild-from-arrays → simulate
→ read back → discard remains correct and unchanged. Only the *material dictionary* becomes
resident.

Perf is **not** the driver (the LUT is a rounding error next to the per-cell world arrays);
the driver is a clean boundary where the engine owns material physics and Java passes ids.

## 2. The coupling: per-cell ids must become STABLE

Today `matIx` ids are **batch-local, first-seen**: `MaterialLut` is `new`-ed per step
(`MinecraftThermalWorld.java:511`) and assigns indices in encounter order
(index 0 = `VACUUM`). It is correct today only because the matching LUT travels *with* the
same batch. A resident LUT is meaningless unless a given `matIx` value means the same
material on every call. **So this change is two coupled parts:**

1. **Stable global material table** — a fixed id per material assigned at load / `/reload`.
2. **Register-once + drop-from-step** — engine stores the table; `orgeStepWorld` drops the
   6 LUT arrays.

## 3. What does NOT change

- `sim_engine.hpp` / `orge_kernel.hpp` — the C++ passes already take `const MaterialLUT&`
  explicitly; the resident table lives **only in the JNI shim** (`orge_jni.cpp`). **No
  kernel-parity work, no engine-algorithm test changes.**
- SectionStore on-disk format — already `Identifier`-keyed (stable). Only the in-flight
  `char` encoding becomes global.
- World lifecycle — still built per `orgeStepWorld`, stepped, read back, destroyed.
- The injection channel, ledger, dt sub-cycling, passes bitmask — untouched.

## 4. Stable id ordering (single source of truth)

The canonical table is built **once per published materials state** and reused by BOTH the
engine registration and the column assembly — so they can never disagree.

- Slot **0 = `MaterialLut.VACUUM`** sentinel (unchanged invariant).
- Slots `1..N` = `MaterialRegistry.all()` sorted by **namespaced id string** (deterministic,
  run-stable, reload-stable for an unchanged datapack).
- `char` width caps at 65535 materials (existing guard).

Embed this ordered table in `ActiveMaterials.State` so the ONE published state carries:
the registry (lookup by id), the ordered `List<Material>` (slot→material), and a
`byId : Identifier→char` map (material→slot). `ColumnAssembler` reads slots from the State;
the engine is registered from the same State.

## 5. JNI ABI

### 5.1 New: register (called on every publish — initial load + each `/reload`)
```c
JNIEXPORT void JNICALL
Java_..._NativeEngine_orgeRegisterMaterials(
    JNIEnv*, jclass,
    jint lutEpoch,                 // generation tag (see §7 thread-safety)
    jint matCount,
    jfloatArray cond, heatCap, molar, minMass, maxMass, visc);
```
Stores the table under `lutEpoch` in a process-global `std::unordered_map<int, MaterialLUT>`
(or a small ring of the last 2 epochs). Replaces an existing epoch.

### 5.2 Changed: step (drops the 6 LUT arrays, gains `lutEpoch`)
```c
JNIEXPORT jdouble JNICALL
Java_..._NativeEngine_orgeStepWorld(
    JNIEnv*, jclass,
    jint lutEpoch,                 // selects the resident table
    jint nCols, jintArray cx, jintArray cz,
    jcharArray matIx, jfloatArray mass, jfloatArray tIn,
    jint passes, jdouble dt,
    jfloatArray tOut, jfloatArray massOut, jcharArray matOut,
    jint injCount, jintArray injColumn, jintArray injCell,
    jcharArray injSpecies, jfloatArray injMass, jfloatArray injTemp,
    jfloatArray ledgerOut);
```
Looks up the resident `MaterialLUT` by `lutEpoch`. **If the epoch is unknown** (step before
any register, or an evicted epoch): no-op safe — copy `matIx/mass/tIn` → `matOut/massOut/tOut`
unchanged, ledger zero, return 0.0 (mirrors today's `columns.isEmpty()` short-circuit; never
crashes, never fabricates).

This is a **hard ABI cut** (signature changes) — `.so` + Java native decl move together.

## 6. Java side

- **`NativeEngine`**: add `native void orgeRegisterMaterials(...)`; change `orgeStepWorld`
  signature; `stepWorld(...)` stops packing/passing `LutArrays`, passes the current
  `lutEpoch` instead. Keep `OrgeEngine.stepWorld(columns, lut, ...)` interface? → drop the
  `lut` param OR ignore it (decide in §10). `StubEngine` mirrors (register = no-op).
- **`ActiveMaterials`**: on `swap(next)` (initial + reload), assign `next.lutEpoch =
  ++epochCounter`, build the ordered table, and call `engine.registerMaterials(epoch, table)`.
  Engine handle obtained via `EngineFactory` (a single shared engine instance — confirm one
  exists; today `NativeEngine` is stateless-newable, so introduce a shared singleton or pass
  the engine into the reload listener).
- **`MaterialLut`**: becomes a *view* over `State`'s ordered table — `indexOf(Identifier)`
  returns the fixed slot (no append, no per-step instance). Remove `new MaterialLut()` from
  the snapshot path; `ColumnAssembler.assemble(...)` takes the State's table.
- **`RegionMarshaller.Flat`**: drop the `LutArrays lut` field; `flatten` no longer calls
  `LutArrays.pack`. `LutArrays.pack` is reused only by the register call.
- **`ColumnBatch`**: carries the `lutEpoch` of the State it was assembled from, so the
  scheduler passes the matching epoch into `stepWorld`.

## 7. Thread-safety (the one real hazard)

Reload publishes on the server/reload thread; the scheduler assembles on the server thread
but **runs the engine step on a background thread** (memory: snapshot→bg `engine.step`→
validate→write). A `/reload` between a batch's assembly and its bg step would otherwise let
an old-id batch hit a freshly-overwritten table.

**Resolution — epoch-keyed tables (recommended):** every batch carries the `lutEpoch` it was
assembled under; the engine keeps tables per epoch and the step selects by epoch. An in-flight
old-epoch batch still finds its table. Keep the last 2 epochs; drop older after the scheduler
confirms no in-flight batch references them. No locks on the hot path (map read).

**Simpler fallback:** single resident table + drain the scheduler (no in-flight bg step)
before registering on reload. Less robust if reload races a long step; only if the epoch map
is deemed over-engineered.

## 8. Invariants / guards

- `0 <= matIx[c] < matCount(epoch)` for every cell — assembly guarantees it (slots come from
  the same table). Engine may `assert` in debug; in release an out-of-range id reads slot 0.
- Slot 0 is always VACUUM with molar 0, finite viscosity (movable) — unchanged.
- Bit-identical results: for a single materials state (no reload), output must match the
  current engine byte-for-byte (same LUT values, same world, same passes). This is the
  primary regression oracle.

## 9. Test plan (TDD)

1. **C++ JNI-shim unit** (new `tests/` or a small harness): register epoch 1, step a known
   world, assert results == calling `advect_world` with the same LUT inline. Step with an
   unknown epoch → safe no-op copy. Re-register a different epoch → both retrievable.
2. **Java `NativeEngine` IT** (real `.so`, native-load guarded): register once, run several
   steps WITHOUT re-passing the LUT, assert mass/temp evolution == the pre-change baseline
   (capture a golden from current HEAD first). Conservation holds.
3. **Stable-id test**: assemble the same world twice (simulating two ticks); assert identical
   `matIx` encoding across ticks (today it can differ) and that ids match registry order.
4. **Reload test**: publish state A (epoch 1), assemble batch under A; publish state B
   (epoch 2, different material set/order); assert the epoch-1 batch still steps correctly
   against epoch-1's table; new batches use epoch 2.
5. **Both loaders build**; `:core:test` + `:core:integrationTest` green on real `.so`.

## 10. Open decisions

1. `OrgeEngine.stepWorld(columns, lut, …)` interface — **drop** the `lut` param everywhere,
   or keep it and ignore (smaller diff, dead param)? Recommend drop for cleanliness.
2. Epoch-keyed map vs single-table+drain (§7) — recommend epoch map (robust, cheap).
3. Shared engine instance for the reload listener to call `registerMaterials` — confirm/build
   a singleton via `EngineFactory` (today engines are newed per use).
4. Eviction policy for old epochs — keep last 2, or have the scheduler explicitly free an
   epoch once drained.

## 11. Ship steps (when implemented)

- Engine: edit `orge_jni.cpp`; `./ORGE-ENGINE/tests/run_tests.sh cheap` + the new shim test;
  `./native/build_liborge.sh`; commit + push `origin/main`.
- Main repo: Java changes + tests; `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test
  :core:integrationTest`; both loaders build; bump the engine gitlink; commit + push
  `origin/rebuild` (after every commit, per project rule).

## 12. Sequencing

Per user (2026-06-03): **this LUT refactor FIRST, then** the lava/water chained-displacement
bug (root cause already found + RED test at `ORGE-ENGINE/tests/lava_water_equilibrium_test.cpp`;
see also the push-train discussion — Pass B' needs N-deep chained displacement).
