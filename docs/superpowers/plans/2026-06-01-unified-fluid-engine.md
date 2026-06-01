# Unified Fluid Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax. **Read the spec first:**
> `docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md` — its ⛔ STOP invariants are binding.

**Goal:** Rebuild ORGE's advection so every cell runs one section-agnostic, viscosity-gated, molar-mass-sorted
fluid calculation — no `gasFlag`, no `% SECTION_EDGE`, no seam patches, no `state` — and refactor the material
data to the canonical schema.

**Architecture:** Keep the whole-region `World` orchestration and conduction. Replace `advect_chunk`'s
internals with two uniform passes (molar-sort full-cell swap + relax-to-`min_mass`/cap-`max_mass` with a
cross-species displacement sub-case), driven off one pre-step snapshot, conservative by construction.
Immovability is `viscosity == +∞` (absent in data). Delete the dormant per-section kernel.

**Tech Stack:** C++17 (`ORGE-ENGINE`, header-only sim + JNI, build via `native/build_liborge.sh`), Java 21
multiloader (`ORGE`, Architectury `core`/fabric/neoforge, Gradle), JNI FFI, datapack JSON materials.

---

## ⛔ Non-negotiable execution discipline (every task)

- **Two separate git repos.** MAIN = `/home/claude/ORGE` (branch `rebuild`); ENGINE = `/home/claude/ORGE/ORGE-ENGINE`
  (branch `main`). **NEVER `git add` ENGINE source from MAIN** — from MAIN you only ever `git add ORGE-ENGINE`
  (the gitlink). Commit ENGINE on `main` and `git push origin main` **before** bumping the MAIN gitlink.
- **`JAVA_HOME=/home/claude/jdk21` on EVERY gradle/native command.**
- **One MAIN integration commit bundles** the rebuilt `.so` + the gitlink bump + any Java. After any `.so`
  rebuild: `md5sum` of the freshly built lib **must equal** the bundled
  `core/src/main/resources/natives/linux-x64/liborge.so` (use `git add -f` for the `.so` — it matches
  `.gitignore`).
- **Push `origin/rebuild` after every MAIN commit** (the user tests from `origin/rebuild`).
- **Commit trailer (every commit):** `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **Never weaken an acceptance assertion to make it pass.** The oracles below are exact (`== 1000.0`, etc.).
- **TESTING LESSON:** headless tests that hand-seed the engine and bypass the live pipeline pass while the game
  breaks. Phase 4 validates against the REAL `.so` through the live pipeline. In-game re-test by the user is
  the final gate.

---

## Engine algorithm reference (all Phase-3 tasks point here — DRY)

`advect_chunk(World&, Chunk& C, const MaterialLUT& mats, const WorldSnapshot* snap)` runs, for one column,
**three uniform operations** in this order. All read cross-cell neighbours from `snap` (the pre-step snapshot
of the whole World, taken once by the caller), walk the **full column height `0..CHUNK_H-1`** and across
loaded chunks, and contain **no** `% SECTION_EDGE`, seam, `gasFlag`, or `state` branch.

**Movability (the only gate):**
```cpp
// A cell can move iff its viscosity is finite. Absent viscosity is marshalled as +INF.
inline bool movable(const Material& m) { return std::isfinite(m.viscosity); }
// Per-step spread fraction: 0 viscosity = fastest (CFL cap); +INF = 0 (frozen).
inline float spread_fraction(float viscosity) {
    if (viscosity <= 0.0f) return ADV_CFL_CAP;          // fastest, stability-capped
    float f = ADV_SPREAD_K / viscosity;                  // higher viscosity -> slower
    return f < ADV_CFL_CAP ? f : ADV_CFL_CAP;            // K/inf -> 0 (frozen), no overflow
}
```

**Pass A — molar-mass sort (gravity), full-cell binary swap.** Iterate cells in a fixed deterministic order;
keep a `std::vector<uint8_t> swapped(CHUNK_N,0)` claim buffer (one swap per cell per step → no checkerboard).
For each cell `i` and the cell `below` it (full column; a column is contiguous, and the cell below the
bottom-of-column is "world floor" = none):
```
if !swapped[i] && !swapped[below]
   && movable(mat[i]) && movable(mat[below])
   && density(i) > density(below) * SWAP_HYST          // out of order, with 5% hysteresis
   then full-swap the entire contents of i and below (mass, mat, T); swapped[i]=swapped[below]=1
