# Engine-Resident Material Table (register-once LUT + stable global ids) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native engine hold the material id→physics table resident (registered once at load + every `/reload`), give every cell a globally-stable `matIx`, and drop the 6-array LUT from each `orgeStepWorld` call — without touching `sim_engine.hpp`/`orge_kernel.hpp` and with byte-identical single-state output.

**Architecture:** Two coupled changes. (1) **Stable global table** — one ordered `List<Material>` per published `ActiveMaterials.State`: slot 0 = VACUUM, slots 1..N = `MaterialRegistry.all()` sorted by namespaced id string. Both the engine registration and the column assembly read this one table, so a given `matIx` means the same material on every call. (2) **Register-once + drop-from-step** — a new `orgeRegisterMaterials(lutEpoch, …)` JNI stores the table in a process-global epoch-keyed store in `orge_jni.cpp`; `orgeStepWorld` drops the 6 LUT arrays and gains a `lutEpoch` selector; unknown epoch → safe no-op copy. Reload-vs-background-step races are handled by epoch-keyed tables (keep last 2). The World stays stateless (rebuild-per-step unchanged).

**Tech Stack:** C++20 JNI shim (`liborge.so`), Java 21, Architectury multiloader (Fabric + NeoForge), Gradle. Authoritative spec: `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.

**Locked open decisions (§10, confirmed with user 2026-06-03):**
1. **Drop** the `lut` param from `OrgeEngine.stepWorld(...)` everywhere (pass `lutEpoch`).
2. **Epoch-keyed table map** in the engine (per-batch `lutEpoch` selects its table).
3. **Shared engine singleton** via `EngineFactory.instance()`; `ActiveMaterials.swap()` and the `Scheduler` use the same instance.
4. **Keep last 2 epochs**; the engine auto-evicts the oldest on register (no Java→engine free call).

---

## Hard constraints (read before every task)

- `JAVA_HOME=/home/claude/jdk21` prefixes ALL gradle invocations.
- **Push `origin/rebuild` after EVERY commit. User tests from origin/rebuild. Don't ask.** Engine commits push `origin/main`; then rebuild the `.so` and bump the gitlink in `rebuild`.
- The engine submodule remote URL embeds a token — **NEVER echo it** (no `git remote -v`, no `git config --get remote...`). Use `git -C ORGE-ENGINE push origin main` only.
- Do **NOT** run the hours-long stress test. Use `./ORGE-ENGINE/tests/run_tests.sh [cheap|full|<name>]`. The fast loop is `cheap`.
- `sim_engine.hpp` and `orge_kernel.hpp` stay **byte-unchanged** (kernel parity). The resident table lives only in `orge_jni.cpp` + a new `orge_jni`-only header. No `movable()`/placement-seed change.
- All Java packages are under `net.rainbowcreation.orge`. On-disk: `core/src/main/java/net/rainbowcreation/orge/{engine,scheduler,material,phase,command}/`.
- **`Identifier` construction:** production code uses `Identifier.fromNamespaceAndPath("orge", "vacuum")`. The test code below uses `Identifier.parse("orge:water")` for brevity — if `parse` is absent in this MC mapping (1.21.11), swap to `Identifier.fromNamespaceAndPath("orge", "water")`. Likewise verify `Material.builder(...)` setter names and the `ColumnTask`/`ColumnResult` constructors against the real types (`material/Material.java`, `engine/ColumnTask.java`, `engine/ColumnResult.java`) before relying on them — do not invent members.

## Spec→task traceability (folds Task-1 conflict inventory `/tmp/orge-spec-conflict-inventory.md`)

| Conflict # | File | Resolved in |
|---|---|---|
| #1,#2 | `orge_jni.cpp` step LUT rebuild + comment | Task 2 |
| #3 | `NativeEngine` native decls | Task 9 |
| #4 | `NativeEngine.stepWorld` packs LutArrays | Task 9 |
| #5 | `RegionMarshaller.Flat`/`flatten` carry lut | Task 9 |
| #6 | `OrgeEngine` interface lut param | Task 8 |
| #7 | `StubEngine` signature | Task 10 |
| #8,#9 | `MaterialLut` first-seen model + javadoc | Task 5 |
| #10,#11 | `MinecraftThermalWorld` `new MaterialLut()` | Task 7 |
| #12 | `ColumnAssembler.assemble` indexOf-append | Task 5 (view) / Task 7 (call site) |
| #13 | `Scheduler` passes lut into step | Task 13 |
| #14 | `ColumnBatch` carries lut, not epoch | Task 7 |
| #15 | `ActiveMaterials.swap` register point | Task 6 + Task 12 |
| #16 | `MaterialRegistry.all()` unordered | Task 4 |
| #17 | `EngineFactory` no singleton | Task 11 |
| #18 | `LutArrays.pack` caller moves | Task 9 |
| #30–#34 | tests assume batch-local ids | Tasks 5,6,7 (per-test) |
| #19–#29, #35,#36 | STALE-DOC / doc javadoc | Task 17 |
| OK-but-adjacent | `MaterialPalette` on-disk first-seen | left as-is (note in Task 17) |

---

## File structure (what changes, and why)

**Engine (C++), `ORGE-ENGINE/`:**
- `lut_store.hpp` *(new, jni-only)* — `LutStore`: epoch→`MaterialLUT` map, register/copyInto/keep-last-2. Testable without a JVM.
- `orge_jni.cpp` *(modify)* — add `orgeRegisterMaterials`; change `orgeStepWorld` signature (drop 6 LUT arrays, add `lutEpoch`); unknown epoch → no-op copy.
- `tests/resident_lut_test.cpp` *(new)* — store round-trip + step-via-store == inline + unknown-epoch + eviction. Added to the cheap tier.
- `tests/run_tests.sh` *(modify)* — list the new cheap test.

**Java (`core/src/main/java/net/rainbowcreation/orge/`):**
- `material/MaterialTable.java` *(new)* — `VACUUM` sentinel (moved here from `MaterialLut`) + `ordered(registry)` + `slots(ordered)`. The single source of truth both register and assembly call.
- `material/ActiveMaterials.java` *(modify)* — `State` carries `orderedMaterials`/`materialSlots`/`lutEpoch`; `swap()` assigns the epoch and registers the engine.
- `material/MaterialRegistry.java` *(no behavioral change; `all()` stays unordered — the deterministic sort lives in `MaterialTable`)*.
- `scheduler/MaterialLut.java` *(modify)* — becomes a **view** over a published table: fixed-slot `indexOf`, no append, no per-step instance; `VACUUM` aliases `MaterialTable.VACUUM`.
- `scheduler/MinecraftThermalWorld.java` *(modify)* — build the view from the State; thread `lutEpoch` into the batch.
- `scheduler/ThermalWorld.java` *(modify)* — `ColumnBatch` gains `lutEpoch`.
- `scheduler/ColumnAssembler.java` *(no logic change; unchanged calls now resolve fixed slots through the view)*.
- `scheduler/Scheduler.java` *(modify)* — pass `batch.lutEpoch()` into `engine.stepWorld(...)`.
- `engine/OrgeEngine.java` *(modify)* — add `registerMaterials`; drop `lut` param from both `stepWorld` overloads.
- `engine/NativeEngine.java` *(modify)* — native `orgeRegisterMaterials` decl + re-signed `orgeStepWorld`; `registerMaterials` packs via `LutArrays`; `stepWorld` passes `lutEpoch`, sizes the ledger from a remembered per-epoch matCount.
- `engine/StubEngine.java` *(modify)* — mirror the new shape (register = remember matCount; step by epoch).
- `engine/RegionMarshaller.java` *(modify)* — `Flat` drops `lut`; `flatten` stops packing it.
- `engine/LutArrays.java` *(no change to the type; only its caller moves — now called by `NativeEngine.registerMaterials`)*.
- `engine/EngineFactory.java` *(modify)* — lazy shared singleton via `instance()`; `create()` delegates.

**Tests / docs:**
- `core/src/test/.../engine/ResidentLutParityIT.java` *(new)* — golden parity + reload-epoch IT on the real `.so`.
- `core/src/test/resources/golden/resident-lut-step.bin` *(new, committed in Task 0)* — the pre-change baseline.
- Various test updates (Tasks 5/6/7).
- Doc annotations (Task 17).

---

## Phase 0 — Capture the regression oracle FIRST

### Task 0: Capture a golden from current HEAD

**Why:** §8/§9 — the single-state path must stay byte-for-byte identical. Capture the baseline on the **current, unmodified** code so the post-refactor IT (Task 14) can assert against it. The capture harness is throwaway; only the golden bytes are committed.

**Files:**
- Create (throwaway): `core/src/test/java/net/rainbowcreation/orge/engine/GoldenCaptureManual.java`
- Create (committed): `core/src/test/resources/golden/resident-lut-step.bin`

- [ ] **Step 1: Write the throwaway capture test (uses the CURRENT `stepWorld(columns, lut, …)` API)**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * THROWAWAY (deleted at the end of Task 0). Runs a fixed world through the CURRENT engine API and
 * serialises the result to the golden resource. Run once on unmodified HEAD with the native present:
 *   JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest \
 *     --tests '*GoldenCaptureManual' -Dorge.capture=1
 */
class GoldenCaptureManual {

    private static final int N = RegionMarshaller.CHUNK_N;

    @Test
    @EnabledIfSystemProperty(named = "orge.capture", matches = "1")
    void capture() throws Exception {
        NativeLoader.load(); // throws if native absent — capture requires the real .so
        OrgeEngine engine = new NativeEngine();

        // Deterministic 4-material LUT (slot 0 = a vacuum-like void, then water/lava/air-like).
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,   0f,    0f,    0f,   0f,   Float.POSITIVE_INFINITY));
        lut.add(mat("orge:water", 4186f,0.6f,  0.018f,125f, 1000f,0f));
        lut.add(mat("orge:lava",  1000f,1.0f,  0.100f,200f, 2000f,5000f));
        lut.add(mat("orge:air",   1005f,0.025f,0.029f,1.0f, 50f,  0f));

        // One column, a small deterministic fill: a water block above an air gap above lava.
        char[] matIx = new char[N];
        float[] mass = new float[N];
        float[] tIn  = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = 0; mass[i] = 0f; tIn[i] = 300f; }
        // engine index = x + 16*y + 6144*z; column (0,0); put cells at x=8,z=8, y=40..36.
        put(matIx, mass, tIn, 8, 40, 8, (char) 1, 1000f, 300f); // water
        put(matIx, mass, tIn, 8, 38, 8, (char) 3, 30f,   300f); // air
        put(matIx, mass, tIn, 8, 36, 8, (char) 2, 2000f, 1500f);// lava

        ColumnTask col = new ColumnTask(0, 0, matIx, mass, tIn);
        List<ColumnTask> cols = List.of(col);

        // 8 steps of conduction+advection at the production dt (0.25 s) — same as the scheduler quantum.
        List<ColumnResult> out = null;
        for (int s = 0; s < 8; s++) {
            out = engine.stepWorld(cols, lut, 0.25,
                    OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION);
            ColumnResult r = out.get(0);
            cols = List.of(new ColumnTask(0, 0, r.matIx(), r.mass(), r.temperature()));
        }

        Path p = Path.of("src/test/resources/golden/resident-lut-step.bin");
        Files.createDirectories(p.getParent());
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(p.toFile()))) {
            ColumnResult r = out.get(0);
            o.writeInt(N);
            for (int i = 0; i < N; i++) o.writeChar(r.matIx()[i]);
            for (int i = 0; i < N; i++) o.writeFloat(r.mass()[i]);
            for (int i = 0; i < N; i++) o.writeFloat(r.temperature()[i]);
        }
        System.out.println("[golden] wrote " + p.toAbsolutePath());
    }

    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(0f).defaultTemperature(300f).build();
    }
    private static void put(char[] mi, float[] m, float[] t, int x, int y, int z, char ix, float mass, float temp) {
        int i = x + 16 * y + 6144 * z; mi[i] = ix; m[i] = mass; t[i] = temp;
    }
}
```

