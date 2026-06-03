# ORGE spec-conflict inventory — engine-resident material table

Spec (authoritative): `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`
Task: READ-ONLY audit. Branch `rebuild`. Date 2026-06-03.

The spec changes: (1) per-cell `matIx` ids become globally STABLE (fixed per material at
load/`/reload`, slot 0 = VACUUM, slots 1..N = `MaterialRegistry.all()` sorted by namespaced id);
(2) the engine holds the LUT resident via a new `orgeRegisterMaterials(lutEpoch, …)` JNI, and
`orgeStepWorld` DROPS the 6 LUT arrays + gains a `lutEpoch` selector; (3) World stays stateless;
(4) `sim_engine.hpp`/`orge_kernel.hpp` UNTOUCHED — resident table lives only in `orge_jni.cpp`;
(5) reload/bg-step races handled by epoch-keyed tables.

Confidence legend: HIGH = directly named in spec / unambiguous. MED = strong inference. LOW = adjacent.

---

## BREAKS-SPEC  (contract/code the change invalidates; MUST change)

1. `ORGE-ENGINE/orge_jni.cpp:22-72` · BREAKS-SPEC · `orgeStepWorld` takes the 6 LUT arrays
   (`jCond..jVisc`) and rebuilds `world.materials` per call (`for (i<matCount) world.materials.add(m)`).
   Spec §5.2 drops the 6 arrays, adds `jint lutEpoch`, and looks the table up from a resident
   per-epoch map. The per-call rebuild loop moves into a new `orgeRegisterMaterials`.
   · Action: change code (primary BREAKS-SPEC site; hard ABI cut). · HIGH