```
`density(cell)` = `molar_mass` of the cell's species (sort key is molar mass ONLY — never current mass).
Vacuum/void uses `molar_mass = 0`. This single pass is fall, buoyancy, stratification, and water-sinking-
through-air. A full swap is a permutation → exactly conservative. **v1: no viscosity-magnitude throttle on
the sink** — any movable out-of-order pair swaps at full rate.

**Pass B — relax toward `min_mass`, cap `max_mass` (same-species + into-vacuum).** Pressure-driven diffusion,
6-directional, across all boundaries, antisymmetric. Define `pressure(m, mat) = max(0, m - mat.min_mass)`.
For each face `(i, j)` where `j` is same species as `i` **or** `j` is vacuum/void (`mat==void_ix && mass<=ε`):
```
diff = pressure(i) - pressure(j)              // both from snapshot
if diff <= 0 continue                          // only the higher side pushes (antisymmetric)
dm   = spread_fraction(mat[i].viscosity) * 0.5 * diff
dm   = min(dm, mass[i] - mat[i].min_mass)      // donor never drops below its own min_mass
dm   = min(dm, mat[i].max_mass - mass[j])      // receiver never exceeds max_mass (compression cap)
if dm <= ε continue
dMass[i] -= dm ; dEnth[i] -= dm*T[i]
dMass[j] += dm ; dEnth[j] += dm*T[i]           // receiver gains donor-temp mass
if j was vacuum: mat[j] = mat[i]               // adopt species when filling void
```
Free expansion stops when donors reach `min_mass` (pressure 0); boxed-in mass compresses up to `max_mass`.
This is leveling + gas volume-fill, unified.

**Pass B′ — cross-species horizontal displacement (the only "displace" case).** A supported, above-`min_mass`
cell `i` whose horizontal neighbour `j` holds a **strictly lighter** different species (by `molar_mass`,
`mass>ε`, both movable) pushes surplus sideways while the lighter content is **buoyed up** one cell:
```
let jUp = cell directly above j (full column)
require jUp is vacuum OR same species as j with capacity   // somewhere for the light content to go
dm = spread_fraction(mat[i].viscosity) * 0.5 * pressure(i)
dm = min(dm, mass[i]-mat[i].min_mass, mat[i].max_mass)     // ... and capacity of j
move j's (lighter) content up into jUp (merge/adopt), then j becomes species(i) with +dm; i -= dm
```
All three writes derive `dm`/moved-mass from the snapshot and are antisymmetric → per-species conservation.
This is the section-agnostic generalization of the old `(2a'')`/wetting, with **no** seam or `% SECTION_EDGE`.

After the three operations, apply accumulated `dMass`/`dEnth` to each cell and recompute `T` (enthalpy mix).
A **settle deadband** (skip moves with `dm ≤ ADV_EPS_MASS`) plus `SWAP_HYST` prevents micro-oscillation;
per-cell max-Δ feeds the existing dormancy/settle path.

> If any Phase-3 code contains `gasFlag`, `airFlag`, `fluidFlag`, `% SECTION_EDGE`, a `seam`/`halo` symbol, or
> reads a `state`/phase enum, the task is WRONG — see the spec's STOP block.

---

## Phase 0 — The guard (banner comments) — ENGINE

### Task 0.1: Loud banner comments into the engine source

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp:1` (top-of-file banner)
- Modify: `ORGE-ENGINE/orge_jni.cpp` (above `orgeStepWorld`)
- Create: `ORGE-ENGINE/ADVECTION_MODEL.md` (one-screen pointer to the spec)

- [ ] **Step 1: Add the banner to `sim_engine.hpp` line 1**, verbatim:

```cpp
// ============================================================================================
//  ORGE UNIFIED FLUID ENGINE — READ docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md
//  INVARIANTS (violating any = rebuilding the OLD engine; STOP):
//   * ONE substance. Every cell runs the SAME flow calc. No gas/liquid/solid branch, no gasFlag,
//     no fluidFlag, no `state`. Immovability is data: viscosity == +INF (absent) => frozen.
//   * Sections/chunks are STORAGE ONLY. Physics NEVER sees a 16-boundary: no `% SECTION_EDGE`,
//     no seam pass, no halo. Passes walk the full column 0..CHUNK_H-1 and across loaded chunks.
//   * Gravity == molar-mass sort (full-cell swap). There is NO separate `fall` pass.
//   * One species per cell. Void = lightest fluid (molar 0). Conserve via snapshot-antisymmetric
//     transfers + the region ledger HOLD.
// ============================================================================================
```

- [ ] **Step 2:** Add a 3-line pointer comment above `Java_..._orgeStepWorld` in `orge_jni.cpp` referencing
  the same spec and the "no section/gasFlag/seam" rule.
- [ ] **Step 3:** Write `ADVECTION_MODEL.md` (≤ 30 lines): the spectrum diagram, the three operations, and
  "the section is a storage detail — never branch on it."
- [ ] **Step 4: Commit (ENGINE).**
```bash
git -C /home/claude/ORGE/ORGE-ENGINE add sim_engine.hpp orge_jni.cpp ADVECTION_MODEL.md
git -C /home/claude/ORGE/ORGE-ENGINE commit -m "docs(engine): unified-fluid banner guard + ADVECTION_MODEL pointer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git -C /home/claude/ORGE/ORGE-ENGINE push origin main
```
*(Gitlink bump deferred to the Phase-4 integration commit; do not touch MAIN here.)*

---

## Phase 1 — Material schema refactor (MAIN / Java, headless)

> These tasks compile/test with `StubEngine` (no new `.so` needed). Run: `JAVA_HOME=/home/claude/jdk21
> ./gradlew :core:test`.

### Task 1.1: Refactor the `Material` record to the canonical schema

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/Material.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialSchemaTest.java`

- [ ] **Step 1: Write the failing test.**
```java
// MaterialSchemaTest.java
@Test void defaults_apply_when_optional_absent() {
    Material m = Material.builder(id("orge:stone"))
        .thermalConductivity(2.0f).heatCapacity(840f).molarMass(0.060f)
        .defaultMass(2500f).defaultTemperature(290f)
        .build();                              // no viscosity, no min/max_mass
    assertTrue(Float.isInfinite(m.viscosity()));          // absent => frozen (+INF)
    assertEquals(2500f, m.minMass(), 0f);                 // absent => default_mass
    assertEquals(2500f, m.maxMass(), 0f);                 // absent => default_mass
    assertEquals(id("minecraft:air"), m.representativeBlock()); // absent => minecraft:air
    assertFalse(m.pinned());
}
@Test void no_state_no_flags_on_record() {
    // Compile-time guard: these accessors must NOT exist.
    for (String gone : new String[]{"state","fluid","gas","air","minFlowMass"})
        assertFalse(hasMethod(Material.class, gone), gone + " must be removed");
}
```
- [ ] **Step 2: Run, verify it fails** (`Material` still has `state`/`minFlowMass`).
  Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*MaterialSchemaTest*'`
- [ ] **Step 3: Rewrite `Material`** to exactly the schema fields: `id, thermalConductivity, heatCapacity,
  molarMass, defaultMass, defaultTemperature` (required) + `viscosity, minMass, maxMass, minTemp, maxTemp,
  minTarget(Identifier), maxTarget(Identifier), representativeBlock(Identifier), pinned`. **Remove**
  `State` enum, `minFlowMass`, the `maxMass()` "0→default" hack, and all flag/`fluid()`/`gas()` helpers.
  `minTarget`/`maxTarget` are **material** ids. Provide a builder applying the absent-defaults
  (viscosity→`Float.POSITIVE_INFINITY`, min/max→`defaultMass`, repr→`minecraft:air`, pinned→`false`).
- [ ] **Step 4: Run tests, verify pass.** Fix every compile break from removed accessors in this step's blast
  radius (note them for Tasks 1.2/1.4/2.1; do NOT yet touch the engine LUT).