> **Note on `Material.builder` / `ColumnTask` / `ColumnResult` field names:** match them to the real types. If `Material.builder(...)` setter names differ, copy them from `core/src/main/java/.../material/Material.java`; if `ColumnTask`'s ctor differs, copy it from `core/src/main/java/.../engine/ColumnTask.java`. Do **not** invent setters.

- [ ] **Step 2: Run the capture against unmodified HEAD (native present)**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*GoldenCaptureManual' -Dorge.capture=1`
Expected: `[golden] wrote …/resident-lut-step.bin` and BUILD SUCCESSFUL. The file exists and is non-empty (`98304*2 + 98304*4 + 98304*4 + 4 ≈ 983044` bytes).

> If the native is not loadable in this environment, capture instead with the existing engine: build a tiny C++ program mirroring the loop in `tests/` writing the same `.bin`, run it, and place the file. The point is a frozen byte baseline; the source of truth is the current advection result, which is identical between the C++ and Java paths (Java only marshals).

- [ ] **Step 3: Commit the golden, delete the throwaway harness**

```bash
cd /home/claude/ORGE
git rm --cached --quiet core/src/test/java/net/rainbowcreation/orge/engine/GoldenCaptureManual.java 2>/dev/null || true
rm core/src/test/java/net/rainbowcreation/orge/engine/GoldenCaptureManual.java
git add core/src/test/resources/golden/resident-lut-step.bin
git commit -m "test(engine): freeze pre-refactor golden for resident-LUT parity oracle"
git push origin rebuild
```
Expected: golden committed; the throwaway `.java` is gone (it references the old API and must not survive the signature change).

---

## Phase 1 — Engine: process-global epoch-keyed resident table

### Task 1: `LutStore` header + C++ shim test (no JVM)

**Files:**
- Create: `ORGE-ENGINE/lut_store.hpp`
- Create: `ORGE-ENGINE/tests/resident_lut_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the failing test**

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/resident_lut_test.cpp -o build/resident_lut_test -pthread
//
// Resident-LUT store oracle (spec 2026-06-03-engine-resident-material-table §9.1). Pure C++:
// includes lut_store.hpp (which includes sim_engine.hpp) — NO JVM. Asserts:
//   (1) register(epoch, lut) then copyInto(epoch) returns a byte-identical MaterialLUT;
//   (2) a World stepped with the stored LUT == a World stepped with the inline LUT (the
//       store does not corrupt material physics) — the single-state bit-identical guarantee;
//   (3) copyInto(unknownEpoch) returns false (engine no-ops in the JNI on this);
//   (4) keep-last-2 eviction: after registering 3 epochs, the oldest is gone, newest 2 remain.
#include "lut_store.hpp"
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)

static MaterialLUT make_lut() {
    constexpr float INF = std::numeric_limits<float>::infinity();
    MaterialLUT lut;
    lut.add(Material{0.f,    0.f,    0.0f,   0.f,   0.f,   INF});    // slot 0 void (frozen)
    lut.add(Material{4186.f, 0.6f,   0.018f, 125.f, 1000.f,0.0f});  // slot 1 water
    lut.add(Material{1000.f, 1.0f,   0.100f, 200.f, 2000.f,5000.f});// slot 2 lava
    return lut;
}
static void seed(World& w) {
    Chunk* C = w.ensureChunk(0,0);
    for (int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    int i = idx(8,40,8); C->matIx[i]=1; C->mass_kg[i]=1000.f; C->T_curr[i]=300.f; // a water cell
}

int main() {
    LutStore store;

    // (1) round-trip
    store.registerLut(1, make_lut());
    MaterialLUT got;
    CHECK(store.copyInto(1, got), "(1) epoch 1 retrievable");
    MaterialLUT inl = make_lut();
    CHECK(got.size() == inl.size(), "(1) size matches");
    bool same = got.size() == inl.size();
    for (size_t k=0;k<got.size() && same;++k) {
        const Material& a = got.byIx((uint16_t)k);
        const Material& b = inl.byIx((uint16_t)k);
        same = a.heatCapacity==b.heatCapacity && a.thermalConductivity==b.thermalConductivity
            && a.molarMass==b.molarMass && a.minMass==b.minMass && a.maxMass==b.maxMass
            && ((std::isinf(a.viscosity)&&std::isinf(b.viscosity)) || a.viscosity==b.viscosity);
    }
    CHECK(same, "(1) stored LUT byte-identical to inline");

    // (2) step-via-store == step-via-inline
    World wStore; wStore.materials = got;            seed(wStore);
    World wInline; wInline.materials = make_lut();    seed(wInline);
    advect_world(wStore,  wStore.materials,  0.25);
    advect_world(wInline, wInline.materials, 0.25);
    Chunk* cs = wStore.findChunk(0,0); Chunk* ci = wInline.findChunk(0,0);
    float maxd = 0.f;
    for (int i=0;i<CHUNK_N;++i) {
        maxd = std::max(maxd, std::fabs(cs->mass_kg[i]-ci->mass_kg[i]));
        if (cs->matIx[i]!=ci->matIx[i]) maxd = 1e9f;
    }
    CHECK(maxd == 0.0f, "(2) step with stored LUT == step with inline LUT (bit-identical)");

    // (3) unknown epoch
    MaterialLUT none;
    CHECK(!store.copyInto(999, none), "(3) unknown epoch -> false (engine no-ops)");

    // (4) keep-last-2 eviction
    store.registerLut(2, make_lut());
    store.registerLut(3, make_lut());
    MaterialLUT tmp;
    CHECK(!store.copyInto(1, tmp), "(4) epoch 1 evicted after 3 registers (keep last 2)");
    CHECK(store.copyInto(2, tmp),  "(4) epoch 2 still present");
    CHECK(store.copyInto(3, tmp),  "(4) epoch 3 still present");

    if (failures) { std::printf("resident_lut_test: %d FAILURES\n", failures); return 1; }
    std::printf("resident_lut_test: OK\n");
    return 0;
}
```

