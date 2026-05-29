# Engine Track §2 — Native Conduction Step (JNI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the no-op `StubEngine` with a real `OrgeEngine` backed by the C++ ORGE-ENGINE conduction kernel, called in-process from `:core` via JNI — a stateless, batched, single-step `step()` selectable in place of `StubEngine`.

**Architecture:** A new header-only `orge_kernel.hpp` exposes a stateless single-section stepper sharing the bit-critical math with `sim_engine.hpp`. `orge_jni.cpp` wraps it as `liborge.so` (SDL-free), accessed zero-copy via `GetPrimitiveArrayCritical`. Java side: `NeighborHalo` gains material-index faces; `BatchMarshaller` flattens `List<StepTask>` ↔ flat primitive arrays; `NativeEngine` makes one JNI call; `NativeLoader` extracts+`System.load`s the bundled native; `EngineFactory` picks `NativeEngine` if the lib loads, else `StubEngine`. Scheduler (§8) and phase change (§7) are out of scope.

**Tech Stack:** Java 21 (JNI, **no** preview — see spec for why not Panama), Minecraft 1.21.11 (Mojang mappings), Architectury multiloader, JUnit 5; C++20 (g++), header-only engine, dependency-free C++ tests.

**Spec:** `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md` — read it first.

---

## Ground rules for every task

- **Repos:** Java work in `/home/claude/ORGE` (branch `rebuild`). C++ work in the submodule `/home/claude/ORGE/ORGE-ENGINE` (branch `main`). **Commit per task; do NOT push.** C++ commits land in the submodule; the parent-repo gitlink bump is the final task.
- **Java build/test (no `java` on PATH — use this EXACT env):**
  ```
  cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH \
    GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
  ```
  - Fast unit tests: `:core:test`. Full both-loader build (slow): `build`.
  - If `/home/claude/jdk21` is gone: `curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar -xz` and point `JAVA_HOME` at the extracted dir.
- **C++ build/test:** `g++` is at `/home/claude/.local/bin/g++` (g++ 15.2, C++20). Run the suite with `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh quick`.
- **`:core` purity:** `:core` must NOT import `net.fabricmc.*` / `net.neoforged.*`. JNI loading uses only pure JDK (`System.load`, `java.io`, `java.nio`) so all new Java classes live in `:core` package `net.rainbowcreation.orge.engine`.
- **Determinism is bit-critical:** the kernel must produce results **bit-identical** to `sim_engine.hpp`. Sum the six neighbours in the fixed order `+x,-x,+y,-y,+z,-z`; route the harmonic-mean + clamp through the shared `orge::keff` / `orge::finalize_temp` helpers. Do not "tidy" the float arithmetic.
- **No void index in the ABI:** a cell or halo-neighbour whose material has `thermalConductivity <= 0` is inert / carries no flux. Void (LUT index whose material has `k <= 0`) and absent/world-edge neighbours are both encoded this way. The `List<Material> lut` passed to `step` is indexed by `matIx`/halo-mat values.
- **Type/index conventions (used in C++ and Java identically):**
  - Section cell index: `sidx(x,y,z) = x + 16*y + 256*z` (x-fastest), length 4096.
  - Halo face order (flat index): `0=negX, 1=posX, 2=negY, 3=posY, 4=negZ, 5=posZ`.
  - Face cell index: X-faces `fc = y + 16*z`; Y-faces `fc = x + 16*z`; Z-faces `fc = x + 16*y`. Each face length 256.
  - `char` (Java) ↔ `uint16_t`/`jchar` (C++) for material indices (matches existing `StepTask.matIx`).
- **Every commit message** ends with:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

## File layout

```
ORGE-ENGINE/ (submodule, branch main)
  orge_kernel.hpp        (T2 — new: stateless single-section stepper + shared keff/finalize)
  sim_engine.hpp         (T2 — modify: route math through orge_kernel.hpp helpers)
  orge_jni.cpp           (T6 — new: JNI glue over orge_kernel.hpp)
  native/build_liborge.sh(T6 — new: build liborge.so, SDL-free, static libstdc++)
  tests/kernel_test.cpp  (T2 — new: kernel physics invariants)
  tests/parity_test.cpp  (T3 — new: World-step vs kernel-with-halo bit-identity)
  tests/run_tests.sh     (T2,T3 — modify: build+run the two new tests)

core/ (:core, package net.rainbowcreation.orge.engine)
  NeighborHalo.java      (T1 — modify: add 6 matIx faces + ordered-face accessors)
  StepTask.java          (T1 — modify: doc note only)
  StubEngine.java        (exists — unchanged; ignores halo)
  OrgeEngine.java        (exists — unchanged)
  BatchMarshaller.java   (T4 — new: flatten tasks->flat arrays; slice flat->List<float[]>)
  NativeEngine.java      (T5 — new: native orgeStep + marshalling; T7 adds static loader)
  NativeLoader.java      (T7 — new: extract bundled native + System.load; os/arch tokens)
  EngineFactory.java     (T7 — new: NativeEngine if lib loads else StubEngine)
  core/src/main/resources/natives/linux-x64/liborge.so   (T6 — built artifact, committed)
Tests: core/src/test/java/net/rainbowcreation/orge/engine/
  NeighborHaloTest.java        (T1)
  BatchMarshallerTest.java     (T4)
  BatchTestSupport.java        (T4 — shared test builders, reused in T8)
  NativeLoaderTest.java        (T7 — pure os/arch token mapping)
  NativeEngineTest.java        (T8 — integration, real .so)
  EngineFactoryTest.java       (T8)
```

---

## Task 1 — Extend `NeighborHalo` with material-index faces

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NeighborHalo.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StepTask.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/NeighborHaloTest.java`

The boundary `k_eff` needs the neighbour's conductivity, so the halo must carry material indices alongside temperatures (DESIGN §2's "temps + material indices"). `NeighborHalo` is referenced only by `StepTask`; `StubEngine` ignores it — so this is a low-risk shape change.

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeighborHaloTest {

    private static float[] tf(float v) { float[] a = new float[NeighborHalo.FACE_CELLS]; java.util.Arrays.fill(a, v); return a; }
    private static char[]  mf(char v)  { char[]  a = new char[NeighborHalo.FACE_CELLS]; java.util.Arrays.fill(a, v); return a; }

    @Test
    void faceCellsIs256() {
        assertEquals(256, NeighborHalo.FACE_CELLS);
    }

    @Test
    void orderedAccessorsFollowNegPosXYZ() {
        NeighborHalo h = new NeighborHalo(
                tf(1), tf(2), tf(3), tf(4), tf(5), tf(6),
                mf((char) 1), mf((char) 2), mf((char) 3), mf((char) 4), mf((char) 5), mf((char) 6));

        float[][] temps = h.tempFaces();
        char[][]  mats  = h.matFaces();

        assertEquals(6, temps.length);
        assertEquals(6, mats.length);
        // Order must be negX, posX, negY, posY, negZ, posZ.
        for (int f = 0; f < 6; f++) {
            assertEquals((float) (f + 1), temps[f][0], "temp face " + f);
            assertEquals((char) (f + 1), mats[f][0], "mat face " + f);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.NeighborHaloTest'`
Expected: COMPILE FAILURE — `NeighborHalo` constructor takes 6 args, not 12; `tempFaces()`/`matFaces()` undefined.

- [ ] **Step 3: Rewrite `NeighborHalo.java`**

