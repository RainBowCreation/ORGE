# Whole-Region Engine Step — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Step the joined active region as one `sim_engine` `World` per cycle (native cross-column X/Z flow, contiguous Y), deleting the per-section halo / seam-flux / cross-seam-reconciliation machinery, while keeping all §11 molar-gas physics.

**Architecture:** Java owns canonical state (`SectionStore`). Each cycle it assembles the active+apron column set into **full-height columns** (`CHUNK_N = 98304` cells each), makes one JNI call `orgeStepWorld` that builds a transient `World`, runs `compute_frame_to_backbuffers`+`swap` (conduction) and/or `snapshot_world`+`advect_chunk(&snap)` (advection), and reads the next state back. Java validates per-species conservation region-wide and writes back through an air-aware reconciler. The per-section kernel + parity test are kept compiled but dormant (not gated, not called).

**Tech Stack:** C++20 JNI (`liborge.so`, g++), Java 21 multiloader mod (`core/` subproject, Gradle, `JAVA_HOME=/home/claude/jdk21`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-01-whole-region-engine-step-design.md`

---

## Critical constants & index mapping (read before any task)

- Engine column dims (`sim_engine.hpp:13-25`): `CHUNK_W=16` (X), `CHUNK_H=384` (Y), `CHUNK_D=16` (Z), **`CHUNK_N = 16*384*16 = 98304`**.
- Engine cell index: **`idx(x,y,z) = x + y*16 + z*6144`** (`x + y*CHUNK_W + z*CHUNK_W*CHUNK_H`). y-stride 16, z-stride 6144.
- Engine Y is 0-based `[0,384)`. **MC→engine Y: `yEngine = yWorld + 64`** (MC `yWorld ∈ [-64,319]`).
- `SectionStore` stores per **16³ section**, section-local index **`sLocal = x + 16*sy + 256*z`** (`sy ∈ [0,15]`), section index `sectionY ∈ [-4,19]` (`SubchunkKey(cx, sectionY, cz)`).
- Section `sectionY` covers `yWorld ∈ [sectionY*16, sectionY*16+15]`, so a section cell `(x, sy, z)` maps to column index:
  **`colIdx = x + 16*(sectionY*16 + sy + 64) + 6144*z`**.
- Old per-section path uses `SEC_N = 4096`; **the new path uses `CHUNK_N = 98304` per column** — do not confuse them.
- `PASS_CONDUCTION = 1`, `PASS_ADVECTION = 2` (`orge_kernel.hpp:24-25`, `OrgeEngine.java`).
- `void_ix = 0` (Chunk default). Void material is LUT index 0. Air is a nonzero LUT index with `airFlag`, `defaultMass≈1.2`.

---

## Task 1 (ENGINE): `orgeStepWorld` JNI + 2-column C++ test

**Repo:** `ORGE-ENGINE` (branch `main`). **Build/run from** `/home/claude/ORGE/ORGE-ENGINE`.

**Files:**
- Create: `ORGE-ENGINE/tests/world_step_test.cpp`
- Modify: `ORGE-ENGINE/orge_jni.cpp` (add a second `extern "C"` function; also `#include "sim_engine.hpp"`)
- Read for reference: `ORGE-ENGINE/sim_engine.hpp:289-304` (`WorldSnapshot`, `snapshot_world`), `:239-249` (`compute_frame_to_backbuffers`, `swap_all_backbuffers`), `:306` (`advect_chunk` with `preStep`).

- [ ] **Step 1: Write the failing C++ test** `ORGE-ENGINE/tests/world_step_test.cpp`

This test compiles `sim_engine.hpp` directly (it does NOT go through JNI — it exercises the same World ops the JNI will call, so it can run headless). It asserts three things the design's R1/T1 require: (a) within-column vertical fall crosses the old 16-cell boundary; (b) water flows across an X/Z column seam when a pre-advection snapshot is passed; (c) an absent neighbour column is a no-flow wall (no fabrication into the unknown).

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/world_step_test.cpp -o build/world_step_test -pthread
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <cassert>
using namespace orge_sim;   // NOTE: confirm the namespace sim_engine.hpp uses; match advection_parity_test.cpp's `using`.

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)
#define CHECK_NEAR(a,b,eps,msg) do { if(std::fabs((a)-(b))>(eps)){ std::printf("FAIL: %s (%.4f vs %.4f)\n", msg,(double)(a),(double)(b)); ++failures; } } while(0)

static uint16_t add_water(MaterialLUT& m){ return m.add(Material{4186f,0.6f,1000f,0.018f,0.0f,true,125f,1000f,false,false}); }
static uint16_t add_air  (MaterialLUT& m){ return m.add(Material{1005f,0.026f,1.2f,0.029f,0.0f,true,0.001f,1000f,true,true}); }
static uint16_t add_void (MaterialLUT& m){ return m.add(Material{0f,0f,0f,0.018f,0.0f,false,9999f,0f,false,false}); }

// total mass of a given species index in a chunk
static double species_mass(const Chunk& C, uint16_t ix){
    double s=0; for(int i=0;i<CHUNK_N;++i) if(C.matIx[i]==ix) s+=C.mass_kg[i]; return s;
}
static void set_cell(Chunk& C, int x,int y,int z, uint16_t ix, float mass, float T){
    int i = idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=mass; C.T_curr[i]=T;
}

static void advect_world(World& w){
    WorldSnapshot snap = snapshot_world(w);
    for(auto& kv : w.chunks) advect_chunk(w, *kv.second, w.materials, &snap);
}

