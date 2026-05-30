# §10 Phase-2a — Mass-conservative liquid flow (water + lava) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make mass move conservatively between cells in the native engine so water and lava fall + spread and the heat they carry advects, then reconcile that mass to vanilla `minecraft:water`/`minecraft:lava` render levels with ORGE as the sole fluid authority.

**Architecture:** Advection is implemented in C++ first in the full-world reference sim (`sim_engine.hpp`, SDL-checkable), then ported bit-identical into the stateless per-section kernel (`orge_kernel.hpp`) with mass-carrying halos + antisymmetric face-flux, then exposed via the JNI ABI (`orge_jni.cpp`). The native artifact is rebuilt + recommitted; the Java side grows the `orgeStep` ABI, returns mass alongside temperature, persists `massOut` to `SectionData`, adds a §9 mass-conservation invariant, plumbs a `fluid` flag through the material LUT, and adds a pure `FluidReconcileLogic` + `FluidReconciler`/`VanillaFluidSuppressor` MC seams.

**Tech Stack:** C++20 header-only engine (g++, dependency-free test harness `tests/test_harness.hpp`); Java 21, Architectury multiloader (MC 1.21.11, Mojang mappings; `Identifier` = `ResourceLocation`); JNI; Mojang DFU codecs; JUnit 5. Build env: `JAVA_HOME=/home/claude/jdk21`.

**Spec:** `docs/superpowers/specs/2026-05-30-fluid-flow-liquid-phase2a-design.md`

---

## Two repositories — read this first

This plan spans **two separate git repos**. Each task is tagged with the repo it lands in; commits are independent.

| Repo | Path | Contents | `git add` from |
|---|---|---|---|
| **ENGINE** | `/home/claude/ORGE/ORGE-ENGINE` (own `.git`) | C++ kernel, reference sim, JNI, C++ tests, native build script, the prebuilt `liborge.so` that gets copied into MAIN | run git **inside** `ORGE-ENGINE/` |
| **MAIN** | `/home/claude/ORGE` (branch `rebuild`) | Java multiloader mod; the bundled native lives at `core/src/main/resources/natives/linux-x64/liborge.so` | run git from `/home/claude/ORGE` |

**Critical:** ENGINE tasks `cd /home/claude/ORGE/ORGE-ENGINE` before `git`. MAIN tasks run git from `/home/claude/ORGE`. Never `git add` across the boundary. The nested repo is untracked by MAIN (it shows as `? ORGE-ENGINE`), so MAIN never commits engine sources.

**The "pinned artifact" reality (resolved ambiguity — flag to user):** the spec speaks of a "new pinned `liborge-*` artifact version" fetched by a Gradle native-fetch task. There is **no Maven/Gradle native-fetch task in this repo** — the native is a *committed binary* at `core/src/main/resources/natives/linux-x64/liborge.so`, placed by commit `040c31a build(engine): bundle prebuilt linux-x64 liborge.so into :core`, and loaded by `NativeLoader` from the classpath `/natives/<os>-<arch>/`. Therefore "bump the pinned version" is implemented as **rebuild the `.so` from the new kernel and recommit it into MAIN's resources** (Task 14). There is no version string to change. Only linux-x64 is bundled today; windows/macos are out of scope for this slice (the audit/CI runs linux-x64).

---

## Conventions for every task

- **ENGINE C++ build+test:** `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh` (builds + runs correctness, stress, kernel, parity). To build+run a single new test file: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/<file>.cpp -o build/<file> -pthread && ./build/<file>`.
- **ENGINE native build:** `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`.
- **MAIN one test class:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "<FQCN>" --rerun-tasks`
- **MAIN whole core suite:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
- **MAIN all 3 loaders compile:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
- The C++ harness has **no test framework**: each test is a `void test_x()` using `TH_CHECK`, `TH_CHECK_MSG`, `TH_CHECK_CLOSE(got, want, rel)`, registered in `main()` via `run("name", test_x)`; a binary exits non-zero if any check failed. "Run to verify it fails" means: compile + run; the new test prints `FAIL` and the binary exits non-zero.
- Commit per task with the message shown. **Do not push** in either repo unless the final task says so. No PR/merge to main.

---

## Physics constants (single source — used identically in `sim_engine.hpp` and `orge_kernel.hpp`)

These live in `orge_kernel.hpp` so both surfaces share one definition (the existing `keff`/`finalize_temp` discipline):

```cpp
// Advection tunables (Phase-2a). dx = 1 m, dt = 1 s in the kernel's unit system.
constexpr float ADV_EPS_MASS   = 1e-4f;   // kg; below this a cell is treated as empty/air
constexpr float ADV_SPREAD_K   = 1000.0f; // numerator of transfer_fraction = k/viscosity
constexpr float ADV_CFL_CAP    = 0.25f;   // max fraction of a cell's excess moved per neighbour per step
```

`transfer_fraction = clamp(ADV_SPREAD_K / viscosity, 0, ADV_CFL_CAP)` — water (viscosity ~1e-3 Pa·s) saturates to the cap (fast); lava (viscosity ~100+ Pa·s) gets a small fraction (slow). A fluid material with `viscosity <= 0` is treated as `ADV_CFL_CAP` (degenerate-safe).

---

## File structure

### ENGINE (C++) — `/home/claude/ORGE/ORGE-ENGINE/`
- Modify `orge_kernel.hpp` — extend `MatLUT` (+`visc`, +`fullMass`, +`fluid`); add advection constants + `advect_section_with_halo` helpers; grow `step_section_with_halo` signature (mass halo in + `massOut` out) and apply conduction-then-advection.
- Modify `sim_engine.hpp` — add `Material.viscosity`/`fluidFlag`; reference advection over the full-world `mass_kg` arrays (fall + viscosity spread + enthalpy), wired into `step_frame`.
- Modify `orge_jni.cpp` — ABI grows `jHaloMass` (in) + `jMassOut` (out); pin/release in order.
- Create `tests/advection_test.cpp` — conservation, settling, spread, viscosity, enthalpy, no-flow boundary (sim_engine reference).
- Create `tests/advection_parity_test.cpp` — cross-section mass conservation + kernel-vs-sim_engine bit-identical on mass + T.
- Modify `tests/run_tests.sh` — build + run the two new test files.

### MAIN (Java) — `/home/claude/ORGE/`
- Create `core/src/main/java/net/rainbowcreation/orge/engine/StepResult.java` — `(float[] temperature, float[] mass)` per section.
- Modify `engine/OrgeEngine.java` — `step(...)` returns `List<StepResult>`.
- Modify `engine/StubEngine.java` — return mass unchanged (identity advection).
- Modify `engine/NativeEngine.java` — new `orgeStep` native decl (haloMass in, massOut out) + assemble/slice mass.
- Modify `engine/BatchMarshaller.java` — flatten `haloMass`, allocate/slice `massOut`.
- Modify `engine/NeighborHalo.java` — six mass faces.
- Modify `scheduler/HaloAssembler.java` — carry neighbour mass into the halo.
- Modify `scheduler/MaterialLut.VOID` + `engine/BatchMarshaller` LUT build — pass viscosity/fullMass/fluid to the native LUT.
- Modify `scheduler/ThermalWorld.java` + `MinecraftThermalWorld.java` — `writeBack(entry, StepResult)` persists `massOut`.
- Modify `scheduler/Scheduler.java` — call new writeBack with `StepResult`; call `FluidReconciler` after `PhaseChanger`.
- Modify `scheduler/StepValidator.java` — `cleanMass(...)` + Σmass / per-cell bounds invariant.
- Create `material/FluidMaterials.java` — `fluid` flag accessor + LUT-array builder.
- Modify `material/Material.java` + `MaterialCodec.java` — `fluid` boolean field + `fluid` JSON key.
- Modify `core/src/main/resources/data/orge/orge/materials/water.json` + `lava.json` — `"fluid": true`.
- Create `phase/FluidReconcileLogic.java` (pure) + `phase/FluidReconciler.java` (interface, NOOP) + `phase/MinecraftFluidReconciler.java` (MC adapter).
- Create `fluid/VanillaFluidSuppressor.java` (`ExpectPlatform`) + `fabric/.../VanillaFluidSuppressorImpl.java` + `neoforge/.../VanillaFluidSuppressorImpl.java`.
- Modify `core/src/main/resources/natives/linux-x64/liborge.so` — rebuilt binary (Task 14).
- Modify `core/src/test/java/net/rainbowcreation/orge/AuditScenarioTest.java` — water falls + spreads + reconciles; water-next-to-lava still steams.

---

# Part A — ENGINE (C++): advection in the reference sim

## Task 1 [ENGINE]: Advection constants + fluid LUT fields in `orge_kernel.hpp`

**Files:**
- Modify: `ORGE-ENGINE/orge_kernel.hpp`
- Test: `ORGE-ENGINE/tests/advection_test.cpp` (created here; grows over Tasks 1–6)

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/advection_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread
//
// Reference-sim advection invariants (sim_engine.hpp): conservation, settling,
// spread, viscosity contrast, enthalpy transport, no-flow boundary.
#include "test_harness.hpp"
#include "../orge_kernel.hpp"

using namespace th;

static void test_lut_has_fluid_fields() {
    // MatLUT must expose viscosity, full-mass and a fluid flag so the kernel can advect.
    std::vector<float> cond = {0.0f, 0.6f};
    std::vector<float> hc   = {0.0f, 4186.0f};
    std::vector<float> visc = {0.0f, 0.001f};
    std::vector<float> full = {0.0f, 1000.0f};
    std::vector<uint8_t> fluid = {0, 1};
    orge::MatLUT lut{cond.data(), hc.data(), visc.data(), full.data(), fluid.data(), (int)cond.size()};
    TH_CHECK(lut.visc[1] == 0.001f);
    TH_CHECK(lut.fullMass[1] == 1000.0f);
    TH_CHECK(lut.fluid[1] == 1);
    TH_CHECK(lut.fluid[0] == 0);
}