2. `ORGE-ENGINE/orge_jni.cpp:59` · BREAKS-SPEC · comment "Build the material LUT in index order
   (matches Java's six-array canonical LUT, Task 2.1)" — describes the per-call build that goes away.
   · Action: change code + comment. · HIGH

3. `core/.../engine/NativeEngine.java:47-57` · BREAKS-SPEC · `native double orgeStepWorld(... float[]
   lutCond..lutVisc ...)` declaration carries the 6 LUT arrays, no `lutEpoch`. Param order "MUST match
   orge_jni.cpp". Must add `native void orgeRegisterMaterials(...)` and re-sign `orgeStepWorld`.
   · Action: change code. · HIGH

4. `core/.../engine/NativeEngine.java:74,79,108-115` · BREAKS-SPEC · `stepWorld(...)` calls
   `RegionMarshaller.flatten(columns, lut)`, pulls `LutArrays L = f.lut()`, and passes
   `L.cond()..L.visc()` into the native every call. Spec §6: stop packing/passing LutArrays; pass the
   current `lutEpoch`. · Action: change code. · HIGH

5. `core/.../engine/RegionMarshaller.java:13-15,17,33` · BREAKS-SPEC · `record Flat(... LutArrays lut)`
   and `flatten(...)` ends with `new Flat(..., LutArrays.pack(lut))`. Spec §6: drop the `lut` field from
   `Flat`; `flatten` no longer calls `LutArrays.pack`. · Action: change code. · HIGH

6. `core/.../engine/OrgeEngine.java:31-32,45-50` · BREAKS-SPEC · the `stepWorld(columns, lut, …)`
   interface (both overloads) takes `List<Material> lut`; default overload reads `lut.size()`. Spec §6 +
   open-decision §10.1 recommend DROPPING the `lut` param (or ignoring it). · Action: change code
   (interface ripple to StubEngine + Scheduler + callers). · HIGH

7. `core/.../engine/StubEngine.java:18` · BREAKS-SPEC · `stepWorld(List<ColumnTask> columns,
   List<Material> lut, …)` mirrors the old signature; spec §6 says StubEngine mirrors the new shape
   (register = no-op, step takes epoch not lut). · Action: change code. · HIGH

8. `core/.../scheduler/MaterialLut.java:39-60,71-74` · BREAKS-SPEC · the canonical batch-local model:
   `private final List<Material> lut = new ArrayList<>()` populated by `indexOf(Material)` which
   "appends on first sight" (`char ix = (char) lut.size(); lut.add(...)`). Spec §6: `MaterialLut`
   becomes a *view* over `State`'s ordered table — `indexOf(Identifier)` returns the fixed slot, no
   append, no per-step instance. · Action: change code. · HIGH

9. `core/.../scheduler/MaterialLut.java:17-18` · BREAKS-SPEC + STALE-DOC · class javadoc: "Real
   materials are appended in first-seen order … Rebuilt fresh each step; no cross-tick stability
   needed." This is THE canonical stale contract; directly contradicts spec §2/§4 (stable global ids).
   · Action: change code + rewrite javadoc. · HIGH

10. `core/.../scheduler/MinecraftThermalWorld.java:511` · BREAKS-SPEC · `MaterialLut lut = new
    MaterialLut();` — a fresh per-snapshot batch LUT. Spec §6: remove `new MaterialLut()` from the
    snapshot path; assembler reads the State's ordered table. · Action: change code. · HIGH

11. `core/.../scheduler/MinecraftThermalWorld.java:508` · ASSUMES-BATCH-LOCAL-IDS (adjacent to #10) ·
    `ActiveMaterials.State mats = ActiveMaterials.current();` is fetched but the per-batch `lut` is
    built independently of it; spec wants the ordered table to come FROM the State (`mats`). The
    `ColumnBatch` must also carry that State's `lutEpoch`. · Action: change code. · MED

12. `core/.../scheduler/ColumnAssembler.java:70,93,99,109` · BREAKS-SPEC · `assemble(... MaterialLut
    lut, MaterialRegistry registry, …)` calls `lut.indexOf(sm)` ("appends if absent — stored is
    authoritative") to assign `matIx`, and `lut.materials().get(mat)`. Under stable ids it must read a
    fixed slot from the published State (no append). · Action: change code. · HIGH

13. `core/.../scheduler/Scheduler.java:199-200,223` · BREAKS-SPEC · `List<Material> lut = batch.lut();
    pendingMaterials = lut; … engine.stepWorld(input, lut, dt, …)` — passes the per-batch lut into the
    step. Spec §6/§7: pass the batch's `lutEpoch` instead; register on reload. · Action: change code. · HIGH

14. `core/.../scheduler/ThermalWorld.java:85,89,102-103` · BREAKS-SPEC · `record ColumnBatch(List<Material>
    lut, …)` and `snapshotColumns` default returns `new ColumnBatch(List.of(), List.of(MaterialLut.VACUUM))`.
    Spec §6: `ColumnBatch` must carry the `lutEpoch` it was assembled under (so the scheduler picks the
    matching resident table). · Action: change code. · HIGH

15. `core/.../material/ActiveMaterials.java:34-37,88,101-103` · BREAKS-SPEC (new behavior) · `State` and
    `swap(State)`/`reloadFrom(...)` are the spec's register point but today do NOT assign a `lutEpoch`,
    build an ordered table, or call `engine.registerMaterials(...)`. Spec §6: on swap assign
    `next.lutEpoch = ++epochCounter`, build the ordered `List<Material>` + `byId` map, register the
    engine. · Action: change code. · HIGH

16. `core/.../material/MaterialRegistry.java:24,48-49` · BREAKS-SPEC / CONTRADICTS-STABLE-ID ·
    `byId = new ConcurrentHashMap<>()` and `all()` returns `byId.values()` — an UNORDERED collection.
    Spec §4 needs slots 1..N = `all()` sorted by namespaced id string (deterministic/reload-stable). An
    unsorted `values()` cannot produce stable ids. · Action: change code (sort in the table builder, or
    here). · HIGH

17. `core/.../engine/EngineFactory.java:14-20` · BREAKS-SPEC (gap) · `EngineFactory.create()` returns a
    fresh `OrgeEngine`; spec §6 / open-decision §10.3 needs a SHARED engine instance the reload listener
    can call `registerMaterials` on. Today engines are newed per use → no place to register the resident
    table. · Action: change code (introduce shared singleton / pass engine into reload listener). · MED

18. `core/.../engine/LutArrays.java:24` · BREAKS-SPEC (re-purpose) · `pack(List<Material> lut)` is today
    called per-step by `RegionMarshaller.flatten`. Spec §6: `LutArrays.pack` is reused ONLY by the
    register call. Not deleted, but its caller and lifecycle change. · Action: change code (re-route
    caller). · MED

---

## STALE-DOC  (describes the old per-step-LUT / batch-local model as current truth)

19. `docs/orgestep.md:100,107-108,122` · STALE-DOC · "Output of this step: a List<ColumnTask> + the
    material LUT", "plus the per-material LUT (LutArrays.pack)", step 5 "Reconstruct the material LUT".
    Describes per-step LUT shipping + per-call rebuild as current. · Action: annotate with pointer to
    new spec (this is the in-repo "how a step works" doc). · HIGH

20. `docs/orgestep.md:87,113` · STALE-DOC · "matIx — from the live block via MaterialBindings (→ the
    batch LUT index)" and "NativeEngine.stepWorld(columns, lut, dt, passes)". Batch-LUT-index +
    lut-in-step framing. · Action: annotate. · HIGH

21. `DESIGN.md:39` · STALE-DOC · "given a batch of subchunks + … + the material LUT + dt, run one step"
    — the step still ships the LUT. Top-level design doc. · Action: annotate with pointer (low edit). · MED

22. `docs/superpowers/specs/2026-05-30-scheduler-track-design.md:54-65,170-171` · STALE-DOC +
    CONTRADICTS-STABLE-ID · "MaterialLut — builds a per-batch List<Material> … no cross-tick index
    stability is required"; "MaterialLut — index 0 = void; stable append". Explicitly states the
    opposite of the new spec. · Action: annotate (historical scheduler design). · HIGH

23. `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md:62-63,127,138` · STALE-DOC ·
    "the material LUT" passed in `step(tasks, lut, dt)`, "bad matIx range validated Java-side". Original
    FFI design ships LUT per step. · Action: annotate. · MED

24. `docs/superpowers/specs/2026-06-01-whole-region-engine-step-design.md:80,124-125,151-152,198,221` ·
    STALE-DOC · `orgeStepWorld` FFI block lists "material LUT (UNCHANGED … reuse BatchMarshaller
    packing)", "RegionStep marshaller (reuse LUT/air-flag packing)", "matIx: live block via
    MaterialBindings → LUT index". The whole-region step design ships the LUT. · Action: annotate. · HIGH

25. `docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md:195,202` · STALE-DOC · "one
    orgeStepWorld JNI … (per-column arrays + a per-material LUT)". The CURRENT unified-fluid design (the
    superseding advection model) still frames the LUT as travelling per step. · Action: annotate. · HIGH

