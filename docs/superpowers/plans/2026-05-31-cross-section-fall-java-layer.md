# Cross-section vertical FALL — Java-layer seam reconciler (Slice 1)

User picked the **Java-layer reconciler** over the originally-locked engine flux ledger (which was
red-teamed with 6 ship-blockers). This slice makes fluid **fall across stacked-section (Y) seams** by
moving floor-fluid into the section below **in the Java scheduler layer, after the engine step** — the
engine stays per-section and untouched. Horizontal cross-seam "flowing" (X/Z) is the NEXT slice.

## Why this dissolves the 6 red-team holes (no rework of the banked ledger needed)

1. **Bit-identity parity** — GONE. No kernel/`sim_engine` change; nothing to keep bit-identical.
2. **§9 fabrication blind-spot** — GONE. The transfer is plain Java arithmetic over both sections'
   stores (`B += dm; A -= dm`), conservative by construction; the logic asserts seam conservation and
   the engine's per-section §9 gate is untouched (runs before the seam pass).
3. **Pending buffer / out-of-range receiver** — not needed in slice 1. A loaded receiver is written
   directly; an *unloaded* receiver simply isn't written and the fluid rests on the donor's floor until
   the receiver loads (the donor re-tries every cycle). No in-flight invisible mass.
4. **Reseed-misfire eats the parcel** — handled by recording the post-seam species into
   `CellMaterialTracker` and re-reconciling **the same cycle** (the donor's drained cell becomes air in
   the world before the next snapshot, so `MaterialChangeReseed` never sees a fluid-vs-air mismatch).
5. **Void-vs-air at the seam** — Java reads the receiver's loaded/species state directly
   (`store.hasSection` + tracker species); no ambiguous halo sentinel.
6. **In-flight conservation accounting** — no buffer; tests sum the two stores directly.

## Core mechanic (mirror of the interior air-displacement foundation, across the seam)

For each stacked column (same `cx,cz`) with an UPPER section A over a loaded LOWER section B, and for
each of the 256 `(x,z)` seam cells (A's `y=0` plane over B's `y=15` plane):

- **A is a fluid (non-gas) cell with mass > eps**, AND
- **B is REAL AIR** (`lut.air[speciesB] && massB > eps`, species index != 0) → **SWAP**: B takes A's
  mass+species+T, A takes B's air mass+species+T (water sinks, the ~1.2 kg air rises into the donor).
  Conservative, residue-free — exactly the interior `(2a)` swap, just across the seam.
- **B is the SAME fluid with capacity** (`speciesB == speciesA && massB < maxMass`) → **DEPOSIT**:
  `dm = min(massA, maxMass − massB)`; `B += dm` (T-mix), `A −= dm` (keep species; if A drops ≤ eps set
  A to 0 mass, species unchanged — the interior fall drain convention, reseed-safe because air-live
  never reseeds).
- **B is void / different fluid / solid / gas, or unloaded** → **no transfer** (fluid rests on A's
  floor; the world-bottom / cross-material wall, same as interior).
- **A is gas** → skip (gas is buoyant, never falls).
- Direction is **down only** (gravity) — never B→A.

Cascade: one section-seam per advection cycle (interior fall refills each section's floor each cycle;
the seam pass drops the floor plane one section per cycle) → a pour visibly cascades to the world floor
at 4 Hz. Acceptable for slice 1.

## Architecture (pure logic + thin MC wiring)

- **`CrossSectionFluidLogic`** (NEW, pure, headless) — operates on plain arrays for the two seam planes
  (massA[256], spA[256], tA[256] for A's y=0; massB/spB/tB for B's y=15) + the batch `List<Material>`
  LUT. Mutates the arrays in place per the rule above; returns a small result: which A-cells and which
  B-cells changed (so the caller re-renders/re-records only those), plus the pre/post seam mass sums for
  a conservation assertion. NO Minecraft, NO engine, NO JNI types.