- [ ] **Step 5: Commit (MAIN, no push yet if mid-phase — but push is safe):**
```bash
git -C /home/claude/ORGE add core/src/main/java/net/rainbowcreation/orge/material/Material.java \
    core/src/test/java/net/rainbowcreation/orge/material/MaterialSchemaTest.java
git -C /home/claude/ORGE commit -m "refactor(material): canonical schema — drop state/flags/min_flow_mass, add min_mass + defaults

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git -C /home/claude/ORGE push origin HEAD:rebuild
```

### Task 1.2: Material loader — required-or-error + optional defaults

**Files:**
- Modify: the material JSON codec/loader (find via `grep -rl "min_flow_mass\|fromJson\|MaterialCodec" core/src/main/java`)
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialLoaderTest.java`

- [ ] **Step 1: Failing test** — (a) a JSON missing a required field throws a clear error; (b) optional absent
  fields resolve to the defaults from 1.1; (c) `min_temp` present without `min_target` throws; (d) targets
  parse as material ids; (e) `viscosity: 0.0` stays `0` (fastest), NOT infinity.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** the codec: read the 5 required (throw `IllegalArgumentException` naming the field
  + material if absent), read optionals with defaults, validate the temp⇒target pairing.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit + push** (MAIN, as in 1.1).

### Task 1.3: Rewrite all material JSON + create missing target materials

**Files:**
- Modify: every `core/src/main/resources/data/orge/orge/materials/*.json`
- Create: `materials/ice.json`, `materials/water.json`(retarget), `materials/air.json`, plus any other
  material id referenced by a `*_target` that has no file (audit with the test below).
- Test: `core/src/test/java/net/rainbowcreation/orge/material/AllMaterialsLoadTest.java`

- [ ] **Step 1: Failing test** — load every JSON via the real loader; assert (a) all parse; (b) every
  `min_target`/`max_target` resolves to an existing material file; (c) `water` has `min_target: orge:ice`
  (material id, not `minecraft:ice`); (d) `generic_solid` has **no** `viscosity` (frozen); (e) `air` has a
  finite viscosity and low `molar_mass`.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Rewrite the JSON.** Example `water.json` (the corrected form):
```json
{
  "thermal_conductivity": 0.6,
  "heat_capacity": 4186,
  "molar_mass": 0.018,
  "default_mass": 1000,
  "default_temperature": 290,
  "viscosity": 0.001,
  "min_mass": 125,
  "max_mass": 1000,
  "min_temp": 273.15, "min_target": "orge:ice",
  "max_temp": 373.15, "max_target": "orge:steam",
  "representative_block": "minecraft:water"
}
```
  Example `generic_solid.json` (frozen — note **no** `viscosity`, `min==max==default`):
```json
{ "thermal_conductivity": 2.0, "heat_capacity": 840, "molar_mass": 0.060,
  "default_mass": 2500, "default_temperature": 290, "representative_block": "minecraft:stone" }
```
  Create `ice.json` (`representative_block: minecraft:ice`, `max_temp: 273.15`, `max_target: orge:water`),
  `air.json` (`molar_mass` ~0.029, small finite `viscosity`, `min_mass` ~1, `max_mass` per design,
  `representative_block: minecraft:air`), and any other missing target materials. Convert `steam.json`
  (`min_target: orge:water`).
- [ ] **Step 4: Run, verify pass** (all load, all targets resolve).
- [ ] **Step 5: Commit + push** (MAIN).

### Task 1.4: Phase change → material-id targets + representative-block render

**Files:**
- Modify: the phase rule/changer (`grep -rl "PhaseRule\|PhaseChanger\|minTarget" core/src/main/java`)
- Test: `core/src/test/java/net/rainbowcreation/orge/.../PhaseChangeTargetTest.java`

- [ ] **Step 1: Failing test** — a water cell above `max_temp` selects the **material** `orge:steam`, and the
  block placed is steam's `representative_block`; a cell below `min_temp` → `orge:ice` → `minecraft:ice`.
  Identity is the material; the block is a separate lookup.
- [ ] **Step 2–4:** Implement: phase change resolves `*_target` as a material id, records the new material as
  the cell's species (signature), and rendering maps material→`representative_block`. Verify pass.
- [ ] **Step 5: Commit + push** (MAIN).

---

## Phase 2 — The LUT contract (the Java↔engine agreement)

### Task 2.1: Java marshalling — pack the six physics numbers; viscosity absent → +∞

**Files:**
- Modify: `core/.../engine/LutArrays.java`, `BatchMarshaller.java`, `RegionMarshaller.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/LutPackTest.java`

- [ ] **Step 1: Failing test** — `LutArrays.pack` emits exactly six per-material arrays
  (`thermal_conductivity, heat_capacity, molar_mass, min_mass, max_mass, viscosity`); a material with absent
  viscosity packs `Float.POSITIVE_INFINITY`; **no** `fluid`/`gas`/`air` arrays exist; slot 0 (void) has
  `molar_mass 0, min 0, max 0` and a finite viscosity.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement** — remove the §11 air flag-flip and all boolean arrays; pack the six floats; map
  void slot per spec. Update `NativeEngine.orgeStepWorld` JNI signature to the six LUT arrays (drop the
  `jFluid/jGas/jAir/jFullMass/jMinFlow` params; keep `jMolar` as `molar_mass`, add `jMinMass`).
- [ ] **Step 4: Run, verify pass** (against `StubEngine`).
- [ ] **Step 5: Commit + push** (MAIN). *(Java now expects the new JNI ABI; the real `.so` catches up in
  Phase 3/4 — until then only `StubEngine` paths run, which is fine for `:core:test`.)*

---

## Phase 3 — Engine advection rebuild (ENGINE / C++)

> Build/test: `cd /home/claude/ORGE/ORGE-ENGINE && JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`
> for the `.so`; compile/run the C++ unit tests in `tests/` per their existing harness (see
> `tests/world_step_test.cpp` for the pattern). Every Phase-3 task commits to ENGINE `main`.

### Task 3.1: Delete the dormant per-section kernel + parity test

**Files:**
- Delete: `ORGE-ENGINE/orge_kernel.hpp`, `ORGE-ENGINE/tests/advection_parity_test.cpp`, and the dormant
  `orgeStep` (per-section) JNI entry in `orge_jni.cpp` if present.
- Modify: any `#include "orge_kernel.hpp"`, build script test list, `NativeEngine`/`OrgeEngine` Java that
  declared the per-section `orgeStep` (remove the native + Java method).

- [ ] **Step 1:** `grep -rn "orge_kernel\|step_section_with_halo\|advection_parity\|orgeStep\b" ORGE-ENGINE core`
  to enumerate references.
- [ ] **Step 2:** Delete the files + every reference; ensure the engine still builds
  (`./native/build_liborge.sh`) and the remaining C++ tests compile.
- [ ] **Step 3: Commit (ENGINE) + push origin main.**

### Task 3.2: New `advect_chunk` skeleton + movability + conservation harness

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (replace `advect_chunk` body + `Material` struct fields)
- Test: `ORGE-ENGINE/tests/unified_basics_test.cpp`

- [ ] **Step 1: Failing C++ test** — (a) a uniform single-species field at `min_mass` is stationary after a
  step (max|Δmass| == 0); (b) a frozen cell (`viscosity == +INF`) never changes even with a heavier movable
  cell directly above it; (c) total mass is invariant. Use a tiny hand-built `World` (1 chunk) per
  `world_step_test.cpp` conventions.
- [ ] **Step 2: Run, verify fail/compile-break.**
- [ ] **Step 3: Implement** the new `Material` struct (`heatCapacity, thermalConductivity, molarMass, minMass,
  maxMass, viscosity`; remove `fluidFlag/gasFlag/airFlag/defaultMass/minFlowMass`), the `movable()` /
  `spread_fraction()` helpers, the `dMass/dEnth` accumulator scaffold, and an EMPTY pass body that applies
  nothing yet (so tests (a) trivially pass and (b) holds because nothing moves). Wire the JNI LUT read in
  `orge_jni.cpp` to the six new arrays (matches Task 2.1).
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit (ENGINE) + push.**

### Task 3.3: Pass A — molar-mass sort (full-cell swap, full column, movable-gated)

**Files:** Modify `sim_engine.hpp` (Pass A); Test `ORGE-ENGINE/tests/sort_swap_test.cpp`

- [ ] **Step 1: Failing C++ tests** (the heart — write all four):
  - **Tube across sections:** a sealed 1-wide column ≥ 33 cells tall (spans ≥ 2 section boundaries) filled
    with light gas, one heavy-fluid cell at top. After enough steps the heavy cell reaches the bottom; gas
    is above; each species' total is **exactly** conserved every step. *(Old engine fails — it stalls at
    `y % 16 == 0`.)*
  - **Stratify-not-annihilate:** sealed 2-cell box, heavy gas over light gas → swaps to light-over-heavy;
    one cell of each remains; both conserved.
  - **Frozen floor:** heavy fluid above a `viscosity==+INF` stone cell never swaps into it.
  - **Falling block:** a material with finite `viscosity` and `min_mass==max_mass` dropped in air sinks one
    cell/step with **no** sideways spread, rests on a frozen floor, mass exactly conserved.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement Pass A** exactly per the algorithm reference (fixed-order iteration, `swapped[]`
  claim buffer, full-column `below` index with no `% SECTION_EDGE`, `movable` gate, `molar_mass` sort key,
  `SWAP_HYST`). Full-cell swap = exchange `mass/mat/T`.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit (ENGINE) + push.**

### Task 3.4: Pass B — relax toward `min_mass`, cap `max_mass` (same-species + vacuum)

**Files:** Modify `sim_engine.hpp` (Pass B); Test `ORGE-ENGINE/tests/relax_spread_test.cpp`

- [ ] **Step 1: Failing C++ tests:**
  - **Expand to min:** 1000 kg of a fluid (`min_mass 125`) in one cell of an otherwise-vacuum sealed box of
    ≥ 8 connected cells spreads until each occupied cell ≈ 125 kg (within ε), occupying ~8 cells; total 1000.
  - **Compress to max:** the same fluid confined to fewer cells than `total/min` packs toward `max_mass` and
    never exceeds it; total conserved.
  - **Viscosity = spread rate:** a high-`viscosity` fluid reaches equilibrium in measurably more steps than a
    low-`viscosity` one (same setup); `viscosity 0` is fastest.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement Pass B** per the reference (pressure = `max(0, m-min_mass)`, antisymmetric flux,
  donor floor `min_mass`, receiver cap `max_mass`, 6-dir all-boundary, vacuum-adopt). Same-species + vacuum
  only.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit (ENGINE) + push.**

### Task 3.5: Pass B′ — cross-species horizontal displacement (buoy lighter up)

**Files:** Modify `sim_engine.hpp` (Pass B′); Test `ORGE-ENGINE/tests/displace_test.cpp`

- [ ] **Step 1: Failing C++ tests:**
  - **Spread across a floor through air:** a tall water cell on a frozen floor with air beside it levels
    sideways over steps; displaced air ends up above; water + air each conserved.
  - **Cross-chunk into air:** a 2-chunk `World`, water at chunk A's edge, pure air in chunk B → water crosses
    the X seam into B and levels; no doubling, no stall; totals conserved. *(Uses the pre-step snapshot for
    the cross-chunk neighbour.)*
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement Pass B′** per the reference (supported above-`min` cell pushes into a strictly-
  lighter different-species horizontal neighbour; the light content is buoyed into `jUp`; antisymmetric).
- [ ] **Step 4: Run, verify pass** (and re-run 3.3/3.4 suites — no regression).
- [ ] **Step 5: Commit (ENGINE) + push.**

### Task 3.6: Build the `.so`; full C++ suite green

- [ ] **Step 1:** `cd /home/claude/ORGE/ORGE-ENGINE && JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`
- [ ] **Step 2:** Build + run every C++ test in `tests/` (the unified suite + `world_step_test` +
  `cross_seam_air_test` etc. that still apply); all green. Remove/replace any obsolete §11-sectioned tests
  that assert the old seam behaviour (do NOT weaken; delete if they encode the dropped model, noting why).
- [ ] **Step 3: Commit (ENGINE) + push origin main.** Record the ENGINE `main` SHA + `md5sum liborge.so`
  for the Phase-4 integration commit.

---

## Phase 4 — Integration + live acceptance (MAIN)

### Task 4.1: Bundle the `.so` + gitlink; run the live-pipeline oracles

**Files:**
- Modify: `core/src/main/resources/natives/linux-x64/liborge.so` (copy the freshly built lib)
- Modify: `ORGE-ENGINE` gitlink (bump to the Task-3.6 ENGINE commit)
- Test: `core/src/test/java/net/rainbowcreation/orge/Section11LivePipelineReproTest.java` (adapt to the new
  schema/engine — keep the `== 1000.0` assertions), and a new `UnifiedFluidLivePipelineTest.java`.

- [ ] **Step 1:** Copy the built `.so` into MAIN resources; verify
  `md5sum ORGE-ENGINE/<built>.so core/src/main/resources/natives/linux-x64/liborge.so` **match**.
- [ ] **Step 2: New live test `UnifiedFluidLivePipelineTest`** driving the REAL `NativeEngine` through the
  live pipeline (`snapshotColumns`→`ColumnAssembler`→`RegionMarshaller`→`stepWorld`→ledger→write-back):
  - tube sort across a 16-block (section) boundary reaches the floor, conserved;
  - water poured across a chunk boundary into pure air crosses and levels, conserved;
  - a frozen solid shelf is never penetrated; stone still conducts heat;
  - region ledger HOLD on an injected non-conservation (nothing written).
- [ ] **Step 3:** Adapt `Section11LivePipelineReproTest` to the new schema; its four symptoms must read
  **exactly `1000.0`**.
- [ ] **Step 4: Run** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` — whole suite green on the real lib.
- [ ] **Step 5: Single bundled integration commit (MAIN) + push:**
```bash
git -C /home/claude/ORGE add -f core/src/main/resources/natives/linux-x64/liborge.so
git -C /home/claude/ORGE add ORGE-ENGINE core/src/test/java/.../UnifiedFluidLivePipelineTest.java \
    core/src/test/java/.../Section11LivePipelineReproTest.java
git -C /home/claude/ORGE commit -m "INT: unified fluid engine — rebuilt .so + gitlink + live oracles (==1000.0)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git -C /home/claude/ORGE push origin HEAD:rebuild
```

### Task 4.2: Full multiloader build + final review + memory

- [ ] **Step 1:** `JAVA_HOME=/home/claude/jdk21 ./gradlew build` — fabric + neoforge jars green.
- [ ] **Step 2:** Dispatch the final code reviewer over the whole change (ENGINE diff + MAIN diff): confirm
  zero `gasFlag`/`% SECTION_EDGE`/`seam`/`state` survivors in advection, conservation discipline intact,
  no weakened assertions.
- [ ] **Step 3:** Update `/home/claude/.claude/projects/-home-claude-ORGE/memory/` with a new
  `orge-unified-fluid.md` (SHAs, `.so` md5, what changed) + the `MEMORY.md` index line; supersede the prior
  whole-region/molar-gas memories' "current" status.
- [ ] **Step 4:** Report to the user that in-game re-test (the 5 scenarios + a falling-sand check) is the
  final gate.

---

## Self-review notes (coverage check)

- Spec invariants 1–7 → enforced by Task 0.1 banner + Phase-3 algorithm + oracles 1–9.
- Schema (required/optional/defaults, material-id targets, `representative_block`≠identity, drop `state`/flags,
  `min_mass`) → Phase 1 (1.1–1.4) + Task 2.1.
- Engine rebuild (two/three uniform passes, delete kernel, section-agnostic) → Phase 3 (3.1–3.6).
- Conservation backstop (ledger HOLD) → unchanged orchestration, re-validated in Task 4.1 Step 2.
- Acceptance oracles 1–9 → C++ tests (3.3–3.5) + live tests (4.1). The `== 1000.0` repro is the live gate.
- Repo discipline (two repos, JAVA_HOME, md5, push rebuild, trailer) → restated per task.