26. `docs/superpowers/specs/2026-05-30-fluid-flow-liquid-phase2a-design.md:144` · STALE-DOC ·
    "(entry) passed in the material LUT." · Action: annotate (low prio). · LOW

27. `docs/superpowers/plans/2026-05-30-scheduler-track.md:126-127,147` · STALE-DOC + CONTRADICTS-STABLE-ID
    · the plan that authored the MaterialLut javadoc: "appended in first-seen order … Rebuilt fresh each
    step; no cross-tick stability needed" + "appending it on first sight". · Action: annotate
    (historical plan). · HIGH

28. `docs/superpowers/plans/2026-06-02-placement-injection-2-java.md:34` · STALE-DOC · "MaterialLut …
    indexOf(Material) (appends on first sight) … Built fresh per snapshotColumns." · Action: annotate. · MED

29. `docs/superpowers/plans/2026-06-03-durable-material-place-break.md:289` · STALE-DOC /
    ASSUMES-BATCH-LOCAL-IDS · describes a `MaterialPalette` with `indexOf(id)` "appending it on first
    sight" — a parallel first-seen scheme (note: this is the SectionData on-disk palette, see §OK below,
    but the plan prose reinforces the first-seen-id mental model). · Action: annotate. · MED

---

## ASSUMES-BATCH-LOCAL-IDS  (code/tests relying on first-seen / non-stable ids)

30. `core/src/test/java/.../scheduler/MaterialLutTest.java:28-58` · ASSUMES-BATCH-LOCAL-IDS ·
    asserts `firstRealMaterialGetsIndexOne`, `distinctMaterialsGetSequentialIndices` (stone→1, water→2,
    iron→3) — pure encounter-order assertions. Under stable ids the slot is the registry-sorted position,
    not insertion order. · Action: change code (rewrite tests once `MaterialLut` is a view). · HIGH

31. `core/src/test/java/.../scheduler/GeometryAssemblerTest.java:22,37,85` · ASSUMES-BATCH-LOCAL-IDS ·
    `MaterialLut lut = new MaterialLut();` then assembles — depends on the per-batch appending instance.
    · Action: change code (construct via published State). · MED

32. `core/src/test/java/.../scheduler/ColumnAssemblerTest.java:99` · ASSUMES-BATCH-LOCAL-IDS ·
    `MaterialLut lut = new MaterialLut();` feeding `ColumnAssembler.assemble`. · Action: change code. · MED

33. `core/src/test/java/.../engine/TestMaterials.java:81` · ASSUMES-BATCH-LOCAL-IDS · `new MaterialLut()`
    test helper builds a batch-local lut. · Action: change code (point at a State-built table). · MED

34. `core/src/test/java/.../scheduler/InjectionDrainTest.java:26-31` · ASSUMES-BATCH-LOCAL-IDS · comment
    "resolver mimics the batch MaterialLut (which APPENDS on first sight): void=0, air=1, water=2." The
    resolver itself (an `id→char`) survives, but the rationale/expected indices assume first-seen.
    · Action: change code (update comment + expected slots to stable ids). · MED

---

## CONTRADICTS-STABLE-ID  (asserts ids are NOT stable / LUT is per-batch/compact)