- [ ] **Step 2: Run it to verify it fails to compile (no `lut_store.hpp` yet)**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && mkdir -p build && g++ -std=c++20 -O2 -g -I. tests/resident_lut_test.cpp -o build/resident_lut_test -pthread`
Expected: FAIL — `fatal error: lut_store.hpp: No such file or directory`.

- [ ] **Step 3: Write `lut_store.hpp` (minimal to pass)**

```cpp
#pragma once
// Process-global resident material table for the JNI shim (spec 2026-06-03-engine-resident-material-
// table §5/§7). JNI-ONLY: included by orge_jni.cpp (and the resident_lut_test). It does NOT modify
// sim_engine.hpp / orge_kernel.hpp — it only stores/returns the MaterialLUT type they already define.
//
// Thread-safety (§7): /reload registers on the server thread; the scheduler runs the engine step on a
// background thread. Tables are keyed by a monotonic lutEpoch; each in-flight batch carries the epoch
// it was assembled under, so an old-epoch batch still finds its table. We keep the last 2 epochs and
// auto-evict the oldest on register (decision 4). The mutex guards only the tiny map read/write +
// MaterialLUT copy (microseconds over a few-dozen materials); the heavy simulation runs UNLOCKED on
// the caller's local World copy.
#include "sim_engine.hpp"   // MaterialLUT
#include <unordered_map>
#include <deque>
#include <mutex>
#include <utility>

struct LutStore {
    std::mutex mtx;
    std::unordered_map<int, MaterialLUT> byEpoch;
    std::deque<int> ages;   // insertion order of distinct live epochs (front = oldest)

    // Register (or replace) the table for an epoch. Keeps at most the last 2 distinct epochs.
    void registerLut(int epoch, MaterialLUT lut) {
        std::lock_guard<std::mutex> g(mtx);
        if (byEpoch.find(epoch) == byEpoch.end()) ages.push_back(epoch);
        byEpoch[epoch] = std::move(lut);
        while (ages.size() > 2) { int old = ages.front(); ages.pop_front(); byEpoch.erase(old); }
    }

    // Copy the epoch's table into `out`. Returns false if the epoch is unknown/evicted (caller no-ops).
    bool copyInto(int epoch, MaterialLUT& out) {
        std::lock_guard<std::mutex> g(mtx);
        auto it = byEpoch.find(epoch);
        if (it == byEpoch.end()) return false;
        out = it->second;   // copy under lock; caller simulates on its own copy, unlocked
        return true;
    }
};
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/resident_lut_test.cpp -o build/resident_lut_test -pthread && ./build/resident_lut_test`
Expected: `resident_lut_test: OK` (exit 0).

- [ ] **Step 5: Add the test to the cheap tier in `run_tests.sh`**

In `ORGE-ENGINE/tests/run_tests.sh`, add to the `CHEAP_TESTS=(` array (and to the `# cheap :` comment line):

```bash
  "resident_lut_test|tests/resident_lut_test.cpp|-O2 -g"
```

- [ ] **Step 6: Run the cheap tier to confirm the harness picks it up**

Run: `cd /home/claude/ORGE && ./ORGE-ENGINE/tests/run_tests.sh cheap`
Expected: all cheap tests PASS, including `resident_lut_test`.

- [ ] **Step 7: Commit (do NOT push engine yet — push after the JNI change in Task 3)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add lut_store.hpp tests/resident_lut_test.cpp tests/run_tests.sh
git commit -m "feat(engine): resident LUT store (epoch-keyed, keep-last-2) + C++ shim test"
```

---

### Task 2: `orge_jni.cpp` — add register, re-sign step, no-op on unknown epoch

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp`

- [ ] **Step 1: Add the include and the process-global store (top of file, after the existing `#include` block)**

```cpp
#include "lut_store.hpp"

// Process-global resident material tables (spec §5/§7). One instance; keyed by lutEpoch.
static LutStore g_lutStore;
```

- [ ] **Step 2: Add the `orgeRegisterMaterials` JNI function (place it just above `orgeStepWorld`)**

```cpp
// ---- Register the resident material table for an epoch (spec §5.1) -----------------------------
// Called on every publish (initial load + each /reload). Builds a MaterialLUT from the six physics
// arrays (same field order as the old per-call rebuild) and stores it under lutEpoch. Keep-last-2
// eviction lives in LutStore. No World, no simulation — pure table registration.
extern "C" JNIEXPORT void JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeRegisterMaterials(
        JNIEnv* env, jclass,
        jint lutEpoch, jint matCount,
        jfloatArray jCond, jfloatArray jHeatCap, jfloatArray jMolar,
        jfloatArray jMinMass, jfloatArray jMaxMass, jfloatArray jVisc)
{
    auto* cond    = static_cast<float*>(env->GetPrimitiveArrayCritical(jCond,    nullptr));
    auto* heatCap = static_cast<float*>(env->GetPrimitiveArrayCritical(jHeatCap, nullptr));
    auto* molar   = static_cast<float*>(env->GetPrimitiveArrayCritical(jMolar,   nullptr));
    auto* minMass = static_cast<float*>(env->GetPrimitiveArrayCritical(jMinMass, nullptr));
    auto* maxMass = static_cast<float*>(env->GetPrimitiveArrayCritical(jMaxMass, nullptr));
    auto* visc    = static_cast<float*>(env->GetPrimitiveArrayCritical(jVisc,    nullptr));
    if (cond && heatCap && molar && minMass && maxMass && visc) {
        MaterialLUT lut;
        // Material aggregate order = the six physics fields ONLY:
        // {heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity}.
        for (int i = 0; i < matCount; ++i) {
            Material m{};
            m.heatCapacity        = heatCap[i];
            m.thermalConductivity = cond[i];
            m.molarMass           = molar[i];
            m.minMass             = minMass[i];
            m.maxMass             = maxMass[i];
            m.viscosity           = visc[i];
            lut.add(m);
        }
        g_lutStore.registerLut(lutEpoch, std::move(lut));
    }
    if (visc)    env->ReleasePrimitiveArrayCritical(jVisc,    visc,    JNI_ABORT);
    if (maxMass) env->ReleasePrimitiveArrayCritical(jMaxMass, maxMass, JNI_ABORT);
    if (minMass) env->ReleasePrimitiveArrayCritical(jMinMass, minMass, JNI_ABORT);
    if (molar)   env->ReleasePrimitiveArrayCritical(jMolar,   molar,   JNI_ABORT);
    if (heatCap) env->ReleasePrimitiveArrayCritical(jHeatCap, heatCap, JNI_ABORT);
    if (cond)    env->ReleasePrimitiveArrayCritical(jCond,    cond,    JNI_ABORT);
}
```

- [ ] **Step 3: Change the `orgeStepWorld` signature — drop the 6 LUT arrays, add `lutEpoch` as the first arg**

Replace the function header (was `jint nCols, … jfloatArray jCond..jVisc, … jint passes`) with:

```cpp
extern "C" JNIEXPORT jdouble JNICALL
Java_net_rainbowcreation_orge_engine_NativeEngine_orgeStepWorld(
        JNIEnv* env, jclass,
        jint lutEpoch,
        jint nCols,
        jintArray jCx, jintArray jCz,
        jcharArray jMatIx, jfloatArray jMass, jfloatArray jTin,
        jint passes, jdouble dt,
        jfloatArray jTout, jfloatArray jMassOut, jcharArray jMatOut,
        jint injCount,
        jintArray jInjColumn, jintArray jInjCell,
        jcharArray jInjSpecies, jfloatArray jInjMass, jfloatArray jInjTemp,
        jfloatArray jLedgerOut)      // [2*matCount]: injected[0..n), then sealedLoss[0..n)
{
```

- [ ] **Step 4: Remove the 6 LUT-array critical acquires, and the per-call LUT rebuild loop; source the table from the store**

Delete the lines that acquire `cond/heatCap/molar/minMass/maxMass/visc` (the `GetPrimitiveArrayCritical(jCond…)` block) and delete `const jint matCount = env->GetArrayLength(jCond);`. Delete the `for (int i = 0; i < matCount; ++i) { … world.materials.add(m); }` rebuild loop (lines ~63-72) and its comment. In their place, after `World world;` resolve the resident table; on a miss, do the no-op copy and return:

```cpp
        World world;
        // Source the resident material table by epoch (spec §5.2). Unknown/evicted epoch (step before
        // any register) => safe no-op: copy inputs straight through, ledger stays zero, return 0.0.
        if (!g_lutStore.copyInto(lutEpoch, world.materials)) {
            const size_t total = static_cast<size_t>(nCols) * CHUNK_N;
            for (size_t i = 0; i < total; ++i) { matOut[i] = matIx[i]; massOut[i] = mass[i]; tout[i] = tin[i]; }
            ms = 0.0;
            // fall through to the release block below (skip injections + sim)
        } else {
        const int matCount = static_cast<int>(world.materials.size());
        // … the existing chunk-build, injection, sub-cycle, and read-back code goes HERE, unchanged …
        }
```

> **Structure note:** wrap the existing body (chunk build → injections → sub-cycle → read-back → timing) in the `else { … }` so the no-op path skips it. Keep the existing `const auto t0 = …; … ms = … (t1 - t0) …;` timing inside the `else`. The `if (cx && cz && matIx && … && tout && massOut && matOut)` guard at the top stays (it no longer mentions the removed LUT pointers). Replace every later use of the old `matCount` (from `GetArrayLength(jCond)`) with the `matCount` declared from `world.materials.size()`.

- [ ] **Step 5: Remove the 6 LUT-array releases from the cleanup block**

Delete the `if (visc) … ; if (maxMass) …; … if (cond) …;` release lines from the bottom `Release…` block of `orgeStepWorld` (those arrays are no longer acquired in step). Leave the `jMatOut/jMassOut/jTout` and `jMatIx/jMass/jTin/jCx/jCz` releases intact.

- [ ] **Step 6: Verify the kernel headers are untouched**

Run: `cd /home/claude/ORGE && git -C ORGE-ENGINE diff --name-only`
Expected: only `orge_jni.cpp` (and Task 1's `lut_store.hpp`, `tests/…` already committed). `sim_engine.hpp` and `orge_kernel.hpp` MUST NOT appear.

- [ ] **Step 7: Compile-check the JNI in isolation** (catches signature/typo errors before the full build)

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -DNDEBUG -fPIC -c -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I. orge_jni.cpp -o build/orge_jni.o`
Expected: compiles with no errors (object file produced).

- [ ] **Step 8: Run the cheap tier (kernel parity unchanged)**

Run: `cd /home/claude/ORGE && ./ORGE-ENGINE/tests/run_tests.sh cheap`
Expected: all PASS (the kernel tests are unaffected; `resident_lut_test` still passes).

- [ ] **Step 9: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_jni.cpp
git commit -m "feat(engine): orgeRegisterMaterials + epoch-selected orgeStepWorld (drop per-call LUT)"
```

---

### Task 3: Build `.so`, push engine `origin/main`

**Files:** none (build + push).

- [ ] **Step 1: Build the shared library**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh`
Expected: `built …/ORGE-ENGINE/build/liborge.so`.

- [ ] **Step 2: Push the engine submodule to `origin/main` (NEVER echo the remote URL)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git push origin main
```
Expected: push succeeds. (The gitlink in `rebuild` is bumped later, in Task 16, after the Java side is green.)

> Capture the new engine HEAD for the memory/handoff: `git -C /home/claude/ORGE/ORGE-ENGINE rev-parse HEAD`.

---

## Phase 2 — Java: the stable global table (no engine call yet)

### Task 4: `MaterialTable` — VACUUM sentinel + ordered table + slots

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/material/MaterialTable.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/MaterialTableTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaterialTableTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void slotZeroIsVacuum() {
        MaterialRegistry reg = new MaterialRegistry();
        List<Material> ordered = MaterialTable.ordered(reg);
        assertEquals(MaterialTable.VACUUM, ordered.get(0), "slot 0 is the VACUUM sentinel");
    }

    @Test
    void realMaterialsSortedByNamespacedId() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.register(mat("orge:water"));   // out-of-order on purpose
        reg.register(mat("orge:air"));
        reg.register(mat("orge:lava"));
        List<Material> ordered = MaterialTable.ordered(reg);
        // slot 0 = VACUUM; then alphabetical by id string: air, lava, water
        assertEquals(MaterialTable.VACUUM.id(), ordered.get(0).id());
        assertEquals("orge:air",   ordered.get(1).id().toString());
        assertEquals("orge:lava",  ordered.get(2).id().toString());
        assertEquals("orge:water", ordered.get(3).id().toString());
    }

    @Test
    void slotsMapMaterialIdToFixedIndex() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.register(mat("orge:water"));
        reg.register(mat("orge:air"));
        List<Material> ordered = MaterialTable.ordered(reg);
        Map<Identifier, Character> slots = MaterialTable.slots(ordered);
        assertEquals((char) 0, slots.get(MaterialTable.VACUUM.id()));
        assertEquals((char) 1, slots.get(Identifier.parse("orge:air")));
        assertEquals((char) 2, slots.get(Identifier.parse("orge:water")));
    }

    @Test
    void orderingIsStableAcrossRebuilds() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.register(mat("orge:water"));
        reg.register(mat("orge:lava"));
        reg.register(mat("orge:air"));
        List<Material> a = MaterialTable.ordered(reg);
        List<Material> b = MaterialTable.ordered(reg);
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).id(), b.get(i).id(), "slot " + i + " stable");
    }
}
```

> Verify `MaterialRegistry.register(Material)` is the real add method; if it differs (e.g. `add`/`put`), match it. Verify `Material.builder` setters against `Material.java`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*MaterialTableTest'`
Expected: FAIL — `MaterialTable` does not exist (compile error).