```java
package net.rainbowcreation.orge.engine;

/**
 * The one-cell neighbour halo around a section — one 16×16 face per side
 * (DESIGN.md §2/§8). Each face carries the neighbour cells' temperatures
 * <b>and</b> material indices: the boundary conductivity {@code k_eff} is a
 * harmonic mean of the two cells' conductivities, so the solver needs the
 * neighbour's material to look it up in the LUT.
 *
 * <p>Each face array is length {@link #FACE_CELLS} (16×16). Faces are named by the
 * axis direction they sit on. A halo cell whose material has conductivity ≤ 0
 * (e.g. void) carries no flux — which is also how an absent / world-edge
 * neighbour is encoded.</p>
 *
 * <p>Face cell index: X-faces {@code y + 16*z}, Y-faces {@code x + 16*z},
 * Z-faces {@code x + 16*y}.</p>
 */
public record NeighborHalo(
        float[] negXT, float[] posXT, float[] negYT, float[] posYT, float[] negZT, float[] posZT,
        char[]  negXM, char[]  posXM, char[]  negYM, char[]  posYM, char[]  negZM, char[]  posZM
) {
    public static final int FACE_CELLS = 256;

    /** Temperature faces in the canonical order negX, posX, negY, posY, negZ, posZ. */
    public float[][] tempFaces() {
        return new float[][]{negXT, posXT, negYT, posYT, negZT, posZT};
    }

    /** Material-index faces in the canonical order negX, posX, negY, posY, negZ, posZ. */
    public char[][] matFaces() {
        return new char[][]{negXM, posXM, negYM, posYM, negZM, posZM};
    }
}
```

- [ ] **Step 4: Add a doc note to `StepTask.java`**