int main(){
    // ---- (a) within-column fall across the 16-cell boundary ----
    {
        World w; uint16_t V=add_void(w.materials); uint16_t WAT=add_water(w.materials); uint16_t AIR=add_air(w.materials); (void)V;
        Chunk* C = w.ensureChunk(0,0);
        for(int i=0;i<CHUNK_N;++i){ C->matIx[i]=AIR; C->mass_kg[i]=1.2f; C->T_curr[i]=300f; }
        for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
        // water sitting just ABOVE a section boundary at y=16 (boundary between section 0 and 1)
        set_cell(*C, 8, 16, 8, WAT, 1000f, 300f);
        double before = species_mass(*C, WAT);
        for(int step=0; step<8; ++step) advect_world(w);
        double after = species_mass(*C, WAT);
        CHECK_NEAR(before, 1000.0, 1e-2, "(a) water mass conserved during fall");
        CHECK_NEAR(after,  1000.0, 1e-2, "(a) water mass STILL 1000 after crossing y=16 boundary");
        CHECK(C->mass_kg[idx(8,16,8)] < 1000f, "(a) water actually left the original cell (fell)");
    }
    // ---- (b) X-seam flow with snapshot ----
    {
        World w; add_void(w.materials); uint16_t WAT=add_water(w.materials); uint16_t AIR=add_air(w.materials);
        Chunk* A = w.ensureChunk(0,0); Chunk* B = w.ensureChunk(1,0);
        for(Chunk* C : {A,B}){ for(int i=0;i<CHUNK_N;++i){C->matIx[i]=AIR;C->mass_kg[i]=1.2f;C->T_curr[i]=300f;} for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1; }
        // a tall water cell at the +X edge of chunk A (x=15), low mass air in B's x=0 neighbour
        set_cell(*A, 15, 40, 8, WAT, 1000f, 300f);
        double total_before = species_mass(*A,WAT)+species_mass(*B,WAT);
        for(int step=0; step<20; ++step) advect_world(w);
        double total_after = species_mass(*A,WAT)+species_mass(*B,WAT);
        CHECK_NEAR(total_before, total_after, 1e-2, "(b) water conserved across X seam");
        CHECK(species_mass(*B,WAT) > 1f, "(b) some water crossed the X seam into chunk B");
    }
    // ---- (c) absent neighbour column is a wall ----
    {
        World w; add_void(w.materials); uint16_t WAT=add_water(w.materials); uint16_t AIR=add_air(w.materials);
        Chunk* A = w.ensureChunk(0,0); // NO chunk at (1,0)
        for(int i=0;i<CHUNK_N;++i){A->matIx[i]=AIR;A->mass_kg[i]=1.2f;A->T_curr[i]=300f;} for(int s=0;s<SECTIONS_Y;++s) A->sectionLoaded[s]=1;
        set_cell(*A, 15, 40, 8, WAT, 1000f, 300f);
        double before = species_mass(*A,WAT);
        for(int step=0; step<20; ++step) advect_world(w);
        double after = species_mass(*A,WAT);
        CHECK_NEAR(before, after, 1e-2, "(c) no water lost into absent neighbour (wall)");
    }
    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("world_step_test OK\n"); return 0;
}
```

> **Implementer note:** The `Material{...}` aggregate order is `{heatCapacity, thermalConductivity, defaultMass, molarMass, viscosity, fluidFlag, minFlowMass, maxMass, gasFlag, airFlag}` (`sim_engine.hpp:28-41`). Confirm the namespace and `using` line by opening `advection_parity_test.cpp` (it already `#include`s the engine and has a working `using`). If the helper names (`snapshot_world`, `WorldSnapshot`) differ, match the verbatim names from `sim_engine.hpp:289-304`.

- [ ] **Step 2: Run the test, expect a BUILD or assertion outcome that proves the harness works first**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/world_step_test.cpp -o build/world_step_test -pthread && ./build/world_step_test
```
Expected at this point: it COMPILES and runs. If `advect_chunk` already flows correctly with a snapshot, (a)/(b)/(c) may PASS immediately — that is the R1 verification. If (b) FAILS (no cross-seam flow), that means the snapshot path needs the fix below; capture the exact failure before proceeding.

- [ ] **Step 3: (Only if Step 2 (b) failed) confirm/repair the snapshot seam path**

Open `sim_engine.hpp:537-599`. Confirm the `if (preStep)` X/Z seam-leveling block exists and is reached. If `advect_world` (which passes `&snap`) does not move water across the seam, diagnose with `superpowers:systematic-debugging` — do NOT guess. The expected mechanism: `snapshot_world(w)` captures pre-advection `matIx/mass/T`; each `advect_chunk(w,C,mats,&snap)` reads neighbour edge cells from `snap`. Re-run Step 2 until (a)(b)(c) all pass. **If the engine genuinely cannot flow across X/Z seams in one `advect_chunk` sweep, STOP and report — this invalidates R1 and needs a design conversation.**

- [ ] **Step 4: Add `orgeStepWorld` to `orge_jni.cpp`**

At the top, add `#include "sim_engine.hpp"` after the existing includes (keep `#include "orge_kernel.hpp"` — the old `orgeStep` stays). Append this function (do not modify the existing `orgeStep`):