(Primary instances are folded into #9, #16, #22, #27 above where they coincide with another category.)

35. `docs/superpowers/specs/2026-05-30-scheduler-track-design.md:58` · CONTRADICTS-STABLE-ID ·
    "no cross-tick index stability is required" — the single most direct contradiction of the spec in the
    docs. · Action: annotate (already covered by #22; called out separately for emphasis). · HIGH

36. `OrgeEngine.java:27` javadoc · CONTRADICTS-STABLE-ID (mild) · `@param lut material table, indexed by
    the columns' matIx values` — frames matIx as indexing a step-supplied table. Under the spec the table
    is resident + epoch-selected. · Action: change code/doc when the param is dropped. · MED

---

## OK-BUT-ADJACENT  (touches the area, still correct under the spec)

- `ORGE-ENGINE/sim_engine.hpp`, `ORGE-ENGINE/orge_kernel.hpp` · OK · take `const MaterialLUT&` / a
  `World.materials` vector explicitly; spec §3 says UNTOUCHED. `orge_kernel.hpp:73` "The only 'empty' is
  VACUUM (matIx==0 …)" stays valid (slot 0 = VACUUM is preserved). No change. · HIGH
- `ORGE-ENGINE/tests/*` (sort_swap, displace, injection, viscosity_*, vertical_merge, time_dt,
  unified_basics, bprime_evacuate, push_chain_tube, lava_water_equilibrium) · OK · build their own inline
  `MaterialLUT`/`world.materials` and use local enum constants (WATER/AIR/STONE) as indices into THAT
  table — not Java's batch ids; spec §3 says no engine-algorithm test changes. Comments only. · HIGH
- `ORGE-ENGINE/sim_server.hpp:55,80-81`, `sim_render.hpp:*` · OK · the dormant/deleted SimServer +
  renderer reference a growable material `std::vector`; not on the `orgeStepWorld` path the spec touches.
  · MED
- `core/.../section/MaterialPalette.java:17,69` · OK-BUT-ADJACENT · the SectionData ON-DISK palette
  appends `Identifier`s on first sight. Spec §3 says "SectionStore on-disk format — already
  Identifier-keyed (stable). Only the in-flight char encoding becomes global." So the per-section disk
  palette legitimately stays first-seen; only the engine-bound `matIx` encoding becomes global. Do NOT
  conflate with `scheduler/MaterialLut`. Worth a one-line note so an implementer doesn't "fix" it. · HIGH
- `core/.../engine/LutArrays.java` (the record + `pack`) · OK as a type · survives; only its caller
  moves (see #18). · HIGH
- `docs/superpowers/specs/2026-05-31-molar-mass-gas-displacement-design.md:28,36`,
  `2026-06-03-block-material-unified-place-break-design.md:28,94,102,134,172` ·
  OK-BUT-ADJACENT · use `matIx==0`/`matIx 0` as the VACUUM sentinel — still valid (slot 0 preserved).
  No first-seen claim. · MED

---

## Docs that should be annotated with a pointer to the new spec

(banner: "SUPERSEDED re: material LUT — see 2026-06-03-engine-resident-material-table-design.md;
matIx ids are now globally STABLE and the LUT is engine-resident (register-once), not shipped per step.")

1. `docs/orgestep.md` — in-repo "how a step works" walkthrough; most actively misleading. (HIGH)
2. `docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md` — the CURRENT advection design;
   still says LUT travels per step. (HIGH)
3. `docs/superpowers/specs/2026-06-01-whole-region-engine-step-design.md` — defines `orgeStepWorld`'s
   old FFI shape. (HIGH)
4. `docs/superpowers/specs/2026-05-30-scheduler-track-design.md` — "no cross-tick stability required". (HIGH)
5. `docs/superpowers/plans/2026-05-30-scheduler-track.md` — source of the MaterialLut first-seen javadoc. (HIGH)
6. `DESIGN.md` (§ around line 39) — top-level "given … the material LUT … run one step". (MED)
7. `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md` — original FFI `step(tasks, lut, dt)`. (MED)
8. `docs/superpowers/plans/2026-06-02-placement-injection-2-java.md` — "MaterialLut … built fresh per
   snapshotColumns". (MED)
9. `docs/superpowers/plans/2026-06-03-durable-material-place-break.md` — reinforces first-seen-id mental
   model (palette is OK, but prose). (MED)

---

## Counts

- BREAKS-SPEC: 18 findings (#1–#18)
- STALE-DOC: 11 findings (#19–#29)
- ASSUMES-BATCH-LOCAL-IDS: 5 findings (#30–#34)
- CONTRADICTS-STABLE-ID: 2 findings (#35–#36; primaries folded into #9/#16/#22/#27)
- OK-BUT-ADJACENT: 6 noted (engine hpp/tests, MaterialPalette, LutArrays, sentinel docs)