int main() {
    run("MatLUT carries fluid fields", test_lut_has_fluid_fields);
    return report();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread`
Expected: FAIL — compile error (`MatLUT` has no `visc`/`fullMass`/`fluid` members; 6-arg aggregate init invalid).

- [ ] **Step 3: Implement**

In `ORGE-ENGINE/orge_kernel.hpp`, after the `FACES` constant block add the advection constants:

```cpp
// Advection tunables (Phase-2a). dx = 1 m, dt = 1 s in the kernel's unit system.
constexpr float ADV_EPS_MASS = 1e-4f;   // kg; below this a cell is empty/air
constexpr float ADV_SPREAD_K = 1000.0f; // numerator of transfer_fraction = k/viscosity
constexpr float ADV_CFL_CAP  = 0.25f;   // max fraction of a cell's excess moved per neighbour/step
```

Replace the `MatLUT` struct with:

```cpp
struct MatLUT {
    const float*   cond;     // W/(m*K)
    const float*   heatCap;  // J/(kg*K)
    const float*   visc;     // Pa*s (advection spread rate)
    const float*   fullMass; // kg for a full 1 m^3 cell (Material.defaultMass)
    const uint8_t* fluid;    // 1 = participates in advection, 0 = inert
    int count;
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: PASS (`passed: 1   failed: 0`).

Also rebuild the existing kernel/parity tests to confirm the `MatLUT` shape change is caught (they will FAIL to compile — fixed in Task 7):
Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/kernel_test.cpp -o build/kernel_test -pthread`
Expected: FAIL to compile (old `MatLUT{cond,hc,count}` 3-arg init). This is expected and resolved in Task 7; do not fix it here.

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp tests/advection_test.cpp
git commit -m "feat(kernel): advection constants + viscosity/fullMass/fluid in MatLUT

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 [ENGINE]: Reference sim — material viscosity/fluid + mass conservation under fall

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp`
- Test: `ORGE-ENGINE/tests/advection_test.cpp`

- [ ] **Step 1: Write the failing test**

Append to `ORGE-ENGINE/tests/advection_test.cpp` (above `main`), and register in `main`:

```cpp
// Helper: total fluid mass over all chunks (non-void cells).
static double total_mass(const World& w) {
    double m = 0.0;
    for (const auto& kv : w.chunks) {
        const Chunk& C = *kv.second;
        for (int i = 0; i < CHUNK_N; ++i) m += (double)C.mass_kg[i];
    }
    return m;
}

static void test_fall_conserves_mass() {
    World w; seed_void(w);
    // fluid material (water-like): heatCap, k, defaultMass, molar, viscosity, fluid=true
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix = 0;
    // a single full water cell floating with empty (still water-material, 0 kg) below it
    for (int y = 0; y < 4; ++y) {
        int i = idx(0, y, 0);
        C->matIx[i] = water; C->T_curr[i] = 300.0f; C->T_next[i] = 300.0f;
        C->mass_kg[i] = (y == 3) ? 1000.0f : 0.0f; // mass only at the top
    }
    recomputeSectionLoaded(*C);
    double m0 = total_mass(w);
    step_n(w, 1.0f, 10);
    TH_CHECK_CLOSE(total_mass(w), m0, 1e-5);          // mass conserved
    TH_CHECK_MSG(C->mass_kg[idx(0,0,0)] > 900.0f, "mass fell to the bottom cell");
    TH_CHECK_MSG(C->mass_kg[idx(0,3,0)] < 1.0f,   "top cell drained");
}
```

Register in `main` (add the line before `return report();`):

```cpp
    run("fall conserves mass and settles to bottom", test_fall_conserves_mass);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread`
Expected: FAIL — compile error: `Material` aggregate has no `viscosity`/`fluidFlag` members (currently 4 fields).

- [ ] **Step 3: Implement**

In `ORGE-ENGINE/sim_engine.hpp`, extend the `Material` struct (add two trailing fields, default-initialized so existing 4-arg aggregate inits still compile):

```cpp
struct Material {
    float heatCapacity;        // J/(kg*K)
    float thermalConductivity; // W/(m*K)
    float defaultMass;         // kg per cell (cell is 1 m^3)
    float molarMass;           // kg/mol
    float viscosity = 0.0f;    // Pa*s (advection spread rate); 0 => non-fluid default
    bool  fluidFlag = false;   // participates in advection
};
```

Add a reference-sim advection pass after the existing `compute_frame_to_backbuffers`/conduction. Add this function before `step_frame`:

```cpp
// ---- Phase-2a advection (reference). Operates on T_curr/mass_kg in place AFTER
// conduction has been swapped in. Fall first (gravity), then viscosity-limited
// horizontal spread. Enthalpy rides with moving mass. Single-chunk-aware: uses
// sample_neighbor_T's chunk map only for read; writes only this chunk's cells.
inline float adv_transfer_fraction(float viscosity) {
    if (viscosity <= 0.0f) return orge::ADV_CFL_CAP;
    float f = orge::ADV_SPREAD_K / viscosity;
    return (f > orge::ADV_CFL_CAP) ? orge::ADV_CFL_CAP : f;
}

// Move dm kg from (src T=Ts) into dst cell; updates dst mass + enthalpy-mixed T.
inline void adv_deposit(Chunk& C, int dst, float dm, float Ts) {
    float md = C.mass_kg[dst];
    float mn = md + dm;
    if (mn > orge::ADV_EPS_MASS)
        C.T_curr[dst] = (md * C.T_curr[dst] + dm * Ts) / mn; // intensive mix
    C.mass_kg[dst] = mn;
}

inline void advect_chunk(World& world, Chunk& C, const MaterialLUT& mats) {
    // (1) FALL: top-down so a falling parcel can cascade in one pass.
    for (int z = 0; z < CHUNK_D; ++z)
    for (int y = 1; y < CHUNK_H; ++y)
    for (int x = 0; x < CHUNK_W; ++x) {
        int i = idx(x,y,z);
        uint16_t mix = C.matIx[i];
        if (mix == C.void_ix || !mats.byIx(mix).fluidFlag) continue;
        float m = C.mass_kg[i];
        if (m <= orge::ADV_EPS_MASS) continue;
        int below = idx(x,y-1,z);
        if (C.matIx[below] == C.void_ix || !mats.byIx(C.matIx[below]).fluidFlag) continue;
        float cap = mats.byIx(C.matIx[below]).defaultMass - C.mass_kg[below];
        if (cap <= 0.0f) continue;
        float dm = (m < cap) ? m : cap;
        float Ts = C.T_curr[i];
        adv_deposit(C, below, dm, Ts);
        C.mass_kg[i] -= dm;
        if (C.mass_kg[i] <= orge::ADV_EPS_MASS) { C.mass_kg[i] = 0.0f; C.T_curr[i] = 0.0f; }
    }
    // (2) SPREAD: flux toward lower-mass horizontal neighbours, viscosity-limited.
    // Flux-accumulation buffer => order-independent.
    std::vector<float> dMass(CHUNK_N, 0.0f);
    std::vector<float> dEnth(CHUNK_N, 0.0f); // mass*T carried, for re-mix
    static const int H[4][3] = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
    for (int z = 0; z < CHUNK_D; ++z)
    for (int y = 0; y < CHUNK_H; ++y)
    for (int x = 0; x < CHUNK_W; ++x) {
        int i = idx(x,y,z);
        uint16_t mix = C.matIx[i];
        if (mix == C.void_ix || !mats.byIx(mix).fluidFlag) continue;
        float m = C.mass_kg[i];
        if (m <= orge::ADV_EPS_MASS) continue;
        float frac = adv_transfer_fraction(mats.byIx(mix).viscosity);
        for (auto& d : H) {
            int nx=x+d[0], ny=y+d[1], nz=z+d[2];
            if (nx<0||nx>=CHUNK_W||nz<0||nz>=CHUNK_D) continue; // no-flow wall at chunk edge
            int j = idx(nx,ny,nz);
            if (C.matIx[j]==C.void_ix || !mats.byIx(C.matIx[j]).fluidFlag) continue;
            float diff = m - C.mass_kg[j];
            if (diff <= 0.0f) continue;
            float dm = frac * 0.5f * diff; // half the excess, antisymmetric pairwise
            float Ts = C.T_curr[i];
            dMass[i] -= dm; dMass[j] += dm;
            dEnth[i] -= dm*Ts; dEnth[j] += dm*Ts;
        }
    }
    for (int i = 0; i < CHUNK_N; ++i) {
        if (dMass[i] == 0.0f) continue;
        float md = C.mass_kg[i], mn = md + dMass[i];
        if (mn > orge::ADV_EPS_MASS)
            C.T_curr[i] = (md*C.T_curr[i] + dEnth[i]) / mn;
        C.mass_kg[i] = mn;
        if (C.mass_kg[i] <= orge::ADV_EPS_MASS) { C.mass_kg[i]=0.0f; C.T_curr[i]=0.0f; }
    }
}
```

Then call it inside `step_frame` AFTER the conduction swap (conduction-then-advection, the documented order):

```cpp
inline void step_frame(World& world, float dt_seconds) {
    compute_frame_to_backbuffers(world, dt_seconds);
    swap_all_backbuffers(world);
    for (auto& kv : world.chunks) advect_chunk(world, *kv.second, world.materials); // Phase-2a
}
```

Add `#include <vector>` if not already present (it is). Add the `MaterialLUT::add` for the 6-field `Material` — no change needed; aggregate init handles defaults.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add sim_engine.hpp tests/advection_test.cpp
git commit -m "feat(sim): reference advection (fall) over mass_kg, conserves mass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 [ENGINE]: Reference sim — settling (column → hydrostatic, no oscillation)

**Files:**
- Test: `ORGE-ENGINE/tests/advection_test.cpp`

- [ ] **Step 1: Write the failing test**

Append + register in `main`:

```cpp
static void test_column_settles_no_oscillation() {
    World w; seed_void(w);
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix = 0;
    // a 1x8x1 column of fluid cells, only the top 4 hold mass (4000 kg total).
    for (int y = 0; y < 8; ++y) {
        int i = idx(0,y,0);
        C->matIx[i]=water; C->T_curr[i]=300.0f; C->T_next[i]=300.0f;
        C->mass_kg[i] = (y >= 4) ? 1000.0f : 0.0f;
    }
    recomputeSectionLoaded(*C);
    double m0 = total_mass(w);
    step_n(w, 1.0f, 50);
    TH_CHECK_CLOSE(total_mass(w), m0, 1e-5);
    // hydrostatic: bottom 4 cells full, top 4 empty; no cell exceeds full mass.
    for (int y = 0; y < 4; ++y) TH_CHECK_MSG(C->mass_kg[idx(0,y,0)] > 999.0f, "lower cells full");
    for (int y = 4; y < 8; ++y) TH_CHECK_MSG(C->mass_kg[idx(0,y,0)] < 1.0f,   "upper cells empty");
    for (int y = 0; y < 8; ++y) TH_CHECK_MSG(C->mass_kg[idx(0,y,0)] <= 1000.0f + 1e-3f, "no overfill");
}
```

```cpp
    run("column settles to hydrostatic without oscillation", test_column_settles_no_oscillation);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: this should PASS already if Task 2's fall is correct. If it FAILS (e.g. overfill or non-settling), the failure pins the bug — fix the fall/cap logic in `sim_engine.hpp::advect_chunk` until green. (Capacity-clamped fall must never exceed `defaultMass`.)

- [ ] **Step 3: Implement**

If the test failed: ensure `cap` in the fall loop is `defaultMass - mass_kg[below]` and `dm = min(m, cap)`. No new code if already green.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/advection_test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/advection_test.cpp sim_engine.hpp
git commit -m "test(sim): column settles hydrostatic, no overfill/oscillation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 [ENGINE]: Reference sim — horizontal spread (stack → flat pool)

**Files:**
- Test: `ORGE-ENGINE/tests/advection_test.cpp`

- [ ] **Step 1: Write the failing test**

Append + register:

```cpp
static void test_spread_flattens_pool() {
    World w; seed_void(w);
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    // floor row y=0, full row of fluid cells in x with all mass in the center cell.
    for (int x = 0; x < 8; ++x) {
        int i = idx(x,0,0);
        C->matIx[i]=water; C->T_curr[i]=300.0f; C->T_next[i]=300.0f;
        C->mass_kg[i] = (x==4) ? 800.0f : 0.0f; // a single under-full pile (no fall possible at y=0)
    }
    recomputeSectionLoaded(*C);
    double m0 = total_mass(w);
    step_n(w, 1.0f, 200);
    TH_CHECK_CLOSE(total_mass(w), m0, 1e-5);
    // mass spread out from the center to neighbours.
    TH_CHECK_MSG(C->mass_kg[idx(4,0,0)] < 800.0f, "center drained as it spread");
    TH_CHECK_MSG(C->mass_kg[idx(3,0,0)] > 0.0f && C->mass_kg[idx(5,0,0)] > 0.0f, "neighbours filled");
}
```

```cpp
    run("horizontal spread flattens a pile", test_spread_flattens_pool);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: PASS if the spread pass from Task 2 is correct (it computes flux toward lower-mass horizontal neighbours). If FAIL, the spread loop is the bug — fix until green.

- [ ] **Step 3: Implement**

No new code expected; if failing, verify the spread loop uses the `H[4]` horizontal offsets and moves `frac*0.5*diff`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/advection_test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/advection_test.cpp sim_engine.hpp
git commit -m "test(sim): horizontal spread flattens a pile (mass conserved)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 [ENGINE]: Reference sim — viscosity contrast (lava wets fewer cells than water)

**Files:**
- Test: `ORGE-ENGINE/tests/advection_test.cpp`

- [ ] **Step 1: Write the failing test**

Append + register:

```cpp
static int wetted_cells_on_floor(const Chunk& C) {
    int n = 0;
    for (int x = 0; x < CHUNK_W; ++x) if (C.mass_kg[idx(x,0,0)] > orge::ADV_EPS_MASS) ++n;
    return n;
}

static void test_viscosity_lava_spreads_less() {
    auto run_pile = [](float visc) {
        World w; seed_void(w);
        uint16_t f = w.materials.add(Material{1000.0f, 1.0f, 1000.0f, 0.05f, visc, true});
        Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
        for (int x = 0; x < CHUNK_W; ++x) {
            int i = idx(x,0,0);
            C->matIx[i]=f; C->T_curr[i]=300.0f; C->T_next[i]=300.0f;
            C->mass_kg[i] = (x==8) ? 900.0f : 0.0f;
        }
        recomputeSectionLoaded(*C);
        step_n(w, 1.0f, 20);
        return wetted_cells_on_floor(*C);
    };
    int waterWet = run_pile(0.001f);  // low viscosity => fast spread
    int lavaWet  = run_pile(5000.0f); // high viscosity => slow spread
    TH_CHECK_MSG(lavaWet < waterWet, "after K steps, lava wets fewer cells than water");
}
```

```cpp
    run("viscosity: lava spreads less than water", test_viscosity_lava_spreads_less);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: PASS if `adv_transfer_fraction` correctly maps higher viscosity → smaller fraction. If FAIL, fix the fraction formula in `orge_kernel.hpp`/`sim_engine.hpp`.

- [ ] **Step 3: Implement**

No new code expected. If failing: confirm `transfer_fraction = clamp(ADV_SPREAD_K/viscosity, 0, ADV_CFL_CAP)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/advection_test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/advection_test.cpp
git commit -m "test(sim): viscosity contrast — lava wets fewer cells than water

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 [ENGINE]: Reference sim — enthalpy transport + no-flow boundary

**Files:**
- Test: `ORGE-ENGINE/tests/advection_test.cpp`

- [ ] **Step 1: Write the failing test**

Append + register both:

```cpp
static void test_enthalpy_rides_with_mass() {
    World w; seed_void(w);
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    // top cell: hot full water (500 K). bottom cell: cold half water (300 K).
    int top = idx(0,1,0), bot = idx(0,0,0);
    C->matIx[top]=water; C->mass_kg[top]=1000.0f; C->T_curr[top]=500.0f; C->T_next[top]=500.0f;
    C->matIx[bot]=water; C->mass_kg[bot]=0.0f;    C->T_curr[bot]=0.0f;   C->T_next[bot]=0.0f;
    recomputeSectionLoaded(*C);
    // energy proxy: sum mass*T (heatCap uniform). conduction also runs, so check the
    // single advection effect by stepping once and asserting the bottom warms to ~500.
    step_n(w, 1.0f, 1);
    TH_CHECK_MSG(C->mass_kg[bot] > 900.0f, "hot mass fell into bottom cell");
    TH_CHECK_CLOSE(C->T_curr[bot], 500.0, 2e-2); // mixed temp ~ source temp (bottom was empty)
}

static void test_no_flow_boundary_conserves() {
    World w; seed_void(w);
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    // fill the whole bottom plane with random-ish mass; chunk edges are no-flow walls.
    for (int z=0; z<CHUNK_D; ++z) for (int x=0; x<CHUNK_W; ++x) {
        int i = idx(x,0,z);
        C->matIx[i]=water; C->T_curr[i]=300.0f; C->T_next[i]=300.0f;
        C->mass_kg[i] = (float)((x*7 + z*13) % 500);
    }
    recomputeSectionLoaded(*C);
    double m0 = total_mass(w);
    step_n(w, 1.0f, 100);
    TH_CHECK_CLOSE(total_mass(w), m0, 1e-4); // nothing leaked past the no-flow walls
}
```

```cpp
    run("enthalpy rides with moving mass", test_enthalpy_rides_with_mass);
    run("no-flow boundary conserves mass", test_no_flow_boundary_conserves);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_test.cpp -o build/advection_test -pthread && ./build/advection_test`
Expected: PASS if enthalpy mixing + chunk-edge no-flow are correct. If FAIL, fix `adv_deposit`'s intensive mix / the edge `continue` in the spread loop.

- [ ] **Step 3: Implement**

No new code expected; fix to green if needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/advection_test`
Expected: PASS (7 tests). This completes the reference-sim correctness suite.

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/advection_test.cpp sim_engine.hpp
git commit -m "test(sim): enthalpy transport + no-flow boundary conservation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Part B — ENGINE (C++): port advection into the stateless kernel

## Task 7 [ENGINE]: Kernel — grow `step_section_with_halo` ABI (mass halo in + massOut), fix existing tests

**Files:**
- Modify: `ORGE-ENGINE/orge_kernel.hpp`
- Modify: `ORGE-ENGINE/tests/kernel_test.cpp` (update call sites + LUT to the new shape)
- Modify: `ORGE-ENGINE/tests/parity_test.cpp` (update `lut_arrays` + call sites)

- [ ] **Step 1: Write the failing test**

Append a new test to `ORGE-ENGINE/tests/kernel_test.cpp` and register it. First update the existing `Lut` helper to the new `MatLUT` shape and the `run(...)` call site (see Step 3), then add:

```cpp
static void test_kernel_advects_fall() {
    Section s; Lut l;
    // material 1 = fluid water; fill a vertical pair: top full, bottom empty.
    fill(s, 0, 0.0f, 0.0f); // all void first
    int top = sidx(0,1,0), bot = sidx(0,0,0);
    s.matIx[top]=1; s.mass[top]=1000.0f; s.Tin[top]=300.0f;
    s.matIx[bot]=1; s.mass[bot]=0.0f;    s.Tin[bot]=0.0f;
    float Tout[SEC_N], Mout[SEC_N];
    orge::MatLUT lv = l.view();
    // all-void mass halo (closed) — declared in the Lut/Section helpers (Step 3).
    orge::step_section_with_halo(s.matIx.data(), s.mass.data(), s.Tin.data(),
                                 s.haloT.data(), s.haloMat.data(), s.haloMass.data(),
                                 lv, 1.0f, Tout, Mout);
    TH_CHECK_MSG(Mout[bot] > 900.0f, "kernel fall: mass moved down");
    TH_CHECK_MSG(Mout[top] < 100.0f, "kernel fall: top drained");
    double m0 = 1000.0, m1 = (double)Mout[top] + (double)Mout[bot];
    TH_CHECK_CLOSE(m1, m0, 1e-5);
}
```

Register: `run("kernel advects mass downward", test_kernel_advects_fall);`

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/kernel_test.cpp -o build/kernel_test -pthread`
Expected: FAIL to compile — `step_section_with_halo` has no mass-halo/massOut parameters; `MatLUT` 6-field; `Section` has no `haloMass`.

- [ ] **Step 3: Implement**

(a) In `ORGE-ENGINE/orge_kernel.hpp`, replace `step_section_with_halo` with the conduction-then-advection version (signature grows `haloMass` in + `massOut` out). The conduction body is **unchanged** (keep it bit-identical); advection is appended, mirroring `sim_engine.hpp::advect_chunk` but using the halo for the six boundary faces and an antisymmetric face-flux so cross-section exchange nets to zero:

```cpp
inline void step_section_with_halo(
    const uint16_t* matIx, const float* mass, const float* Tin,
    const float* haloT, const uint16_t* haloMat, const float* haloMass,
    const MatLUT& lut, float dt, float* Tout, float* massOut)
{
    constexpr float inv_dx2 = 1.0f;
    // ---- (1) CONDUCTION (unchanged; bit-identical to sim_engine) ----
    for (int z = 0; z < SEC; ++z) for (int y = 0; y < SEC; ++y) for (int x = 0; x < SEC; ++x) {
        const int i = sidx(x, y, z);
        const uint16_t mix = matIx[i];
        const float k1  = lut.cond[mix];
        const float Cth = std::max(1e-8f, mass[i] * lut.heatCap[mix]);
        const float Tc  = Tin[i];
        float dT = 0.0f;
        auto flux = [&](int nx, int ny, int nz, int face, int fc) {
            float Tn; uint16_t mn;
            if (nx>=0&&nx<SEC&&ny>=0&&ny<SEC&&nz>=0&&nz<SEC) { const int j=sidx(nx,ny,nz); Tn=Tin[j]; mn=matIx[j]; }
            else { const int o=face*FACE+fc; Tn=haloT[o]; mn=haloMat[o]; }
            dT += keff(k1, lut.cond[mn]) * (Tn - Tc) * inv_dx2;
        };
        flux(x+1,y,z,POSX,y+SEC*z); flux(x-1,y,z,NEGX,y+SEC*z);
        flux(x,y+1,z,POSY,x+SEC*z); flux(x,y-1,z,NEGY,x+SEC*z);
        flux(x,y,z+1,POSZ,x+SEC*y); flux(x,y,z-1,NEGZ,x+SEC*y);
        Tout[i] = finalize_temp(Tc, Cth, dt, dT);
    }

    // ---- (2) ADVECTION over the post-conduction temperature field ----
    // Working copies: massOut starts from mass, Tout already holds post-conduction T.
    for (int i = 0; i < SEC_N; ++i) massOut[i] = mass[i];

    auto isFluid = [&](uint16_t m) { return m != 0 && lut.fluid[m] != 0; };
    auto deposit = [&](int dst, float dm, float Ts) {
        float md = massOut[dst], mn = md + dm;
        if (mn > ADV_EPS_MASS) Tout[dst] = (md*Tout[dst] + dm*Ts) / mn;
        massOut[dst] = mn;
    };

    // (2a) FALL: interior only uses own cells; the -y face uses the halo's mass/mat,
    // but a halo cell is read-only (we never write into it) so to keep mass conserved
    // across the seam we only fall INTO an interior below-cell. Falling OUT of the
    // bottom plane into the halo is handled by antisymmetric face-flux in (2b)-style
    // vertical exchange; here we keep fall interior to avoid writing the halo.
    for (int z=0; z<SEC; ++z) for (int y=1; y<SEC; ++y) for (int x=0; x<SEC; ++x) {
        int i = sidx(x,y,z);
        if (!isFluid(matIx[i])) continue;
        float m = massOut[i]; if (m <= ADV_EPS_MASS) continue;
        int below = sidx(x,y-1,z);
        if (!isFluid(matIx[below])) continue;
        float cap = lut.fullMass[matIx[below]] - massOut[below];
        if (cap <= 0.0f) continue;
        float dm = (m < cap) ? m : cap;
        deposit(below, dm, Tout[i]);
        massOut[i] -= dm;
        if (massOut[i] <= ADV_EPS_MASS) { massOut[i]=0.0f; Tout[i]=0.0f; }
    }

    // (2b) SPREAD: horizontal (x,z) + vertical (y) face flux, antisymmetric across faces.
    // Interior faces move mass between own cells; boundary faces compute the SAME flux
    // from this side that the neighbouring section computes from its side (Phi(A->B) =
    // -Phi(B->A)), so each section writes only its own cell and the seam nets to zero.
    float dM[SEC_N]; for (int i=0;i<SEC_N;++i) dM[i]=0.0f;
    float dE[SEC_N]; for (int i=0;i<SEC_N;++i) dE[i]=0.0f;
    static const int H[4][3] = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
    auto faceMass = [&](int face, int fc){ return haloMass[face*FACE+fc]; };
    for (int z=0; z<SEC; ++z) for (int y=0; y<SEC; ++y) for (int x=0; x<SEC; ++x) {
        int i = sidx(x,y,z);
        if (!isFluid(matIx[i])) continue;
        float m = massOut[i]; if (m <= ADV_EPS_MASS) continue;
        float frac = (lut.visc[matIx[i]] <= 0.0f) ? ADV_CFL_CAP
                   : std::min(ADV_CFL_CAP, ADV_SPREAD_K / lut.visc[matIx[i]]);
        for (auto& d : H) {
            int nx=x+d[0], ny=y+d[1], nz=z+d[2];
            float mNb; uint16_t matNb;
            bool interior = (nx>=0&&nx<SEC&&ny>=0&&ny<SEC&&nz>=0&&nz<SEC);
            if (interior) { int j=sidx(nx,ny,nz); mNb=massOut[j]; matNb=matIx[j]; }
            else {
                int face, fc;
                if (nx<0){face=NEGX;fc=y+SEC*z;} else if(nx>=SEC){face=POSX;fc=y+SEC*z;}
                else if(nz<0){face=NEGZ;fc=x+SEC*y;} else {face=POSZ;fc=x+SEC*y;}
                matNb = haloMat[face*FACE+fc]; mNb = faceMass(face, fc);
            }
            if (!isFluid(matNb)) continue;
            float diff = m - mNb;
            if (diff <= 0.0f) continue;              // only flux toward lower mass; antisymmetric
            float dm = frac * 0.5f * diff;
            dM[i] -= dm; dE[i] -= dm*Tout[i];
            if (interior) { int j=sidx(nx,ny,nz); dM[j]+=dm; dE[j]+=dm*Tout[i]; }
            // boundary: neighbour section adds the +dm on its own pass (antisymmetry).
        }
    }
    for (int i=0;i<SEC_N;++i) {
        if (dM[i]==0.0f) continue;
        float md=massOut[i], mn=md+dM[i];
        if (mn > ADV_EPS_MASS) Tout[i] = (md*Tout[i] + dE[i]) / mn;
        massOut[i] = mn;
        if (massOut[i] <= ADV_EPS_MASS) { massOut[i]=0.0f; Tout[i]=0.0f; }
    }
}
```

(b) In `ORGE-ENGINE/tests/kernel_test.cpp`: extend `struct Section` with `std::vector<float> haloMass = std::vector<float>(FACES*FACE, 0.0f);`. Update `struct Lut` to the 5-array form and a matching `view()`:

```cpp
struct Lut {
    std::vector<float>   cond     = {0.0f, 100.0f};
    std::vector<float>   heatCap  = {0.0f, 500.0f};
    std::vector<float>   visc     = {0.0f, 0.001f};
    std::vector<float>   fullMass = {0.0f, 1000.0f};
    std::vector<uint8_t> fluid    = {0, 0}; // conduction tests: non-fluid by default
    orge::MatLUT view() const {
        return orge::MatLUT{cond.data(), heatCap.data(), visc.data(),
                            fullMass.data(), fluid.data(), (int)cond.size()};
    }
};
```

Update the `run(...)` helper's call to pass `s.haloMass.data()` + a `massOut` buffer:

```cpp
std::vector<float> run(const Section& s, const Lut& l, float dt, int steps) {
    Lut lut = l;
    std::vector<float> cur = s.Tin, m = s.mass;
    std::vector<float> outT(SEC_N, 0.0f), outM(SEC_N, 0.0f);
    orge::MatLUT lv = lut.view();
    for (int n = 0; n < steps; ++n) {
        orge::step_section_with_halo(s.matIx.data(), m.data(), cur.data(),
                                     s.haloT.data(), s.haloMat.data(), s.haloMass.data(),
                                     lv, dt, outT.data(), outM.data());
        cur = outT; m = outM;
    }
    return cur;
}
```

(The conduction tests use `fluid={0,0}`, so advection is a no-op for them and they stay bit-identical.) In `test_kernel_advects_fall` set `l.fluid = {0, 1};` at the top.

(c) In `ORGE-ENGINE/tests/parity_test.cpp`: update `lut_arrays` to also fill viscosity/fullMass/fluid, build the 5-array `MatLUT`, add all-zero `haloMass` buffers + `massOut` buffers to both `step_section_with_halo` calls. Keep the solid material non-fluid (`fluid=0`) so the existing T-only parity check is unaffected.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/kernel_test.cpp -o build/kernel_test -pthread && ./build/kernel_test`
Expected: PASS (existing 6 conduction tests + the new fall test).
Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/parity_test.cpp -o build/parity_test -pthread && ./build/parity_test`
Expected: PASS (conduction parity still bit-identical).

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp tests/kernel_test.cpp tests/parity_test.cpp
git commit -m "feat(kernel): step_section_with_halo grows mass halo in + massOut out

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8 [ENGINE]: Kernel — cross-section mass conservation + bit-identical (mass + T) parity

**Files:**
- Create: `ORGE-ENGINE/tests/advection_parity_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/advection_parity_test.cpp`. It mirrors `parity_test.cpp` but (a) makes the material a **fluid**, seeds a mass gradient straddling the section seam, and (b) compares BOTH the mass field and the T field bit-for-bit between one engine frame and the two-section kernel run, AND asserts total mass across the two sections is conserved:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/advection_parity_test.cpp -o build/advection_parity_test -pthread
//
// Cross-section conservation + bit-identical (mass + T): one engine frame over a
// 2-section chunk must equal the stateless kernel stepping both sections with
// mass-carrying halos, and total mass across the seam is exactly conserved.
#include "test_harness.hpp"
#include "../orge_kernel.hpp"
#include <vector>
#include <cstring>

using namespace th;
using orge::SEC; using orge::SEC_N; using orge::FACE; using orge::FACES; using orge::sidx;

static int cidx(int x,int yl,int z,int sy){ return idx(x, sy*SEC+yl, z); }

static void lut_arrays(const MaterialLUT& m, std::vector<float>& cond, std::vector<float>& hc,
                       std::vector<float>& visc, std::vector<float>& full, std::vector<uint8_t>& fluid) {
    size_t n=m.size(); cond.resize(n); hc.resize(n); visc.resize(n); full.resize(n); fluid.resize(n);
    for (size_t i=0;i<n;++i){ const auto& M=m.byIx((uint16_t)i);
        cond[i]=M.thermalConductivity; hc[i]=M.heatCapacity; visc[i]=M.viscosity;
        full[i]=M.defaultMass; fluid[i]=M.fluidFlag?1:0; }
}

static void test_two_section_mass_parity() {
    World w; seed_void(w);
    // fluid material: heatCap, k, defaultMass, molar, viscosity, fluid=true
    uint16_t water = w.materials.add(Material{4186.0f, 0.6f, 1000.0f, 0.018f, 0.001f, true});
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    // sections 0 and 1 full of fluid cells; mass gradient peaks at the seam (section 1 yl=0).
    fill_section_with(*C, water, 300.0f, 0, w.materials);
    fill_section_with(*C, water, 300.0f, 1, w.materials);
    for (int z=0;z<SEC;++z) for (int x=0;x<SEC;++x) {
        C->mass_kg[cidx(x,15,z,0)] = 200.0f;  // top of section 0 (just below seam)
        C->mass_kg[cidx(x,0,z,1)]  = 900.0f;  // bottom of section 1 (just above seam)
    }
    recomputeSectionLoaded(*C);

    std::vector<float> cond,hc,visc,full; std::vector<uint8_t> fluid;
    lut_arrays(w.materials, cond,hc,visc,full,fluid);
    orge::MatLUT lv{cond.data(),hc.data(),visc.data(),full.data(),fluid.data(),(int)cond.size()};

    auto build = [&](int sy, std::vector<uint16_t>& mat, std::vector<float>& mass, std::vector<float>& Tin){
        mat.assign(SEC_N,0); mass.assign(SEC_N,0.0f); Tin.assign(SEC_N,0.0f);
        for (int z=0;z<SEC;++z) for (int yl=0;yl<SEC;++yl) for (int x=0;x<SEC;++x){
            int i=sidx(x,yl,z), ci=cidx(x,yl,z,sy);
            mat[i]=C->matIx[ci]; mass[i]=C->mass_kg[ci]; Tin[i]=C->T_curr[ci]; }
    };
    std::vector<uint16_t> mat0,mat1; std::vector<float> mass0,mass1,Tin0,Tin1;
    build(0,mat0,mass0,Tin0); build(1,mat1,mass1,Tin1);

    std::vector<float> hT0(FACES*FACE,0),hT1(FACES*FACE,0),hM0(FACES*FACE,0),hM1(FACES*FACE,0);
    std::vector<uint16_t> hMat0(FACES*FACE,0),hMat1(FACES*FACE,0);
    for (int z=0;z<SEC;++z) for (int x=0;x<SEC;++x){
        int fc=x+SEC*z;
        // section 0 +y face <- section 1 yl=0
        hT0[orge::POSY*FACE+fc]=Tin1[sidx(x,0,z)]; hM0[orge::POSY*FACE+fc]=mass1[sidx(x,0,z)]; hMat0[orge::POSY*FACE+fc]=mat1[sidx(x,0,z)];
        // section 1 -y face <- section 0 yl=15
        hT1[orge::NEGY*FACE+fc]=Tin0[sidx(x,15,z)]; hM1[orge::NEGY*FACE+fc]=mass0[sidx(x,15,z)]; hMat1[orge::NEGY*FACE+fc]=mat0[sidx(x,15,z)];
    }

    std::vector<float> oT0(SEC_N),oT1(SEC_N),oM0(SEC_N),oM1(SEC_N);
    orge::step_section_with_halo(mat0.data(),mass0.data(),Tin0.data(),hT0.data(),hMat0.data(),hM0.data(),lv,1.0f,oT0.data(),oM0.data());
    orge::step_section_with_halo(mat1.data(),mass1.data(),Tin1.data(),hT1.data(),hMat1.data(),hM1.data(),lv,1.0f,oT1.data(),oM1.data());

    // total mass conserved across the seam (kernel side).
    double mIn=0, mK=0;
    for (int i=0;i<SEC_N;++i){ mIn+=mass0[i]+mass1[i]; mK+=oM0[i]+oM1[i]; }
    TH_CHECK_CLOSE(mK, mIn, 1e-4);

    // engine frame, then bit-identical compare on mass AND temperature.
    step_frame(w, 1.0f);
    int mism=0;
    for (int z=0;z<SEC;++z) for (int yl=0;yl<SEC;++yl) for (int x=0;x<SEC;++x){
        float eT0=C->T_curr[cidx(x,yl,z,0)], kT0=oT0[sidx(x,yl,z)];
        float eM0=C->mass_kg[cidx(x,yl,z,0)], kM0=oM0[sidx(x,yl,z)];
        float eT1=C->T_curr[cidx(x,yl,z,1)], kT1=oT1[sidx(x,yl,z)];
        float eM1=C->mass_kg[cidx(x,yl,z,1)], kM1=oM1[sidx(x,yl,z)];
        if (std::memcmp(&eT0,&kT0,4)) ++mism; if (std::memcmp(&eM0,&kM0,4)) ++mism;
        if (std::memcmp(&eT1,&kT1,4)) ++mism; if (std::memcmp(&eM1,&kM1,4)) ++mism;
    }
    TH_CHECK_MSG(mism==0, "kernel mass+T must match the engine frame bit-for-bit");
}

int main() {
    run("two-section mass conservation + bit-identical parity", test_two_section_mass_parity);
    return report();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_parity_test.cpp -o build/advection_parity_test -pthread && ./build/advection_parity_test`
Expected: FAIL — the kernel's antisymmetric boundary flux and the engine's whole-chunk advection will not match bit-for-bit until the kernel's seam handling mirrors the engine. (Likely mismatch at the seam plane.)

- [ ] **Step 3: Implement**

Make the kernel's boundary-face advection bit-identical to the engine's whole-chunk pass. Two coordinated edits:

1. In `sim_engine.hpp::advect_chunk`, the cross-section vertical exchange must be expressed as the **same antisymmetric face flux** the kernel uses, not a chunk-global fall, so that one section's view of the seam equals the kernel's. Refactor `advect_chunk` so the y-direction at a section boundary uses the identical `frac*0.5*diff` toward-lower-mass rule the kernel applies on its `POSY/NEGY` halo faces (i.e. treat the seam as a spread face, plus interior fall within a section). Keep fall purely interior to a section.
2. In `orge_kernel.hpp`, add the vertical faces (`POSY`,`NEGY`) to the spread loop's neighbour set (currently `H[4]` is horizontal only) using the halo mass/mat, so the kernel exchanges across the y-seam by the same rule.

Concretely: change the kernel spread loop's offset set to the full 6-neighbour set and route `±y` boundary faces through `POSY`/`NEGY` halo lookups; and in the engine, drive the inter-section y exchange through the matching `frac*0.5*diff` rule. Iterate (red→green) until `mism==0` and the mass-conservation check holds. This is the **core algorithm to get right** (spec Decision 5).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/advection_parity_test`
Expected: PASS. Also re-run the full suite + the new files via run_tests (next step adds them) — for now confirm `kernel_test` and `parity_test` still PASS after the kernel spread-loop change:
Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./build/kernel_test && ./build/parity_test && ./build/advection_test`
Expected: all PASS.

- [ ] **Step 5: Wire into run_tests.sh + commit (ENGINE repo)**

Edit `ORGE-ENGINE/tests/run_tests.sh`: after the `parity_test` build/run blocks add `advection_test` and `advection_parity_test` (build with `-O2 -g`, run, capture `RC`), mirroring the existing blocks. Then:

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/advection_parity_test.cpp tests/run_tests.sh
git commit -m "feat(kernel): antisymmetric face-flux — cross-section mass conserved, bit-identical to sim

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Part C — ENGINE (C++): JNI ABI

## Task 9 [ENGINE]: JNI — `orgeStep` grows `jHaloMass` in + `jMassOut` out

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp`

This is a native-bridge edit with no headless C++ unit test (it needs a JVM). It is verified by (a) compiling the `.so` and (b) the MAIN-side `NativeEngineTest` against the rebuilt native (Task 14+). The change is mechanical and must mirror the kernel's new signature + the new LUT arrays + reverse-order release.

- [ ] **Step 1: State the verification (no separate failing test)**

The bridge must: accept `jfloatArray jHaloMass`, `jcharArray`→`uint8_t*` is **not** used (fluid LUT is `jbyteArray`); accept `jfloatArray jVisc, jFullMass` and `jbyteArray jFluid`; write `jfloatArray jMassOut`; pin all in acquisition order, release `jMassOut` + `jTout` with mode 0 (copy back), the rest with `JNI_ABORT`, in reverse order. Compilation of the `.so` is the gate.

- [ ] **Step 2: Verify the current `.so` builds (baseline)**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
Expected: `built .../liborge.so` (baseline, pre-change).

- [ ] **Step 3: Implement**

Replace `ORGE-ENGINE/orge_jni.cpp` with the new ABI (note the param order matches the Java native declaration in Task 12 exactly):

```cpp
#include <jni.h>
#include <chrono>
#include <cstdint>
#include "orge_kernel.hpp"

extern "C" JNIEXPORT jdouble JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStep(
        JNIEnv* env, jclass,
        jint n,
        jcharArray jMatIx, jfloatArray jMass, jfloatArray jTin,
        jfloatArray jHaloT, jcharArray jHaloMat, jfloatArray jHaloMass,
        jfloatArray jCond, jfloatArray jHeatCap, jfloatArray jVisc,
        jfloatArray jFullMass, jbyteArray jFluid,
        jdouble dt, jfloatArray jTout, jfloatArray jMassOut)
{
    const jint matCount = env->GetArrayLength(jCond);

    auto* matIx    = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jMatIx,    nullptr));
    auto* mass     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMass,     nullptr));
    auto* tin      = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTin,      nullptr));
    auto* haloT    = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHaloT,    nullptr));
    auto* haloMat  = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jHaloMat,  nullptr));
    auto* haloMass = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHaloMass, nullptr));
    auto* cond     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jCond,     nullptr));
    auto* heatCap  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHeatCap,  nullptr));
    auto* visc     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jVisc,     nullptr));
    auto* fullMass = static_cast<float*>   (env->GetPrimitiveArrayCritical(jFullMass, nullptr));
    auto* fluid    = static_cast<uint8_t*> (env->GetPrimitiveArrayCritical(jFluid,    nullptr));
    auto* tout     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTout,     nullptr));
    auto* massOut  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMassOut,  nullptr));

    double ms = 0.0;
    if (matIx && mass && tin && haloT && haloMat && haloMass && cond && heatCap
            && visc && fullMass && fluid && tout && massOut) {
        orge::MatLUT lut{cond, heatCap, visc, fullMass, fluid, static_cast<int>(matCount)};
        const auto t0 = std::chrono::steady_clock::now();
        for (int s = 0; s < n; ++s) {
            const size_t so = static_cast<size_t>(s) * orge::SEC_N;
            const size_t ho = static_cast<size_t>(s) * orge::FACES * orge::FACE;
            orge::step_section_with_halo(matIx + so, mass + so, tin + so,
                                         haloT + ho, haloMat + ho, haloMass + ho,
                                         lut, static_cast<float>(dt),
                                         tout + so, massOut + so);
        }
        const auto t1 = std::chrono::steady_clock::now();
        ms = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1e6;
    }

    // Release in reverse acquisition order. Outputs (massOut, tout) copy back (mode 0).
    if (massOut)  env->ReleasePrimitiveArrayCritical(jMassOut,  massOut,  0);
    if (tout)     env->ReleasePrimitiveArrayCritical(jTout,     tout,     0);
    if (fluid)    env->ReleasePrimitiveArrayCritical(jFluid,    fluid,    JNI_ABORT);
    if (fullMass) env->ReleasePrimitiveArrayCritical(jFullMass, fullMass, JNI_ABORT);
    if (visc)     env->ReleasePrimitiveArrayCritical(jVisc,     visc,     JNI_ABORT);
    if (heatCap)  env->ReleasePrimitiveArrayCritical(jHeatCap,  heatCap,  JNI_ABORT);
    if (cond)     env->ReleasePrimitiveArrayCritical(jCond,     cond,     JNI_ABORT);
    if (haloMass) env->ReleasePrimitiveArrayCritical(jHaloMass, haloMass, JNI_ABORT);
    if (haloMat)  env->ReleasePrimitiveArrayCritical(jHaloMat,  haloMat,  JNI_ABORT);
    if (haloT)    env->ReleasePrimitiveArrayCritical(jHaloT,    haloT,    JNI_ABORT);
    if (tin)      env->ReleasePrimitiveArrayCritical(jTin,      tin,      JNI_ABORT);
    if (mass)     env->ReleasePrimitiveArrayCritical(jMass,     mass,     JNI_ABORT);
    if (matIx)    env->ReleasePrimitiveArrayCritical(jMatIx,    matIx,    JNI_ABORT);
    return ms;
}
```

- [ ] **Step 4: Verify it compiles into a `.so`**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
Expected: `built .../liborge.so` with no compiler errors.

- [ ] **Step 5: Commit (ENGINE repo)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_jni.cpp
git commit -m "feat(jni): orgeStep ABI grows haloMass in + massOut out + fluid LUT arrays

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Part D — MAIN (Java): result type, material flag, marshalling

## Task 10 [MAIN]: `StepResult` + `OrgeEngine.step` returns mass; `StubEngine` identity advection

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/StepResult.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/StubEngineMassTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import java.util.List;
import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class StubEngineMassTest {
    @Test
    void stubReturnsTemperatureAndUnchangedMass() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        List<StepResult> out = new StubEngine().step(List.of(a), stdLut(), 1.0);
        assertEquals(1, out.size());
        assertEquals(SectionConstants(), out.get(0).temperature().length);
        // StubEngine does not advect: mass comes back identical to the task's mass.
        assertArrayEquals(a.mass(), out.get(0).mass(), 0f);
    }
    private static int SectionConstants() { return 4096; }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.StubEngineMassTest" --rerun-tasks`
Expected: FAIL — `StepResult` does not exist; `step` returns `List<float[]>`.

- [ ] **Step 3: Implement**

Create `StepResult.java`:

```java
package net.rainbowcreation.orge.engine;

/**
 * One section's output from a single engine step (DESIGN §10 Phase-2a): the new
 * per-cell temperatures AND per-cell mass. Mass now flows back from the engine
 * (advection), where before only temperature did.
 *
 * @param temperature K per cell (length 4096, x-fastest)
 * @param mass        kg per cell (length 4096, x-fastest) after advection
 */
public record StepResult(float[] temperature, float[] mass) {}
```

In `OrgeEngine.java` change the method to:

```java
    List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dtSeconds);
```

(and update the import to add `StepResult` if needed; it's the same package). Update the javadoc `@return` to "new temperature + mass per section".

In `StubEngine.java`, change `step` to return `List<StepResult>` echoing each task's temperature and mass unchanged (identity — the stub does no advection). For each task build `new StepResult(task.temperature().clone(), task.mass().clone())`.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.StubEngineMassTest" --rerun-tasks`
Expected: PASS. (Other call sites — `NativeEngine`, `Scheduler`, `NativeEngineTest` — will not compile yet; fixed in Tasks 11, 13, 14. The `:core:test` task will still report this one class green when run in isolation only if the module compiles; if compilation of the module fails, proceed to Task 11 and re-run after.)

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/StepResult.java core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java core/src/test/java/net/rainbowcreation/orge/engine/StubEngineMassTest.java
git commit -m "feat(engine): StepResult (temperature + mass); step returns mass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11 [MAIN]: `Material.fluid` field + codec `fluid` key + water/lava JSON

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/Material.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java`
- Modify: `core/src/main/resources/data/orge/orge/materials/water.json`, `lava.json`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialFluidTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialFluidTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "w");

    @Test
    void legacyConstructorsDefaultFluidFalse() {
        Material m = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
        assertFalse(m.fluid());
        Material n = new Material(ID, 1f, 2f, 0f, 100f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null, 300f, false);
        assertFalse(n.fluid());
    }

    @Test
    void codecParsesFluidTrue() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000,
                  "viscosity": 0.001, "fluid": true }
                """;
        Material m = MaterialCodec.fromJson(ID, JsonParser.parseString(json));
        assertTrue(m.fluid());
        assertEquals(0.001f, m.viscosity(), 1e-6f);
    }

    @Test
    void codecDefaultsFluidFalse() {
        String json = """
                { "thermal_conductivity": 0.6, "heat_capacity": 4186, "default_mass": 1000 }
                """;
        assertFalse(MaterialCodec.fromJson(ID, JsonParser.parseString(json)).fluid());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialFluidTest" --rerun-tasks`
Expected: FAIL — `fluid()` accessor / 14-arg constructor / `fluid` codec key not defined.

- [ ] **Step 3: Implement**

In `Material.java`: add `boolean fluid` as the final record component (after `pinned`), add an `@param fluid` javadoc line, and update BOTH existing convenience constructors to pass `false` for `fluid`:

```java
public record Material(
        Identifier id,
        float thermalConductivity,
        float heatCapacity,
        float viscosity,
        float defaultMass,
        float molarMass,
        float boilingPoint,
        float freezingPoint,
        Identifier boilingTarget,
        Identifier freezingTarget,
        Identifier representativeBlock,
        float defaultTemperature,
        boolean pinned,
        boolean fluid
) {
    /** Backward-compatible constructor: no pin temperature, not a source, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                Float.NaN, false, false);
    }

    /** Constructor with pin temperature + pinned flag, not a fluid. */
    public Material(Identifier id, float thermalConductivity, float heatCapacity,
                    float viscosity, float defaultMass, float molarMass,
                    float boilingPoint, float freezingPoint,
                    Identifier boilingTarget, Identifier freezingTarget,
                    Identifier representativeBlock, float defaultTemperature, boolean pinned) {
        this(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock,
                defaultTemperature, pinned, false);
    }

    public boolean hasDefaultTemperature() {
        return !Float.isNaN(defaultTemperature);
    }
}
```

In `MaterialCodec.java`: add `boolean fluid` to `BodyData` (after `pinned`), add the codec field after the `pinned` one:

```java
                    Codec.BOOL.optionalFieldOf("fluid", false)
                            .forGetter(BodyData::fluid)
```

and pass `bd.fluid()` as the final argument to the 14-arg `Material` constructor in `fromJson`. Update the class javadoc "Optional with defaults" list to include `fluid → false`.

In `water.json` add `"fluid": true` (it already has `viscosity`? — it does not; add `"viscosity": 0.001` too). In `lava.json` add `"fluid": true` and `"viscosity": 100`. Example `water.json`:

```json
{
  "thermal_conductivity": 0.6,
  "heat_capacity": 4186,
  "default_mass": 1000,
  "molar_mass": 0.018,
  "viscosity": 0.001,
  "fluid": true,
  "freezing_point": 273.15,
  "freezing_target": "minecraft:ice",
  "boiling_point": 373.15,
  "boiling_target": "orge:steam",
  "representative_block": "minecraft:water"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.material.MaterialFluidTest" --rerun-tasks`
Expected: PASS. Also run existing `MaterialCodecSourceTest`/`MaterialFieldsTest` → PASS (compat constructors preserved).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/material/Material.java core/src/main/java/net/rainbowcreation/orge/material/MaterialCodec.java core/src/main/resources/data/orge/orge/materials/water.json core/src/main/resources/data/orge/orge/materials/lava.json core/src/test/java/net/rainbowcreation/orge/material/MaterialFluidTest.java
git commit -m "feat(material): fluid flag (field + codec key) + water/lava fluid JSON

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12 [MAIN]: `NeighborHalo` + `HaloAssembler` carry neighbour mass

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NeighborHalo.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/HaloAssemblerMassTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HaloAssemblerMassTest {
    @Test
    void neighbourMassIsCarriedOntoTheFace() {
        // a -x neighbour whose cells all carry 750 kg.
        float[] t = new float[256]; char[] m = new char[256]; float[] mass = new float[256];
        java.util.Arrays.fill(t, 280f);
        java.util.Arrays.fill(m, (char) 1);
        java.util.Arrays.fill(mass, 750f);
        HaloAssembler.Neighbor negX = new HaloAssembler.Neighbor(t, m, mass);
        HaloAssembler.Neighbor none = HaloAssembler.Neighbor.absent();
        NeighborHalo h = HaloAssembler.assemble(negX, none, none, none, none, none);
        assertEquals(750f, h.negXMass()[0], 0f);
        assertEquals(0f, h.posXMass()[0], 0f); // absent => 0 kg
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.HaloAssemblerMassTest" --rerun-tasks`
Expected: FAIL — `NeighborHalo` has no `negXMass()`/mass faces; `HaloAssembler.Neighbor` has no mass component / `absent()`.

- [ ] **Step 3: Implement**

In `NeighborHalo.java`: add six mass faces (`float[] negXMass, posXMass, negYMass, posYMass, negZMass, posZMass`) as additional record components after the material faces, plus a `massFaces()` accessor in canonical order:

```java
public record NeighborHalo(
        float[] negXT, float[] posXT, float[] negYT, float[] posYT, float[] negZT, float[] posZT,
        char[]  negXM, char[]  posXM, char[]  negYM, char[]  posYM, char[]  negZM, char[]  posZM,
        float[] negXMass, float[] posXMass, float[] negYMass, float[] posYMass, float[] negZMass, float[] posZMass
) {
    public static final int FACE_CELLS = 256;

    public float[][] tempFaces() { return new float[][]{negXT, posXT, negYT, posYT, negZT, posZT}; }
    public char[][]  matFaces()  { return new char[][]{negXM, posXM, negYM, posYM, negZM, posZM}; }
    public float[][] massFaces() { return new float[][]{negXMass, posXMass, negYMass, posYMass, negZMass, posZMass}; }
}
```

In `HaloAssembler.java`: add `float[] mass` to the `Neighbor` record, add a static `Neighbor absent()` returning all-zero faces (length 256), and make `assemble(...)` populate the mass faces from each neighbour's mass (absent neighbour → 0 kg, matching the no-flow wall: a 0-mass face means no mass flows in). The existing `copyCell` helper gains the mass face/array arguments. Where an existing caller used a 2-arg `Neighbor(t, m)`, update it to the 3-arg form (search call sites in `MinecraftThermalWorld`).

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.HaloAssemblerMassTest" --rerun-tasks`
Expected: PASS. Run any existing `HaloAssemblerTest` → PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/NeighborHalo.java core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java core/src/test/java/net/rainbowcreation/orge/scheduler/HaloAssemblerMassTest.java
git commit -m "feat(scheduler): NeighborHalo + HaloAssembler carry neighbour mass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13 [MAIN]: `BatchMarshaller` flattens haloMass + fluid LUT, slices massOut

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerMassTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerMassTest {
    @Test
    void flattenCarriesHaloMassAndFluidLut() {
        StepTask a = solidSection(new net.rainbowcreation.orge.section.SubchunkKey(0,0,0), 300f);
        List<Material> lut = List.of(
                new Material(Identifier.fromNamespaceAndPath("orge","void"), 0f,1f,0f,0f,0f,
                        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null),
                new Material(Identifier.fromNamespaceAndPath("orge","water"), 0.6f,4186f,0.001f,1000f,0.018f,
                        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null, Float.NaN, false, true));
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(a), lut);
        assertEquals(1 * BatchMarshaller.FACES * BatchMarshaller.FACE, f.haloMass().length);
        assertEquals(2, f.lutVisc().length);
        assertEquals(0.001f, f.lutVisc()[1], 1e-6f);
        assertEquals(1000f, f.lutFullMass()[1], 0f);
        assertEquals((byte) 1, f.lutFluid()[1]);
        assertEquals((byte) 0, f.lutFluid()[0]);
    }

    @Test
    void sliceMassSplitsPerSection() {
        float[] flat = new float[2 * BatchMarshaller.SEC_N];
        flat[0] = 11f; flat[BatchMarshaller.SEC_N] = 22f;
        List<float[]> per = BatchMarshaller.sliceMass(flat, 2);
        assertEquals(11f, per.get(0)[0], 0f);
        assertEquals(22f, per.get(1)[0], 0f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.BatchMarshallerMassTest" --rerun-tasks`
Expected: FAIL — `Flat` has no `haloMass`/`lutVisc`/`lutFullMass`/`lutFluid`; no `sliceMass`.

- [ ] **Step 3: Implement**

In `BatchMarshaller.java`: extend `Flat` with `float[] haloMass, float[] lutVisc, float[] lutFullMass, byte[] lutFluid`. In `flatten`: allocate `haloMass = new float[n*FACES*FACE]` and fill it from `t.halo().massFaces()` alongside the temp/mat faces (same offset math). Build `lutVisc`/`lutFullMass`/`lutFluid` from each `Material` (`viscosity()`, `defaultMass()`, `fluid() ? (byte)1 : (byte)0`). Add `sliceMass(float[] massOut, int n)` identical in shape to `slice(...)`. (The existing `slice` is reused for temperatures.)

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.BatchMarshallerMassTest" --rerun-tasks`
Expected: PASS. Run existing `BatchMarshallerTest` → PASS (existing fields unchanged, new fields appended).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerMassTest.java
git commit -m "feat(engine): BatchMarshaller flattens haloMass + fluid LUT, slices massOut

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 14 [MAIN]: Rebuild + bundle the new `liborge.so`; update `NativeEngine.orgeStep` to the new ABI

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- Modify (binary): `core/src/main/resources/natives/linux-x64/liborge.so`
- Modify: `core/src/test/java/net/rainbowcreation/orge/engine/NativeEngineTest.java` (adapt to `StepResult`)
- Possibly modify: `core/src/test/java/net/rainbowcreation/orge/engine/BatchTestSupport.java` (if `StubEngine`/`solidSection` shape changed)

This task crosses the repo boundary in sequence: the `.so` is **built in ENGINE** (Task 9 output) and **committed as a binary in MAIN**. There is no version string; the artifact identity is the file content.

- [ ] **Step 1: Write/adapt the failing test**

Update `NativeEngineTest.java` so each `e.step(...)` returns `List<StepResult>` and assertions read `.get(0).temperature()`. Add one new test proving mass falls through the real native:

```java
    @Test
    void nativeAdvectsMassDownward() {
        NativeEngine e = engineOrSkip();
        // a fluid section: build via BatchTestSupport.fluidSection (added below) with the top
        // plane full and the cell below empty; after one step the lower cell gains mass.
        StepTask a = fluidSection(new SubchunkKey(0, 0, 0));
        List<StepResult> out = e.step(List.of(a), fluidLut(), 1.0);
        int top = 0 + 16 * 1 + 256 * 0; // sidx(0,1,0)
        int bot = 0 + 16 * 0 + 256 * 0; // sidx(0,0,0)
        assertTrue(out.get(0).mass()[bot] > out.get(0).mass()[top], "mass fell downward");
        float total = out.get(0).mass()[top] + out.get(0).mass()[bot];
        assertEquals(1000f, total, 1f, "mass conserved in the falling pair");
    }
```

Add `fluidSection(...)` + `fluidLut()` helpers to `BatchTestSupport.java`: a section whose cells (index top/bot) are a fluid material (LUT index 1 with `fluid=true`, `viscosity=0.001`, `defaultMass=1000`), `mass[top]=1000`, `mass[bot]=0`, all-void halo (incl. zero mass faces).

- [ ] **Step 2: Run test to verify it fails**

First rebuild the native from ENGINE and copy it into MAIN's resources:

```bash
JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh \
    /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.NativeEngineTest" --rerun-tasks`
Expected: FAIL — `NativeEngine.step` still returns `List<float[]>`; the hand-declared `orgeStep` native signature does not match the rebuilt `.so` (would `UnsatisfiedLinkError` at call), and the test references `StepResult`.

- [ ] **Step 3: Implement**

In `NativeEngine.java`: change the native declaration to the new ABI (order MUST match `orge_jni.cpp` Task 9 exactly), make `step` return `List<StepResult>`, allocate a `massOut` buffer, and zip temperature + mass per section:

```java
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat, float[] haloMass,
            float[] lutCond, float[] lutHeatCap, float[] lutVisc,
            float[] lutFullMass, byte[] lutFluid,
            double dtSeconds,
            float[] tOut, float[] massOut);

    @Override
    public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dtSeconds) {
        if (tasks.isEmpty()) { lastStepMillis = 0.0; return new ArrayList<>(); }
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        float[] tOut = new float[f.n() * BatchMarshaller.SEC_N];
        float[] massOut = new float[f.n() * BatchMarshaller.SEC_N];
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.haloMass(),
                f.lutCond(), f.lutHeatCap(), f.lutVisc(), f.lutFullMass(), f.lutFluid(),
                dtSeconds, tOut, massOut);
        List<float[]> t = BatchMarshaller.slice(tOut, f.n());
        List<float[]> m = BatchMarshaller.sliceMass(massOut, f.n());
        List<StepResult> out = new ArrayList<>(f.n());
        for (int s = 0; s < f.n(); s++) out.add(new StepResult(t.get(s), m.get(s)));
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.NativeEngineTest" --rerun-tasks`
Expected: PASS (conduction tests + the new advection test) against the rebuilt `.so`.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/resources/natives/linux-x64/liborge.so core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java core/src/test/java/net/rainbowcreation/orge/engine/NativeEngineTest.java core/src/test/java/net/rainbowcreation/orge/engine/BatchTestSupport.java
git commit -m "build(engine): rebundle liborge.so (advection ABI); NativeEngine new ABI + StepResult

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Part E — MAIN (Java): scheduler writeBack, §9 invariant, reconcile, suppress

## Task 15 [MAIN]: `ThermalWorld.writeBack(entry, StepResult)` persists massOut; `MinecraftThermalWorld` writes engine mass

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/ThermalWorldWriteBackMassTest.java` (against a fake `ThermalWorld`)

- [ ] **Step 1: Write the failing test**

Test a small fake `ThermalWorld` recording the `StepResult` it receives, asserting `writeBack` now takes a `StepResult` and exposes mass:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalWorldWriteBackMassTest {
    @Test
    void writeBackReceivesTemperatureAndMass() {
        float[] t = new float[4096]; float[] m = new float[4096];
        t[0] = 350f; m[0] = 640f;
        StepResult[] seen = new StepResult[1];
        ThermalWorld w = new ThermalWorld() {
            public Batch snapshot(int range) { return new Batch(java.util.List.of(), java.util.List.of()); }
            public void writeBack(BatchEntry e, StepResult r) { seen[0] = r; }
        };
        ThermalWorld.BatchEntry e = new ThermalWorld.BatchEntry(
                Identifier.fromNamespaceAndPath("minecraft","overworld"),
                new SubchunkKey(0,0,0),
                new StepTask(new SubchunkKey(0,0,0), new char[4096], new float[4096], new float[4096], null));
        w.writeBack(e, new StepResult(t, m));
        assertEquals(350f, seen[0].temperature()[0], 0f);
        assertEquals(640f, seen[0].mass()[0], 0f);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.ThermalWorldWriteBackMassTest" --rerun-tasks`
Expected: FAIL — `writeBack(BatchEntry, StepResult)` not defined (current signature takes `float[]`).

- [ ] **Step 3: Implement**

In `ThermalWorld.java`: change the `writeBack` signature to `void writeBack(BatchEntry entry, StepResult result)` and update the javadoc to "write the result's temperatures AND mass into the section".

In `MinecraftThermalWorld.java`: update `writeBack` to accept `StepResult`, copy `result.temperature()` into `data.temperatureArray()` and **`result.mass()` into `data.massArray()`** (replacing the old `entry.task().mass()` copy — the engine now owns mass, advection moves it). Keep the `store.put` + dirty-mark.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.ThermalWorldWriteBackMassTest" --rerun-tasks`
Expected: PASS. (Scheduler will not compile until Task 16; that's next.)

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java core/src/test/java/net/rainbowcreation/orge/scheduler/ThermalWorldWriteBackMassTest.java
git commit -m "feat(scheduler): writeBack persists engine massOut (StepResult)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 16 [MAIN]: §9 mass-conservation invariant in `StepValidator`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorMassTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepValidatorMassTest {
    @Test
    void acceptsConservedMassWithinEpsilon() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 400f; after[1] = 600f; // moved 100 kg between two cells
        assertTrue(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsNonConservedMass() {
        float[] before = new float[4096]; float[] after = new float[4096];
        java.util.Arrays.fill(before, 500f); java.util.Arrays.fill(after, 500f);
        after[0] = 5000f; // 4500 kg created out of nothing
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }

    @Test
    void rejectsCellAboveFullMassBound() {
        float[] before = new float[4096]; float[] after = new float[4096];
        // total conserved but one cell exceeds full mass (1000 kg) + epsilon.
        before[0] = 1000f; after[0] = 1000f; after[1] = -0.0f;
        after[0] = 1200f; after[1] = -200f;
        assertFalse(StepValidator.massConserved(after, before, 1000f));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.StepValidatorMassTest" --rerun-tasks`
Expected: FAIL — `massConserved(...)` not defined.

- [ ] **Step 3: Implement**

Add to `StepValidator.java` (and a `cleanMass` mirroring `clean` for non-finite mass → 0, clamped to `[0, fullMassBound]`, used by the scheduler before the conservation check):

```java
    /** Per-region mass-conservation tolerance: ε·N (ε = 1e-2 kg per cell). */
    public static final float MASS_EPSILON_PER_CELL = 1e-2f;

    /**
     * §9 invariant (DESIGN §10): true iff total mass is conserved within {@code ε·N}
     * (boundary in/out = 0, no-flow walls) AND every cell is within {@code [0, fullMass+ε]}.
     * Used as the accept/reject gate on a step's mass output.
     *
     * @param after        engine mass output (length N)
     * @param before       snapshot input mass (length N)
     * @param fullMassBound the largest legal per-cell mass for this section (max material defaultMass)
     */
    public static boolean massConserved(float[] after, float[] before, float fullMassBound) {
        double sumA = 0, sumB = 0;
        float cellEps = MASS_EPSILON_PER_CELL;
        for (int i = 0; i < after.length; i++) {
            if (!Float.isFinite(after[i])) return false;
            if (after[i] < -cellEps || after[i] > fullMassBound + cellEps) return false;
            sumA += after[i]; sumB += before[i];
        }
        return Math.abs(sumA - sumB) <= cellEps * after.length;
    }

    /** Non-finite mass → 0; finite mass clamped to [0, fullMassBound]. Mirrors {@link #clean}. */
    public static float[] cleanMass(float[] mass, float fullMassBound) {
        float[] out = new float[mass.length];
        for (int i = 0; i < mass.length; i++) {
            float v = mass[i];
            if (!Float.isFinite(v) || v < 0f) out[i] = 0f;
            else out[i] = Math.min(v, fullMassBound);
        }
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.StepValidatorMassTest" --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorMassTest.java
git commit -m "feat(scheduler): §9 mass-conservation invariant + cleanMass clamp

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 17 [MAIN]: `Scheduler` wires `StepResult` → validate mass → writeBack → reconcile

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerMassTest.java`

- [ ] **Step 1: Write the failing test**

A deterministic scheduler test (fake `StepRunner`/`ThermalWorld`/engine) asserting: a step returning a `StepResult` with valid conserved mass is written back via `writeBack(entry, StepResult)`, and a step returning mass that violates conservation holds previous values (rejected). Mirror the existing scheduler test setup. Assert the `FluidReconciler` (a counting fake) is invoked once per written section.

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.*;
import net.rainbowcreation.orge.phase.FluidReconciler;
import net.rainbowcreation.orge.phase.PhaseChanger;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerMassTest {
    // ... build a fake ThermalWorld whose snapshot returns ONE fluid section (full mass at top
    // cell, empty below), a synchronous StepRunner, and a real StubEngine (identity mass).
    // After 20+1 ticks the writeBack must have received a StepResult whose mass equals the
    // task mass (conserved), and the counting FluidReconciler fired exactly once.

    @Test
    void conservedMassStepWritesBackAndReconciles() {
        int[] reconcileCount = {0};
        FluidReconciler counting = entry -> reconcileCount[0]++;
        // ... wire Scheduler(engine, world, runner, worker, PhaseChanger.NOOP, counting); tick 21x.
        // assertEquals(1, reconcileCount[0]);
        // assertNotNull(world.lastWriteback);  // a StepResult was written
        assertTrue(true); // replace with concrete fake wiring mirroring existing SchedulerTest
    }
}
```

(The executor MUST replace the placeholder with concrete fakes mirroring the existing `SchedulerTest` in the same package — read it first for the established fake shapes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SchedulerMassTest" --rerun-tasks`
Expected: FAIL — `Scheduler` has no constructor taking a `FluidReconciler`; `complete` still consumes `List<float[]>`.

- [ ] **Step 3: Implement**

In `Scheduler.java`:
- Add a `FluidReconciler fluidReconciler` field (import from `net.rainbowcreation.orge.phase`), a constructor overload adding it (default `FluidReconciler.NOOP` in the existing constructors), keeping backward-compatible constructors.
- `submit()` already captures `pendingEntries`; capture each entry's snapshot mass + the section's `fullMassBound` (max `defaultMass` over the batch LUT — a constant per step) for the §9 check.
- In `complete()`, change `results` to `List<StepResult>`. Per entry: `float[] cleanT = StepValidator.clean(r.temperature(), entry.task().temperature()); float[] cleanM = StepValidator.cleanMass(r.mass(), fullMassBound);` then `if (!StepValidator.massConserved(cleanM, entry.task().mass(), fullMassBound)) { LOGGER.warn(...); hold previous (skip writeBack for this entry); continue; }` else `world.writeBack(entry, new StepResult(cleanT, cleanM));`.
- After `phaseChanger.applyPhaseChanges(entry);` add `fluidReconciler.reconcile(entry);`.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SchedulerMassTest" --rerun-tasks`
Expected: PASS. Run existing `SchedulerTest` → PASS (NOOP reconciler; backward-compatible constructors).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerMassTest.java
git commit -m "feat(scheduler): StepResult cycle — clean/conserve mass, writeBack, reconcile

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 18 [MAIN]: `FluidReconcileLogic` (pure) — mass fraction → render level

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/FluidReconcileLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcileLogicTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidReconcileLogicTest {
    @Test
    void nearFullIsSourceLevelZero() {
        // f >= 0.95 -> level 0 (full block).
        assertEquals(0, FluidReconcileLogic.levelForFraction(0.95f));
        assertEquals(0, FluidReconcileLogic.levelForFraction(1.0f));
    }

    @Test
    void partialMapsToOneThroughSeven() {
        // level = round((1 - f) * 7), clamped 1..7 for 0 < f < 0.95.
        assertEquals(7, FluidReconcileLogic.levelForFraction(0.01f)); // almost empty -> thin
        assertEquals(4, FluidReconcileLogic.levelForFraction(0.5f));
        assertTrue(FluidReconcileLogic.levelForFraction(0.9f) >= 1);
    }

    @Test
    void belowEpsilonIsRemoved() {
        // f <= 0 (mass ~ 0) -> REMOVE sentinel.
        assertEquals(FluidReconcileLogic.REMOVE, FluidReconcileLogic.levelForFraction(0f));
    }

    @Test
    void fractionFromMass() {
        assertEquals(0.5f, FluidReconcileLogic.fraction(500f, 1000f), 1e-6f);
        assertEquals(0f, FluidReconcileLogic.fraction(0f, 1000f), 0f);
        assertEquals(0f, FluidReconcileLogic.fraction(10f, 0f), 0f); // guard zero full mass
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcileLogicTest" --rerun-tasks`
Expected: FAIL — `FluidReconcileLogic` not defined.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.phase;

/**
 * Pure mass → vanilla fluid render-level mapping (DESIGN §10 Decision 9). LEVEL is a visual
 * depth readout of mass fraction {@code f = m / fullMass}: {@code f >= 0.95} renders a full
 * block (level 0); {@code 0 < f < 0.95} renders {@code round((1-f)*7)} clamped 1..7; {@code f<=0}
 * means the cell is empty and the fluid block should be removed ({@link #REMOVE}). No Minecraft
 * types — the MC adapter ({@code MinecraftFluidReconciler}) turns the level into a block state.
 */
public final class FluidReconcileLogic {

    /** Sentinel: the cell holds no fluid; remove any managed fluid block. */
    public static final int REMOVE = -1;

    /** Fraction above which the cell renders as a full (level-0) block. */
    public static final float FULL_FRACTION = 0.95f;

    private FluidReconcileLogic() {}

    /** {@code m / fullMass}, guarded for a zero/negative full mass (→ 0). */
    public static float fraction(float massKg, float fullMassKg) {
        if (fullMassKg <= 0f) return 0f;
        float f = massKg / fullMassKg;
        return f < 0f ? 0f : f;
    }

    /** Maps a mass fraction to a vanilla fluid LEVEL, or {@link #REMOVE} when empty. */
    public static int levelForFraction(float f) {
        if (f <= 0f) return REMOVE;
        if (f >= FULL_FRACTION) return 0;
        int level = Math.round((1f - f) * 7f);
        if (level < 1) return 1;
        if (level > 7) return 7;
        return level;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcileLogicTest" --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/FluidReconcileLogic.java core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcileLogicTest.java
git commit -m "feat(phase): FluidReconcileLogic — mass fraction -> vanilla render level

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 19 [MAIN]: `FluidReconciler` seam + `MinecraftFluidReconciler` adapter

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/FluidReconciler.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/MinecraftFluidReconciler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcilerNoopTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidReconcilerNoopTest {
    @Test
    void noopDoesNothing() {
        ThermalWorld.BatchEntry e = new ThermalWorld.BatchEntry(
                Identifier.fromNamespaceAndPath("minecraft","overworld"),
                new SubchunkKey(0,0,0),
                new StepTask(new SubchunkKey(0,0,0), new char[4096], new float[4096], new float[4096], null));
        assertDoesNotThrow(() -> FluidReconciler.NOOP.reconcile(e));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcilerNoopTest" --rerun-tasks`
Expected: FAIL — `FluidReconciler` not defined.

- [ ] **Step 3: Implement**

`FluidReconciler.java` (mirrors `PhaseChanger`):

```java
package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §10 reconcile seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after write-back and beside {@link PhaseChanger}: map each cell's stored mass
 * to a {@code minecraft:water}/{@code lava} render level — place a block on mass gain, remove on
 * mass ≈ 0. Keeps the scheduler loader-free; the live impl is {@code MinecraftFluidReconciler}.
 */
public interface FluidReconciler {
    void reconcile(ThermalWorld.BatchEntry entry);
    FluidReconciler NOOP = entry -> {};
}
```

`MinecraftFluidReconciler.java` — the MC adapter mirroring `MinecraftPhaseChanger`'s server-bind + section-read pattern. For each cell whose material is a fluid (`Material.fluid()`): read `data.massAt(i)`, compute `FluidReconcileLogic.fraction(mass, material.defaultMass())` then `levelForFraction(...)`; on `REMOVE` set the block to `minecraft:air` (only if it is currently the managed fluid block); otherwise set `material.representativeBlock()` (water/lava) with the computed `LEVEL` blockstate property (clamped to the block's `LiquidBlock.LEVEL` range) and the `FALLING` flag when the cell below has capacity. Use `Block.UPDATE_CLIENTS` only (no neighbour cascade), exactly like `MinecraftPhaseChanger`. Server-thread only; `bindServer`/`unbindServer`. Skip cells where the block already matches the target state (avoid churn).

This adapter touches Minecraft types and is verified by loader compilation + the integration audit (Task 21); the level math is the already-tested `FluidReconcileLogic`.

- [ ] **Step 4: Run test to verify it passes + loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.phase.FluidReconcilerNoopTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/FluidReconciler.java core/src/main/java/net/rainbowcreation/orge/phase/MinecraftFluidReconciler.java core/src/test/java/net/rainbowcreation/orge/phase/FluidReconcilerNoopTest.java
git commit -m "feat(phase): FluidReconciler seam + MinecraftFluidReconciler (mass -> level)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 20 [MAIN]: `VanillaFluidSuppressor` via `ExpectPlatform` (both loaders) — with mixin follow-up call-out

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/fluid/VanillaFluidSuppressor.java`
- Create: `fabric-1.21/src/main/java/net/rainbowcreation/orge/fluid/fabric/VanillaFluidSuppressorImpl.java`
- Create: `neoforge-1.21/src/main/java/net/rainbowcreation/orge/fluid/neoforge/VanillaFluidSuppressorImpl.java`

**KNOWN RISK (spec "Known risk — vanilla fluid suppression"):** Architectury common events do **not** expose vanilla fluid-tick cancellation, and **no mixin toolchain is set up in this repo**. This task wires the `ExpectPlatform` seam and implements the *best available event-based suppression per loader*, and **explicitly documents** that full suppression (cancelling vanilla `LiquidBlock` scheduled/random ticks and neighbour-update spread for managed blocks) likely requires introducing a per-loader **mixin** dependency. The executor MUST NOT assume an event-only solution is sufficient — if event hooks cannot fully cancel vanilla fluid ticking, leave a `TODO(mixin)` with the concrete mixin target (`net.minecraft.world.level.material.FlowingFluid#tick` / `LiquidBlock#tick`) and surface it in the task's commit message. This task is "seam + best-effort + documented follow-up", not "guaranteed full suppression".

- [ ] **Step 1: Define the seam (compile-gated, no headless unit test)**

This seam manipulates loader fluid-tick registration and cannot be JUnit-tested headlessly; it is verified by loader compilation here and by in-game behaviour later. Create the common seam:

```java
package net.rainbowcreation.orge.fluid;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * Suppresses vanilla liquid physics for ORGE-managed fluid blocks so ORGE is the sole
 * authority (DESIGN §10 Decision 8). Architectury exposes no common fluid-tick cancellation,
 * so each loader bridges its own hook here via {@link ExpectPlatform}.
 *
 * <p><b>Known risk / follow-up:</b> full suppression (cancelling {@code FlowingFluid#tick} /
 * {@code LiquidBlock#tick} scheduled+random ticks and neighbour-update spread) is NOT expressible
 * through the loaders' common event APIs and likely requires a per-loader <b>mixin</b> into
 * {@code net.minecraft.world.level.material.FlowingFluid#tick}. No mixin toolchain exists in this
 * repo yet. Each impl does the best event-based suppression available and leaves a
 * {@code TODO(mixin)} where an event hook is insufficient.</p>
 */
public final class VanillaFluidSuppressor {
    private VanillaFluidSuppressor() {}

    /** Install the per-loader suppression hooks. Called once from {@code Orge.init()}. */
    @ExpectPlatform
    public static void install() {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
```

- [ ] **Step 2: Verify the seam fails to resolve without impls**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL (the common class compiles; impls are resolved by Architectury at the loader projects).

- [ ] **Step 3: Implement both loader impls (best-effort event suppression + documented mixin TODO)**

`fabric-1.21/.../fabric/VanillaFluidSuppressorImpl.java`: `public static void install()`. Use the best available Fabric hook to neutralize vanilla fluid spread for managed blocks (e.g. intercept block-update / fluid scheduled ticks where the API allows; if not expressible, leave the body as a documented no-op with a precise `TODO(mixin): mixin into FlowingFluid#tick to cancel for ORGE-managed water/lava`). Keep server-side only.

`neoforge-1.21/.../neoforge/VanillaFluidSuppressorImpl.java`: `public static void install()`. NeoForge exposes more fluid/level events than Fabric; cancel what is cancellable (e.g. a `BlockEvent`/level-tick listener that vetoes vanilla fluid flow for managed positions) and leave the same precise `TODO(mixin)` where events are insufficient.

Wire `VanillaFluidSuppressor.install()` into `core/.../Orge.java` `init()` alongside the existing platform registrations (search for `SectionStorePlatform.registerChunkHooks` to find the call site).

- [ ] **Step 4: Verify all loaders compile**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/fluid/VanillaFluidSuppressor.java fabric-1.21/src/main/java/net/rainbowcreation/orge/fluid/fabric/VanillaFluidSuppressorImpl.java neoforge-1.21/src/main/java/net/rainbowcreation/orge/fluid/neoforge/VanillaFluidSuppressorImpl.java core/src/main/java/net/rainbowcreation/orge/Orge.java
git commit -m "feat(fluid): VanillaFluidSuppressor ExpectPlatform seam (best-effort; mixin follow-up flagged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# Part F — MAIN (Java): integration audit

## Task 21 [MAIN]: Extend `AuditScenarioTest` — water falls + spreads + reconciles; water-next-to-lava still steams

**Files:**
- Modify: `core/src/test/java/net/rainbowcreation/orge/AuditScenarioTest.java`

This headless test runs against the real native (`NativeEngine`) for the advection half and the pure `FluidReconcileLogic`/`PhaseRule` for the reconcile/phase half — no Minecraft world. It proves the Phase-2a story end-to-end at the pure+native boundary.

- [ ] **Step 1: Write the failing test**

Add two tests to `AuditScenarioTest.java`. First, a native advection scenario (skipped if no bundled native, like `NativeEngineTest`):

```java
    @Test
    void waterFallsSpreadsAndReconciles() {
        net.rainbowcreation.orge.engine.NativeEngine e;
        try { net.rainbowcreation.orge.engine.NativeLoader.load(); e = new net.rainbowcreation.orge.engine.NativeEngine(); }
        catch (Throwable t) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "no native"); return; }

        // one section: a full water cell high in the section, empty fluid cells below + beside it.
        // build matIx (water=1), mass (1000 at the high cell, 0 elsewhere fluid), all-void halo.
        // step ~30 times feeding massOut back as the next mass input (like NativeEngineTest.run).
        // assert: total mass conserved; the bottom row holds mass; a horizontal neighbour gained mass.
        // then map the settled mass through FluidReconcileLogic and assert a full-ish cell -> level 0
        // and an empty cell -> REMOVE.
        // (Concrete buffer setup mirrors BatchTestSupport.fluidSection from Task 14.)
        assertTrue(true); // executor: replace with the concrete native loop + assertions
    }
```

Second, confirm the §7 seam is untouched by advection — reuse the existing `waterNextToPinnedLavaBoilsAndLavaSurvives` flow but assert the material separation (Decision 7): water mass and lava mass never merge in the reconcile mapping. Add:

```java
    @Test
    void waterNextToLavaStillSteamsAndMassesDoNotMerge() {
        // water cell adjacent to a pinned lava cell. Advection only moves mass between same-material
        // fluid cells, so the lava's 3100 kg never flows into the water cell and vice-versa; the
        // water-on-lava interaction stays owned by §7 (PhaseRule -> orge:steam).
        Material water = water();
        // a water cell heated above boiling still yields orge:steam via PhaseRule (unchanged §7).
        Optional<Identifier> target = PhaseRule.targetBlock(400f, water);
        assertEquals(ORGE_STEAM, target.orElse(null));
        // reconcile keeps materials separate: water fraction uses water's full mass, lava uses lava's.
        assertEquals(0, net.rainbowcreation.orge.phase.FluidReconcileLogic.levelForFraction(
                net.rainbowcreation.orge.phase.FluidReconcileLogic.fraction(1000f, water.defaultMass())));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.AuditScenarioTest" --rerun-tasks`
Expected: FAIL — the placeholder native loop is not yet written / references helpers; the second test fails if imports/material `fluid` not present.

- [ ] **Step 3: Implement**

Replace the `assertTrue(true)` placeholder in `waterFallsSpreadsAndReconciles` with the concrete native loop: build a fluid `StepTask` (matIx all water index 1, water `Material` with `fluid=true`, mass = 1000 at one upper cell and 0 in the fluid cells below/beside, all-zero halo incl. mass faces), a LUT `[void, water]`, run `e.step(...)` ~30 times feeding `StepResult.mass()` back as the next task's mass and `StepResult.temperature()` as the next temperature, then assert: total mass ≈ 1000 each step (conservation), a lower cell ended with mass > 0, a horizontal neighbour gained mass, and the reconcile mapping turns a near-full settled cell into level 0 and an emptied cell into `REMOVE`. Make `water()` carry `fluid=true` (use the 14-arg constructor) so it participates.

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.AuditScenarioTest" --rerun-tasks`
Expected: PASS (both new tests + the existing two). Then run the WHOLE suite + all loaders:
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/test/java/net/rainbowcreation/orge/AuditScenarioTest.java
git commit -m "test(audit): water falls+spreads+reconciles (native); §7 steam + material separation hold

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification (run before declaring done — no push)

- [ ] ENGINE full suite: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh` → ALL TESTS PASSED (now includes advection + advection_parity).
- [ ] ENGINE clean: `cd /home/claude/ORGE/ORGE-ENGINE && git status` → clean tree (all engine commits landed; native rebuilt).
- [ ] MAIN suite: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` → BUILD SUCCESSFUL.
- [ ] MAIN loaders: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava` → BUILD SUCCESSFUL.
- [ ] MAIN clean: `git status` from `/home/claude/ORGE` → only `? ORGE-ENGINE` untracked; all plan commits landed; **nothing pushed**.

---

## Not in this plan (spec deferrals — do NOT implement; carry forward)

These are explicitly **out of scope** per the spec's "Out of scope (deferred)" list. The executor must not drift into them:

- **Gas buoyancy / diffusion** (steam rising) — a later spec with an inverted gravity term in the same native pass; closes the §7 steam loop. No gas buoyancy here.
- **Latent heat** — phase-change energy plateaus (per-cell latent-energy accumulator, native, with halo/JNI plumbing); the next track after this slice's in-game audit. Not here.
- **Eager seeding-on-chunk-load** — mass comes only from the existing block-derived path (§5 + `b4a79d5`) and `/orge fill`/`set`. No conservative seeding pass.
- **Buckets / player interactions / rain** — no water entry/exit points beyond what already exists.
- **Water ↔ lava interaction beyond §7** — advection never merges different-material masses; the water-on-lava → steam/stone interaction stays entirely in §7. No new merge logic.
- **Region-boundary water streaming** — unloaded/unsimulated neighbours are **no-flow walls**; water piling at the loaded edge is an accepted known limitation, revisited when seeding/region-streaming is designed.
- **Temperature-dependent material curves** — materials stay flat constants.
- **windows/macos native builds** — only `linux-x64` `liborge.so` is rebuilt/bundled for this slice (matches the existing bundle + the audit/CI platform).