```cpp
extern "C" JNIEXPORT jdouble JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStepWorld(
        JNIEnv* env, jclass,
        jint nCols,
        jintArray jCx, jintArray jCz,
        jcharArray jMatIx, jfloatArray jMass, jfloatArray jTin,
        jfloatArray jCond, jfloatArray jHeatCap, jfloatArray jVisc,
        jfloatArray jFullMass, jbyteArray jFluid,
        jfloatArray jMinFlow, jfloatArray jMaxMass, jbyteArray jGas, jbyteArray jAir,
        jfloatArray jMolar,
        jint passes, jdouble dt,
        jfloatArray jTout, jfloatArray jMassOut, jcharArray jMatOut)
{
    const jint matCount = env->GetArrayLength(jCond);
    auto* cx       = static_cast<jint*>    (env->GetPrimitiveArrayCritical(jCx,       nullptr));
    auto* cz       = static_cast<jint*>    (env->GetPrimitiveArrayCritical(jCz,       nullptr));
    auto* matIx    = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jMatIx,    nullptr));
    auto* mass     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMass,     nullptr));
    auto* tin      = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTin,      nullptr));
    auto* cond     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jCond,     nullptr));
    auto* heatCap  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHeatCap,  nullptr));
    auto* visc     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jVisc,     nullptr));
    auto* fullMass = static_cast<float*>   (env->GetPrimitiveArrayCritical(jFullMass, nullptr));
    auto* fluid    = static_cast<uint8_t*> (env->GetPrimitiveArrayCritical(jFluid,    nullptr));
    auto* minFlow  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMinFlow,  nullptr));
    auto* maxMass  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMaxMass,  nullptr));
    auto* gas      = static_cast<uint8_t*> (env->GetPrimitiveArrayCritical(jGas,      nullptr));
    auto* air      = static_cast<uint8_t*> (env->GetPrimitiveArrayCritical(jAir,      nullptr));
    auto* molar    = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMolar,    nullptr));
    auto* tout     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTout,     nullptr));
    auto* massOut  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMassOut,  nullptr));
    auto* matOut   = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jMatOut,   nullptr));

    double ms = 0.0;
    if (cx && cz && matIx && mass && tin && cond && heatCap && visc && fullMass && fluid
            && minFlow && maxMass && gas && air && molar && tout && massOut && matOut) {
        using namespace orge_sim;   // match the engine namespace (confirm against sim_engine.hpp)
        const auto t0 = std::chrono::steady_clock::now();

        World world;
        // Build the material LUT in index order (matches Java's flat LUT).
        for (int i = 0; i < matCount; ++i) {
            Material m{};
            m.heatCapacity        = heatCap[i];
            m.thermalConductivity = cond[i];
            m.defaultMass         = fullMass[i];
            m.molarMass           = molar[i];
            m.viscosity           = visc[i];
            m.fluidFlag           = fluid[i] != 0;
            m.minFlowMass         = minFlow[i];
            m.maxMass             = maxMass[i];
            m.gasFlag             = gas[i] != 0;
            m.airFlag             = air[i] != 0;
            world.materials.add(m);
        }
        // Build one full-height chunk per column from the flat arrays.
        for (int c = 0; c < nCols; ++c) {
            Chunk* C = world.ensureChunk(cx[c], cz[c]);
            const size_t base = static_cast<size_t>(c) * CHUNK_N;
            for (int i = 0; i < CHUNK_N; ++i) {
                C->matIx[i]   = matIx[base + i];
                C->mass_kg[i] = mass[base + i];
                C->T_curr[i]  = tin[base + i];
            }
            for (int s = 0; s < SECTIONS_Y; ++s) C->sectionLoaded[s] = 1; // full-height: step every section
        }
        // Step per the passes bitmask (conduction then advection, matching step_frame order).
        if (passes & PASS_CONDUCTION) {
            compute_frame_to_backbuffers(world, static_cast<float>(dt));
            swap_all_backbuffers(world);
        }
        if (passes & PASS_ADVECTION) {
            WorldSnapshot snap = snapshot_world(world);
            for (auto& kv : world.chunks)
                advect_chunk(world, *kv.second, world.materials, &snap);
        }
        // Read back in the same column order.
        for (int c = 0; c < nCols; ++c) {
            Chunk* C = world.findChunk(cx[c], cz[c]);
            const size_t base = static_cast<size_t>(c) * CHUNK_N;
            for (int i = 0; i < CHUNK_N; ++i) {
                matOut[base + i]  = C->matIx[i];
                massOut[base + i] = C->mass_kg[i];
                tout[base + i]    = C->T_curr[i];
            }
        }
        const auto t1 = std::chrono::steady_clock::now();
        ms = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1e6;
    }

    if (matOut)   env->ReleasePrimitiveArrayCritical(jMatOut,   matOut,   0);
    if (massOut)  env->ReleasePrimitiveArrayCritical(jMassOut,  massOut,  0);
    if (tout)     env->ReleasePrimitiveArrayCritical(jTout,     tout,     0);
    if (molar)    env->ReleasePrimitiveArrayCritical(jMolar,    molar,    JNI_ABORT);
    if (air)      env->ReleasePrimitiveArrayCritical(jAir,      air,      JNI_ABORT);
    if (gas)      env->ReleasePrimitiveArrayCritical(jGas,      gas,      JNI_ABORT);
    if (maxMass)  env->ReleasePrimitiveArrayCritical(jMaxMass,  maxMass,  JNI_ABORT);
    if (minFlow)  env->ReleasePrimitiveArrayCritical(jMinFlow,  minFlow,  JNI_ABORT);
    if (fluid)    env->ReleasePrimitiveArrayCritical(jFluid,    fluid,    JNI_ABORT);
    if (fullMass) env->ReleasePrimitiveArrayCritical(jFullMass, fullMass, JNI_ABORT);
    if (visc)     env->ReleasePrimitiveArrayCritical(jVisc,     visc,     JNI_ABORT);
    if (heatCap)  env->ReleasePrimitiveArrayCritical(jHeatCap,  heatCap,  JNI_ABORT);
    if (cond)     env->ReleasePrimitiveArrayCritical(jCond,     cond,     JNI_ABORT);
    if (tin)      env->ReleasePrimitiveArrayCritical(jTin,      tin,      JNI_ABORT);
    if (mass)     env->ReleasePrimitiveArrayCritical(jMass,     mass,     JNI_ABORT);
    if (matIx)    env->ReleasePrimitiveArrayCritical(jMatIx,    matIx,    JNI_ABORT);
    if (cz)       env->ReleasePrimitiveArrayCritical(jCz,       cz,       JNI_ABORT);
    if (cx)       env->ReleasePrimitiveArrayCritical(jCx,       cx,       JNI_ABORT);
    return ms;
}
```

- [ ] **Step 5: Compile the JNI TU to verify it builds (header-correctness gate)**

Run (compile-only; full `.so` is rebuilt in Task 2):
```bash
cd /home/claude/ORGE/ORGE-ENGINE && JAVA_HOME=/home/claude/jdk21 g++ -std=c++20 -O3 -DNDEBUG -fPIC -c -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I. orge_jni.cpp -o /tmp/orge_jni.o && echo COMPILE_OK
```
Expected: `COMPILE_OK`. If `sim_engine.hpp` symbol/namespace names differ from the assumptions above, fix the `using namespace` / helper names now.

- [ ] **Step 6: Run the C++ world test green**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/world_step_test.cpp -o build/world_step_test -pthread && ./build/world_step_test
```
Expected: `world_step_test OK`.

- [ ] **Step 7: Confirm the old parity test still compiles (kept dormant, not gated)**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/advection_parity_test.cpp -o build/advection_parity_test -pthread && ./build/advection_parity_test; echo "exit=$?"
```
Expected: it still builds and runs (pass or its prior status). We are not changing the kernel, so it should be unchanged.

- [ ] **Step 8: Commit (ENGINE `main`)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && git add orge_jni.cpp tests/world_step_test.cpp && \
git commit -m "feat(jni): orgeStepWorld — whole-region World step (build World, conduct+swap / snapshot+advect, read back)

Adds the whole-region JNI entry alongside the dormant per-section orgeStep.
2-column world_step_test proves within-column fall across the 16-cell boundary,
X-seam flow via snapshot_world, and absent-column wall (no fabrication).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && \
git push origin main && echo PUSHED
```

---

## Task 2 (ENGINE→.so): rebuild `liborge.so`, verify md5, copy to resources

**Files:**
- Build: `ORGE-ENGINE/build/liborge.so`
- Copy to: `core/src/main/resources/natives/linux-x64/liborge.so`

- [ ] **Step 1: Rebuild the shared library**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh && md5sum build/liborge.so
```
Expected: `built .../build/liborge.so` and an md5 hash printed.

- [ ] **Step 2: Copy into the mod resources and confirm md5 build==bundled**

```bash
cp /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so && \
md5sum /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```
Expected: the two md5 hashes are identical.

- [ ] **Step 3: No commit yet** — the `.so` is bundled into the single MAIN commit in Task 8 (with the gitlink + Java). Leave it staged-in-working-tree.

---

## Task 3 (JAVA): `stepWorld` engine API + `NativeEngine` binding + `StubEngine` + LUT-pack extract

**Repo:** MAIN (`/home/claude/ORGE`, branch `rebuild`). **Package base:** `net.rainbowcreation.orge.engine`.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/ColumnTask.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/ColumnResult.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/LutArrays.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/RegionMarshaller.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java` (use the shared LUT pack — keep output identical)
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/RegionMarshallerTest.java`

- [ ] **Step 1: Create the column data types**

`ColumnTask.java`:
```java
package net.rainbowcreation.orge.engine;

/** One full-height column handed to the whole-region engine step. Arrays are length RegionMarshaller.CHUNK_N
 *  in engine index order: idx = x + 16*y + 6144*z, y in [0,384). */
public record ColumnTask(int cx, int cz, char[] matIx, float[] mass, float[] temperature) {
}
```

`ColumnResult.java`:
```java
package net.rainbowcreation.orge.engine;

/** Next-state of one column, same length/order as ColumnTask. */
public record ColumnResult(char[] matIx, float[] mass, float[] temperature) {
}
```

- [ ] **Step 2: Extract the shared LUT pack** `LutArrays.java`

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import java.util.List;