- [ ] **Step 3: Write `MaterialTable`**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for the engine-resident material table (spec
 * 2026-06-03-engine-resident-material-table §4). Both the engine registration
 * ({@link ActiveMaterials#swap}) and the column assembly ({@code MaterialLut} view) build their
 * id↔slot mapping HERE, so a {@code matIx} value means the same material on every {@code orgeStepWorld}
 * call.
 *
 * <ul>
 *   <li>Slot 0 = {@link #VACUUM} sentinel (unchanged invariant).</li>
 *   <li>Slots 1..N = {@link MaterialRegistry#all()} sorted by namespaced id string
 *       (deterministic, run-stable, reload-stable for an unchanged datapack).</li>
 * </ul>
 */
public final class MaterialTable {

    private MaterialTable() {}

    /**
     * The index-0 vacuum sentinel (moved here from {@code scheduler.MaterialLut} so both the engine
     * registration and assembly can reference it without a scheduler dependency). Per spec invariant 5
     * it is the lightest <i>movable</i> fluid: {@code molar==0, minMass==0, maxMass==0} with a FINITE
     * viscosity (displaceable, not frozen); conductivity 0 keeps it inert to heat flux.
     */
    public static final Material VACUUM = Material.builder(
                    Identifier.fromNamespaceAndPath("orge", "vacuum"))
            .thermalConductivity(0f)
            .heatCapacity(1f)
            .molarMass(0f)
            .defaultMass(0f)
            .defaultTemperature(Float.NaN)
            .viscosity(0f)
            .minMass(0f).maxMass(0f)
            .build();

    /** The ordered table: slot 0 = VACUUM, slots 1..N = {@code registry.all()} sorted by id string. */
    public static List<Material> ordered(MaterialRegistry registry) {
        List<Material> out = new ArrayList<>();
        out.add(VACUUM);
        registry.all().stream()
                .sorted(Comparator.comparing(m -> m.id().toString()))
                .forEach(out::add);
        return List.copyOf(out);
    }

    /** Reverse map material id → fixed slot, for {@code ordered}. Guards the 65535-slot char ceiling. */
    public static Map<Identifier, Character> slots(List<Material> ordered) {
        if (ordered.size() > Character.MAX_VALUE + 1) {
            throw new IllegalStateException("material table overflow: more than 65535 materials");
        }
        Map<Identifier, Character> m = new HashMap<>(ordered.size() * 2);
        for (int i = 0; i < ordered.size(); i++) {
            m.put(ordered.get(i).id(), (char) i);
        }
        return Map.copyOf(m);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*MaterialTableTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/material/MaterialTable.java \
        core/src/test/java/net/rainbowcreation/orge/material/MaterialTableTest.java
git commit -m "feat(material): MaterialTable — stable ordered LUT (slot0=VACUUM, sorted by id)"
git push origin rebuild
```

---

### Task 5: `MaterialLut` becomes a view (fixed slots, no append)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialLut.java`
- Modify (test): `core/src/test/java/net/rainbowcreation/orge/scheduler/MaterialLutTest.java`

- [ ] **Step 1: Rewrite the test to assert the VIEW semantics (fixed slots, no append)**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaterialLutTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    private static MaterialLut view(Material... reals) {
        List<Material> ordered = new java.util.ArrayList<>();
        ordered.add(MaterialTable.VACUUM);
        for (Material m : reals) ordered.add(m);
        List<Material> immut = List.copyOf(ordered);
        return new MaterialLut(immut, MaterialTable.slots(immut));
    }

    @Test
    void slotZeroIsVacuum() {
        MaterialLut lut = view();
        assertEquals(MaterialTable.VACUUM, lut.materials().get(0));
        assertEquals((char) 0, lut.indexOf(MaterialTable.VACUUM));
    }

    @Test
    void indexOfReturnsFixedSlotNotInsertionOrder() {
        Material water = mat("orge:water");
        Material air = mat("orge:air");
        // order passed in is water,air but the slots come from the table (built by caller); here we
        // build a view where air=1, water=2 to prove indexOf reads the FIXED slot, not first-seen.
        List<Material> ordered = List.of(MaterialTable.VACUUM, air, water);
        MaterialLut lut = new MaterialLut(ordered, MaterialTable.slots(ordered));
        assertEquals((char) 1, lut.indexOf(air));
        assertEquals((char) 2, lut.indexOf(water));
    }

    @Test
    void indexOfUnknownIdIsVacuumSentinel() {
        MaterialLut lut = view(mat("orge:water"));
        assertEquals((char) 0, lut.indexOf(Identifier.parse("orge:never_registered")));
    }

    @Test
    void indexOfNeverAppends() {
        MaterialLut lut = view(mat("orge:water"));
        int before = lut.materials().size();
        lut.indexOf(Identifier.parse("orge:unknown"));   // must not grow the table
        assertEquals(before, lut.materials().size());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*MaterialLutTest'`
Expected: FAIL — no `MaterialLut(List, Map)` constructor; the old appending API still in place.

- [ ] **Step 3: Rewrite `MaterialLut` as a view**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;

import java.util.List;
import java.util.Map;

/**
 * A read-only VIEW over a published {@link net.rainbowcreation.orge.material.ActiveMaterials.State}'s
 * stable material table (spec 2026-06-03-engine-resident-material-table §4/§6). {@code matIx} ids are
 * now <b>globally stable</b>: slot 0 = {@link #VACUUM}, slots 1..N fixed at load/{@code /reload} by
 * {@link MaterialTable}. {@code indexOf} returns the fixed slot for an id and NEVER appends; there is
 * no per-step instance and no cross-tick instability.
 *
 * <p>(Historical: this class used to be {@code new}-ed per step and assigned indices in first-seen
 * encounter order. That batch-local model is gone — see the spec.)</p>
 */
public final class MaterialLut {

    /** The index-0 vacuum sentinel; aliases {@link MaterialTable#VACUUM} (its canonical home). */
    public static final Material VACUUM = MaterialTable.VACUUM;

    private final List<Material> ordered;          // slot -> material (slot 0 = VACUUM); immutable
    private final Map<Identifier, Character> byId;  // material id -> fixed slot; immutable

    /** Wrap a published stable table. Both args come from {@link MaterialTable} (same State). */
    public MaterialLut(List<Material> ordered, Map<Identifier, Character> byId) {
        this.ordered = ordered;
        this.byId = byId;
    }

    /** Fixed slot for {@code material}, or 0 (VACUUM) if its id is not in the table. Never appends. */
    public char indexOf(Material material) {
        return indexOf(material.id());
    }

    /** Fixed slot for {@code id}, or 0 (the {@link #VACUUM} sentinel) if this id is not in the table. */
    public char indexOf(Identifier id) {
        Character existing = byId.get(id);
        return existing != null ? existing : (char) 0;
    }

    /** The table handed to the engine register/assembly; index 0 = {@link #VACUUM}. Immutable. */
    public List<Material> materials() {
        return ordered;
    }
}
```

- [ ] **Step 4: Run the MaterialLut test**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*MaterialLutTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Fix the other tests that `new MaterialLut()` (conflict #31,#32,#33)**

These will no longer compile (the no-arg appending ctor is gone). Update each to build a view from a table. For `core/src/test/java/.../scheduler/GeometryAssemblerTest.java:22,37,85`, `ColumnAssemblerTest.java:99`, and `engine/TestMaterials.java:81`, replace `new MaterialLut()` with a small helper:

```java
// drop-in: a view holding VACUUM + the materials this test needs, slots from MaterialTable
static MaterialLut lutOf(net.rainbowcreation.orge.material.Material... reals) {
    java.util.List<net.rainbowcreation.orge.material.Material> ordered = new java.util.ArrayList<>();
    ordered.add(net.rainbowcreation.orge.material.MaterialTable.VACUUM);
    for (var m : reals) ordered.add(m);
    java.util.List<net.rainbowcreation.orge.material.Material> immut = java.util.List.copyOf(ordered);
    return new MaterialLut(immut, net.rainbowcreation.orge.material.MaterialTable.slots(immut));
}
```

> For each test, pass the materials it previously appended (read the test to see which ids it exercises) so the resulting slots match its assertions. If an assertion hard-codes a first-seen index that no longer holds under id-sorting, update the expected index to the sorted slot. `InjectionDrainTest.java:26-31` (conflict #34) — update the comment and the expected void/air/water indices to the stable slots (`MaterialTable.slots`).

- [ ] **Step 6: Run the full unit suite**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: GREEN (all `MaterialLut`-touching tests updated; nothing else changed yet).

- [ ] **Step 7: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialLut.java core/src/test/java
git commit -m "refactor(scheduler): MaterialLut is a stable-slot view (no first-seen append)"
git push origin rebuild
```

---

### Task 6: `ActiveMaterials.State` carries the ordered table + slots + epoch

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/ActiveMaterials.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/ActiveMaterialsTableTest.java`

> This task adds the data to `State` and a one-shot epoch setter, but does **not** call the engine yet (that is Task 12, after `OrgeEngine.registerMaterials` exists).

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActiveMaterialsTableTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void stateExposesOrderedTableSlot0Vacuum() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.register(mat("orge:water"));
        ActiveMaterials.State s = new ActiveMaterials.State(reg);
        assertEquals(MaterialTable.VACUUM.id(), s.orderedMaterials().get(0).id());
        assertEquals((char) 0, s.materialSlots().get(MaterialTable.VACUUM.id()));
        assertEquals((char) 1, s.materialSlots().get(Identifier.parse("orge:water")));
    }

    @Test
    void swapAssignsMonotonicEpochs() {
        ActiveMaterials.State a = new ActiveMaterials.State(new MaterialRegistry());
        ActiveMaterials.State b = new ActiveMaterials.State(new MaterialRegistry());
        ActiveMaterials.swap(a);
        int ea = ActiveMaterials.current().lutEpoch();
        ActiveMaterials.swap(b);
        int eb = ActiveMaterials.current().lutEpoch();
        assertTrue(eb > ea, "each swap bumps the epoch (" + ea + " -> " + eb + ")");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*ActiveMaterialsTableTest'`
Expected: FAIL — `State.orderedMaterials()/materialSlots()/lutEpoch()` do not exist.

- [ ] **Step 3: Extend `State` (build the table in the ctor; epoch assigned once at swap)**

In `ActiveMaterials.java`, replace the `State` class body with:

```java
    public static final class State {
        private final MaterialRegistry registry;
        private final java.util.List<Material> orderedMaterials;       // slot -> material (slot 0 = VACUUM)
        private final java.util.Map<Identifier, Character> materialSlots; // id -> fixed slot
        private volatile int lutEpoch = 0;                              // assigned once by swap()

        public State(MaterialRegistry registry) {
            this.registry = registry;
            this.orderedMaterials = MaterialTable.ordered(registry);
            this.materialSlots = MaterialTable.slots(this.orderedMaterials);
        }

        public MaterialRegistry registry() {
            return registry;
        }

        /** The stable ordered table (slot 0 = VACUUM); the engine is registered from this list. */
        public java.util.List<Material> orderedMaterials() {
            return orderedMaterials;
        }

        /** Material id → fixed slot, for building a {@code MaterialLut} view at assembly time. */
        public java.util.Map<Identifier, Character> materialSlots() {
            return materialSlots;
        }

        /** The generation tag selecting this State's resident engine table (spec §5/§7). */
        public int lutEpoch() {
            return lutEpoch;
        }

        /** One-shot epoch assignment at publish time (package-private; called only by swap). */
        void assignEpoch(int epoch) {
            this.lutEpoch = epoch;
        }
    }
```

- [ ] **Step 4: Bump the epoch in `swap()` (engine register still NOT wired — Task 12)**

Add the counter field near `active`:

```java
    private static final java.util.concurrent.atomic.AtomicInteger EPOCHS =
            new java.util.concurrent.atomic.AtomicInteger(0);
```

And change `swap`:

```java
    public static void swap(State next) {
        if (next == null) {
            throw new NullPointerException("next state must not be null");
        }
        next.assignEpoch(EPOCHS.incrementAndGet());
        active = next;
        // Engine registration is wired in Task 12 (needs OrgeEngine.registerMaterials + the singleton).
    }
```

Add the import (top of file): `import net.minecraft.resources.Identifier;` is already implied by usage in the new `State`; ensure it's imported.

- [ ] **Step 5: Run the test + full unit suite**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*ActiveMaterialsTableTest' && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: both GREEN.

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/material/ActiveMaterials.java \
        core/src/test/java/net/rainbowcreation/orge/material/ActiveMaterialsTableTest.java
git commit -m "feat(material): State carries stable ordered table + slots + per-swap lutEpoch"
git push origin rebuild
```

---

### Task 7: Assemble from the published table; `ColumnBatch` carries `lutEpoch`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java` (`ColumnBatch`)
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java` (snapshot path)
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/StableIdAssemblyTest.java`

- [ ] **Step 1: Write the failing stable-id test (assemble the same world twice → identical matIx)**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec §9.3: assembling the same world twice (two ticks) yields identical matIx, and ids match the
 *  registry sort order — proving the engine-bound encoding is globally stable, not first-seen. */
class StableIdAssemblyTest {

    private static Material mat(String id) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(1f).thermalConductivity(1f).molarMass(1f)
                .minMass(0f).maxMass(1f).viscosity(0f)
                .defaultMass(0f).defaultTemperature(300f).build();
    }

    @Test
    void twoViewsOverSameTableAgreeOnSlots() {
        MaterialRegistry reg = new MaterialRegistry();
        reg.register(mat("orge:water"));
        reg.register(mat("orge:air"));
        List<Material> ordered = MaterialTable.ordered(reg);
        Map<Identifier, Character> slots = MaterialTable.slots(ordered);

        MaterialLut tickA = new MaterialLut(ordered, slots);
        MaterialLut tickB = new MaterialLut(ordered, slots);
        assertEquals(tickA.indexOf(Identifier.parse("orge:water")),
                     tickB.indexOf(Identifier.parse("orge:water")));
        assertEquals(tickA.indexOf(Identifier.parse("orge:air")),
                     tickB.indexOf(Identifier.parse("orge:air")));
        // ids follow registry sort order (air < water), independent of registration order
        assertEquals((char) 1, tickA.indexOf(Identifier.parse("orge:air")));
        assertEquals((char) 2, tickA.indexOf(Identifier.parse("orge:water")));
    }
}
```

- [ ] **Step 2: Run to verify it fails/compiles-then-passes**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*StableIdAssemblyTest'`
Expected: PASS already (it only uses Task 4/5 types) — this guards the invariant. If it fails, fix before proceeding.

- [ ] **Step 3: Add `lutEpoch` to `ColumnBatch`**

In `ThermalWorld.java`, replace the `ColumnBatch` record with:

```java
    /** A cycle's worth of column work: the dimension-tagged columns + the shared stable material table,
     *  the {@code lutEpoch} selecting the engine-resident table, plus this cycle's drained placement
     *  injections (and the intents they came from, for clear-on-success). */
    record ColumnBatch(List<ColumnEntry> entries, List<Material> lut, int lutEpoch,
                       List<net.rainbowcreation.orge.engine.EngineInjection> injections,
                       List<PendingInjections.Intent> drained) {
        /** Back-compat: no injections, epoch 0 (empty/no-real-table cycles). */
        public ColumnBatch(List<ColumnEntry> entries, List<Material> lut) {
            this(entries, lut, 0, List.of(), List.of());
        }
        /** Back-compat: epoch only, no injections. */
        public ColumnBatch(List<ColumnEntry> entries, List<Material> lut, int lutEpoch) {
            this(entries, lut, lutEpoch, List.of(), List.of());
        }
    }
```

The default `snapshotColumns` (same file) stays valid via the 2-arg ctor:

```java
    default ColumnBatch snapshotColumns(int range) {
        return new ColumnBatch(List.of(), List.of(MaterialLut.VACUUM));
    }
```

- [ ] **Step 4: Build the view from the State + thread the epoch in `MinecraftThermalWorld.snapshotColumns`**

At `MinecraftThermalWorld.java:510-511` replace:

```java
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut();
```
with:
```java
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut(mats.orderedMaterials(), mats.materialSlots());
```

At `MinecraftThermalWorld.java:604` replace the return:

```java
        return new ColumnBatch(entries, lut.materials(), injections, drained);
```
with (carry the State's epoch):
```java
        return new ColumnBatch(entries, lut.materials(), mats.lutEpoch(), injections, drained);
```

> `ColumnAssembler.assemble(...)` and the resolver (`lut.indexOf(m)`) are **unchanged** — they now resolve fixed slots through the view because every registry material (plus VACUUM) is already in the table. No append happens; the `columnSource(level, dim, store, lut, mats)` path likewise reads fixed slots. The early `srv == null` return (line 508) keeps the 2-arg ctor (epoch 0) — fine, entries are empty.

- [ ] **Step 5: Run the unit suite**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: GREEN.

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/StableIdAssemblyTest.java
git commit -m "feat(scheduler): assemble from published stable table; ColumnBatch carries lutEpoch"
git push origin rebuild
```

---

## Phase 3 — Register-once + drop LUT from the call

### Task 8: `OrgeEngine` interface — add `registerMaterials`, drop the `lut` param

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java`

> This breaks `NativeEngine`/`StubEngine`/`Scheduler` compilation until Tasks 9–13 land. Do Tasks 8→13 as one contiguous run; the suite is only re-green at Task 14. (If your workflow needs every task to compile, fold 8–10 into a single commit — note this in the commit.)

- [ ] **Step 1: Replace the two `stepWorld` overloads and add `registerMaterials`**

Replace the interface body's step methods + javadoc with:

```java
    /**
     * Register the resident material table for {@code lutEpoch} (spec §5.1). Called on every publish
     * (initial datapack load + each {@code /reload}). The engine holds the table until evicted; later
     * {@link #stepWorld} calls select it by epoch. The {@link StubEngine} no-ops (records matCount only).
     *
     * @param lutEpoch generation tag (from {@code ActiveMaterials.State.lutEpoch()})
     * @param table    the stable ordered table (slot 0 = VACUUM), {@code MaterialTable.ordered(...)}
     */
    void registerMaterials(int lutEpoch, java.util.List<net.rainbowcreation.orge.material.Material> table);

    /**
     * Step the joined active region as one engine {@code World}. The material physics is supplied
     * out-of-band by {@link #registerMaterials}; this call selects it by {@code lutEpoch}. An unknown
     * epoch (none registered yet / evicted) is a safe no-op (inputs pass through unchanged).
     *
     * @param columns   full-height columns to step
     * @param lutEpoch  selects the resident table (spec §5.2)
     * @param dtSeconds time step (1.0 s conduction; 0.25 s advection quantum)
     * @param passes    pass bitmask ({@link #PASS_CONDUCTION} and/or {@link #PASS_ADVECTION})
     */
    java.util.List<ColumnResult> stepWorld(java.util.List<ColumnTask> columns,
                                           int lutEpoch, double dtSeconds, int passes);

    /**
     * Injection-aware step (placement displace-and-inject). Applies {@code injections} once before
     * advection. The default delegates to the 4-arg form with a zero (empty) ledger; {@link NativeEngine}
     * and {@link StubEngine} override to size the ledger from the registered table.
     */
    default RegionStepResult stepWorld(java.util.List<ColumnTask> columns,
                                       int lutEpoch, double dtSeconds, int passes,
                                       java.util.List<EngineInjection> injections) {
        java.util.List<ColumnResult> cols = stepWorld(columns, lutEpoch, dtSeconds, passes);
        return new RegionStepResult(cols, new float[0], new float[0]);
    }
```

Keep `PASS_CONDUCTION`/`PASS_ADVECTION` and `lastStepMillis()` as-is. (Conflict #6/#36 resolved: the `lut` param and its "indexed by matIx" javadoc are gone.)

- [ ] **Step 2: (compiles after Tasks 9–10)** — proceed; do not run the suite yet.

---

### Task 9: `NativeEngine` — native register + re-signed step; `RegionMarshaller` drops lut

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/RegionMarshaller.java`

- [ ] **Step 1: `RegionMarshaller` — drop `lut` from `Flat` and `flatten`**

In `RegionMarshaller.java` replace the record + `flatten`:

```java
    public record Flat(int nCols, int[] cx, int[] cz,
                       char[] matIx, float[] mass, float[] tIn) {}

    public static Flat flatten(List<ColumnTask> cols) {
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
        return new Flat(n, cx, cz, matIx, mass, tIn);
    }
```

Remove the now-unused `import net.rainbowcreation.orge.material.Material;` if nothing else in the file uses it. (`LutArrays` is untouched; its `pack` now has exactly one caller — `NativeEngine.registerMaterials`, below.)

- [ ] **Step 2: `NativeEngine` — native decls (match `orge_jni.cpp` arg order EXACTLY)**

Replace the `orgeStepWorld` native declaration block (lines ~47-57) with both natives:

```java
    /** Register the resident table for an epoch (spec §5.1). Arg order MUST match orge_jni.cpp. */
    private static native void orgeRegisterMaterials(
            int lutEpoch, int matCount,
            float[] cond, float[] heatCap, float[] molar,
            float[] minMass, float[] maxMass, float[] visc);

    /** Step selecting the resident table by epoch (spec §5.2). Arg order MUST match orge_jni.cpp. */
    private static native double orgeStepWorld(
            int lutEpoch,
            int nCols, int[] cx, int[] cz,
            char[] matIx, float[] mass, float[] tIn,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut,
            int injCount,
            int[] injColumn, int[] injCell,
            char[] injSpecies, float[] injMass, float[] injTemp,
            float[] ledgerOut);
```

- [ ] **Step 3: Add the per-epoch matCount memory + `registerMaterials`**

Add a field near the other instance fields:

```java
    // Remembers each registered epoch's material count, so stepWorld can size the placement ledger
    // (2*matCount) without re-shipping the table. Mirrors the native resident store's keep-last-2.
    private final java.util.Map<Integer, Integer> epochMatCount = new java.util.concurrent.ConcurrentHashMap<>();
```

And the method:

```java
    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        if (table.isEmpty()) return;               // bootstrap empty state: nothing to register
        LutArrays L = LutArrays.pack(table);
        orgeRegisterMaterials(lutEpoch, L.matCount(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc());
        epochMatCount.put(lutEpoch, table.size());
    }
```

- [ ] **Step 4: Rewrite `stepWorld` to pass `lutEpoch` and size the ledger from `epochMatCount`**

Replace both `stepWorld` overrides with:

```java
    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, int lutEpoch,
                                        double dtSeconds, int passes) {
        return stepWorld(columns, lutEpoch, dtSeconds, passes, java.util.List.of()).columns();
    }

    @Override
    public RegionStepResult stepWorld(List<ColumnTask> columns, int lutEpoch,
                                      double dtSeconds, int passes,
                                      List<EngineInjection> injections) {
        int matCount = epochMatCount.getOrDefault(lutEpoch, 0);
        if (columns.isEmpty()) {
            lastStepMillis = 0.0;
            return new RegionStepResult(new ArrayList<>(), new float[matCount], new float[matCount]);
        }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);

        int injCount = injections.size();
        int[] injCol; int[] injCell; char[] injSp; float[] injMs; float[] injTp; float[] ledgerOut;
        if (injCount == 0) {
            injCol = EMPTY_INT; injCell = EMPTY_INT; injSp = EMPTY_CHAR;
            injMs = EMPTY_FLOAT; injTp = EMPTY_FLOAT; ledgerOut = EMPTY_FLOAT;
        } else {
            injCol = new int[injCount]; injCell = new int[injCount]; injSp = new char[injCount];
            injMs = new float[injCount]; injTp = new float[injCount];
            for (int k = 0; k < injCount; k++) {
                EngineInjection in = injections.get(k);
                injCol[k] = in.columnId(); injCell[k] = in.cellIndex();
                injSp[k] = in.species(); injMs[k] = in.mass(); injTp[k] = in.temperature();
            }
            ledgerOut = new float[2 * matCount];   // exactly what the native writes for this epoch
        }

        lastStepMillis = orgeStepWorld(
                lutEpoch, f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                passes, dtSeconds, tOut, massOut, matOut,
                injCount, injCol, injCell, injSp, injMs, injTp, ledgerOut);

        float[] injected = new float[matCount];
        float[] sealedLoss = new float[matCount];
        if (injCount > 0 && matCount > 0) {
            System.arraycopy(ledgerOut, 0, injected, 0, matCount);
            System.arraycopy(ledgerOut, matCount, sealedLoss, 0, matCount);
        }
        return new RegionStepResult(
                RegionMarshaller.slice(matOut, massOut, tOut, f.nCols()), injected, sealedLoss);
    }
```

Keep the `import net.rainbowcreation.orge.material.Material;` (used by `registerMaterials`), `EMPTY_*` constants, `ScratchPool`, `lastStepMillis()`.

- [ ] **Step 4b: (compiles after Task 10)** — proceed.

---

### Task 10: `StubEngine` mirrors the new shape

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java`

- [ ] **Step 1: Rewrite to register (remember matCount) + step by epoch**

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A no-op {@link OrgeEngine} for headless/native-absent runs: returns each column's input unchanged.
 * {@code registerMaterials} records only the per-epoch material count so {@link #stepWorld} can size a
 * (zero) placement ledger; no physics runs (DESIGN.md §2).
 */
public final class StubEngine implements OrgeEngine {

    private double lastStepMillis = 0.0;
    private final Map<Integer, Integer> epochMatCount = new ConcurrentHashMap<>();

    @Override
    public void registerMaterials(int lutEpoch, List<Material> table) {
        epochMatCount.put(lutEpoch, table.size());
    }

    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, int lutEpoch,
                                        double dtSeconds, int passes) {
        List<ColumnResult> out = new ArrayList<>(columns.size());
        for (ColumnTask c : columns) {
            out.add(new ColumnResult(c.matIx().clone(), c.mass().clone(), c.temperature().clone()));
        }
        lastStepMillis = 0.0;
        return out;
    }

    @Override
    public RegionStepResult stepWorld(List<ColumnTask> columns, int lutEpoch,
                                      double dtSeconds, int passes, List<EngineInjection> injections) {
        int n = epochMatCount.getOrDefault(lutEpoch, 0);
        return new RegionStepResult(stepWorld(columns, lutEpoch, dtSeconds, passes), new float[n], new float[n]);
    }

    @Override
    public double lastStepMillis() {
        return lastStepMillis;
    }
}
```

---

### Task 11: `EngineFactory` — lazy shared singleton

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/EngineFactory.java`

- [ ] **Step 1: Add `instance()`; route `create()` through it**

Replace the class body below the logger with:

```java
    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private static volatile OrgeEngine instance;

    private EngineFactory() {}

    /**
     * The single shared engine for this process. {@code ActiveMaterials.swap()} registers the resident
     * table on it and the {@code Scheduler} steps it — they MUST be the same instance (decision §10.3).
     */
    public static OrgeEngine instance() {
        OrgeEngine e = instance;
        if (e == null) {
            synchronized (EngineFactory.class) {
                e = instance;
                if (e == null) { e = build(); instance = e; }
            }
        }
        return e;
    }

    /** Back-compat entry point ({@code Orge.java}); returns the shared singleton. */
    public static OrgeEngine create() {
        return instance();
    }

    private static OrgeEngine build() {
        try {
            NativeLoader.load();
            LOGGER.info("[ORGE] engine = NativeEngine (native liborge loaded from {}) -- "
                    + "full physics ACTIVE: conduction + advection + phase change.",
                    NativeLoader.resolvedResource());
            return new NativeEngine();
        } catch (Throwable t) {
            LOGGER.warn("[ORGE] engine = StubEngine (NO-OP) -- native liborge unavailable for this platform "
                    + "(tried {}). NO physics will run: fluids will not move and temperatures will not change. "
                    + "Reason: {}", NativeLoader.resolvedResource(), t.toString());
            return new StubEngine();
        }
    }
```

> `Orge.java:151` (`EngineFactory.create()`) is unchanged and now receives the singleton — the same instance `ActiveMaterials.swap()` will register into (Task 12).

---

### Task 12: `ActiveMaterials.swap()` registers the engine

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/ActiveMaterials.java`

- [ ] **Step 1: Call `registerMaterials` from `swap` via the singleton**

Change `swap` (the engine register line added now that the API exists):

```java
    public static void swap(State next) {
        if (next == null) {
            throw new NullPointerException("next state must not be null");
        }
        int epoch = EPOCHS.incrementAndGet();
        next.assignEpoch(epoch);
        active = next;
        // Publish the stable table to the resident engine (initial load + every /reload). The shared
        // singleton is the same instance the Scheduler steps; StubEngine no-ops in native-absent runs.
        net.rainbowcreation.orge.engine.EngineFactory.instance()
                .registerMaterials(epoch, next.orderedMaterials());
    }
```

> Package cycle note: `material` now references `engine.EngineFactory` while `engine.LutArrays` references `material.Material`. Java permits package cycles; there is no class-init cycle (the initial `active = new State(...)` bypasses `swap`, so the engine is built lazily on the first real publish, by which point all classes are loaded).

- [ ] **Step 2: (full suite green at Task 14)** — proceed to Task 13.

---

### Task 13: `Scheduler` passes `lutEpoch` into the step

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`

- [ ] **Step 1: Capture the epoch and pass it to `engine.stepWorld`**

At `Scheduler.java:199-200`, keep `pendingMaterials` (still used by the §9 ledger) and add the epoch:

```java
        List<Material> lut = batch.lut();
        pendingMaterials = lut;
        final int lutEpoch = batch.lutEpoch();
```

In the submitted lambda (around line 223), replace the engine call:

```java
                net.rainbowcreation.orge.engine.RegionStepResult rr =
                        engine.stepWorld(input, lutEpoch, dt,
                                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, injections);
```

> `pendingMaterials` (the ordered table) still feeds `StepValidator.SpeciesMassLedger.add(..., pendingMaterials)` and `expect(...)` — the §9 region gate is unchanged. Only the *engine input* switches from the material list to the epoch.

- [ ] **Step 2: Compile the whole module**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL (all of Tasks 8–13 now consistent). Fix any remaining call sites the compiler flags (e.g. test fakes implementing `OrgeEngine` must add `registerMaterials` + the new `stepWorld` shape).

- [ ] **Step 3: Run the full unit suite**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: GREEN. Update any test `OrgeEngine` fakes / `stepWorld(... lut ...)` call sites the compiler surfaced (search: `grep -rn "stepWorld(" core/src/test`).

- [ ] **Step 4: Commit Tasks 8–13 together**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java \
        core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java \
        core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java \
        core/src/main/java/net/rainbowcreation/orge/engine/RegionMarshaller.java \
        core/src/main/java/net/rainbowcreation/orge/engine/EngineFactory.java \
        core/src/main/java/net/rainbowcreation/orge/material/ActiveMaterials.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java \
        core/src/test/java
git commit -m "feat(engine): register-once resident table; drop LUT from stepWorld (epoch-selected)"
git push origin rebuild
```

---

## Phase 4 — Integration + parity gate (real `.so`)

### Task 14: `:core:integrationTest` — golden parity + reload-epoch

**Files:**
- Create: `core/src/test/java/net/rainbowcreation/orge/engine/ResidentLutParityIT.java`

> The `.so` was rebuilt in Task 3. Ensure the integrationTest source set loads it (same mechanism the existing `:core:integrationTest` uses). Guard with `NativeLoader.load()` so the IT is skipped where the native is absent (match the existing IT pattern in the repo).

- [ ] **Step 1: Write the parity + reload IT**

```java
package net.rainbowcreation.orge.engine;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResidentLutParityIT {

    private static final int N = RegionMarshaller.CHUNK_N;

    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }

    private static Material mat(String id, float hc, float k, float mol, float mn, float mx, float visc) {
        return Material.builder(Identifier.parse(id))
                .heatCapacity(hc).thermalConductivity(k).molarMass(mol)
                .minMass(mn).maxMass(mx).viscosity(visc)
                .defaultMass(0f).defaultTemperature(300f).build();
    }
    private static void put(char[] mi, float[] m, float[] t, int x, int y, int z, char ix, float mass, float temp) {
        int i = x + 16 * y + 6144 * z; mi[i] = ix; m[i] = mass; t[i] = temp;
    }

    /** The SAME deterministic table + world the Task-0 golden was captured from. */
    private static List<Material> lut() {
        List<Material> lut = new ArrayList<>();
        lut.add(mat("orge:void",  0f,   0f,    0f,    0f,   0f,   Float.POSITIVE_INFINITY));
        lut.add(mat("orge:water", 4186f,0.6f,  0.018f,125f, 1000f,0f));
        lut.add(mat("orge:lava",  1000f,1.0f,  0.100f,200f, 2000f,5000f));
        lut.add(mat("orge:air",   1005f,0.025f,0.029f,1.0f, 50f,  0f));
        return lut;
    }
    private static ColumnTask seedColumn() {
        char[] matIx = new char[N]; float[] mass = new float[N]; float[] tIn = new float[N];
        for (int i = 0; i < N; i++) { matIx[i] = 0; mass[i] = 0f; tIn[i] = 300f; }
        put(matIx, mass, tIn, 8, 40, 8, (char) 1, 1000f, 300f);
        put(matIx, mass, tIn, 8, 38, 8, (char) 3, 30f,   300f);
        put(matIx, mass, tIn, 8, 36, 8, (char) 2, 2000f, 1500f);
        return new ColumnTask(0, 0, matIx, mass, tIn);
    }

    @Test
    void registerOnceRunsBitIdenticalToGolden() throws Exception {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        engine.registerMaterials(7, lut());      // register ONCE under epoch 7

        List<ColumnTask> cols = List.of(seedColumn());
        ColumnResult r = null;
        for (int s = 0; s < 8; s++) {            // 8 steps WITHOUT re-passing the LUT
            r = engine.stepWorld(cols, 7, 0.25,
                    OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
            cols = List.of(new ColumnTask(0, 0, r.matIx(), r.mass(), r.temperature()));
        }

        // Compare against the frozen golden (captured pre-refactor on the old per-step-LUT path).
        try (InputStream in = getClass().getResourceAsStream("/golden/resident-lut-step.bin");
             DataInputStream g = new DataInputStream(in)) {
            assertNotNull(in, "golden resource present");
            assertEquals(N, g.readInt(), "golden cell count");
            for (int i = 0; i < N; i++) assertEquals(g.readChar(),  r.matIx()[i],       "matIx[" + i + "]");
            for (int i = 0; i < N; i++) assertEquals(g.readFloat(), r.mass()[i],   0f,   "mass["  + i + "]");
            for (int i = 0; i < N; i++) assertEquals(g.readFloat(), r.temperature()[i], 0f, "temp[" + i + "]");
        }
    }

    @Test
    void oldEpochBatchStillStepsUnderItsTableWhileNewEpochIsLive() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        engine.registerMaterials(1, lut());                 // epoch 1
        // epoch 2: a DIFFERENT table (water has higher viscosity) — registering it must not disturb 1
        List<Material> lut2 = lut();
        lut2.set(1, mat("orge:water", 4186f, 0.6f, 0.018f, 125f, 1000f, 9000f));
        engine.registerMaterials(2, lut2);

        List<ColumnTask> cols = List.of(seedColumn());
        ColumnResult underEpoch1 = engine.stepWorld(cols, 1, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        // re-run a fresh seed under epoch 1 only (no epoch 2 step) — must equal the first epoch-1 result,
        // proving epoch 2's presence did not overwrite epoch 1's resident table.
        ColumnResult underEpoch1Again = engine.stepWorld(List.of(seedColumn()), 1, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(underEpoch1.matIx()[i], underEpoch1Again.matIx()[i], "epoch-1 matIx stable @" + i);
            assertEquals(underEpoch1.mass()[i],  underEpoch1Again.mass()[i], 0f, "epoch-1 mass stable @" + i);
        }
    }

    @Test
    void unknownEpochIsSafeNoOp() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        OrgeEngine engine = new NativeEngine();
        ColumnTask seed = seedColumn();
        ColumnResult r = engine.stepWorld(List.of(seed), 999 /* never registered */, 0.25,
                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION).get(0);
        for (int i = 0; i < N; i++) {
            assertEquals(seed.matIx()[i], r.matIx()[i], "no-op matIx pass-through @" + i);
            assertEquals(seed.mass()[i],  r.mass()[i], 0f, "no-op mass pass-through @" + i);
            assertEquals(seed.temperature()[i], r.temperature()[i], 0f, "no-op temp pass-through @" + i);
        }
    }
}
```

- [ ] **Step 2: Run the integration suite on the real `.so`**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest`
Expected: GREEN. `registerOnceRunsBitIdenticalToGolden` proves the resident path is byte-identical to the pre-refactor golden; the reload + unknown-epoch tests prove §7 isolation and the no-op contract.

> If `registerOnceRunsBitIdenticalToGolden` fails on a handful of cells, the divergence is in the marshalling, not the kernel (the kernel is unchanged). Diff the failing indices; the usual culprit is a LUT field mismatch between the Task-0 golden table and `lut()` here (they MUST be identical). Do **not** "fix" by regenerating the golden — that would mask a real regression.

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE
git add core/src/test/java/net/rainbowcreation/orge/engine/ResidentLutParityIT.java
git commit -m "test(engine): resident-LUT parity IT (golden bit-identical + reload epoch + no-op)"
git push origin rebuild
```

---

### Task 15: Both loaders build

**Files:** none (build verification).

- [ ] **Step 1: Build both platform JARs**

Run: `cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :fabric:build :neoforge:build`
Expected: BUILD SUCCESSFUL; JARs under `fabric/build/libs/` and `neoforge/build/libs/`.

> Multiloader note (Architectury): all edited classes live in `core` (the shared common module), so no `@ExpectPlatform` impl or per-loader entrypoint changes are needed. The reload listener (`MaterialJsonLoader`) is common; both loaders register it the same way. If `:fabric:build`/`:neoforge:build` is not the exact task in this repo, use the repo's standard `./gradlew build` and confirm both loader JARs are produced.

- [ ] **Step 2: (no commit — verification only)**

---

## Phase 5 — Ship

### Task 16: Bump the engine gitlink in `rebuild`; final green; push

**Files:**
- Modify: the `ORGE-ENGINE` gitlink (submodule pointer) in the superproject.

- [ ] **Step 1: Point the superproject at the pushed engine commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE
git commit -m "chore: bump ORGE-ENGINE gitlink to resident-LUT engine (register-once + epoch step)"
git push origin rebuild
```
Expected: `git -C ORGE-ENGINE rev-parse HEAD` matches the gitlink the superproject now records (`git ls-tree rebuild ORGE-ENGINE`).

- [ ] **Step 2: Full green gate (engine cheap tier + Java suites + both loaders)**

```bash
cd /home/claude/ORGE
./ORGE-ENGINE/tests/run_tests.sh cheap
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest
JAVA_HOME=/home/claude/jdk21 ./gradlew :fabric:build :neoforge:build
```
Expected: all PASS / BUILD SUCCESSFUL.

---

### Task 17: Annotate stale docs (fold conflict inventory STALE-DOC items)

**Files (annotate — prepend the banner; do NOT rewrite the bodies):**
- `docs/orgestep.md`
- `docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md`
- `docs/superpowers/specs/2026-06-01-whole-region-engine-step-design.md`
- `docs/superpowers/specs/2026-05-30-scheduler-track-design.md`
- `docs/superpowers/plans/2026-05-30-scheduler-track.md`
- `DESIGN.md` (near the "given … the material LUT … run one step" line)
- `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md`
- `docs/superpowers/plans/2026-06-02-placement-injection-2-java.md`
- `docs/superpowers/plans/2026-06-03-durable-material-place-break.md`

- [ ] **Step 1: Prepend this banner at the top of each file above**

```markdown
> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.
```

- [ ] **Step 2: Add a one-line clarifier where the on-disk palette is described (avoid a false "fix" later)**

In `docs/superpowers/plans/2026-06-03-durable-material-place-break.md` near the `MaterialPalette … indexOf(id) appending on first sight` prose, add:

```markdown
> NOTE: the SectionData **on-disk** `MaterialPalette` legitimately stays first-seen (it is `Identifier`-keyed
> and stable); only the **engine-bound** `matIx` encoding became global. Do not conflate the two.
```

- [ ] **Step 3: Commit (docs only — no code risk)**

```bash
cd /home/claude/ORGE
git add docs DESIGN.md
git commit -m "docs: annotate stale per-step-LUT / first-seen-id passages (point to resident-table spec)"
git push origin rebuild
```

---

## Done criteria (the whole plan)

- `./ORGE-ENGINE/tests/run_tests.sh cheap` green incl. `resident_lut_test`; `sim_engine.hpp`/`orge_kernel.hpp` unchanged.
- `:core:test` + `:core:integrationTest` green on the real `.so`; `ResidentLutParityIT.registerOnceRunsBitIdenticalToGolden` proves byte-identical single-state output.
- `orgeStepWorld` no longer ships the 6 LUT arrays; `orgeRegisterMaterials` registers once on load + every `/reload`; unknown epoch is a safe no-op.
- `matIx` is globally stable (assemble-twice identical); reload races are isolated by epoch (keep last 2).
- Both loader JARs build; engine gitlink bumped; everything pushed to `origin/rebuild` (engine to `origin/main`).
- Stale docs annotated; the conflict inventory's BREAKS-SPEC items are all resolved (traceability table above).

## Sequencing reminder

This LUT refactor lands FIRST. The separately-tracked lava/water chained-displacement bug (RED repro at `ORGE-ENGINE/tests/lava_water_equilibrium_test.cpp` + `push_chain_tube_test.cpp`, not yet pushed; Pass B′ N-deep chained displacement) is a SEPARATE effort — do not let it bleed into this plan.