In `StepTask.java`, update the `@param halo` line to:
```java
 * @param halo        one-cell neighbour temperatures + material indices around the
 *                    section (6 faces; see {@link NeighborHalo})
```
(No structural change — `StepTask` already holds a `NeighborHalo halo`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.NeighborHaloTest'`
Expected: PASS. (`StubEngine` still compiles — it only reads `task.temperature()`.)

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/engine/NeighborHalo.java core/src/main/java/net/rainbowcreation/orge/engine/StepTask.java core/src/test/java/net/rainbowcreation/orge/engine/NeighborHaloTest.java
git commit -m "$(printf 'feat(engine): halo carries material-index faces for boundary k_eff\n\nNeighborHalo now holds six char[256] matIx faces alongside the temp\nfaces, with ordered tempFaces()/matFaces() accessors. Needed because\nboundary conductivity is a harmonic mean requiring the neighbour material.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 2 — C++ `orge_kernel.hpp` + share math with `sim_engine.hpp` + kernel invariant tests

**Files:**
- Create: `ORGE-ENGINE/orge_kernel.hpp`
- Modify: `ORGE-ENGINE/sim_engine.hpp` (route math through the shared helpers)
- Create: `ORGE-ENGINE/tests/kernel_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (build + run kernel_test)

- [ ] **Step 1: Write the failing test (`tests/kernel_test.cpp`)**

```cpp
// Build: g++ -std=c++20 -O2 -I. tests/kernel_test.cpp -o build/kernel_test -pthread
//
// Physics invariants for the stateless single-section stepper (orge_kernel.hpp).
// Reuses the th:: assertion harness (World-independent macros).
#include "test_harness.hpp"
#include "../orge_kernel.hpp"
#include <vector>
#include <cstring>

using namespace th;
using orge::SEC; using orge::SEC_N; using orge::FACE; using orge::FACES; using orge::sidx;

namespace {

// A single-section input: geometry + temps + an all-void halo (closed system).
struct Section {
    std::vector<uint16_t> matIx = std::vector<uint16_t>(SEC_N, 0);
    std::vector<float>    mass  = std::vector<float>(SEC_N, 0.0f);
    std::vector<float>    Tin   = std::vector<float>(SEC_N, 0.0f);
    std::vector<float>    haloT = std::vector<float>(FACES*FACE, 0.0f);
    std::vector<uint16_t> haloMat = std::vector<uint16_t>(FACES*FACE, 0); // 0 = void
};

// LUT index 0 = void (k=0). index 1 = stable solid (heatCap=500, k=100).
struct Lut {
    std::vector<float> cond    = {0.0f, 100.0f};
    std::vector<float> heatCap = {0.0f, 500.0f};
    orge::MatLUT view() const { return orge::MatLUT{cond.data(), heatCap.data(), (int)cond.size()}; }
};

void fill(Section& s, uint16_t mat, float T, float mass) {
    for (int i = 0; i < SEC_N; ++i) { s.matIx[i] = mat; s.Tin[i] = T; s.mass[i] = (mat==0)?0.0f:mass; }
}

double energy(const Section& s, const Lut& l) {
    double E = 0.0;
    for (int i = 0; i < SEC_N; ++i) {
        uint16_t m = s.matIx[i];
        if (l.cond[m] <= 0.0f) continue;
        E += (double)s.mass[i] * (double)l.heatCap[m] * (double)s.Tin[i];
    }
    return E;
}

std::vector<float> run(const Section& s, const Lut& l, float dt, int steps) {
    Lut lut = l;
    std::vector<float> cur = s.Tin;
    std::vector<float> out(SEC_N, 0.0f);
    orge::MatLUT lv = lut.view();
    for (int n = 0; n < steps; ++n) {
        orge::step_section_with_halo(s.matIx.data(), s.mass.data(), cur.data(),
                                     s.haloT.data(), s.haloMat.data(), lv, dt, out.data());
        cur = out;
    }
    return cur;
}

} // namespace

static void test_void_inert() {
    Section s; Lut l; fill(s, /*void*/0, 0.0f, 0.0f);
    s.Tin[sidx(3,3,3)] = 4321.0f; // scribble into a void cell
    auto r = run(s, l, 1.0f, 50);
    float got = r[sidx(3,3,3)], want = 4321.0f;
    TH_CHECK_MSG(std::memcmp(&got, &want, sizeof(float)) == 0,
                 "void cell must copy through bit-exact");
}

static void test_uniform_is_fixed_point() {
    Section s; Lut l; fill(s, 1, 300.0f, 1000.0f);
    auto r = run(s, l, 1.0f, 100);
    for (int i = 0; i < SEC_N; ++i) TH_CHECK_CLOSE(r[i], 300.0, 1e-6);
}

static void test_heat_flows_hot_to_cold() {
    Section s; Lut l; fill(s, 1, 300.0f, 1000.0f);
    int hot = sidx(8,8,8), nb = sidx(9,8,8);
    s.Tin[hot] = 1000.0f;
    auto r = run(s, l, 1.0f, 1);
    TH_CHECK_MSG(r[hot] < 1000.0f, "hot cell cools");
    TH_CHECK_MSG(r[nb]  > 300.0f,  "neighbour warms");
}

static void test_energy_conserved_closed_section() {
    Section s; Lut l; fill(s, 1, 300.0f, 1000.0f);
    s.Tin[sidx(2,2,2)] = 900.0f; s.Tin[sidx(13,13,13)] = 100.0f; // gradients, halo all void => closed
    double E0 = energy(s, l);
    auto r = run(s, l, 1.0f, 200);
    Section after = s; after.Tin = r;
    TH_CHECK_CLOSE(energy(after, l), E0, 1e-4);
}

static void test_insulator_blocks_flux() {
    // matIx 2 = insulator (k=0) but with mass/heatCap; a hot insulator neighbour
    // must not heat the solid.
    Section s; Lut l; l.cond = {0.0f, 100.0f, 0.0f}; l.heatCap = {0.0f, 500.0f, 500.0f};
    fill(s, 1, 300.0f, 1000.0f);
    int cell = sidx(0,8,8);
    // -x neighbour of cell (0,8,8) is the negX halo at fc = y + 16*z = 8 + 16*8
    int fc = 8 + 16*8;
    s.haloMat[orge::NEGX*FACE + fc] = 2;       // insulator
    s.haloT  [orge::NEGX*FACE + fc] = 5000.0f; // very hot
    auto r = run(s, l, 1.0f, 1);
    TH_CHECK_CLOSE(r[cell], 300.0, 1e-6);
}

static void test_determinism() {
    Section s; Lut l; fill(s, 1, 300.0f, 1000.0f);
    s.Tin[sidx(4,4,4)] = 1200.0f;
    auto a = run(s, l, 1.0f, 37);
    auto b = run(s, l, 1.0f, 37);
    bool same = (std::memcmp(a.data(), b.data(), SEC_N*sizeof(float)) == 0);
    TH_CHECK_MSG(same, "identical setup + steps => bit-identical field");
}

int main() {
    run("void cells are inert", test_void_inert);
    run("uniform field is a fixed point", test_uniform_is_fixed_point);
    run("heat flows hot->cold", test_heat_flows_hot_to_cold);
    run("energy conserved in a closed section", test_energy_conserved_closed_section);
    run("insulator neighbour blocks flux", test_insulator_blocks_flux);
    run("determinism", test_determinism);
    return report();
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -I. tests/kernel_test.cpp -o build/kernel_test -pthread`
Expected: COMPILE FAILURE — `orge_kernel.hpp` does not exist.

- [ ] **Step 3: Create `orge_kernel.hpp`**

```cpp
#pragma once
#include <algorithm>
#include <cstdint>

// Stateless, SDL-free single-section conduction stepper, and the bit-critical
// math shared with sim_engine.hpp. Header-only, no dependencies.
namespace orge {

constexpr int SEC   = 16;
constexpr int SEC_N = SEC*SEC*SEC;  // 4096
constexpr int FACE  = SEC*SEC;      // 256
constexpr int FACES = 6;

// Flat halo face order.
enum Face { NEGX = 0, POSX = 1, NEGY = 2, POSY = 3, NEGZ = 4, POSZ = 5 };

inline int sidx(int x, int y, int z) { return x + y*SEC + z*SEC*SEC; }

// Harmonic-mean conductivity; zero if either side is non-conductive (void /
// insulator / absent neighbour). Single source for sim_engine.hpp too.
inline float keff(float k1, float k2) {
    return (k1 <= 0.0f || k2 <= 0.0f) ? 0.0f : 2.0f*k1*k2/(k1+k2);
}

// Forward-Euler finalize with the [0,6000] K clamp. Single source.
inline float finalize_temp(float Tc, float Cth, float dt, float dT) {
    float Tn = Tc + (dt/Cth)*dT;
    if      (Tn <    0.0f) Tn = 0.0f;
    else if (Tn > 6000.0f) Tn = 6000.0f;
    return Tn;
}

struct MatLUT { const float* cond; const float* heatCap; int count; };

// One conduction step over a single 16^3 section. Interior neighbours come from
// the section's own cells; the six boundary faces come from the halo (temps +
// material indices). Neighbours are summed in the fixed order +x,-x,+y,-y,+z,-z
// to stay bit-identical to sim_engine.hpp's simulate_section_16x16x16.
inline void step_section_with_halo(
    const uint16_t* matIx, const float* mass, const float* Tin,
    const float* haloT, const uint16_t* haloMat,
    const MatLUT& lut, float dt, float* Tout)
{
    constexpr float inv_dx2 = 1.0f;
    for (int z = 0; z < SEC; ++z) {
        for (int y = 0; y < SEC; ++y) {
            for (int x = 0; x < SEC; ++x) {
                const int i = sidx(x, y, z);
                const uint16_t mix = matIx[i];
                const float k1  = lut.cond[mix];
                const float Cth = std::max(1e-8f, mass[i] * lut.heatCap[mix]);
                const float Tc  = Tin[i];

                float dT = 0.0f;
                auto flux = [&](int nx, int ny, int nz, int face, int fc) {
                    float Tn; uint16_t mn;
                    if (nx >= 0 && nx < SEC && ny >= 0 && ny < SEC && nz >= 0 && nz < SEC) {
                        const int j = sidx(nx, ny, nz); Tn = Tin[j]; mn = matIx[j];
                    } else {
                        const int o = face*FACE + fc; Tn = haloT[o]; mn = haloMat[o];
                    }
                    dT += keff(k1, lut.cond[mn]) * (Tn - Tc) * inv_dx2;
                };
                flux(x+1, y, z, POSX, y + SEC*z);
                flux(x-1, y, z, NEGX, y + SEC*z);
                flux(x, y+1, z, POSY, x + SEC*z);
                flux(x, y-1, z, NEGY, x + SEC*z);
                flux(x, y, z+1, POSZ, x + SEC*y);
                flux(x, y, z-1, NEGZ, x + SEC*y);

                Tout[i] = finalize_temp(Tc, Cth, dt, dT);
            }
        }
    }
}

} // namespace orge
```

- [ ] **Step 4: Route `sim_engine.hpp` math through the shared helpers**

At the top of `sim_engine.hpp`, after the existing `#include`s, add:
```cpp
#include "orge_kernel.hpp"
```
In `simulate_section_16x16x16`, replace the k_eff block (currently lines ~187–197):
```cpp
                    const Material& mn = mats.byIx(nb[n].mix);
                    const float k1 = m.thermalConductivity, k2 = mn.thermalConductivity;
                    float k_eff = 0.0f;
                    //if (k1 > 0.0f && k2 > 0.0f) k_eff = 2.0f * k1 * k2 / (k1 + k2);
                    //else                        k_eff = std::max(k1, k2);
                    if (k1 <= 0.0f || k2 <= 0.0f) {
                        k_eff = 0.0f;
                    } else {
                        k_eff = 2.0f * k1 * k2 / (k1 + k2);
                    }
                    dT += (k_eff * (nb[n].T - Tc)) * inv_dx2;
```
with:
```cpp
                    const Material& mn = mats.byIx(nb[n].mix);
                    dT += orge::keff(m.thermalConductivity, mn.thermalConductivity)
                          * (nb[n].T - Tc) * inv_dx2;
```
And replace the finalize block (currently lines ~200–203):
```cpp
                float Tnew = Tc + (dt_seconds / Cth) * dT;
                if      (Tnew <   0.0f) Tnew = 0.0f;
                else if (Tnew > 6000.0f) Tnew = 6000.0f;
                C.T_next[i] = Tnew;
```
with:
```cpp
                C.T_next[i] = orge::finalize_temp(Tc, Cth, dt_seconds, dT);
```
This is behaviour-preserving; the existing correctness/stress suite is the regression guard.

- [ ] **Step 5: Wire the two new tests into `run_tests.sh`**

In `tests/run_tests.sh`, after the `echo "==> Building stress_test"` block, add:
```bash
echo "==> Building kernel_test"
$CXX $STD -O2 -g $INC tests/kernel_test.cpp -o build/kernel_test -pthread

echo "==> Building parity_test"
$CXX $STD -O2 -g $INC tests/parity_test.cpp -o build/parity_test -pthread
```
And after the `./build/stress_test ...` run block, add:
```bash
echo
echo "==> Running kernel_test"
./build/kernel_test || RC=$?

echo
echo "==> Running parity_test"
./build/parity_test || RC=$?
```
(Note: `parity_test.cpp` is created in Task 3. If running Task 2 in isolation, temporarily build/run only `kernel_test`; the committed `run_tests.sh` references both and both land within this plan.)

- [ ] **Step 6: Build and run kernel_test + the existing suite (no regression)**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE
g++ -std=c++20 -O2 -I. tests/kernel_test.cpp -o build/kernel_test -pthread && ./build/kernel_test
g++ -std=c++20 -O2 -I. tests/correctness_test.cpp -o build/correctness_test -pthread && ./build/correctness_test
```
Expected: both print `passed: N   failed: 0`. The correctness run proves the `sim_engine.hpp` refactor changed nothing.

- [ ] **Step 7: Commit (in the submodule)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && git add orge_kernel.hpp sim_engine.hpp tests/kernel_test.cpp tests/run_tests.sh
git commit -m "$(printf 'feat(kernel): stateless single-section conduction stepper\n\nNew orge_kernel.hpp: step_section_with_halo() + shared keff/finalize_temp\nhelpers. sim_engine.hpp now routes its bit-critical math through the same\nhelpers (behaviour-preserving; correctness suite unchanged). Adds kernel\nphysics-invariant tests.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 3 — C++ parity test: stateful World step ≡ stateless kernel-with-halo

**Files:**
- Create: `ORGE-ENGINE/tests/parity_test.cpp`

This is the linchpin: it proves the kernel is a faithful refactor of the engine. Build a single chunk with two stacked sections (sy=0, sy=1), step it with the engine, then step the same two sections through the kernel feeding each the other's boundary plane as a halo (all other faces void = world edge). Results must be **bit-identical**.

- [ ] **Step 1: Write the failing test**

```cpp
// Build: g++ -std=c++20 -O2 -I. tests/parity_test.cpp -o build/parity_test -pthread
//
// Parity: one engine frame over a 2-section chunk must equal the stateless
// kernel stepping the same two sections with cross-section halos, bit-for-bit.
#include "test_harness.hpp"
#include "../orge_kernel.hpp"
#include <vector>
#include <cstring>

using namespace th;
using orge::SEC; using orge::SEC_N; using orge::FACE; using orge::FACES; using orge::sidx;

// Engine chunk index for section sy, local (x,yl,z).
static int cidx(int x, int yl, int z, int sy) { return idx(x, sy*SEC + yl, z); }

// Build the LUT arrays the kernel needs from the engine's MaterialLUT.
static void lut_arrays(const MaterialLUT& m, std::vector<float>& cond, std::vector<float>& hc) {
    cond.resize(m.size()); hc.resize(m.size());
    for (size_t i = 0; i < m.size(); ++i) { cond[i] = m.byIx((uint16_t)i).thermalConductivity; hc[i] = m.byIx((uint16_t)i).heatCapacity; }
}

static void test_two_section_parity() {
    // ---- engine world: one chunk, sections 0 and 1 loaded with a solid + gradients
    World w; seed_void(w);
    uint16_t solid = add_material(w, /*heatCap*/500.0f, /*k*/100.0f, /*mass*/1000.0f);
    Chunk* C = w.ensureChunk(0, 0);
    C->void_ix = 0;
    fill_section_with(*C, solid, 300.0f, 0, w.materials);
    fill_section_with(*C, solid, 350.0f, 1, w.materials);
    C->T_curr[cidx(4,4,4,0)] = 1500.0f; C->T_next[cidx(4,4,4,0)] = 1500.0f;
    C->T_curr[cidx(9,2,11,1)] = 50.0f;  C->T_next[cidx(9,2,11,1)] = 50.0f;
    recomputeSectionLoaded(*C);

    // ---- capture initial temps + build kernel inputs for both sections
    std::vector<float> cond, hc; lut_arrays(w.materials, cond, hc);
    orge::MatLUT lv{cond.data(), hc.data(), (int)cond.size()};

    auto buildSection = [&](int sy, std::vector<uint16_t>& mat, std::vector<float>& mass, std::vector<float>& Tin) {
        mat.assign(SEC_N, 0); mass.assign(SEC_N, 0.0f); Tin.assign(SEC_N, 0.0f);
        for (int z=0; z<SEC; ++z) for (int yl=0; yl<SEC; ++yl) for (int x=0; x<SEC; ++x) {
            int i = sidx(x,yl,z), ci = cidx(x,yl,z,sy);
            mat[i] = C->matIx[ci]; mass[i] = C->mass_kg[ci]; Tin[i] = C->T_curr[ci];
        }
    };
    std::vector<uint16_t> mat0, mat1; std::vector<float> mass0, mass1, Tin0, Tin1;
    buildSection(0, mat0, mass0, Tin0);
    buildSection(1, mat1, mass1, Tin1);

    // Halos: section 0's +y face = section 1's bottom plane (yl=0); section 1's
    // -y face = section 0's top plane (yl=15). Everything else is void (world edge).
    std::vector<float> haloT0(FACES*FACE, 0.0f), haloT1(FACES*FACE, 0.0f);
    std::vector<uint16_t> haloM0(FACES*FACE, 0), haloM1(FACES*FACE, 0);
    for (int z=0; z<SEC; ++z) for (int x=0; x<SEC; ++x) {
        int fc = x + SEC*z;
        // section 0 +y  <- section 1 yl=0
        haloT0[orge::POSY*FACE + fc] = Tin1[sidx(x,0,z)];
        haloM0[orge::POSY*FACE + fc] = mat1[sidx(x,0,z)];
        // section 1 -y  <- section 0 yl=15
        haloT1[orge::NEGY*FACE + fc] = Tin0[sidx(x,SEC-1,z)];
        haloM1[orge::NEGY*FACE + fc] = mat0[sidx(x,SEC-1,z)];
    }

    std::vector<float> out0(SEC_N), out1(SEC_N);
    orge::step_section_with_halo(mat0.data(), mass0.data(), Tin0.data(), haloT0.data(), haloM0.data(), lv, 1.0f, out0.data());
    orge::step_section_with_halo(mat1.data(), mass1.data(), Tin1.data(), haloT1.data(), haloM1.data(), lv, 1.0f, out1.data());

    // ---- engine: exactly one frame
    step_frame(w, 1.0f);

    // ---- compare bit-for-bit
    int mism = 0;
    for (int z=0; z<SEC; ++z) for (int yl=0; yl<SEC; ++yl) for (int x=0; x<SEC; ++x) {
        float e0 = C->T_curr[cidx(x,yl,z,0)], k0 = out0[sidx(x,yl,z)];
        float e1 = C->T_curr[cidx(x,yl,z,1)], k1 = out1[sidx(x,yl,z)];
        if (std::memcmp(&e0,&k0,sizeof(float)) != 0) ++mism;
        if (std::memcmp(&e1,&k1,sizeof(float)) != 0) ++mism;
    }
    TH_CHECK_MSG(mism == 0, "kernel-with-halo must match the engine frame bit-for-bit");
}

int main() {
    run("two-section World/kernel parity", test_two_section_parity);
    return report();
}
```

- [ ] **Step 2: Run it to verify it fails first, then passes**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -I. tests/parity_test.cpp -o build/parity_test -pthread && ./build/parity_test
```
Expected first compile/run: if any index/halo mapping is wrong it reports mismatches (FAIL). Fix the halo wiring until it prints `passed: 1   failed: 0`. The math itself is already shared, so a green result confirms the halo/index conventions match the engine.

- [ ] **Step 3: Run the full C++ suite**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh quick`
Expected: `ALL TESTS PASSED` (correctness, stress, kernel, parity).

- [ ] **Step 4: Commit (in the submodule)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && git add tests/parity_test.cpp
git commit -m "$(printf 'test(kernel): World-step vs kernel-with-halo bit-identity parity\n\nProves step_section_with_halo is a faithful refactor of the engine: a\n2-section chunk stepped by the engine equals the same sections stepped\nby the kernel with cross-section halos, bit-for-bit.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 4 — Java `BatchMarshaller` (pure flatten/slice) + tests

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java`
- Create: `core/src/test/java/net/rainbowcreation/orge/engine/BatchTestSupport.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerTest.java`

Pure, native-free logic: flatten `List<StepTask>` into the contiguous primitive arrays the JNI call expects, and slice the flat `tOut` back into `List<float[]>`. Fully unit-testable without the native library.

- [ ] **Step 1: Write the shared test support (`BatchTestSupport.java`)**

```java
package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Arrays;
import java.util.List;

/** Builders shared by the engine tests. */
final class BatchTestSupport {
    static final int SEC_N = 4096;
    static final int FACE = 256;

    private BatchTestSupport() {}

    static Material material(String id, float cond, float heatCap, float defaultMass) {
        return new Material(Identifier.parse(id), cond, heatCap, /*viscosity*/0f, defaultMass,
                /*molarMass*/0.05f, /*boiling*/9999f, /*freezing*/0f, null, null, null);
    }

    /** void (k=0) at index 0, a stable solid (k=100, heatCap=500) at index 1. */
    static List<Material> stdLut() {
        return List.of(material("orge:void", 0f, 0f, 0f),
                       material("orge:solid", 100f, 500f, 1000f));
    }

    static char[] fillChar(int len, char v)  { char[] a = new char[len];  Arrays.fill(a, v); return a; }
    static float[] fillFloat(int len, float v){ float[] a = new float[len]; Arrays.fill(a, v); return a; }

    /** An all-void halo (no flux on any face). */
    static NeighborHalo voidHalo() {
        return new NeighborHalo(
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f),
                fillFloat(FACE, 0f), fillFloat(FACE, 0f), fillFloat(FACE, 0f),
                fillChar(FACE, (char) 0), fillChar(FACE, (char) 0), fillChar(FACE, (char) 0),
                fillChar(FACE, (char) 0), fillChar(FACE, (char) 0), fillChar(FACE, (char) 0));
    }

    /** A uniform solid (matIx 1) section at temperature T, default mass, void halo. */
    static StepTask solidSection(SubchunkKey key, float T) {
        return new StepTask(key,
                fillChar(SEC_N, (char) 1), fillFloat(SEC_N, 1000f), fillFloat(SEC_N, T), voidHalo());
    }
}
```

- [ ] **Step 2: Write the failing test (`BatchMarshallerTest.java`)**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMarshallerTest {

    @Test
    void flattenLaysOutSectionsContiguously() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        StepTask b = solidSection(new SubchunkKey(1, 0, 0), 400f);
        BatchMarshaller.Flat f = BatchMarshaller.flatten(List.of(a, b), stdLut());

        assertEquals(2, f.n());
        assertEquals(2 * SEC_N, f.tIn().length);
        assertEquals(300f, f.tIn()[0]);              // section 0
        assertEquals(400f, f.tIn()[SEC_N]);          // section 1 starts at SEC_N
        assertEquals(2 * 6 * FACE, f.haloT().length);
        // LUT arrays mirror the material list order.
        assertArrayEquals(new float[]{0f, 100f}, f.lutCond());
        assertArrayEquals(new float[]{0f, 500f}, f.lutHeatCap());
    }

    @Test
    void sliceReconstructsPerSectionArrays() {
        float[] flat = new float[2 * SEC_N];
        java.util.Arrays.fill(flat, 0, SEC_N, 1f);
        java.util.Arrays.fill(flat, SEC_N, 2 * SEC_N, 2f);
        List<float[]> out = BatchMarshaller.slice(flat, 2);
        assertEquals(2, out.size());
        assertEquals(SEC_N, out.get(0).length);
        assertEquals(1f, out.get(0)[0]);
        assertEquals(2f, out.get(1)[0]);
    }

    @Test
    void rejectsWrongTemperatureLength() {
        StepTask bad = new StepTask(new SubchunkKey(0, 0, 0),
                fillChar(SEC_N, (char) 1), fillFloat(SEC_N, 1000f),
                fillFloat(SEC_N - 1, 300f), voidHalo());
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(bad), stdLut()));
    }

    @Test
    void rejectsMaterialIndexOutsideLut() {
        StepTask bad = solidSection(new SubchunkKey(0, 0, 0), 300f);
        bad.matIx()[0] = (char) 99; // LUT has only 2 entries
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(bad), stdLut()));
    }

    @Test
    void rejectsEmptyLut() {
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        assertThrows(IllegalArgumentException.class,
                () -> BatchMarshaller.flatten(List.of(a), List.<Material>of()));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.BatchMarshallerTest'`
Expected: COMPILE FAILURE — `BatchMarshaller` does not exist.

- [ ] **Step 4: Write `BatchMarshaller.java`**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a batch of {@link StepTask}s into the contiguous primitive arrays the
 * native {@code orgeStep} expects, and slices the flat result back into per-section
 * arrays. Pure (no native dependency) so it is unit-testable on its own.
 *
 * <p>Layout (see plan ground rules): per section, {@code matIx/mass/temperature}
 * are length {@value #SEC_N} (x-fastest); halos are six {@value #FACE}-cell faces
 * in the order negX, posX, negY, posY, negZ, posZ.</p>
 */
final class BatchMarshaller {

    static final int SEC_N = 4096;
    static final int FACE = 256;
    static final int FACES = 6;

    private BatchMarshaller() {}

    /** Flat inputs for one {@code orgeStep} call. {@code matCount} = LUT size. */
    record Flat(int n, char[] matIx, float[] mass, float[] tIn,
                float[] haloT, char[] haloMat,
                float[] lutCond, float[] lutHeatCap, int matCount) {}

    static Flat flatten(List<StepTask> tasks, List<Material> lut) {
        int m = lut.size();
        if (m == 0) throw new IllegalArgumentException("material LUT is empty");

        int n = tasks.size();
        char[] matIx = new char[n * SEC_N];
        float[] mass = new float[n * SEC_N];
        float[] tIn = new float[n * SEC_N];
        float[] haloT = new float[n * FACES * FACE];
        char[] haloMat = new char[n * FACES * FACE];

        for (int s = 0; s < n; s++) {
            StepTask t = tasks.get(s);
            requireLen(t.matIx(), SEC_N, "matIx", s);
            requireLen(t.mass(), SEC_N, "mass", s);
            requireLen(t.temperature(), SEC_N, "temperature", s);

            int base = s * SEC_N;
            System.arraycopy(t.matIx(), 0, matIx, base, SEC_N);
            System.arraycopy(t.mass(), 0, mass, base, SEC_N);
            System.arraycopy(t.temperature(), 0, tIn, base, SEC_N);
            for (int c = 0; c < SEC_N; c++) requireMat(matIx[base + c], m, "matIx", s);

            float[][] tf = t.halo().tempFaces();
            char[][] mf = t.halo().matFaces();
            for (int f = 0; f < FACES; f++) {
                requireLen(tf[f], FACE, "halo temp face " + f, s);
                requireLen(mf[f], FACE, "halo mat face " + f, s);
                int off = (s * FACES + f) * FACE;
                System.arraycopy(tf[f], 0, haloT, off, FACE);
                System.arraycopy(mf[f], 0, haloMat, off, FACE);
                for (int c = 0; c < FACE; c++) requireMat(haloMat[off + c], m, "halo mat", s);
            }
        }

        float[] cond = new float[m];
        float[] heatCap = new float[m];
        for (int i = 0; i < m; i++) {
            Material mat = lut.get(i);
            cond[i] = mat.thermalConductivity();
            heatCap[i] = mat.heatCapacity();
        }
        return new Flat(n, matIx, mass, tIn, haloT, haloMat, cond, heatCap, m);
    }

    static List<float[]> slice(float[] tOut, int n) {
        List<float[]> out = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            float[] sec = new float[SEC_N];
            System.arraycopy(tOut, s * SEC_N, sec, 0, SEC_N);
            out.add(sec);
        }
        return out;
    }

    private static void requireLen(Object arr, int len, String what, int section) {
        int actual = java.lang.reflect.Array.getLength(arr);
        if (actual != len)
            throw new IllegalArgumentException(
                    "section " + section + " " + what + " length " + actual + " != " + len);
    }

    private static void requireMat(char ix, int matCount, String what, int section) {
        if (ix >= matCount)
            throw new IllegalArgumentException(
                    "section " + section + " " + what + " index " + (int) ix + " >= LUT size " + matCount);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.BatchMarshallerTest'`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/engine/BatchMarshaller.java core/src/test/java/net/rainbowcreation/orge/engine/BatchMarshallerTest.java core/src/test/java/net/rainbowcreation/orge/engine/BatchTestSupport.java
git commit -m "$(printf 'feat(engine): BatchMarshaller flattens step batch to flat native arrays\n\nPure flatten(List<StepTask>,List<Material>) -> contiguous matIx/mass/temp\n+ halo + LUT arrays, and slice(float[],n) back to per-section arrays, with\nlength + material-index validation. Native-free and unit-tested.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 5 — Java `NativeEngine` (native declaration + marshalling)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`

Declares the `native` method and wires `BatchMarshaller` around it. **No static loader yet** (Task 7 adds it) — the class compiles, but the native method is unresolved until the library is loaded. No new test here (it needs the `.so`, built in Task 6; integration tests are Task 8).

- [ ] **Step 1: Write `NativeEngine.java`**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link OrgeEngine} backed by the native liborge conduction kernel, called
 * in-process via JNI (DESIGN.md §2; the spec records why JNI and not Panama).
 * Stateless and batched: one {@link #orgeStep} call per {@link #step} invocation.
 */
public final class NativeEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;

    /**
     * One conduction step over a flattened batch. All arrays are flat (see
     * {@link BatchMarshaller}); {@code tOut} (length n·4096) receives the new
     * temperatures. Returns the native compute time in milliseconds.
     */
    private static native double orgeStep(
            int n,
            char[] matIx, float[] mass, float[] tIn,
            float[] haloT, char[] haloMat,
            float[] lutCond, float[] lutHeatCap,
            double dtSeconds,
            float[] tOut);

    @Override
    public List<float[]> step(List<StepTask> tasks, List<Material> lut, double dtSeconds) {
        if (tasks.isEmpty()) {
            lastStepMillis = 0.0;
            return new ArrayList<>();
        }
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        float[] tOut = new float[f.n() * BatchMarshaller.SEC_N];
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.lutCond(), f.lutHeatCap(),
                dtSeconds, tOut);
        return BatchMarshaller.slice(tOut, f.n());
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL (native methods need no library at compile time).

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java
git commit -m "$(printf 'feat(engine): NativeEngine JNI facade (native decl + marshalling)\n\norgeStep native method + step() wiring via BatchMarshaller. Library loader\nand integration tests follow; class compiles standalone.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 6 — C++ `orge_jni.cpp` + build `liborge.so` + bundle into `:core` resources

**Files:**
- Create: `ORGE-ENGINE/orge_jni.cpp`
- Create: `ORGE-ENGINE/native/build_liborge.sh`
- Create: `core/src/main/resources/natives/linux-x64/liborge.so` (built artifact)

- [ ] **Step 1: Write `orge_jni.cpp`**

The JNI symbol for `net.rainbowcreation.orge.engine.NativeEngine.orgeStep` (no overloads → no signature suffix) is `Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStep`.

```cpp
#include <jni.h>
#include <chrono>
#include <cstdint>
#include "orge_kernel.hpp"

// Zero-copy JNI bridge to orge::step_section_with_halo over a flattened batch.
// Arrays are pinned via GetPrimitiveArrayCritical; no other JNI calls occur
// while pinned. Returns native compute time in milliseconds.
extern "C" JNIEXPORT jdouble JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStep(
        JNIEnv* env, jclass,
        jint n,
        jcharArray jMatIx, jfloatArray jMass, jfloatArray jTin,
        jfloatArray jHaloT, jcharArray jHaloMat,
        jfloatArray jCond, jfloatArray jHeatCap,
        jdouble dt, jfloatArray jTout)
{
    const jint matCount = env->GetArrayLength(jCond);

    auto* matIx   = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jMatIx,   nullptr));
    auto* mass    = static_cast<float*>   (env->GetPrimitiveArrayCritical(jMass,    nullptr));
    auto* tin     = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTin,     nullptr));
    auto* haloT   = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHaloT,   nullptr));
    auto* haloMat = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jHaloMat, nullptr));
    auto* cond    = static_cast<float*>   (env->GetPrimitiveArrayCritical(jCond,    nullptr));
    auto* heatCap = static_cast<float*>   (env->GetPrimitiveArrayCritical(jHeatCap, nullptr));
    auto* tout    = static_cast<float*>   (env->GetPrimitiveArrayCritical(jTout,    nullptr));

    double ms = 0.0;
    if (matIx && mass && tin && haloT && haloMat && cond && heatCap && tout) {
        orge::MatLUT lut{cond, heatCap, static_cast<int>(matCount)};
        const auto t0 = std::chrono::steady_clock::now();
        for (int s = 0; s < n; ++s) {
            const size_t so = static_cast<size_t>(s) * orge::SEC_N;
            const size_t ho = static_cast<size_t>(s) * orge::FACES * orge::FACE;
            orge::step_section_with_halo(matIx + so, mass + so, tin + so,
                                         haloT + ho, haloMat + ho,
                                         lut, static_cast<float>(dt), tout + so);
        }
        const auto t1 = std::chrono::steady_clock::now();
        ms = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count() / 1e6;
    }

    // Release in reverse acquisition order. tOut is written → mode 0 (copy back).
    // Inputs are read-only → JNI_ABORT (no copy-back).
    if (tout)    env->ReleasePrimitiveArrayCritical(jTout,    tout,    0);
    if (heatCap) env->ReleasePrimitiveArrayCritical(jHeatCap, heatCap, JNI_ABORT);
    if (cond)    env->ReleasePrimitiveArrayCritical(jCond,    cond,    JNI_ABORT);
    if (haloMat) env->ReleasePrimitiveArrayCritical(jHaloMat, haloMat, JNI_ABORT);
    if (haloT)   env->ReleasePrimitiveArrayCritical(jHaloT,   haloT,   JNI_ABORT);
    if (tin)     env->ReleasePrimitiveArrayCritical(jTin,     tin,     JNI_ABORT);
    if (mass)    env->ReleasePrimitiveArrayCritical(jMass,    mass,    JNI_ABORT);
    if (matIx)   env->ReleasePrimitiveArrayCritical(jMatIx,   matIx,   JNI_ABORT);
    return ms;
}
```

- [ ] **Step 2: Write `native/build_liborge.sh`**

`-static-libstdc++ -static-libgcc` so the `.so` doesn't depend on the conda g++ runtime being on the test JVM's library path.

```bash
#!/usr/bin/env bash
# Build liborge.so (SDL-free JNI conduction kernel). Linux x64.
#   ./native/build_liborge.sh [output-path]
set -euo pipefail

ENGINE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JH="${JAVA_HOME:-/home/claude/jdk21}"
OUT="${1:-$ENGINE_ROOT/build/liborge.so}"
CXX="${CXX:-g++}"

mkdir -p "$(dirname "$OUT")"
"$CXX" -std=c++20 -O3 -DNDEBUG -fPIC -shared \
    -static-libstdc++ -static-libgcc \
    -I"$JH/include" -I"$JH/include/linux" -I"$ENGINE_ROOT" \
    "$ENGINE_ROOT/orge_jni.cpp" -o "$OUT"
echo "built $OUT"
```

- [ ] **Step 3: Build the library and verify the exported symbol**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE
chmod +x native/build_liborge.sh
JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh build/liborge.so
nm -D --defined-only build/liborge.so | grep orgeStep
```
Expected: builds without error; `nm` lists `Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStep`.

- [ ] **Step 4: Bundle into `:core` resources**

Run:
```bash
mkdir -p /home/claude/ORGE/core/src/main/resources/natives/linux-x64
cp /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```

- [ ] **Step 5: Commit (two repos)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE && git add orge_jni.cpp native/build_liborge.sh
git commit -m "$(printf 'feat(jni): liborge.so JNI bridge + build script (SDL-free)\n\norge_jni.cpp wraps step_section_with_halo zero-copy via\nGetPrimitiveArrayCritical; build_liborge.sh links a static-libstdc++\nshared object. No SimServer/SDL/socket in this target.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
cd /home/claude/ORGE && git add core/src/main/resources/natives/linux-x64/liborge.so
git commit -m "$(printf 'build(engine): bundle prebuilt linux-x64 liborge.so into :core\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 7 — `NativeLoader` + wire `NativeEngine` to load it + `EngineFactory`

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/NativeLoader.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java` (add static loader)
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/EngineFactory.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/NativeLoaderTest.java`

- [ ] **Step 1: Write the failing test (`NativeLoaderTest.java`) — pure token mapping**

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoaderTest {

    @Test
    void osTokens() {
        assertEquals("linux", NativeLoader.osToken("Linux"));
        assertEquals("windows", NativeLoader.osToken("Windows 11"));
        assertEquals("macos", NativeLoader.osToken("Mac OS X"));
    }

    @Test
    void archTokens() {
        assertEquals("x64", NativeLoader.archToken("amd64"));
        assertEquals("x64", NativeLoader.archToken("x86_64"));
        assertEquals("arm64", NativeLoader.archToken("aarch64"));
    }

    @Test
    void libFileNamePerOs() {
        assertEquals("liborge.so", NativeLoader.libFileName("linux"));
        assertEquals("orge.dll", NativeLoader.libFileName("windows"));
        assertEquals("liborge.dylib", NativeLoader.libFileName("macos"));
    }

    @Test
    void unknownOsThrows() {
        assertThrows(IllegalStateException.class, () -> NativeLoader.osToken("Plan9"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.NativeLoaderTest'`
Expected: COMPILE FAILURE — `NativeLoader` does not exist.

- [ ] **Step 3: Write `NativeLoader.java`**

```java
package net.rainbowcreation.orge.engine;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts the bundled {@code liborge} native for this platform from
 * {@code /natives/<os>-<arch>/} on the classpath to a temp file and
 * {@link System#load(String)}s it (DESIGN.md §2 "Build & native integration";
 * the old SimServerManager extraction trick, minus the subprocess).
 */
public final class NativeLoader {

    private static volatile boolean loaded = false;

    private NativeLoader() {}

    /** Idempotent. Throws {@link UnsatisfiedLinkError} if no matching native is bundled. */
    public static synchronized void load() {
        if (loaded) return;
        String os = osToken(System.getProperty("os.name"));
        String arch = archToken(System.getProperty("os.arch"));
        String lib = libFileName(os);
        String resource = "/natives/" + os + "-" + arch + "/" + lib;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("no bundled native library at " + resource);
            }
            Path tmp = Files.createTempFile("orge-native-", suffixFor(os));
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            throw e;
        } catch (Exception e) {
            throw new UnsatisfiedLinkError("failed to load native: " + e);
        }
    }

    public static boolean isLoaded() { return loaded; }

    static String osToken(String osName) {
        String s = osName.toLowerCase(Locale.ROOT);
        if (s.contains("linux")) return "linux";
        if (s.contains("win")) return "windows";
        if (s.contains("mac") || s.contains("darwin")) return "macos";
        throw new IllegalStateException("unsupported OS: " + osName);
    }

    static String archToken(String osArch) {
        String s = osArch.toLowerCase(Locale.ROOT);
        if (s.equals("amd64") || s.equals("x86_64")) return "x64";
        if (s.equals("aarch64") || s.equals("arm64")) return "arm64";
        throw new IllegalStateException("unsupported arch: " + osArch);
    }

    static String libFileName(String os) {
        return switch (os) {
            case "linux" -> "liborge.so";
            case "windows" -> "orge.dll";
            case "macos" -> "liborge.dylib";
            default -> throw new IllegalStateException("unsupported OS token: " + os);
        };
    }

    private static String suffixFor(String os) {
        return switch (os) {
            case "windows" -> ".dll";
            case "macos" -> ".dylib";
            default -> ".so";
        };
    }
}
```

- [ ] **Step 4: Add the static loader to `NativeEngine.java`**

Add inside `NativeEngine`, immediately above the `orgeStep` declaration:
```java
    static {
        NativeLoader.load();
    }
```

- [ ] **Step 5: Write `EngineFactory.java`**

```java
package net.rainbowcreation.orge.engine;

/**
 * Picks the engine implementation at runtime: {@link NativeEngine} when the
 * liborge native loads for this platform, otherwise the no-op {@link StubEngine}
 * (DESIGN.md §2). Keeps {@code :core} runnable where no native is bundled.
 */
public final class EngineFactory {

    private EngineFactory() {}

    public static OrgeEngine create() {
        try {
            NativeLoader.load();
            return new NativeEngine();
        } catch (Throwable t) {
            System.err.println("[ORGE] native engine unavailable, using StubEngine: " + t.getMessage());
            return new StubEngine();
        }
    }
}
```

- [ ] **Step 6: Run the loader test**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.engine.NativeLoaderTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd /home/claude/ORGE && git add core/src/main/java/net/rainbowcreation/orge/engine/NativeLoader.java core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java core/src/main/java/net/rainbowcreation/orge/engine/EngineFactory.java core/src/test/java/net/rainbowcreation/orge/engine/NativeLoaderTest.java
git commit -m "$(printf 'feat(engine): NativeLoader + EngineFactory native/stub selection\n\nNativeLoader extracts /natives/<os>-<arch>/liborge.* and System.loads it;\nNativeEngine loads it in a static block; EngineFactory.create() returns\nNativeEngine when the native loads else StubEngine.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 8 — Java integration tests against the real `liborge.so`

**Files:**
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/NativeEngineTest.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/EngineFactoryTest.java`

These require the bundled `liborge.so` from Task 6 (present on this linux-x64 sandbox). They are guarded with `assumeTrue(NativeLoader.isLoaded()...)` so they skip (not fail) on a platform with no bundled native.

- [ ] **Step 1: Write `NativeEngineTest.java`**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.rainbowcreation.orge.engine.BatchTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeEngineTest {

    private static NativeEngine engineOrSkip() {
        try {
            NativeLoader.load();
        } catch (Throwable t) {
            assumeTrue(false, "no bundled liborge for this platform: " + t.getMessage());
        }
        return new NativeEngine();
    }

    @Test
    void uniformFieldIsUnchanged() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        List<float[]> out = e.step(List.of(a), stdLut(), 1.0);
        assertEquals(1, out.size());
        for (float v : out.get(0)) assertEquals(300f, v, 1e-4f);
    }

    @Test
    void heatFlowsHotToColdWithinSection() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        int hot = 8 + 16 * 8 + 256 * 8;          // sidx(8,8,8)
        int nb = 9 + 16 * 8 + 256 * 8;           // sidx(9,8,8)
        a.temperature()[hot] = 1000f;
        List<float[]> out = e.step(List.of(a), stdLut(), 1.0);
        assertTrue(out.get(0)[hot] < 1000f, "hot cell cools");
        assertTrue(out.get(0)[nb] > 300f, "neighbour warms");
        assertTrue(e.lastStepMillis() >= 0.0);
    }

    @Test
    void batchPreservesOrder() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 300f);
        StepTask b = solidSection(new SubchunkKey(1, 0, 0), 500f);
        List<float[]> out = e.step(List.of(a, b), stdLut(), 1.0);
        assertEquals(2, out.size());
        assertEquals(300f, out.get(0)[0], 1e-4f);
        assertEquals(500f, out.get(1)[0], 1e-4f);
    }

    @Test
    void matchesStubOnUniformField() {
        NativeEngine e = engineOrSkip();
        StepTask a = solidSection(new SubchunkKey(0, 0, 0), 285f);
        List<float[]> nativeOut = e.step(List.of(a), stdLut(), 1.0);
        List<float[]> stubOut = new StubEngine().step(List.of(a), stdLut(), 1.0);
        assertArrayEquals(stubOut.get(0), nativeOut.get(0), 1e-4f);
    }

    @Test
    void emptyBatchReturnsEmpty() {
        NativeEngine e = engineOrSkip();
        assertTrue(e.step(List.of(), stdLut(), 1.0).isEmpty());
    }
}
```

- [ ] **Step 2: Write `EngineFactoryTest.java`**

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineFactoryTest {

    @Test
    void createReturnsAnEngine() {
        OrgeEngine e = EngineFactory.create();
        assertNotNull(e);
        // On this linux-x64 sandbox the native is bundled, so we expect NativeEngine;
        // on a platform without it, the factory must still return a (Stub) engine.
        assertTrue(e instanceof NativeEngine || e instanceof StubEngine);
        if (NativeLoader.isLoaded()) assertInstanceOf(NativeEngine.class, e);
    }
}
```

- [ ] **Step 3: Run the full `:core` test suite**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test`
Expected: BUILD SUCCESSFUL; all prior `:core` tests plus the new engine tests pass (native tests run, not skipped, because `liborge.so` is bundled).

- [ ] **Step 4: Commit**

```bash
cd /home/claude/ORGE && git add core/src/test/java/net/rainbowcreation/orge/engine/NativeEngineTest.java core/src/test/java/net/rainbowcreation/orge/engine/EngineFactoryTest.java
git commit -m "$(printf 'test(engine): NativeEngine + EngineFactory integration against liborge.so\n\nKnown-answer conduction, batch ordering, stub-equivalence on a uniform\nfield, empty batch, and factory selection. Guarded to skip where no native\nis bundled.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 9 — Finalize: docs, packaging notes, full build, submodule pointer bump

**Files:**
- Modify: `DESIGN.md` (§2: record JNI decision)
- Create: `docs/superpowers/notes/2026-05-29-native-packaging.md` (CI/multi-platform follow-up)
- Modify: parent repo gitlink for `ORGE-ENGINE`

- [ ] **Step 1: Update DESIGN.md §2**

Replace the second bullet of §2 (the "In-process via Panama FFI" bullet) with:
```markdown
- **In-process via JNI** (`System.load`, stable on Java 21 — *not* Panama:
  `java.lang.foreign` is a preview API on Java 21 that requires `--enable-preview`
  at compile and runtime, so a vanilla Minecraft launcher cannot load it; see
  `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md`). JNI also accesses
  the Java arrays zero-copy via `GetPrimitiveArrayCritical`. No child process, no
  socket, no per-tick serialization across a process boundary.
```
And in the "Build & native integration notes" section, replace the `jextract` bullet with:
```markdown
- A small JNI bridge (`orge_jni.cpp`) over the header-only `orge_kernel.hpp` builds
  `liborge.{so,dll,dylib}` (SDL-free). No jextract / generated bindings — the single
  `native double orgeStep(...)` method is hand-declared in `NativeEngine`.
```

- [ ] **Step 2: Write the packaging follow-up note**

```markdown
# Native packaging — deferred multi-platform work (2026-05-29)

The engine seam (§2) ships a **linux-x64** `liborge.so` committed at
`core/src/main/resources/natives/linux-x64/liborge.so`, built from the ORGE-ENGINE
submodule via `native/build_liborge.sh`. The following is deferred (needs network +
the ORGE-ENGINE CI, neither available in the build sandbox):

- ORGE-ENGINE CI builds `liborge` for {windows, linux, macos} × {x64, arm64} and
  publishes them as release artifacts (pinned version).
- A Gradle task fetches the pinned artifacts into
  `src/main/resources/natives/<os>-<arch>/` so the jar bundles every platform; the
  runtime `NativeLoader` already extracts + `System.load`s the matching one (and
  falls back to `StubEngine` via `EngineFactory` when absent).
- Build the `.dll` (`orge.dll`) and `.dylib` (`liborge.dylib`) variants; `NativeLoader`
  already maps their names.

Until then, only linux-x64 runs the real engine; other platforms transparently fall
back to `StubEngine` (logged once).
```

- [ ] **Step 3: Full both-loader build (smoke)**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build`
Expected: BUILD SUCCESSFUL on both loaders; the `liborge.so` resource is packed into the `:core` output (and thus both loader jars).

- [ ] **Step 4: Verify the submodule is committed and clean, then bump the parent gitlink**

Run:
```bash
cd /home/claude/ORGE/ORGE-ENGINE && git status --short && git log --oneline -3
cd /home/claude/ORGE && git add ORGE-ENGINE && git status --short
```
Expected: submodule working tree clean, its log shows the Task 2/3/6 commits; `git add ORGE-ENGINE` stages the gitlink update (resolves the persistent `M ORGE-ENGINE`).

- [ ] **Step 5: Commit docs + gitlink**

```bash
cd /home/claude/ORGE && git add DESIGN.md docs/superpowers/notes/2026-05-29-native-packaging.md ORGE-ENGINE
git commit -m "$(printf 'docs(engine): record JNI decision in DESIGN §2; bump ORGE-ENGINE gitlink\n\nUpdates DESIGN §2 + native-integration notes to JNI (not Panama, preview\non Java 21); adds the multi-platform packaging follow-up note; advances\nthe ORGE-ENGINE submodule pointer to the kernel+JNI commits.\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Self-review checklist (run before declaring done)

- [ ] **Spec coverage:** JNI-not-Panama (T9 docs + whole plan), stateless single-section stepper (T2), shared bit-critical math (T2), halo temps+matIx (T1), parity (T3), marshalling (T4), NativeEngine (T5), liborge.so build+bundle (T6), NativeLoader+EngineFactory (T7), integration + fallback (T7/T8), packaging follow-up (T9), submodule bump (T9). Out-of-scope §7/§8 stay untouched.
- [ ] **No placeholders:** every step has concrete code/commands.
- [ ] **Type/name consistency:** `orgeStep` signature identical in `NativeEngine.java` (T5) and `orge_jni.cpp` (T6); `BatchMarshaller.Flat` accessors match their uses in `NativeEngine` (T5); `NeighborHalo.tempFaces()/matFaces()` (T1) used by `BatchMarshaller` (T4); face order `negX,posX,negY,posY,negZ,posZ` and `sidx`/`fc` conventions identical in C++ (T2) and Java (ground rules / T4).
- [ ] **Determinism:** kernel sums `+x,-x,+y,-y,+z,-z` and routes through `orge::keff`/`orge::finalize_temp`; parity test (T3) is the gate.