/** Flat per-material LUT arrays shared by BatchMarshaller (per-section, dormant) and RegionMarshaller
 *  (whole-region). Encapsulates the §11 air-flag flip and the slot-0 AIR_DENSITY label so both paths
 *  stay identical. */
public record LutArrays(float[] cond, float[] heatCap, float[] visc, float[] fullMass,
                        byte[] fluid, float[] minFlow, float[] maxMass, byte[] gas, byte[] air,
                        float[] molar, int matCount) {

    public static final float AIR_DENSITY = 1.2f;

    public static LutArrays pack(List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");
        float[] cond = new float[m], heatCap = new float[m], visc = new float[m], fullMass = new float[m];
        byte[] fluid = new byte[m], gas = new byte[m], air = new byte[m];
        float[] minFlow = new float[m], maxMass = new float[m], molar = new float[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
            visc[i] = mat.viscosity();
            fullMass[i] = mat.defaultMass();
            // §11 air flag-flip (engine-LUT view ONLY; Material.fluid()/gas() unchanged):
            fluid[i] = (mat.fluid() || mat.air()) ? (byte) 1 : (byte) 0;
            minFlow[i] = mat.minFlowMass();
            maxMass[i] = mat.maxMass();
            gas[i] = (mat.gas() || mat.air()) ? (byte) 1 : (byte) 0;
            air[i] = mat.air() ? (byte) 1 : (byte) 0;
            molar[i] = mat.molarMass();
        }
        fullMass[0] = AIR_DENSITY; // VOID/ambient sentinel density label
        return new LutArrays(cond, heatCap, visc, fullMass, fluid, minFlow, maxMass, gas, air, molar, m);
    }
}
```

- [ ] **Step 3: Point `BatchMarshaller` at the shared pack (keep output identical)**

In `BatchMarshaller.flatten`, replace the inline LUT-packing block (`BatchMarshaller.java:79-109`, the `float[] cond = new float[m]; ... fullMass[0] = AIR_DENSITY;`) with:
```java
        LutArrays L = LutArrays.pack(lut);
        return new Flat(n, matIx, mass, tIn, haloT, haloMat, haloMass,
                L.cond(), L.heatCap(), L.visc(), L.fullMass(), L.fluid(),
                L.minFlow(), L.maxMass(), L.gas(), L.air(), L.molar(), L.matCount());
```
Keep the `AIR_DENSITY` reference working: if `BatchMarshaller.AIR_DENSITY` is referenced elsewhere, redefine it as `static final float AIR_DENSITY = LutArrays.AIR_DENSITY;`.

- [ ] **Step 4: Add `stepWorld` to the interface** `OrgeEngine.java`

Add inside the interface (keep the existing `step`):
```java
    java.util.List<ColumnResult> stepWorld(java.util.List<ColumnTask> columns,
                                           java.util.List<net.rainbowcreation.orge.material.Material> lut,
                                           double dtSeconds, int passes);
```

- [ ] **Step 5: Create the region marshaller** `RegionMarshaller.java`

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import java.util.ArrayList;
import java.util.List;

/** Packs ColumnTasks into the flat arrays orgeStepWorld expects and slices results back.
 *  Column arrays are CHUNK_N long in engine order (idx = x + 16*y + 6144*z). */
public final class RegionMarshaller {
    public static final int CHUNK_W = 16, CHUNK_H = 384, CHUNK_D = 16;
    public static final int CHUNK_N = CHUNK_W * CHUNK_H * CHUNK_D; // 98304

    public record Flat(int nCols, int[] cx, int[] cz,
                       char[] matIx, float[] mass, float[] tIn,
                       LutArrays lut) {}

    public static Flat flatten(List<ColumnTask> cols, List<Material> lut) {
        int n = cols.size();
        int[] cx = new int[n], cz = new int[n];
        char[] matIx = new char[n * CHUNK_N];
        float[] mass = new float[n * CHUNK_N];
        float[] tIn = new float[n * CHUNK_N];
        for (int c = 0; c < n; c++) {
            ColumnTask t = cols.get(c);
            if (t.matIx().length != CHUNK_N || t.mass().length != CHUNK_N || t.temperature().length != CHUNK_N)
                throw new IllegalArgumentException("column arrays must be CHUNK_N=" + CHUNK_N);
            cx[c] = t.cx(); cz[c] = t.cz();
            int base = c * CHUNK_N;
            System.arraycopy(t.matIx(), 0, matIx, base, CHUNK_N);
            System.arraycopy(t.mass(), 0, mass, base, CHUNK_N);
            System.arraycopy(t.temperature(), 0, tIn, base, CHUNK_N);
        }
        return new Flat(n, cx, cz, matIx, mass, tIn, LutArrays.pack(lut));
    }

    public static List<ColumnResult> slice(char[] matOut, float[] massOut, float[] tOut, int nCols) {
        List<ColumnResult> out = new ArrayList<>(nCols);
        for (int c = 0; c < nCols; c++) {
            int base = c * CHUNK_N;
            char[] mi = new char[CHUNK_N];
            float[] ms = new float[CHUNK_N];
            float[] tt = new float[CHUNK_N];
            System.arraycopy(matOut, base, mi, 0, CHUNK_N);
            System.arraycopy(massOut, base, ms, 0, CHUNK_N);
            System.arraycopy(tOut, base, tt, 0, CHUNK_N);
            out.add(new ColumnResult(mi, ms, tt));
        }
        return out;
    }

    private RegionMarshaller() {}
}
```

- [ ] **Step 6: Add the native binding + `stepWorld` to `NativeEngine.java`**

Add the native declaration next to `orgeStep`:
```java
    private static native double orgeStepWorld(
            int nCols, int[] cx, int[] cz,
            char[] matIx, float[] mass, float[] tIn,
            float[] lutCond, float[] lutHeatCap, float[] lutVisc,
            float[] lutFullMass, byte[] lutFluid,
            float[] lutMinFlow, float[] lutMaxMass, byte[] lutGas, byte[] lutAir,
            float[] lutMolar,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut);
```
Add the method (reuse `scratch` for the flat outputs):
```java
    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                        double dtSeconds, int passes) {
        if (columns.isEmpty()) { lastStepMillis = 0.0; return new ArrayList<>(); }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns, lut);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);
        LutArrays L = f.lut();
        lastStepMillis = orgeStepWorld(
                f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                L.cond(), L.heatCap(), L.visc(), L.fullMass(), L.fluid(),
                L.minFlow(), L.maxMass(), L.gas(), L.air(), L.molar(),
                passes, dtSeconds, tOut, massOut, matOut);
        return RegionMarshaller.slice(matOut, massOut, tOut, f.nCols());
    }
```
Ensure the imports for `ColumnTask`, `ColumnResult`, `RegionMarshaller`, `LutArrays`, `Material` are present.

- [ ] **Step 7: Implement the stub** in `StubEngine.java`