- **`ThermalWorld.settleCrossSectionSeams(entries, lut)`** (NEW interface method, `default` no-op) →
  returns `List<TouchedSection>` (dimension, key, synthetic `char[]` species for the changed plane).
  Implemented in **`MinecraftThermalWorld`**: for each batch entry with a loaded section below, pull the
  two seam planes from the `SectionStore` (mass/temp) and `CellMaterialTracker` (species, via an
  id→index map over the batch LUT), call `CrossSectionFluidLogic`, write the mutated planes back to both
  stores, re-record both sections' species in `CellMaterialTracker`, wake the receiver's (and donor's)
  flow pass, and emit a `TouchedSection` for each section whose blocks changed. If the receiver is
  loaded but UNTRACKED (never stepped), **wake it and skip this cycle** (it becomes tracked next cycle,
  then receives) — documented slice-1 lag.
- **`FluidReconciler.reconcile(dim, key, outMat, lut)`** (NEW overload; the existing `BatchEntry`
  overload delegates to it) so a non-batch receiver section can be re-rendered from a synthetic species
  array.
- **`Scheduler.writeBackResults`**: AFTER the entry loop (line ~361), on advection cycles, call
  `world.settleCrossSectionSeams(pendingEntries, pendingMaterials)` and re-reconcile each returned
  `TouchedSection` via the new overload.

## Constraints (non-negotiable)

- MAIN repo only (`/home/claude/ORGE`, branch `rebuild`). **No ENGINE change, no JNI, no `.so` rebuild,
  no gitlink bump** — this slice is pure Java.
- `JAVA_HOME=/home/claude/jdk21` on every gradle call.
- Push `origin/rebuild` after every MAIN commit (standing authorization).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Subagent-driven TDD: fresh implementer per task + two-stage review (spec, then quality).

## Task 1 [MAIN, pure] — `CrossSectionFluidLogic` vertical-fall logic + unit tests

Create `core/.../scheduler/CrossSectionFluidLogic.java`: a pure static API that, given the two seam
planes (A's `y=0`, B's `y=15`) as `float[] massA, tempA; char[] spA` and `massB, tempB, spB` (length
256 each) plus `List<Material> lut` and an `ADV_EPS` floor, mutates all six arrays in place applying the
swap/deposit/no-transfer rule per `(x,z)` column, and returns a result object exposing: a `boolean[256]`
(or index set) of changed A-cells, the same for B-cells, and `double massBefore`/`double massAfter`
(summed over the two planes, fluid+air) for a conservation check. Reuse the interior semantics:
`lut.get(sp).air()/fluid()/gas()/maxMass()`; species index 0 = void.

- TDD `CrossSectionFluidLogicTest` (no Minecraft): (a) water(1000) over real-air(1.2) → SWAP: B=water
  1000, A=air 1.2, both flagged changed, `massBefore==massAfter` to float precision, **no 1.2 residue**;
  (b) water(1000) over same-water(300, cap 1000) → DEPOSIT dm=700: B=1000, A=300, conserved; (c) water
  over same-water already full (1000) → no transfer; (d) water over a different fluid / solid / void
  (sp 0) → no transfer; (e) GAS donor over air → no transfer; (f) deposit that fully drains A (A=50 over
  same-water 990, cap 10 → dm=10, A=40) and a full-drain case (A=50 over air → swap, A=air); (g)
  empty/eps A cell → skipped; (h) a 256-cell mixed plane → per-column independence + total conservation.
- `:core:test` green. Commit MAIN, push.

## Task 2 [MAIN] — `ThermalWorld` seam hook + `MinecraftThermalWorld` implementation

- `ThermalWorld`: add `default List<TouchedSection> settleCrossSectionSeams(List<BatchEntry> entries,
  List<Material> lut) { return List.of(); }` and a small `TouchedSection(Identifier dim, SubchunkKey key,
  char[] species)` record (the `species` is the full 4096 plane-or-section array the reconciler needs;
  simplest: a 4096 `char[]` for the touched section with the seam plane edited and the rest copied from
  the tracker's current species, so `reconcile` renders correctly).
- `MinecraftThermalWorld.settleCrossSectionSeams`: build an id→index map from `lut`; for each entry
  (it is the UPPER section A), construct `belowKey = new SubchunkKey(cx, sectionY-1, cz)`; require
  `store.hasSection(belowKey)`. Load A and B `SectionData`; extract A's `y=0` plane (`i = x + 0 + 256*z`)
  and B's `y=15` plane (`i = x + 16*15 + 256*z`) into the six arrays; species from
  `cellMaterials.prior(dim, key)` / `prior(dim, belowKey)` mapped through the id→index map. If
  `prior(belowKey) == null` (untracked receiver): `wakeNeighbourFlow(dim, belowKey)` and skip this pair.
  Call `CrossSectionFluidLogic`; if any cell changed, write the mutated planes back into both
  `SectionData` (`setMass`/`setTemperature` or the live arrays), build the post-seam full species arrays
  for A and B and `cellMaterials.record(...)` them, `wakeNeighbourFlow` the receiver (and donor), and add
  a `TouchedSection` for each changed section. Return the list. Assert (or log-warn) the conservation
  delta from the logic result.
- TDD (headless, real `SectionStore` + a fake/captured wake + a real `CellMaterialTracker`): two stacked
  sections, A's floor = water, B's top = air → after the call: B's top plane store = water, A's floor
  store = air, both tracker signatures updated, receiver woken, returned `TouchedSection` list has A+B;
  the untracked-receiver case wakes-and-skips; the unloaded-receiver case (`!hasSection`) is a no-op.
- `:core:test` green. Commit MAIN, push.

## Task 3 [MAIN] — `FluidReconciler` overload + `Scheduler` post-loop wiring

- `FluidReconciler`: add `void reconcile(Identifier dim, SubchunkKey key, char[] outMaterial,
  List<Material> outLut)`; refactor `MinecraftFluidReconciler` so the existing
  `reconcile(BatchEntry, outMat, lut)` delegates to it (it already only uses `entry.key()`/
  `entry.dimension()`). Add a no-op default or stub-impl as the other implementations require.
- `Scheduler.writeBackResults`: after the `for` loop, **only when `advection`**, call
  `List<TouchedSection> touched = world.settleCrossSectionSeams(pendingEntries, pendingMaterials);`
  then `for (TouchedSection t : touched) fluidReconciler.reconcile(t.dim(), t.key(), t.species(),
  pendingMaterials);`. (Donor A may be reconciled twice this cycle — once full in the loop, once drained
  here — which is correct and idempotent.)
- TDD: a `Scheduler` test (extend `SchedulerEngineIntegrationTest`/`CapturingWorld` or a focused fake)
  asserting `settleCrossSectionSeams` is invoked after write-back on an advection cycle and each touched
  section is reconciled with its species array; NOT invoked on a conduction-only cycle.
- `:core:test` green. Commit MAIN, push.

## Task 4 [MAIN, native-backed integration] — end-to-end cascade test + push

- Add a native-backed `AuditScenarioTest` case (skips if `liborge` absent): two stacked real sections in
  the `SectionStore`, water poured onto the UPPER section's floor with air in the LOWER section's top;
  drive K full cycles (snapshot → real native step → writeBack → `settleCrossSectionSeams` → reconcile).
  Assert: the water moves DOWN across the seam over successive cycles (lower-section top fills, upper
  floor empties), **total mass across both sections is conserved every cycle** (no creation, no residue,
  no ghost ice), and a second snapshot does NOT re-inflate the drained donor (reseed-safe). Confirm the
  cascade reaches the lower floor in ≈(#seams) cycles.
- `:core:test` (native-backed) + `:fabric-1.21:build :neoforge-1.21:build` green. Commit MAIN, push.

## Verification

- `:core:test` green incl. the pure logic suite + seam-hook test + native cascade regression; both
  loaders build. No ENGINE/`.so`/gitlink change.
- In-game (user): pour water onto a block spanning section seams → it cascades DOWN through the seams to
  the floor (not piling on each section's floor); combined mass conserved; no residue, no ghost ice.

## Out of scope (next slice)

Horizontal cross-seam flow/wetting (X/Z seams — the `(2b-i)` boundary air-wetting deferral); persisting
an unloaded-receiver buffer; dormant-donor pile draining when the receiver loads late.