Add (echoes inputs so marshalling round-trips are testable without the native lib):
```java
    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                        double dtSeconds, int passes) {
        List<ColumnResult> out = new ArrayList<>(columns.size());
        for (ColumnTask c : columns) {
            out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), c.temperature().clone()));
        }
        lastStepMillis = 0.0;
        return out;
    }
```

- [ ] **Step 8: Write the failing marshaller test** `RegionMarshallerTest.java`

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionMarshallerTest {
    @Test
    void flattenAndSliceRoundTrips() {
        int N = RegionMarshaller.CHUNK_N;
        char[] mi = new char[N]; float[] ms = new float[N]; float[] tt = new float[N];
        mi[idx(3, 70 + 64, 5)] = 1; ms[idx(3, 70 + 64, 5)] = 1000f; tt[idx(3, 70 + 64, 5)] = 350f;
        ColumnTask a = new ColumnTask(2, -1, mi, ms, tt);
        ColumnTask b = new ColumnTask(7, 4, new char[N], new float[N], new float[N]);

        RegionMarshaller.Flat f = RegionMarshaller.flatten(List.of(a, b),
                List.of(net.rainbowcreation.orge.engine.TestMaterials.voidMat(),
                        net.rainbowcreation.orge.engine.TestMaterials.water()));
        assertEquals(2, f.nCols());
        assertArrayEquals(new int[]{2, 7}, f.cx());
        assertArrayEquals(new int[]{-1, 4}, f.cz());
        assertEquals(1, f.matIx()[idx(3, 134, 5)]);     // column 0 at engine y=134
        assertEquals(1000f, f.mass()[idx(3, 134, 5)]);

        List<ColumnResult> r = RegionMarshaller.slice(f.matIx(), f.mass(), f.tIn(), 2);
        assertEquals(1, r.get(0).matIx()[idx(3, 134, 5)]);
        assertEquals(350f, r.get(0).temperature()[idx(3, 134, 5)]);
    }

    private static int idx(int x, int y, int z) { return x + 16 * y + 6144 * z; }
}
```
Create `core/src/test/java/net/rainbowcreation/orge/engine/TestMaterials.java` with `static Material voidMat()` and `static Material water()` matching the `Material` constructor used in the repro test (`Section11LivePipelineReproTest` lines 70-89) — reuse those exact constructor calls.

- [ ] **Step 9: Run it (expect compile/round-trip pass)**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests net.rainbowcreation.orge.engine.RegionMarshallerTest
```
Expected: PASS (stub path; no native lib needed).

- [ ] **Step 10: Confirm old engine tests still pass (LUT-pack extract is behaviour-preserving)**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.*'
```
Expected: PASS. If a BatchMarshaller test compares exact LUT arrays, the extract must produce byte-identical output.

- [ ] **Step 11: Commit (MAIN `rebuild`)**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/engine/ core/src/test/java/net/rainbowcreation/orge/engine/ && \
git commit -m "feat(engine-api): stepWorld(ColumnTask) + RegionMarshaller + shared LutArrays

Adds the whole-region Java FFI (ColumnTask/ColumnResult/RegionMarshaller) and the
NativeEngine.orgeStepWorld binding + StubEngine echo. Extracts the §11 LUT pack into
LutArrays shared by the dormant BatchMarshaller and RegionMarshaller (output identical).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

---

## Task 4 (JAVA): `ColumnAssembler` — full-height column from SectionStore + blocks

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/ColumnAssembler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/ColumnAssemblerTest.java`

**Responsibility:** Given a column `(cx,cz)`, the section range `[-4,19]`, a per-section reader (`matIx` from live blocks, stored `mass`/`T` from `SectionStore`), produce one `ColumnTask` with `CHUNK_N` arrays in engine order, applying: ambient-air fill for empty cells and the single legitimate fresh-fluid seed (stored ≤ 0 ⇒ `defaultMass`).

- [ ] **Step 1: Write the failing test** `ColumnAssemblerTest.java`

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColumnAssemblerTest {
    private static int colIdx(int x, int sectionY, int sy, int z) {
        return x + 16 * (sectionY * 16 + sy + 64) + 6144 * z;
    }

    @Test
    void airFilledColumnAndFreshFluidSeed() {
        // LUT: 0 void, 1 water (defaultMass 1000), 2 air (defaultMass 1.2)
        List<Material> lut = List.of(TM.voidMat(), TM.water(), TM.air());
        // section reader: section (cx=0,sy=4) has one freshly-placed water cell (stored mass 0) at (1,2,3);
        // everything else is air (matIx=2). All other sections fully air.
        ColumnAssembler.SectionSource src = (cx, cz, sectionY) -> {
            char[] mat = new char[4096];
            float[] mass = new float[4096];
            float[] temp = new float[4096];
            java.util.Arrays.fill(mat, (char) 2);     // air
            java.util.Arrays.fill(mass, 1.2f);
            java.util.Arrays.fill(temp, 300f);
            if (sectionY == 4) {
                int s = 1 + 16 * 2 + 256 * 3;
                mat[s] = 1; mass[s] = 0f; temp[s] = 290f; // fresh water, stored mass 0 => must seed to 1000
            }
            return new ColumnAssembler.SectionCells(mat, mass, temp);
        };

        ColumnTask t = ColumnAssembler.assemble(0, 0, lut, src);
        assertEquals(RegionMarshaller.CHUNK_N, t.matIx().length);
        int wi = colIdx(1, 4, 2, 3);
        assertEquals(1, t.matIx()[wi], "water mapped to engine column index");
        assertEquals(1000f, t.mass()[wi], 1e-4, "fresh fluid (stored<=0) seeded to defaultMass");
        // a neighbouring air cell stays ambient
        int ai = colIdx(0, 0, 0, 0);
        assertEquals(2, t.matIx()[ai]);
        assertEquals(1.2f, t.mass()[ai], 1e-4);
    }

    // Minimal local material factory mirroring Section11LivePipelineReproTest constructor calls.
    static final class TM {
        static Material voidMat() { return net.rainbowcreation.orge.engine.TestMaterials.voidMat(); }
        static Material water()   { return net.rainbowcreation.orge.engine.TestMaterials.water(); }
        static Material air()     { return net.rainbowcreation.orge.engine.TestMaterials.air(); }
    }
}
```
Add `static Material air()` to `TestMaterials` (from repro test lines 70-76) if not present.

- [ ] **Step 2: Run it, expect FAIL (class missing)**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests net.rainbowcreation.orge.scheduler.ColumnAssemblerTest
```
Expected: compile failure / `ColumnAssembler` not found.

- [ ] **Step 3: Implement `ColumnAssembler.java`**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.ColumnTask;
import net.rainbowcreation.orge.engine.RegionMarshaller;
import net.rainbowcreation.orge.material.Material;
import java.util.List;

/** Builds one full-height engine column (CHUNK_N cells, idx = x + 16*y + 6144*z) from the 24 vanilla
 *  sections of a chunk column. matIx comes from live blocks (provided by the SectionSource); empty cells
 *  are ambient-air; a fluid cell whose stored mass is <= 0 (freshly placed/streamed) is seeded once to its
 *  material defaultMass. This is the ONLY legitimate seed in the pipeline. */
public final class ColumnAssembler {
    public static final int MIN_SECTION_Y = -4;
    public static final int MAX_SECTION_Y = 19; // inclusive -> 24 sections -> 384 cells
    private static final int SEC = 4096;

    /** Per-section cell view: matIx from live blocks, mass/T from SectionStore (or ambient). */
    public record SectionCells(char[] matIx, float[] mass, float[] temperature) {}

    @FunctionalInterface
    public interface SectionSource {
        SectionCells read(int cx, int cz, int sectionY);
    }

    public static ColumnTask assemble(int cx, int cz, List<Material> lut, SectionSource src) {
        int N = RegionMarshaller.CHUNK_N;
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] temp = new float[N];
        for (int sectionY = MIN_SECTION_Y; sectionY <= MAX_SECTION_Y; sectionY++) {
            SectionCells cells = src.read(cx, cz, sectionY);
            for (int z = 0; z < 16; z++) {
                for (int sy = 0; sy < 16; sy++) {
                    int engineY = sectionY * 16 + sy + 64;
                    int rowBase = 16 * engineY + 6144 * z;        // + x below
                    int secRow = 16 * sy + 256 * z;               // + x below
                    for (int x = 0; x < 16; x++) {
                        int ci = rowBase + x;
                        int si = secRow + x;
                        char mat = cells.matIx()[si];
                        float storedMass = cells.mass()[si];
                        Material m = lut.get(mat);
                        float seeded;
                        if (m.fluid() && storedMass <= 0f) {
                            seeded = m.defaultMass();              // fresh-fluid seed (once)
                        } else {
                            seeded = storedMass;
                        }
                        matIx[ci] = mat;
                        mass[ci] = seeded;
                        temp[ci] = cells.temperature()[si];
                    }
                }
            }
        }
        return new ColumnTask(cx, cz, matIx, mass, temp);
    }

    private ColumnAssembler() {}
}
```

- [ ] **Step 4: Run the test green**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests net.rainbowcreation.orge.scheduler.ColumnAssemblerTest
```
Expected: PASS.

- [ ] **Step 5: Commit (MAIN `rebuild`)**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/scheduler/ColumnAssembler.java core/src/test/java/net/rainbowcreation/orge/scheduler/ColumnAssemblerTest.java core/src/test/java/net/rainbowcreation/orge/engine/TestMaterials.java && \
git commit -m "feat(assembler): ColumnAssembler — full-height engine column from sections (air-fill + single fresh-fluid seed)

Maps 24 vanilla sections (sLocal=x+16*sy+256*z, sectionY in [-4,19]) into engine
column order (idx=x+16*y+6144*z, y=worldY+64). Ambient-air fills empty cells; a fluid
cell with stored mass<=0 is seeded once to defaultMass (the only legitimate seed).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

---

## Task 5 (JAVA): rewire `Scheduler` to the whole-region step; delete Y-seam machinery

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java` (add column-oriented snapshot/writeback hooks)
- Modify: the live `ThermalWorld` impl (find it: `grep -rl "implements ThermalWorld" core fabric neoforge`)
- Delete: `core/src/main/java/net/rainbowcreation/orge/scheduler/SeamCoStep.java`
- Delete: `core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java`
- Delete: `core/src/main/java/net/rainbowcreation/orge/scheduler/NeighborHalo.java` (if only used by the halo path — verify with grep first)
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/RegionSchedulerTest.java`

> **Approach:** this task changes orchestration. Drive it with `superpowers:systematic-debugging` discipline if the live impl is large. Keep `StepValidator`/`SpeciesMassLedger` unchanged here (region-scoping is just "feed the whole region's cells through one ledger" — Task 6/7 lean on it). Keep `MinecraftFluidReconciler` calls intact for now (Task 6 makes them air-aware).

- [ ] **Step 1: Extend `ThermalWorld` with column hooks**

Add to the interface:
```java
    /** Assemble this cycle's active+apron column set (player-sphere union at range, expanded by one ring of
     *  LOADED neighbour columns; MC-unloaded neighbours excluded). Each entry is a full-height ColumnTask
     *  plus the key it came from for write-back. */
    record ColumnBatch(List<ColumnEntry> entries, List<Material> lut) {}
    record ColumnEntry(Identifier dimension, int cx, int cz,
                       net.rainbowcreation.orge.engine.ColumnTask task) {}

    ColumnBatch snapshotColumns(int range);

    /** Persist one validated column result (T+mass) back into the 24 sections of (cx,cz) and reconcile blocks
     *  to the engine output species; record per-cell materials for next cycle; settle/wake. */
    void writeBackColumn(ColumnEntry entry, net.rainbowcreation.orge.engine.ColumnResult result);
```
(Imports: `java.util.List`, `net.rainbowcreation.orge.engine.ColumnTask/ColumnResult`, `net.rainbowcreation.orge.material.Material`, `net.minecraft...Identifier` as already used.)

- [ ] **Step 2: Implement `snapshotColumns`/`writeBackColumn` in the live `ThermalWorld`**

In the live impl, `snapshotColumns(range)`:
1. Compute the awake column set: the set of `(cx,cz)` that have any active section in the player-sphere union at `range` (reuse the existing active-set logic that `snapshot(range)` used, projected to columns).
2. **Apron:** add every loaded neighbour column `(cx±1,cz)`, `(cx,cz±1)` that is currently chunk-loaded. Exclude unloaded.
3. For each column, build a `ColumnAssembler.SectionSource` that reads live block→matIx (via `MaterialBindings`) and `SectionStore.get(key)` mass/T, and call `ColumnAssembler.assemble(cx,cz,lut,src)`.

`writeBackColumn(entry, result)`:
1. For each of the 24 sections, scatter the column result back to a `SectionData` (`FULL`) using the inverse index map (`colIdx → (sectionY, sLocal)`), clamp via `StepValidator.cleanMass`/`clean`.
2. Call the (Task-6 air-aware) reconciler to set MC blocks to `result.matIx()` species, conserving air.
3. `recordCellMaterials` with `result.matIx()`; `noteSettle` with max |Δmass|/|ΔT|; wake any neighbour column that received mass across an X/Z boundary.

> Write this against the live impl's existing helpers (the same ones `writeBack` used). Keep methods focused; if the impl file is already large, put the column scatter/gather in a small new helper class `ColumnSectionCodec` next to it.

- [ ] **Step 3: Rewire `Scheduler.submit` to the column path**

Replace the section snapshot + halo assembly + SeamCoStep expansion (`Scheduler.java:182` and the surrounding `snapshot`/`SeamCoStep.expand`/`HaloAssembler.assemble` usage) with:
```java
        ThermalWorld.ColumnBatch batch = world.snapshotColumns(worker.range());
        if (batch.entries().isEmpty()) { /* same idle handling as before */ return; }
        List<ColumnTask> input = new ArrayList<>(batch.entries().size());
        for (ThermalWorld.ColumnEntry e : batch.entries()) input.add(e.task());
        List<Material> lut = batch.lut();
        // conduction or advection per the existing cadence flags:
        List<ColumnResult> results = conduction
                ? engine.stepWorld(input, lut, STEP_DT_SECONDS, OrgeEngine.PASS_CONDUCTION)
                : engine.stepWorld(input, lut, ADVECTION_DT_SECONDS, OrgeEngine.PASS_ADVECTION);
        // stash batch.entries() + results for writeBackResults (mirror the existing pendingEntries pattern)
```
Update the `pending*`/`writeBackResults` fields to carry `List<ColumnEntry>` + `List<ColumnResult>`. In `writeBackResults`, run the region-wide `SpeciesMassLedger` over all columns' `(before=task.mass, after=result.mass, inMat=task.matIx, outMat=result.matIx)`; if `ledger.conserved()`, call `world.writeBackColumn(entry, result)` for each; else HOLD (write nothing this cycle), preserving the existing advection-HOLD semantics.

- [ ] **Step 4: Delete the dead seam classes**

```bash
cd /home/claude/ORGE && grep -rl "SeamCoStep\|HaloAssembler" core --include=*.java | grep -v test
```
Remove all production references, then:
```bash
git rm core/src/main/java/net/rainbowcreation/orge/scheduler/SeamCoStep.java \
       core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java
```
For `NeighborHalo.java` and `SeamFluxWake.java`: grep first; delete only if no remaining production reference (the old `StepTask.halo()` and per-section path may still reference `NeighborHalo` — if `BatchMarshaller`/`StepTask` are kept dormant-but-compiled, leave `NeighborHalo` and just stop using it). Document whatever you keep as dormant.

- [ ] **Step 5: Write a focused scheduler test** `RegionSchedulerTest.java`

A fake `ThermalWorld` returning a 2-column `ColumnBatch` (one column has water that should flow into the apron column), a `StubEngine` (echo) OR a tiny fake engine that moves 100 kg across the seam, asserting: `submit(advection)` calls `stepWorld` once with both columns, and on a conserved ledger `writeBackColumn` is called for each entry. Use the stub to assert wiring (no native lib).
```java
// Assert: engine.stepWorld invoked with 2 columns; writeBackColumn called twice on conservation; HOLD on a
// deliberately non-conserving fake (no writeBackColumn calls).
```

- [ ] **Step 6: Run scheduler + full core compile**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests net.rainbowcreation.orge.scheduler.RegionSchedulerTest && \
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :core:compileTestJava
```
Expected: PASS + clean compile (the deletions must not leave dangling references).

- [ ] **Step 7: Commit (MAIN `rebuild`)**

```bash
cd /home/claude/ORGE && git add -A core/src && \
git commit -m "refactor(scheduler): whole-region column step; delete SeamCoStep/HaloAssembler

Scheduler now snapshots the active+apron column set, runs one engine.stepWorld per
cycle, validates region-wide per-species conservation, and writes back per column.
Deletes the per-section halo assembly and Y-seam co-step (subsumed by contiguous-Y
columns + the loaded apron).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

---

## Task 6 (JAVA): air-aware reconciler; delete mass-fabricating reseed branches

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialChangeReseed.java` (delete fluid-reseed-on-material-change; keep nothing that fabricates mass)
- Modify: the live `MinecraftFluidReconciler` (find: `grep -rl "class MinecraftFluidReconciler" fabric neoforge core`)
- Modify: `core/.../scheduler/MassSnapshot.java` (the fresh-fluid seed now lives in `ColumnAssembler`; remove the duplicate seed so it can't double-apply)
- Test: extend the repro test in Task 7 (this task makes it possible to pass).

- [ ] **Step 1: Remove the mass-fabricating reseed**

In `MaterialChangeReseed.java`, delete the `reseeds(prior, live)` branch that resets a changed-material fluid cell to `defaultMass` (this is what fabricated mass when the reconciler flipped air→water). Keep only genuinely-needed temperature handling, if any. If the whole class becomes unused, `git rm` it and remove its call site in the live `ThermalWorld`/snapshot. The §11 broken-block→vacuum behaviour now comes from the engine (vacuum cell) + the air-aware reconciler, not a Java reseed.

- [ ] **Step 2: Make the block↔cell reconciler air-conserving**

In `MinecraftFluidReconciler`, the rule becomes: **the engine output species (`result.matIx()` per cell) is the source of truth.** For each cell whose engine species differs from the current block:
- engine says `water`, block is air ⇒ set block to water (the mass already came from the engine; do NOT add mass).
- engine says `air`/`vacuum`, block is water ⇒ set block to air (the mass left in the engine step; do NOT delete extra).
- never create or destroy mass to "make the block match" — mass is whatever the engine returned and `SectionStore` already persisted in `writeBackColumn`.
Remove any path that re-derives mass from block geometry on reconcile (that was the `+1.2/cell` source).

- [ ] **Step 3: De-duplicate the fresh-fluid seed**

In `MassSnapshot.java`, remove the `stored <= 0 ⇒ defaultMass` seed (now owned by `ColumnAssembler`). If `MassSnapshot` is no longer referenced by the column path, `git rm` it and drop its call site. Verify with `grep -rl MassSnapshot core --include=*.java | grep -v test`.

- [ ] **Step 4: Compile**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric:compileJava :neoforge:compileJava
```
Expected: clean compile across loaders (the reconciler lives loader-side).

- [ ] **Step 5: Commit (MAIN `rebuild`)**

```bash
cd /home/claude/ORGE && git add -A core fabric neoforge && \
git commit -m "fix(conserve): air-aware reconciler + delete mass-fabricating reseed

Engine output species is the source of truth on write-back; the reconciler only flips
blocks to match (never re-derives mass from geometry), conserving air. Removes the
fluid-reseed-on-material-change and the duplicate fresh-fluid seed (now in ColumnAssembler).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

---

## Task 7 (TEST): adapt the repro oracle to the new pipeline → all 4 == 1000.0; multi-column integration

**Files:**
- Modify: `core/src/test/java/net/rainbowcreation/orge/Section11LivePipelineReproTest.java` (drive `ColumnAssembler → NativeEngine.stepWorld → StepValidator → air-aware write-back`; flip asserts to `== 1000.0`)
- Create: `core/src/test/java/net/rainbowcreation/orge/WholeRegionLivePipelineTest.java`

> This test uses the REAL `NativeEngine` (loads `liborge.so` rebuilt in Task 2). It is the acceptance oracle. The discipline (spec §9): drive the real live pipeline, never hand-seed the engine.

- [ ] **Step 1: Rewrite `liveCycle()` to the column pipeline**

Replace the `MassSnapshot → MaterialChangeReseed → HaloAssembler → engine.step` body with:
```java
    private static void liveCycle(NativeEngine e, FakeColumn col, List<Material> lut) {
        // 1) assemble full-height column from the fake sections (blocks->matIx, stored mass/T)
        ColumnTask task = ColumnAssembler.assemble(col.cx, col.cz, lut, col.source());
        // 2) one whole-region step (advection)
        List<ColumnResult> res = e.stepWorld(List.of(task), lut, 0.25, OrgeEngine.PASS_ADVECTION);
        ColumnResult r = res.get(0);
        // 3) region-wide per-species conservation gate
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(r.mass(), task.mass(), task.matIx(), r.matIx(), lut);
        assertTrue(ledger.conserved(), "region step must conserve every tracked species");
        // 4) write back: persist engine mass + species as next signature (air-aware = trust engine output)
        col.persist(r);
    }
```
`FakeColumn` holds the 24-section fake store (reuse `FakeSection` cell layout, but addressed as a column). `col.source()` returns a `ColumnAssembler.SectionSource`. `col.persist(r)` writes `r.mass()`/`r.matIx()` back into the fake store by the inverse index map (no reseed, no geometry re-derivation).

- [ ] **Step 2: Flip the four asserts to exact conservation**

- `repro_placeWaterIntoAir_*`: after N cycles, total water in the column `== 1000f` (±1e-2). Replace `assertTrue(headTotal < 900f, ...)` with `assertEquals(1000f, headTotal, 1e-2, "water placed into air conserves to 1000")`.
- `repro_spreadInto2Cells_*`: replace `assertTrue(total < 990f, ...)` with `assertEquals(1000f, total, 1e-2, "spread across 2 cells conserves to 1000")`.
- `repro_crossSeamDrop_*`: this is now an **intra-column** vertical fall across the old 16-boundary (no separate sections). Replace the SeamCoStep assertion and `assertTrue(total > 1500f, ...)` with `assertEquals(1000f, total, 1e-2, "fall across the old section boundary conserves to 1000 (no doubling)")`.
- `repro_wetting_*`: replace `assertTrue(Math.abs(waterTotal - 1000f) > 5f, ...)` with `assertEquals(1000f, waterTotal, 1e-2, "wetting trough conserves to 1000 (no +1.2/cell)")`.

- [ ] **Step 3: Create the multi-column integration test** `WholeRegionLivePipelineTest.java`

Two columns `(0,0)` and `(1,0)` (the apron). Water plug at the `+X` edge of column `(0,0)` over a floor; air elsewhere. Run the column pipeline over both columns through `NativeEngine.stepWorld` for ~30 advection cycles. Assert: (a) total water across both columns `== 1000f` (±1e-2) every cycle; (b) some water ends up in column `(1,0)` (crossed the X seam); (c) total air mass conserved (sum over both columns of air-species mass unchanged ±1e-2). Drive it through the same `liveCycle`-style helper but with a 2-column `stepWorld` call.

- [ ] **Step 4: Run the oracle**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test \
  --tests net.rainbowcreation.orge.Section11LivePipelineReproTest \
  --tests net.rainbowcreation.orge.WholeRegionLivePipelineTest
```
Expected: all four repro tests and the multi-column test PASS (exact conservation). If any fail, diagnose with `superpowers:systematic-debugging` — the failure is real (do not relax the asserts).

- [ ] **Step 5: Run the whole core suite**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test
```
Expected: green. Disable/delete any obsolete tests that asserted the OLD seam/halo/reseed behaviour, noting why in the commit.

- [ ] **Step 6: Commit (MAIN `rebuild`)**

```bash
cd /home/claude/ORGE && git add -A core/src/test && \
git commit -m "test(oracle): repro pipeline -> whole-region, all 4 conserve to ==1000.0; +multi-column seam test

Drives ColumnAssembler -> NativeEngine.stepWorld -> region ledger -> air-aware writeback over
the real liborge.so. Flips the four §11 symptom asserts to exact 1000.0 and adds a 2-column
X-seam conservation test (water crosses the seam; total water + air conserved).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

---

## Task 8 (INT/MAIN): bundle `.so` + gitlink + build; full verification; push; hand to in-game gate

**Files:**
- Modify: `core/src/main/resources/natives/linux-x64/liborge.so` (already copied in Task 2)
- Modify: the `ORGE-ENGINE` gitlink (submodule pointer)

- [ ] **Step 1: Confirm ENGINE is pushed and the gitlink will point at it**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && git rev-parse HEAD && git status --short && \
git log origin/main..HEAD --oneline   # must be EMPTY (ENGINE already pushed in Task 1)
```
Expected: HEAD == the Task-1 commit, clean tree, no unpushed ENGINE commits.

- [ ] **Step 2: Re-confirm the bundled `.so` matches the rebuilt one**

```bash
md5sum /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```
Expected: identical hashes. If not, re-run Task 2.

- [ ] **Step 3: Full build + full test suite (real native lib)**

```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew build
```
Expected: BUILD SUCCESSFUL, all tests green (incl. the four repro oracles and the multi-column test on the real `.so`).

- [ ] **Step 4: Stage the bundle — `.so` + gitlink + any remaining Java — in ONE MAIN commit**

```bash
cd /home/claude/ORGE && git add ORGE-ENGINE core/src/main/resources/natives/linux-x64/liborge.so && git status --short
```
Verify: `ORGE-ENGINE` shows as a gitlink change (NOT individual ENGINE source files — never `git add` ENGINE source from MAIN), and the `.so` is staged.

- [ ] **Step 5: Commit and push `origin/rebuild`**

```bash
cd /home/claude/ORGE && git commit -m "INT: whole-region engine step — bundle liborge.so + ENGINE gitlink

Production now steps the joined active+apron region as one World per cycle
(orgeStepWorld); per-section halo/seam machinery removed. Bundles the rebuilt
liborge.so (orgeStepWorld) and bumps the ORGE-ENGINE gitlink. Repro oracle: all four
§11 symptoms conserve to ==1000.0; multi-column X-seam test green.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" && git push origin rebuild && echo PUSHED
```

- [ ] **Step 6: Hand off to the in-game gate**

Report to the user: branch `origin/rebuild` updated; ask them to in-game re-test the four scenarios (place water into air, spread along floor, drop across the old 16-boundary, long wetting trough) plus a cross-chunk-seam flow, and confirm no mass doubling / loss / +1.2 growth. The in-game re-test is the final acceptance gate (spec §9).

---

## Self-review notes (carried from the spec)

- **R1 (T1 Step 2-3):** the pre-advection `WorldSnapshot` is the make-or-break — verified first, before any Java work depends on it.
- **R2 (perf):** full-height columns are `CHUNK_N=98304` cells each; `ScratchPool` reuse keeps the flat output arrays grow-only. Y-band trimming is a deferred optimization, not in this plan.
- **R3 (T6):** the air-aware reconciler is the last place mass can be fabricated/lost; Task 7's oracle pins it to exact 1000.0.
- **R4:** `orge_kernel.hpp` + `advection_parity_test.cpp` are kept compiling but ungated (Task 1 Step 7 only confirms they still build).
- **Constraints:** ENGINE committed+pushed (Task 1) before the MAIN gitlink bump (Task 8); one MAIN commit bundles `.so`+gitlink; `JAVA_HOME=/home/claude/jdk21` on every gradle/native call; push `origin/rebuild` after every MAIN commit.
