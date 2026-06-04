# Engine B — Stage 1: Energy-Flux Mechanical Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Engine A's advection (Pass A sort / Pass B relax / Pass B′ displace / DFS / overburden) on the `rebuild` track with the unified **energy-vector** step — Encrypt → Resolve → Decrypt — for the *mechanical* channels only (mass, momentum, pressure, gravity), and plumb the new per-cell velocity channel end-to-end (engine ↔ JNI ↔ SectionStore). Conduction stays in the existing kernel until Stage 3.

**Architecture:** Each cell, in parallel, Encrypts its own state into one energy-flux vector `E=(Ex,Ey,Ez)` (gravity aims it, thermal is amplitude, pressure is isotropic). One Resolve pass nets antisymmetric face fluxes (advective mass+momentum, pressure-gradient kick) over `dt`. Decrypt re-partitions each cell's resolved `(Δm, Δp⃗)` back into mass + velocity. The new code lives in a fresh `engine_b.hpp` that **reuses** the existing `World`/`Chunk`/`Material`/`MaterialLUT`/snapshot machinery from `sim_engine.hpp`; the Engine-A advection passes are deleted once Engine B is wired. Physics is validated by *physical acceptance tests* (not golden parity — Engine B is a different model), proven in pure C++ first, then wired through JNI and Java.

**Tech Stack:** C++20 header-only engine (`liborge.so` via JNI), Java 21 (`:core` Architectury-common module, JUnit 5), Gradle. NeoForge + Fabric loaders consume `:core` unchanged.

---

## Scope of THIS plan (read first)

This plan covers **Stage 1 only** of the four-stage Engine-B build (design spec §11). Stage 1 = the energy-flux *mechanical* core + the velocity-channel plumbing. It produces working, testable software: a fluid engine that flows, sorts by density, displaces, pins, fills gas, and stays numerically stable, validated by acceptance **tests 1, 3, 4, 6, 8, 13, 15** (formula spec §I / §I-bis).

**Deliberately deferred to later stages** (each gets its own plan when reached, because their exact code depends on Stage-1 tuning and decisions — writing bite-sized steps now would be false precision):

- **Stage 2** — Inertia + emergent sort/bubbles + absorb/reflect commit (§C.5). Tests 2, 5, 7.
- **Stage 3** — Thermal unification: advected internal energy (the full `E_adv = ṁ·h` + `ΔE_th→T` closure, §C.2/§D.3) and the diffusive Fourier channel `q` (§C.3) **subsuming `orge_kernel.hpp`** (L8). Tests 9, 10, 11, 14.
- **Stage 4** — Calibrate globals `K, γ, α, T_ref, k, λ` (§G.2), slope-limiter + species sharpening (§K #4), perf. Tests 12, 16.

Source of truth for every formula below: `docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md` (RATIFIED) and its design companion `2026-06-04-engine-b-velocity-field-design.md`.

### Stage-1 simplifications vs the full formula (explicit, so they are not mistaken for bugs)

1. **Temperature is advected as a passive mass-weighted scalar**, not via the energy closure. When mass moves, the donor's `T` rides with it and the receiver mass-averages (`T' = (m·T + Σ ṁ·T_donor)/m'`). The full energy-conserving closure (flow-work, KE↔heat viscous dissipation, §D.3) lands in Stage 3. Conduction continues to run in the existing kernel (`compute_frame_to_backbuffers`) under `PASS_CONDUCTION` — untouched in Stage 1.
2. **No reflection redirect.** §C.5 absorb-vs-reflect *emerges* from the EOS already (a stiff receiver's `p` spikes and reverses the pressure flux next tick); Stage 1 relies on that emergent behavior and the capacity clamp. The explicit momentum-redirect-to-open-faces refinement is Stage 2.
3. **`E_adv` (energy carried by mass) is computed and stored on the vector** (so `E=(Ex,Ey,Ez)` is the real object the user designed) but its thermal *unpacking* is the passive scalar of (1). The vector is honored as the primary inter-cell object throughout.

### Engine-B globals (PROVISIONAL — calibrated in Stage 4, §G.2)

These starting values make Stage-1 acceptance tests pass *qualitatively* and stay sub-acoustic-CFL for typical materials. They are **not** the final tuned values; Stage 4 bisects each against its acceptance test. Do not spend Stage-1 effort tuning them beyond "tests 1/3/4/6/8/13/15 pass".

| sym | start | role |
|---|---|---|
| `K`     | `1.0e3f` | EOS stiffness (soft, pseudo-compressible — §G.1b) |
| `gamma` | `3.0f`   | wall sharpness `^γ` |
| `alpha` | `1.0f`   | thermal-expansion gain |
| `T_ref` | `288.0f` | EOS reference temp (K) |
| `g`     | `10.0f`  | gravity (m/s²), down = −y |
| `dx,V,A`| `1.0f`   | cell size / volume / face area |
| `eps_mass` | `1e-6f` | void floor (kg) |

---

## File Structure

**Created:**
- `ORGE-ENGINE/engine_b.hpp` — the entire Engine-B step: `orgeb::Globals`, `eos_pressure`, `CellEncrypt`/`encrypt_world`, `CellAccum`/`resolve_world`, `decrypt_world`, `step_world_b`. Header-only, depends only on `sim_engine.hpp` (for `World`/`Chunk`/`Material`/snapshot) and `<cmath>`. One clear responsibility: the unified mechanical fluid step.
- `ORGE-ENGINE/tests/engine_b_eos_test.cpp` — EOS unit tests.
- `ORGE-ENGINE/tests/engine_b_encrypt_test.cpp` — Encrypt unit tests.
- `ORGE-ENGINE/tests/engine_b_resolve_test.cpp` — Resolve unit tests (the §J worked numbers + conservation).
- `ORGE-ENGINE/tests/engine_b_decrypt_test.cpp` — Decrypt unit tests (vacuum guards, free-slip).
- `ORGE-ENGINE/tests/engine_b_step_test.cpp` — `step_world_b` invariants (rest, conservation, frozen floor).
- `ORGE-ENGINE/tests/engine_b_accept_test.cpp` — physical acceptance tests 1,3,4,6,8,13,15.
- `core/src/test/java/net/rainbowcreation/orge/engine/VelocityMarshallingTest.java` — RegionMarshaller velocity round-trip (unit).
- `core/src/test/java/net/rainbowcreation/orge/section/SectionVelocityTest.java` — SectionData velocity (unit).
- `core/src/test/java/net/rainbowcreation/orge/section/SectionCodecVelocityTest.java` — codec v3 round-trip + v1/v2 back-compat (unit).
- `core/src/test/java/net/rainbowcreation/orge/engine/EngineBVelocityIT.java` — native velocity round-trip + defaultMass LUT (integration).

**Modified:**
- `ORGE-ENGINE/sim_engine.hpp` — add `defaultMass` to `Material` (append); add `vx,vy,vz` to `Chunk` + `ChunkSnapshot` + `snapshot_world`.
- `ORGE-ENGINE/orge_jni.cpp` — `orgeRegisterMaterials` grows a 7th array (`jDefMass`); `orgeStepWorld` grows velocity in/out arrays and calls `step_world_b` under `PASS_ADVECTION`.
- `ORGE-ENGINE/tests/run_tests.sh` — swap Engine-A advection tests for the Engine-B test roster.
- `core/.../engine/NativeEngine.java` — native decls grow `defaultMass` + velocity arrays; `stepWorld`/`registerMaterials` plumb them.
- `core/.../engine/LutArrays.java` — pack `defaultMass` (7th array).
- `core/.../engine/RegionMarshaller.java` — `Flat` + `flatten`/`slice` grow `vx/vy/vz`.
- `core/.../engine/ScratchPool.java` — velocity scratch buffers.
- `core/.../section/SectionData.java` — `velX/velY/velZ` channels + accessors + promote/demote.
- `core/.../section/SectionCodec.java` — `FORMAT_VERSION = 3`; velocity block; v1/v2 read default-0.
- `core/.../scheduler/ColumnAssembler.java` — `SectionCells` grows velocity; assembly reads it.
- `core/.../scheduler/Scheduler.java` — write-back velocity to SectionStore.
- `core/.../scheduler/StepValidator.java` — `cleanVelocity` sanitizer.

**Deleted (in Task 9, once Engine B is wired):** `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, `compute_overburden`, `compute_overburden_samespecies`, `overburden_at`, the DFS push-train, `BAccum`/`apply_baccum`, `advect_world` (replaced by `step_world_b`); the Engine-A advection test files (see Task 9 for the exact roster).

---

## Build / test / push reference (this worktree)

- **Engine C++ cheap tier:** `cd /home/claude/ORGE-B/ORGE-ENGINE && ./tests/run_tests.sh`
- **Single C++ test at full workload:** `./tests/run_tests.sh <name>` (e.g. `engine_b_resolve_test`)
- **Engine quick (pre-commit):** `./tests/run_tests.sh quick` — **NEVER `full`**.
- **Rebuild `.so`:** `JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so`
- **Java gate:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest` (skipped=0 ⇒ real `.so`).
- **Both loaders build:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :neoforge-1.21:build :fabric-1.21:build`
- **Push (standing authorization, `rebuild`):** engine first (`git -C ORGE-ENGINE push origin rebuild`, verify HEAD==origin), then bump the parent gitlink and push parent. See memory `[[always-push-rebuild]]`.

**Manual ad-hoc C++ compile (for tests not yet in run_tests.sh):**
```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
g++ -std=c++20 -O2 -g -I. tests/<name>.cpp -o /tmp/<name> -pthread && /tmp/<name>
```

---

# STAGE 1A — Native energy-flux core (pure C++, no Java)

Prove the physics in C++ first. Every test in 1A compiles `engine_b.hpp`/`sim_engine.hpp` directly — no JNI, fast loop.

---

### Task 1: Add `defaultMass` to the engine `Material`

The EOS (§B.1) needs `m_0 = default_mass` (rest density at 1 atm, zero gauge pressure). The engine-side `Material` aggregate currently lacks it (Java's `Material` has it; only the engine dropped it). Append it so existing 6-field aggregate initializers still compile (`defaultMass` zero-inits) and Engine B can read it.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (the `Material` struct, ~lines 43–50)
- Test: `ORGE-ENGINE/tests/engine_b_eos_test.cpp` (created here; first assertion only)

- [ ] **Step 1: Write the failing test** — `ORGE-ENGINE/tests/engine_b_eos_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_eos_test.cpp -o /tmp/engine_b_eos_test
// Engine-B EOS unit tests (formula spec §B.1).
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)
#define CLOSE(a,b,tol,msg) do { if(std::fabs((a)-(b)) > (tol)){ \
    std::printf("FAIL: %s (got %.6g want %.6g)\n", msg, (double)(a), (double)(b)); ++failures; } } while(0)

int main(){
    // Material now carries defaultMass as the 7th field (append).
    // {heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass}
    Material water{4186.f, 0.6f, 0.018f, 125.f, 1100.f, 0.001f, 1000.f};
    CHECK(water.defaultMass == 1000.f, "defaultMass field present and assignable");

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_eos_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_eos_test.cpp -o /tmp/engine_b_eos_test 2>&1 | head`
Expected: FAIL to compile — `engine_b.hpp` does not exist yet AND `Material` has no `defaultMass`. (We create `engine_b.hpp` in Task 2; this step only needs the `Material` field. Temporarily change the include to `#include "sim_engine.hpp"` to confirm the field error, then revert to `engine_b.hpp` — OR accept the compile failure and proceed; the field is what Task 1 adds.)

To isolate Task 1's change, compile a one-liner instead:
```bash
echo '#include "sim_engine.hpp"
int main(){ Material m{}; m.defaultMass=1.f; return 0; }' > /tmp/t1.cpp
g++ -std=c++20 -I. /tmp/t1.cpp -o /tmp/t1 2>&1 | head
```
Expected: FAIL — `'struct Material' has no member named 'defaultMass'`.

- [ ] **Step 3: Add the field** — `ORGE-ENGINE/sim_engine.hpp`, `Material` struct

Append `defaultMass` as the **last** field (so `Material{a,b,c,d,e,f}` 6-arg aggregate init still works, `defaultMass` zero-inits):

```cpp
struct Material {
    float heatCapacity;        // J/(kg*K)
    float thermalConductivity; // W/(m*K)
    float molarMass;           // kg/mol
    float minMass;             // kg (EOS expansion floor)
    float maxMass;             // kg (EOS hard wall)
    float viscosity;           // Pa*s (+INF = frozen)
    float defaultMass = 0.0f;  // kg — EOS rest density m_0 (Engine B §B.1). Zero-inits for 6-arg aggregates.
};
```

- [ ] **Step 4: Verify the field compiles**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -I. /tmp/t1.cpp -o /tmp/t1 && echo OK`
Expected: `OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add sim_engine.hpp
git commit -m "feat(engine-b): add defaultMass (m_0) to engine Material for the EOS"
```

---

### Task 2: EOS pressure `eos_pressure` + `engine_b.hpp` skeleton

Implement §B.1: continuous gas↔liquid `χ`, heat-shifted `m_rest`, the stiff `^γ` compression wall and the `χ`-scaled expansion branch, `p=0` exactly at `m_rest`.

**Files:**
- Create: `ORGE-ENGINE/engine_b.hpp`
- Test: `ORGE-ENGINE/tests/engine_b_eos_test.cpp` (extend Task 1's file)

- [ ] **Step 1: Write the failing tests** — replace `ORGE-ENGINE/tests/engine_b_eos_test.cpp` body's `main` with:

```cpp
int main(){
    using namespace orgeb;
    Globals G;  // provisional defaults

    Material water{4186.f, 0.6f, 0.018f, 125.f, 1100.f, 0.001f, 1000.f};
    Material air  {1005.f, 0.026f,0.029f, 0.1f,  50.f,  1.8e-5f,1.2f };

    CHECK(water.defaultMass == 1000.f, "defaultMass field present");

    // p == 0 exactly at m_rest (== defaultMass at T_ref for a liquid: alpha*chi*(T-Tref)=0).
    CLOSE(eos_pressure(water, water.defaultMass, G.T_ref, G), 0.0f, 1e-3f, "liquid p==0 at m_rest");

    // Compression (m > m_rest) is positive and grows with overfill.
    float p_lo = eos_pressure(water, 1050.f, G.T_ref, G);
    float p_hi = eos_pressure(water, 1090.f, G.T_ref, G);
    CHECK(p_lo > 0.f && p_hi > p_lo, "liquid compression p>0 and monotone");

    // Gas below m_rest expands: p < 0 (drives toward min_mass).
    CHECK(eos_pressure(air, 0.5f, G.T_ref, G) < 0.f, "gas below rest expands (p<0)");

    // chi: gas (air) close to 1, liquid (water) close to 0.
    CHECK(chi(air)   > 0.9f, "air chi ~ 1 (gas)");
    CHECK(chi(water) < 0.2f, "water chi ~ 0 (liquid)");

    // Heating a gas lowers m_rest (thermal expansion) -> at fixed m, hotter is more expansive (more negative p below rest).
    float p_cold = eos_pressure(air, 1.0f, G.T_ref, G);
    float p_hot  = eos_pressure(air, 1.0f, G.T_ref + 200.f, G);
    CHECK(p_hot <= p_cold, "heating gas lowers m_rest (more expansive at fixed m)");

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_eos_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_eos_test.cpp -o /tmp/engine_b_eos_test 2>&1 | head`
Expected: FAIL to compile — `engine_b.hpp: No such file`.

- [ ] **Step 3: Create `engine_b.hpp` with `Globals`, `chi`, `eos_pressure`**

```cpp
#pragma once
// Engine B — unified energy-vector fluid step (mechanical core, Stage 1).
// Implements docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md (RATIFIED).
// Reuses World/Chunk/Material/MaterialLUT/snapshot from sim_engine.hpp. Header-only.
//
// Pipeline (the user's three words):
//   ENCRYPT (per cell, own state) -> one energy vector E=(Ex,Ey,Ez)
//   RESOLVE (the only cross-cell step) -> antisymmetric face fluxes over dt
//   DECRYPT (per cell, own state) -> re-partition into mass + velocity
// Stage 1 = mechanical channels only (mass, momentum, gravity, pressure). Temperature
// is advected as a passive mass-weighted scalar; conduction stays in orge_kernel.hpp
// under PASS_CONDUCTION until Stage 3.
#include "sim_engine.hpp"
#include <cmath>
#include <algorithm>
#include <limits>

namespace orgeb {

// PROVISIONAL globals (calibrated in Stage 4, formula spec §G.2). Not material fields.
struct Globals {
    float K        = 1.0e3f;  // EOS stiffness (soft pseudo-compressible, §G.1b acoustic CFL)
    float gamma    = 3.0f;    // wall sharpness (>= 2)
    float alpha    = 1.0f;    // thermal-expansion gain
    float T_ref    = 288.0f;  // EOS reference temperature (K)
    float g        = 10.0f;   // gravity magnitude (m/s^2), down = -y
    float dx       = 1.0f;    // cell edge (m)
    float V        = 1.0f;    // cell volume (m^3)
    float A        = 1.0f;    // face area (m^2)
    float eps_mass = 1e-6f;   // void floor (kg)
};

// Continuous gas<->liquid descriptor chi = (m_max - m_0)/(m_max - m_min) in [0,1].
// 0 = liquid (stiff, sharp free surface), 1 = gas (expands toward min_mass).
inline float chi(const Material& m) {
    float denom = m.maxMass - m.minMass;
    if (denom <= 1e-9f) return 0.0f;          // sand/solid (min==max): treat as liquid-like
    float c = (m.maxMass - m.defaultMass) / denom;
    return std::clamp(c, 0.0f, 1.0f);
}

// EOS pressure p(m,T) (formula spec §B.1). p == 0 exactly at m_rest.
inline float eos_pressure(const Material& m, float mass, float T, const Globals& G) {
    if (mass <= G.eps_mass || m.defaultMass <= 0.0f) return 0.0f;   // void / massless: no gauge pressure
    float c = chi(m);
    float m_rest = m.defaultMass * (1.0f - G.alpha * c * (T - G.T_ref) / G.T_ref);
    m_rest = std::clamp(m_rest, m.minMass, m.maxMass);
    if (mass >= m_rest) {
        float denom = std::max(1e-6f, m.maxMass - m_rest);          // guard sand (max==rest)
        float x = (mass - m_rest) / denom;
        return G.K * std::pow(x, G.gamma);                          // compression -> stiff wall
    } else {
        float denom = std::max(1e-6f, m_rest - m.minMass);
        float x = (m_rest - mass) / denom;
        return -G.K * c * x;                                        // expansion (gas) -> toward min_mass
    }
}

} // namespace orgeb
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_eos_test.cpp -o /tmp/engine_b_eos_test && /tmp/engine_b_eos_test`
Expected: `engine_b_eos_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_eos_test.cpp
git commit -m "feat(engine-b): EOS p(m,T) + chi + Globals (formula spec §B.1)"
```

---

### Task 3: Velocity channel on `Chunk` + snapshot

Encrypt reads `u`, Decrypt writes `u`. Add the persisted per-cell velocity `(vx,vy,vz)` to `Chunk` (default 0 — a resting, conservation-neutral start) and carry it through the snapshot.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`Chunk` struct ~130–159; `ChunkSnapshot` ~342–346; `snapshot_world` ~354–362)
- Test: `ORGE-ENGINE/tests/engine_b_encrypt_test.cpp` (created here; velocity-only assertions first)

- [ ] **Step 1: Write the failing test** — `ORGE-ENGINE/tests/engine_b_encrypt_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_encrypt_test.cpp -o /tmp/engine_b_encrypt_test
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)

int main(){
    // Velocity channel exists, defaults to 0, and survives a snapshot round-trip.
    World w;
    Chunk* C = w.ensureChunk(0,0);
    int i = idx(8, 40, 8);
    CHECK(C->vx[i] == 0.f && C->vy[i] == 0.f && C->vz[i] == 0.f, "velocity defaults to 0");
    C->vx[i] = 1.5f; C->vy[i] = -2.0f; C->vz[i] = 0.25f;
    WorldSnapshot s = snapshot_world(w);
    const ChunkSnapshot* cs = s.find(0,0);
    CHECK(cs != nullptr, "snapshot has the chunk");
    CHECK(cs->vx[i] == 1.5f && cs->vy[i] == -2.0f && cs->vz[i] == 0.25f, "snapshot carries velocity");

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_encrypt_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_encrypt_test.cpp -o /tmp/engine_b_encrypt_test 2>&1 | head`
Expected: FAIL to compile — `'struct Chunk' has no member named 'vx'`.

- [ ] **Step 3: Add velocity to `Chunk`, `ChunkSnapshot`, `snapshot_world`** — `ORGE-ENGINE/sim_engine.hpp`

In `Chunk` (add the three vectors + their constructor init):
```cpp
    // NEW (Engine B): persisted per-cell bulk velocity (m/s). Default 0 = resting start.
    std::vector<float> vx;
    std::vector<float> vy;
    std::vector<float> vz;
```
and in the `Chunk()` constructor initializer list, after `mass_kg(CHUNK_N, 0.0f)`:
```cpp
        , vx(CHUNK_N, 0.0f)
        , vy(CHUNK_N, 0.0f)
        , vz(CHUNK_N, 0.0f)
```

In `ChunkSnapshot`:
```cpp
struct ChunkSnapshot {
    std::array<uint16_t, CHUNK_N> matIx;
    std::vector<float> mass;   // CHUNK_N
    std::vector<float> T;      // CHUNK_N
    std::vector<float> vx, vy, vz;  // CHUNK_N each (Engine B)
};
```

In `snapshot_world`, extend the per-chunk copy:
```cpp
        ChunkSnapshot cs; cs.matIx = C.matIx; cs.mass = C.mass_kg; cs.T = C.T_curr;
        cs.vx = C.vx; cs.vy = C.vy; cs.vz = C.vz;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_encrypt_test.cpp -o /tmp/engine_b_encrypt_test && /tmp/engine_b_encrypt_test`
Expected: `engine_b_encrypt_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add sim_engine.hpp tests/engine_b_encrypt_test.cpp
git commit -m "feat(engine-b): persisted per-cell velocity channel on Chunk + snapshot"
```

---

### Task 4: Encrypt — `CellEncrypt`, `encrypt_world`

Implement §B.2–§B.4: amplitude `h = c·T + p/ρ + ½‖u‖²`; gravity body-impulse `u_g = u + dt·g·(0,−1,0)`; viscous drag `dragScale = 1/(1+dt·λ)`, `λ = μ/(ρ·dx²)`; the energy vector `E = ρ·h·(u_g·dragScale)`. Frozen (`μ=∞`) ⇒ `dragScale=0` ⇒ `E=0` with no branch; void/massless ⇒ `E=0`.

**Files:**
- Modify: `ORGE-ENGINE/engine_b.hpp` (append to `namespace orgeb`)
- Test: `ORGE-ENGINE/tests/engine_b_encrypt_test.cpp` (extend)

- [ ] **Step 1: Write the failing tests** — extend `main` in `engine_b_encrypt_test.cpp` (before the final report block):

```cpp
    using namespace orgeb;
    Globals G;
    constexpr float INF = std::numeric_limits<float>::infinity();
    Material water {4186.f, 0.6f, 0.018f, 125.f, 1100.f, 0.001f, 1000.f};
    Material frozen{800.f,  2.0f, 0.060f, 2500.f,2500.f, INF,    2500.f};

    // Gravity body impulse: a resting water cell gains u_g = (0, -g*dt, 0).
    {
        CellEncrypt e = encrypt_cell(water, /*mass*/1000.f, /*T*/288.f,
                                     /*ux*/0.f,/*uy*/0.f,/*uz*/0.f, G, /*dt*/0.5f);
        CHECK(std::fabs(e.ugy - (-G.g * 0.5f)) < 1e-4f, "gravity impulse u_g.y = -g*dt");
        CHECK(e.ugx == 0.f && e.ugz == 0.f, "gravity is y-only");
        // E points downward (negative y) for a falling resting cell with finite drag.
        CHECK(e.Ey < 0.f, "E aims downward under gravity");
    }
    // Frozen cell: dragScale 0 => E == 0 (still has a partition, just no advective vector).
    {
        CellEncrypt e = encrypt_cell(frozen, 2500.f, 288.f, 0.f, 0.f, 0.f, G, 0.5f);
        CHECK(e.Ex == 0.f && e.Ey == 0.f && e.Ez == 0.f, "frozen (visc=INF) emits E=0");
    }
    // Light cell emits a WEAK vector vs a heavy cell at the same drive (air-pushes-water fix).
    {
        Material air{1005.f, 0.026f, 0.029f, 0.1f, 50.f, 1.8e-5f, 1.2f};
        CellEncrypt ea = encrypt_cell(air,   1.2f,   288.f, 1.f, 0.f, 0.f, G, 0.25f);
        CellEncrypt ew = encrypt_cell(water, 1000.f, 288.f, 1.f, 0.f, 0.f, G, 0.25f);
        CHECK(std::fabs(ea.Ex) < std::fabs(ew.Ex), "light cell -> weaker |E| than heavy cell");
    }
    // Void / massless cell emits E=0.
    {
        Material vac{0.f,0.f,0.f,0.f,0.f,0.f,0.f};
        CellEncrypt e = encrypt_cell(vac, 0.f, 288.f, 0.f, 0.f, 0.f, G, 0.5f);
        CHECK(e.Ex==0.f && e.Ey==0.f && e.Ez==0.f, "void emits E=0");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_encrypt_test.cpp -o /tmp/engine_b_encrypt_test 2>&1 | head`
Expected: FAIL to compile — `'CellEncrypt' was not declared`, `'encrypt_cell' was not declared`.

- [ ] **Step 3: Implement `CellEncrypt` + `encrypt_cell` + `encrypt_world`** — append to `namespace orgeb` in `engine_b.hpp`:

```cpp
// Per-cell Encrypt output. The energy vector E is the primary inter-cell object (the user's
// design); u_g/p/h are the stored partition Resolve is allowed to read (decision D4).
struct CellEncrypt {
    float Ex = 0.f, Ey = 0.f, Ez = 0.f;  // energy-flux vector = rho * h * (u_g * dragScale)
    float ugx = 0.f, ugy = 0.f, ugz = 0.f; // gravity-updated velocity (momentum carrier, §C.2/§D.2)
    float p = 0.f;                        // EOS pressure (pressure-flux kick, §C.2)
    float h = 0.f;                        // stagnation specific energy (§B.2)
    float rho = 0.f;                      // m / V (cached for Resolve)
};

inline CellEncrypt encrypt_cell(const Material& m, float mass, float T,
                                float ux, float uy, float uz,
                                const Globals& G, float dt) {
    CellEncrypt e;
    e.rho = mass / G.V;
    e.p   = eos_pressure(m, mass, T, G);
    // Amplitude h (§B.2): thermal + pressure-work + kinetic, per kg.
    float kin = 0.5f * (ux*ux + uy*uy + uz*uz);
    e.h = m.heatCapacity * T + ((e.rho > G.eps_mass) ? e.p / e.rho : 0.0f) + kin;
    // Gravity body impulse (§B.3): once per tick, down = -y. Applied to all cells; frozen cells
    // get E=0 from the drag denominator below, so gravity never moves them.
    e.ugx = ux;
    e.ugy = uy - dt * G.g;
    e.ugz = uz;
    // Viscous drag (§B.4): lambda = mu/(rho*dx^2); dt*lambda dimensionless; frozen (mu=INF) -> 0.
    float dragScale = 0.0f;
    if (mass > G.eps_mass && e.rho > G.eps_mass && std::isfinite(m.viscosity)) {
        float lambda = m.viscosity / (e.rho * G.dx * G.dx);
        dragScale = 1.0f / (1.0f + dt * lambda);
    }
    // E = rho * h * w_drive, w_drive = u_g * dragScale (§B.4). Void/frozen -> E=0.
    float wsx = e.ugx * dragScale, wsy = e.ugy * dragScale, wsz = e.ugz * dragScale;
    float rh = e.rho * e.h;
    e.Ex = rh * wsx; e.Ey = rh * wsy; e.Ez = rh * wsz;
    return e;
}

// Parallel-map Encrypt over the whole world (GPU-ideal: zero neighbor reads). Reads the snapshot
// so Resolve sees a single consistent pre-step image (L7).
struct ChunkEncrypt { std::vector<CellEncrypt> cells; ChunkEncrypt(): cells(CHUNK_N) {} };
struct WorldEncrypt {
    std::unordered_map<ChunkCoord, ChunkEncrypt, CoordHasher> chunks;
    const ChunkEncrypt* find(int cx, int cz) const {
        auto it = chunks.find(ChunkCoord{cx,cz});
        return (it==chunks.end()) ? nullptr : &it->second;
    }
};
inline WorldEncrypt encrypt_world(const World& world, const WorldSnapshot& snap,
                                  const MaterialLUT& mats, const Globals& G, float dt) {
    WorldEncrypt we;
    for (const auto& kv : world.chunks) {
        const Chunk& C = *kv.second;
        const ChunkSnapshot* s = snap.find(C.cx, C.cz);
        ChunkEncrypt ce;
        for (int i = 0; i < CHUNK_N; ++i) {
            uint16_t mix = s->matIx[i];
            ce.cells[i] = encrypt_cell(mats.byIx(mix), s->mass[i], s->T[i],
                                       s->vx[i], s->vy[i], s->vz[i], G, dt);
        }
        we.chunks.emplace(ChunkCoord{C.cx,C.cz}, std::move(ce));
    }
    return we;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_encrypt_test.cpp -o /tmp/engine_b_encrypt_test && /tmp/engine_b_encrypt_test`
Expected: `engine_b_encrypt_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_encrypt_test.cpp
git commit -m "feat(engine-b): Encrypt — energy vector E from own state (gravity/drag/amplitude, §B.2-B.4)"
```

---

### Task 5: Resolve — `CellAccum`, `resolve_world`

Implement §C: per face, face-drive `W = ½(w_i+w_j)·n̂` (`w` recovered from `E/(ρh)`), upwind donor, advective `ṁ = ρ_donor·W·A·dt`, momentum `p⃗_adv = ṁ·u_g,donor`, passive temperature cargo `ṁ·T_donor`, the conservative pressure-flux kick `−p_face·A·dt·n̂_out`, capacity clamp, free-slip walls. Antisymmetric ⇒ conservation by construction.

This is the only cross-cell step and the core conservation guarantee. Implement it as a single per-cell accumulation over the 6 faces, reading each neighbor's `CellEncrypt` + snapshot from the single pre-step image.

**Files:**
- Modify: `ORGE-ENGINE/engine_b.hpp` (append)
- Test: `ORGE-ENGINE/tests/engine_b_resolve_test.cpp` (created here)

- [ ] **Step 1: Write the failing tests** — `ORGE-ENGINE/tests/engine_b_resolve_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_resolve_test.cpp -o /tmp/engine_b_resolve_test
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)
#define CLOSE(a,b,tol,msg) do { if(std::fabs((double)(a)-(double)(b)) > (tol)){ \
    std::printf("FAIL: %s (got %.6g want %.6g)\n", msg, (double)(a), (double)(b)); ++failures; } } while(0)

using namespace orgeb;
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float m,float T,float ux=0,float uy=0,float uz=0){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=ux; C.vy[i]=uy; C.vz[i]=uz;
}

int main(){
    constexpr float INF = std::numeric_limits<float>::infinity();
    Globals G; G.g = 0.0f;  // gravity off for the pure-advection / conservation algebra

    // ---- §J.4 pure advection: water u=(+1,0,0) into still water, dt=0.25 -> mdot=125 kg ----
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});                                   // void
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f}); // inviscid water
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,40,8, WAT, 1000.f, 300.f, /*ux*/1.f);
        set_cell(*C, 9,40,8, WAT, 1000.f, 300.f, /*ux*/0.f);
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        const ChunkAccum* a = acc.find(0,0);
        // donor 8,40,8 loses 125 kg across +x face; receiver 9,40,8 gains 125 kg.
        CLOSE(a->cells[idx(8,40,8)].dm, -125.0, 1e-3, "donor dm = -125 kg");
        CLOSE(a->cells[idx(9,40,8)].dm, +125.0, 1e-3, "receiver dm = +125 kg");
    }

    // ---- conservation: a random-ish field sums to zero net dm ----
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        for(int x=6;x<11;++x) set_cell(*C, x,40,8, WAT, 900.f + 30.f*x, 300.f, /*ux*/0.3f*(x-8));
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        const ChunkAccum* a = acc.find(0,0);
        double net=0; for(int i=0;i<CHUNK_N;++i) net += a->cells[i].dm;
        CLOSE(net, 0.0, 1e-2, "net dm == 0 (antisymmetric face flux conserves mass)");
    }

    // ---- air-into-water is negligible (light donor -> tiny mdot/momentum) ----
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t AIR = w.materials.add(Material{1005.f,0.026f,0.029f,0.1f,50.f,0.f,1.2f});
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,40,8, AIR, 1.2f, 300.f, /*ux*/1.f);     // air moving at the water
        set_cell(*C, 9,40,8, WAT, 1000.f, 300.f);
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        const ChunkAccum* a = acc.find(0,0);
        CHECK(std::fabs(a->cells[idx(9,40,8)].dm) < 0.2, "air moves <0.2 kg into 1000 kg water");
    }

    // ---- §J.5 hydrostatic balance: interior cell with pressure-graded column, net momentum 0 ----
    {
        World w; Globals Gh; Gh.g = 10.f;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        // A liquid whose EOS gives p exactly hydrostatic for the masses we set (we set p via mass).
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        // Stack three cells; pick masses so the EOS pressure rises ~rho*g per cell downward.
        // We only assert the SIGN/near-zero of the interior net momentum given a monotone column.
        set_cell(*C, 8,41,8, WAT, 1010.f, 288.f);   // A (above)
        set_cell(*C, 8,40,8, WAT, 1020.f, 288.f);   // M (interior)
        set_cell(*C, 8,39,8, WAT, 1030.f, 288.f);   // L (below)
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, Gh, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, Gh, 0.25f);
        const ChunkAccum* a = acc.find(0,0);
        // Interior M: gravity impulse (in u_g) is balanced against the pressure-flux kick.
        // With a denser-below column the net vertical momentum change on M is small/upward-resisting,
        // not a runaway downward spike.
        double dpy = a->cells[idx(8,40,8)].dpy;
        CHECK(std::fabs(dpy) < 6000.0, "interior cell pressure flux resists gravity (no runaway)");
    }

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_resolve_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_resolve_test.cpp -o /tmp/engine_b_resolve_test 2>&1 | head`
Expected: FAIL to compile — `'WorldAccum' was not declared`, `'resolve_world' was not declared`.

- [ ] **Step 3: Implement `CellAccum`, `resolve_world`** — append to `namespace orgeb` in `engine_b.hpp`:

```cpp
// Per-cell resolved exchange (sum over 6 faces).
struct CellAccum {
    double dm  = 0.0;   // net mass (+ into i)
    double dpx = 0.0, dpy = 0.0, dpz = 0.0;  // advective momentum + pressure flux (gravity rides in u_g)
    double dmT = 0.0;   // net mass-weighted temperature cargo (passive scalar, Stage 1)
};
struct ChunkAccum { std::vector<CellAccum> cells; ChunkAccum(): cells(CHUNK_N) {} };
struct WorldAccum {
    std::unordered_map<ChunkCoord, ChunkAccum, CoordHasher> chunks;
    ChunkAccum* find(int cx, int cz) {
        auto it = chunks.find(ChunkCoord{cx,cz});
        return (it==chunks.end()) ? nullptr : &it->second;
    }
    const ChunkAccum* find(int cx, int cz) const {
        auto it = chunks.find(ChunkCoord{cx,cz});
        return (it==chunks.end()) ? nullptr : &it->second;
    }
};

// Recover the (drag-damped) drive velocity component along an axis from the energy vector:
// w = E / (rho * h). Returns 0 for void/degenerate cells.
inline float drive_axis(float E, float rho, float h, const Globals& G) {
    float d = rho * h;
    return (std::fabs(d) > G.eps_mass) ? (E / d) : 0.0f;
}

// RESOLVE: the only cross-cell step. One pass, 6 faces per cell, antisymmetric flux from the single
// pre-step snapshot (L7) -> conservation by construction. Free-slip walls: a face adjacent to an
// immovable/absent cell passes no advective flux (the wall reaction balances the pressure).
inline WorldAccum resolve_world(const World& world, const WorldSnapshot& snap,
                                const WorldEncrypt& enc, const MaterialLUT& mats,
                                const Globals& G, float dt) {
    WorldAccum out;
    for (const auto& kv : world.chunks)
        out.chunks.emplace(ChunkCoord{kv.second->cx, kv.second->cz}, ChunkAccum{});

    // Face directions: +x,-x,+y,-y,+z,-z and the per-axis outward-normal sign for cell i.
    static const int DX[6]={+1,-1,0,0,0,0}, DY[6]={0,0,+1,-1,0,0}, DZ[6]={0,0,0,0,+1,-1};

    for (const auto& kv : world.chunks) {
        const Chunk& C = *kv.second;
        const ChunkSnapshot* si = snap.find(C.cx, C.cz);
        const ChunkEncrypt*  ei = enc.find(C.cx, C.cz);
        ChunkAccum* ai = out.find(C.cx, C.cz);

        for (int z=0; z<CHUNK_D; ++z)
        for (int y=0; y<CHUNK_H; ++y)
        for (int x=0; x<CHUNK_W; ++x) {
            int i = idx(x,y,z);
            uint16_t mi = si->matIx[i];
            const Material& Mi = mats.byIx(mi);

            for (int f=0; f<6; ++f) {
                int nx=x+DX[f], ny=y+DY[f], nz=z+DZ[f];
                if (ny < 0 || ny >= CHUNK_H) continue;            // world top/bottom = wall
                int ncx=C.cx, ncz=C.cz, lx=nx, lz=nz;
                if (nx<0){ncx=C.cx-1; lx=CHUNK_W-1;} else if(nx>=CHUNK_W){ncx=C.cx+1; lx=0;}
                if (nz<0){ncz=C.cz-1; lz=CHUNK_D-1;} else if(nz>=CHUNK_D){ncz=C.cz+1; lz=0;}
                const ChunkSnapshot* sj = snap.find(ncx,ncz);
                const ChunkEncrypt*  ej = enc.find(ncx,ncz);
                if (!sj || !ej) continue;                         // absent neighbor = world-edge wall
                int j = idx(lx,ny,lz);
                uint16_t mj = sj->matIx[j];
                const Material& Mj = mats.byIx(mj);

                // Each unordered pair is handled once (only when i < the neighbor's linear identity),
                // and we write BOTH sides antisymmetrically -> exact conservation, no double count.
                // Tie-break by (chunk, index) ordering:
                bool iFirst = (ncx>C.cx) || (ncx==C.cx && ncz>C.cz) ||
                              (ncx==C.cx && ncz==C.cz && j>i);
                if (!iFirst) continue;

                // Free-slip wall: any face touching an immovable cell passes no advective flux.
                bool wall = !std::isfinite(Mi.viscosity) || !std::isfinite(Mj.viscosity);

                // Outward normal sign for i along this face's axis.
                float nxs = (float)DX[f], nys = (float)DY[f], nzs = (float)DZ[f];

                // Face-drive W = 1/2 (w_i + w_j) . n_hat ; w recovered from E/(rho h).
                float wix = drive_axis(ei->cells[i].Ex, ei->cells[i].rho, ei->cells[i].h, G);
                float wiy = drive_axis(ei->cells[i].Ey, ei->cells[i].rho, ei->cells[i].h, G);
                float wiz = drive_axis(ei->cells[i].Ez, ei->cells[i].rho, ei->cells[i].h, G);
                float wjx = drive_axis(ej->cells[j].Ex, ej->cells[j].rho, ej->cells[j].h, G);
                float wjy = drive_axis(ej->cells[j].Ey, ej->cells[j].rho, ej->cells[j].h, G);
                float wjz = drive_axis(ej->cells[j].Ez, ej->cells[j].rho, ej->cells[j].h, G);
                float W = 0.5f * ((wix+wjx)*nxs + (wiy+wjy)*nys + (wiz+wjz)*nzs);

                CellAccum& Ai = ai->cells[i];
                ChunkAccum* aj = out.find(ncx,ncz);
                CellAccum& Aj = aj->cells[j];

                // Advective channel (skipped at walls).
                if (!wall && std::fabs(W) > 1e-12f) {
                    bool donorIsI = (W > 0.0f);
                    const ChunkSnapshot* sd = donorIsI ? si : sj;
                    const ChunkEncrypt*  ed = donorIsI ? ei : ej;
                    int di = donorIsI ? i : j;
                    float rho_d = ed->cells[di].rho;
                    float mdot = rho_d * W * G.A * dt;             // signed: >0 means i->j
                    // Capacity clamp: never overfill the receiver past max_mass this tick.
                    {
                        const ChunkSnapshot* sr = donorIsI ? sj : si;
                        const Material& Mr = mats.byIx(donorIsI ? mj : mi);
                        int ri = donorIsI ? j : i;
                        float room = std::max(0.0f, Mr.maxMass - sr->mass[ri]);
                        float headroom_donor = std::max(0.0f, sd->mass[di] - 0.0f);
                        float cap = std::min(room, headroom_donor);
                        if (std::fabs(mdot) > cap) mdot = (mdot>0?1.f:-1.f)*cap;
                    }
                    // Momentum carried = mdot * u_g,donor (gravity-updated velocity).
                    float ugx=ed->cells[di].ugx, ugy=ed->cells[di].ugy, ugz=ed->cells[di].ugz;
                    float Td = sd->T[di];
                    // i receives +mdot if donor is j (W<0); donates if donor is i (W>0).
                    double sgn_i = (W > 0.0f) ? -1.0 : +1.0;       // i loses if it's the donor
                    Ai.dm  += sgn_i * mdot * (W>0?1.0:-1.0) * 0.0; // placeholder removed below
                    // Cleaner: amount leaving i->j is +mdot (donor i). Apply to both sides:
                    Ai.dm  += -mdot;   Aj.dm  += +mdot;
                    Ai.dpx += -mdot*ugx; Aj.dpx += +mdot*ugx;
                    Ai.dpy += -mdot*ugy; Aj.dpy += +mdot*ugy;
                    Ai.dpz += -mdot*ugz; Aj.dpz += +mdot*ugz;
                    Ai.dmT += -mdot*Td;  Aj.dmT += +mdot*Td;
                }

                // Pressure-gradient kick (conservative flux form, §C.2). At a wall, p_face = own p
                // (free-slip): equal and opposite on the pair sums to 0 -> skip (wall reaction).
                if (!wall) {
                    float p_face = 0.5f * (ei->cells[i].p + ej->cells[j].p);
                    float k = p_face * G.A * dt;
                    // i loses momentum across its outward normal; j gains the opposite.
                    Ai.dpx += -k * nxs; Aj.dpx += +k * nxs;
                    Ai.dpy += -k * nys; Aj.dpy += +k * nys;
                    Ai.dpz += -k * nzs; Aj.dpz += +k * nzs;
                }
            }
        }
    }
    return out;
}
```

> **Implementation note for the worker:** the `Ai.dm += sgn_i ... * 0.0` line above is dead scaffolding left in the spec text only to show the upwind reasoning; the two real lines that follow (`Ai.dm += -mdot; Aj.dm += +mdot;`) are correct because `mdot` is already signed by `W` (positive ⇒ flux `i→j`). **Delete the placeholder line** when you implement — it is a no-op but must not ship. (Self-check: after deletion, the §J.4 test still gives donor `dm=-125`, receiver `+125`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_resolve_test.cpp -o /tmp/engine_b_resolve_test && /tmp/engine_b_resolve_test`
Expected: `engine_b_resolve_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_resolve_test.cpp
git commit -m "feat(engine-b): Resolve — antisymmetric advective + pressure-flux face exchange (§C)"
```

---

### Task 6: Decrypt — `decrypt_world` (mass, velocity, passive T, guards)

Implement §D for the mechanical channels: `m' = m + Δm`; `u' = (m·u_g + Δp⃗)/m'`; passive `T' = (m·T + ΔmT)/m'`; species commit (§D.5 relabel/merge); vacuum & CFL guards (§D.4); plus the **free-slip no-penetration** velocity clamp at wall faces (so a cell resting on the floor doesn't accelerate downward forever).

**Files:**
- Modify: `ORGE-ENGINE/engine_b.hpp` (append)
- Test: `ORGE-ENGINE/tests/engine_b_decrypt_test.cpp` (created here)

- [ ] **Step 1: Write the failing tests** — `ORGE-ENGINE/tests/engine_b_decrypt_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_decrypt_test.cpp -o /tmp/engine_b_decrypt_test
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)
#define CLOSE(a,b,tol,msg) do { if(std::fabs((double)(a)-(double)(b)) > (tol)){ \
    std::printf("FAIL: %s (got %.6g want %.6g)\n", msg, (double)(a), (double)(b)); ++failures; } } while(0)
using namespace orgeb;
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float m,float T,float ux=0,float uy=0,float uz=0){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=ux; C.vy[i]=uy; C.vz[i]=uz;
}

int main(){
    constexpr float INF = std::numeric_limits<float>::infinity();
    Globals G; G.g = 0.f;

    // §J.4 full step: donor keeps speed (1.0), receiver picks up (0.111), masses 875/1125.
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,40,8, WAT, 1000.f, 300.f, 1.f);
        set_cell(*C, 9,40,8, WAT, 1000.f, 300.f, 0.f);
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        decrypt_world(w, snap, enc, acc, w.materials, G, 0.25f);
        CLOSE(C->mass_kg[idx(8,40,8)], 875.0, 1e-2, "donor mass 875");
        CLOSE(C->mass_kg[idx(9,40,8)], 1125.0, 1e-2, "receiver mass 1125");
        CLOSE(C->vx[idx(8,40,8)], 1.0, 1e-2, "donor keeps speed 1.0");
        CLOSE(C->vx[idx(9,40,8)], 0.111, 2e-2, "receiver picks up ~0.111");
    }

    // Vacuum guard: a cell drained below eps becomes void with u=0 (no m->0 blow-up).
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,0.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,40,8, WAT, 1e-9f, 300.f, 5.f);   // essentially empty but with a velocity
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        decrypt_world(w, snap, enc, acc, w.materials, G, 0.25f);
        CHECK(C->matIx[idx(8,40,8)] == 0, "drained cell relabels to void");
        CHECK(C->vx[idx(8,40,8)] == 0.f, "void cell velocity zeroed");
    }

    // CFL clamp: velocity never exceeds dx/dt.
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t WAT = w.materials.add(Material{4186.f,0.6f,0.018f,0.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,40,8, WAT, 1000.f, 300.f, 50.f);  // absurd speed
        WorldSnapshot snap = snapshot_world(w);
        WorldEncrypt enc = encrypt_world(w, snap, w.materials, G, 0.25f);
        WorldAccum acc = resolve_world(w, snap, enc, w.materials, G, 0.25f);
        decrypt_world(w, snap, enc, acc, w.materials, G, 0.25f);
        float spd = std::fabs(C->vx[idx(8,40,8)]);
        CHECK(spd <= G.dx/0.25f + 1e-3f, "velocity CFL-clamped to dx/dt");
    }

    // Free-slip floor: a resting water cell on a frozen floor does NOT accelerate downward forever.
    {
        World w; Globals Gh; Gh.g = 10.f;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t STONE = w.materials.add(Material{800.f,2.f,0.06f,2500.f,2500.f,INF,2500.f});
        uint16_t WAT   = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,2000.f,0.f,1000.f});
        Chunk* C = w.ensureChunk(0,0);
        set_cell(*C, 8,39,8, STONE, 2500.f, 288.f);     // floor
        set_cell(*C, 8,40,8, WAT,   1000.f, 288.f);     // water resting on it
        for(int n=0;n<10;++n){
            WorldSnapshot snap = snapshot_world(w);
            WorldEncrypt enc = encrypt_world(w, snap, w.materials, Gh, 0.25f);
            WorldAccum acc = resolve_world(w, snap, enc, w.materials, Gh, 0.25f);
            decrypt_world(w, snap, enc, acc, w.materials, Gh, 0.25f);
        }
        CHECK(C->vy[idx(8,40,8)] >= -1e-3f, "water on floor: downward velocity clamped (no penetration)");
    }

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_decrypt_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_decrypt_test.cpp -o /tmp/engine_b_decrypt_test 2>&1 | head`
Expected: FAIL to compile — `'decrypt_world' was not declared`.

- [ ] **Step 3: Implement `decrypt_world`** — append to `namespace orgeb` in `engine_b.hpp`:

```cpp
// Is the cell at (lx,ny,lz) relative to chunk C a free-slip wall (immovable / absent / world edge)?
inline bool wall_neighbor(const World& world, const WorldSnapshot& snap, const MaterialLUT& mats,
                          const Chunk& C, int x,int y,int z,int dx,int dy,int dz) {
    int nx=x+dx, ny=y+dy, nz=z+dz;
    if (ny<0 || ny>=CHUNK_H) return true;
    int ncx=C.cx, ncz=C.cz, lx=nx, lz=nz;
    if (nx<0){ncx=C.cx-1; lx=CHUNK_W-1;} else if(nx>=CHUNK_W){ncx=C.cx+1; lx=0;}
    if (nz<0){ncz=C.cz-1; lz=CHUNK_D-1;} else if(nz>=CHUNK_D){ncz=C.cz+1; lz=0;}
    const ChunkSnapshot* sj = snap.find(ncx,ncz);
    if (!sj) return true;
    return !std::isfinite(mats.byIx(sj->matIx[idx(lx,ny,lz)]).viscosity);
}

// DECRYPT: per cell, own state only. Re-partition (Delta m, Delta p, Delta mT) -> new m, u, T.
inline void decrypt_world(World& world, const WorldSnapshot& snap, const WorldEncrypt& enc,
                          const WorldAccum& acc, const MaterialLUT& mats,
                          const Globals& G, float dt) {
    for (auto& kv : world.chunks) {
        Chunk& C = *kv.second;
        const ChunkSnapshot* s = snap.find(C.cx, C.cz);
        const ChunkEncrypt*  e = enc.find(C.cx, C.cz);
        const ChunkAccum*    a = acc.find(C.cx, C.cz);
        for (int z=0; z<CHUNK_D; ++z)
        for (int y=0; y<CHUNK_H; ++y)
        for (int x=0; x<CHUNK_W; ++x) {
            int i = idx(x,y,z);
            uint16_t mi = s->matIx[i];
            // Frozen cells never advect: leave them exactly as-is (E was 0, but the pressure flux
            // could still have nudged them; explicitly hold immovable terrain).
            if (!std::isfinite(mats.byIx(mi).viscosity)) {
                C.matIx[i]=mi; C.mass_kg[i]=s->mass[i]; C.T_curr[i]=s->T[i];
                C.vx[i]=0.f; C.vy[i]=0.f; C.vz[i]=0.f;
                continue;
            }
            const CellAccum& A = a->cells[i];
            float m0 = s->mass[i];
            float mNew = m0 + (float)A.dm;

            // §D.4 vacuum guard.
            if (mNew < G.eps_mass) {
                C.matIx[i]   = C.void_ix;
                C.mass_kg[i] = 0.f;
                C.T_curr[i]  = s->T[i];     // keep a sane T (passive)
                C.vx[i]=C.vy[i]=C.vz[i]=0.f;
                continue;
            }

            // Momentum -> velocity (start from gravity-updated u_g; §D.2).
            float ugx=e->cells[i].ugx, ugy=e->cells[i].ugy, ugz=e->cells[i].ugz;
            float ux = (m0*ugx + (float)A.dpx) / mNew;
            float uy = (m0*ugy + (float)A.dpy) / mNew;
            float uz = (m0*ugz + (float)A.dpz) / mNew;

            // Free-slip / no-penetration: zero any velocity component pointing INTO a wall face.
            if (ux > 0 && wall_neighbor(world,snap,mats,C,x,y,z,+1,0,0)) ux=0;
            if (ux < 0 && wall_neighbor(world,snap,mats,C,x,y,z,-1,0,0)) ux=0;
            if (uy > 0 && wall_neighbor(world,snap,mats,C,x,y,z,0,+1,0)) uy=0;
            if (uy < 0 && wall_neighbor(world,snap,mats,C,x,y,z,0,-1,0)) uy=0;
            if (uz > 0 && wall_neighbor(world,snap,mats,C,x,y,z,0,0,+1)) uz=0;
            if (uz < 0 && wall_neighbor(world,snap,mats,C,x,y,z,0,0,-1)) uz=0;

            // CFL speed cap: never move more than one cell per tick (§D.4).
            float spd = std::sqrt(ux*ux+uy*uy+uz*uz);
            float vmax = G.dx / std::max(1e-6f, dt);
            if (spd > vmax) { float s2 = vmax/spd; ux*=s2; uy*=s2; uz*=s2; }

            // Passive temperature (mass-weighted advected scalar; full energy closure is Stage 3).
            float Tnew = (m0*s->T[i] + (float)A.dmT) / mNew;

            // §D.5 species commit: if this cell was ~empty and refilled, relabel to the inflow species.
            // Stage 1: cargo species is implicit (single-species pairs dominate the acceptance tests);
            // when m0 ~ 0 we adopt the dominant donor by keeping mi if it had mass, else the receiver
            // takes the species of the snapshot cell that fed it. For Stage 1 we relabel only on the
            // empty->refill case using the cell's own prior species when present, else nearest inflow.
            uint16_t sNew = mi;
            if (m0 < G.eps_mass) {
                // refilled from neighbors: adopt the heaviest-inflow neighbor species.
                sNew = dominant_inflow_species(world, snap, C, x,y,z, mats, G);
            }

            C.matIx[i]   = sNew;
            C.mass_kg[i] = mNew;
            C.T_curr[i]  = Tnew;
            C.vx[i]=ux; C.vy[i]=uy; C.vz[i]=uz;
        }
    }
}
```

Add the small helper `dominant_inflow_species` **above** `decrypt_world`:

```cpp
// Pick the species of the heaviest neighbor that could have donated mass into (x,y,z) this tick.
// Stage-1 minimal species commit for the empty->refill case (§D.5). Falls back to void.
inline uint16_t dominant_inflow_species(const World& world, const WorldSnapshot& snap,
                                        const Chunk& C, int x,int y,int z,
                                        const MaterialLUT& mats, const Globals& G) {
    static const int DX[6]={+1,-1,0,0,0,0}, DY[6]={0,0,+1,-1,0,0}, DZ[6]={0,0,0,0,+1,-1};
    uint16_t best = C.void_ix; float bestMass = 0.f;
    for (int f=0; f<6; ++f) {
        int nx=x+DX[f], ny=y+DY[f], nz=z+DZ[f];
        if (ny<0||ny>=CHUNK_H) continue;
        int ncx=C.cx,ncz=C.cz,lx=nx,lz=nz;
        if (nx<0){ncx=C.cx-1;lx=CHUNK_W-1;} else if(nx>=CHUNK_W){ncx=C.cx+1;lx=0;}
        if (nz<0){ncz=C.cz-1;lz=CHUNK_D-1;} else if(nz>=CHUNK_D){ncz=C.cz+1;lz=0;}
        const ChunkSnapshot* sj = snap.find(ncx,ncz);
        if (!sj) continue;
        int j = idx(lx,ny,lz);
        if (sj->mass[j] > bestMass && std::isfinite(mats.byIx(sj->matIx[j]).viscosity)) {
            bestMass = sj->mass[j]; best = sj->matIx[j];
        }
    }
    return best;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_decrypt_test.cpp -o /tmp/engine_b_decrypt_test && /tmp/engine_b_decrypt_test`
Expected: `engine_b_decrypt_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_decrypt_test.cpp
git commit -m "feat(engine-b): Decrypt — m/u/passiveT repartition + vacuum/CFL/free-slip guards (§D)"
```

---

### Task 7: `step_world_b` orchestration

Wire the three passes into one call: snapshot → encrypt → resolve → decrypt → advance `simClock`. This is the Engine-B equivalent of `advect_world` (law C: one sweep per call, `dt` scales amount not distance).

**Files:**
- Modify: `ORGE-ENGINE/engine_b.hpp` (append)
- Test: `ORGE-ENGINE/tests/engine_b_step_test.cpp` (created here)

- [ ] **Step 1: Write the failing tests** — `ORGE-ENGINE/tests/engine_b_step_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_step_test.cpp -o /tmp/engine_b_step_test
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
static int failures=0;
#define CHECK(cond,msg) do{ if(!(cond)){ std::printf("FAIL: %s\n",msg); ++failures; } }while(0)
using namespace orgeb;
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float m,float T){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T;
}
static double total_mass(const Chunk& C){ double s=0; for(int i=0;i<CHUNK_N;++i) s+=C.mass_kg[i]; return s; }

int main(){
    constexpr float INF=std::numeric_limits<float>::infinity();
    Globals G;

    // (a) uniform pool at rest (single layer on a floor) is stationary in mass.
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t STONE=w.materials.add(Material{800.f,2.f,0.06f,2500.f,2500.f,INF,2500.f});
        uint16_t WAT=w.materials.add(Material{4186.f,0.6f,0.018f,1000.f,1000.f,0.f,1000.f}); // min==max==def
        Chunk* C=w.ensureChunk(0,0);
        for(int z=6;z<10;++z) for(int x=6;x<10;++x){ set_cell(*C,x,29,z,STONE,2500.f,288.f); set_cell(*C,x,30,z,WAT,1000.f,288.f); }
        std::vector<float> before(C->mass_kg.begin(),C->mass_kg.end());
        for(int n=0;n<5;++n) step_world_b(w, w.materials, G, 0.25f);
        float maxd=0.f; for(int i=0;i<CHUNK_N;++i) maxd=std::max(maxd,std::fabs(C->mass_kg[i]-before[i]));
        CHECK(maxd < 1.0f, "(a) full resting pool on floor is ~stationary");
    }
    // (b) total mass invariant across a step (conservation).
    {
        World w;
        w.materials.add(Material{0,0,0,0,0,INF,0});
        uint16_t AIR=w.materials.add(Material{1005.f,0.026f,0.029f,0.1f,50.f,1.8e-5f,1.2f});
        uint16_t WAT=w.materials.add(Material{4186.f,0.6f,0.018f,125.f,1100.f,0.001f,1000.f});
        Chunk* C=w.ensureChunk(0,0);
        for(int i=0;i<CHUNK_N;++i){ C->matIx[i]=AIR; C->mass_kg[i]=1.2f; C->T_curr[i]=300.f; }
        set_cell(*C,8,40,8,WAT,1000.f,300.f);
        double before=total_mass(*C);
        step_world_b(w, w.materials, G, 0.25f);
        CHECK(std::fabs(before-total_mass(*C)) < 1e-1, "(b) total mass invariant across the step");
    }
    if(failures){ std::printf("%d FAILURES\n",failures); return 1; }
    std::printf("engine_b_step_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_step_test.cpp -o /tmp/engine_b_step_test 2>&1 | head`
Expected: FAIL to compile — `'step_world_b' was not declared`.

- [ ] **Step 3: Implement `step_world_b`** — append to `namespace orgeb` in `engine_b.hpp`:

```cpp
// One unified mechanical sweep (law C: dt scales amount, not distance). Snapshot -> Encrypt ->
// Resolve -> Decrypt. The G overload lets tests pass tuned globals; the default uses Globals{}.
inline void step_world_b(World& world, const MaterialLUT& mats, const Globals& G, float dt) {
    WorldSnapshot snap = snapshot_world(world);
    WorldEncrypt  enc  = encrypt_world(world, snap, mats, G, dt);
    WorldAccum    acc  = resolve_world(world, snap, enc, mats, G, dt);
    decrypt_world(world, snap, enc, acc, mats, G, dt);
    world.simClock += dt;
}
// Default-globals entry point used by orgeStepWorld (orge_jni.cpp).
inline void step_world_b(World& world, const MaterialLUT& mats, double dt) {
    static const Globals G{};
    step_world_b(world, mats, G, (float)dt);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_step_test.cpp -o /tmp/engine_b_step_test && /tmp/engine_b_step_test`
Expected: `engine_b_step_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_step_test.cpp
git commit -m "feat(engine-b): step_world_b — snapshot/encrypt/resolve/decrypt orchestration (law C)"
```

---

### Task 8: Physical acceptance tests 1, 3, 4, 6, 8, 13, 15

The Stage-1 gate. Each acceptance test from formula spec §I/§I-bis as a CHECK block. These validate the *model*, not arithmetic.

**Files:**
- Test: `ORGE-ENGINE/tests/engine_b_accept_test.cpp` (created here)

- [ ] **Step 1: Write the acceptance tests** — `ORGE-ENGINE/tests/engine_b_accept_test.cpp`

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/engine_b_accept_test.cpp -o /tmp/engine_b_accept_test
// Engine B Stage-1 physical acceptance tests (formula spec §I / §I-bis): 1,3,4,6,8,13,15.
#include "engine_b.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
static int failures=0;
#define CHECK(cond,msg) do{ if(!(cond)){ std::printf("FAIL: %s\n",msg); ++failures; } }while(0)
using namespace orgeb;
constexpr float INF=std::numeric_limits<float>::infinity();
static void sc(Chunk& C,int x,int y,int z,uint16_t ix,float m,float T){ int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T; }
struct Mats { uint16_t VOID,STONE,WAT,AIR,LAVA; };
static Mats stdmats(World& w){
    Mats M;
    M.VOID = w.materials.add(Material{0,0,0,0,0,INF,0});
    M.STONE= w.materials.add(Material{800.f,2.f,0.06f,2500.f,2500.f,INF,2500.f});
    M.WAT  = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,1100.f,0.001f,1000.f});
    M.AIR  = w.materials.add(Material{1005.f,0.026f,0.029f,0.1f,50.f,1.8e-5f,1.2f});
    M.LAVA = w.materials.add(Material{1450.f,1.0f,0.060f,1500.f,3000.f,100.f,2600.f});
    return M;
}
static double speciesMass(const Chunk& C, uint16_t s){ double t=0; for(int i=0;i<CHUNK_N;++i) if(C.matIx[i]==s) t+=C.mass_kg[i]; return t; }

int main(){
    Globals G;

    // TEST 1 — flat-surface rest: a placed water cell spreads and STOPS (no perpetual wander).
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        for(int z=0;z<CHUNK_D;++z) for(int x=0;x<CHUNK_W;++x) sc(*C,x,29,z,M.STONE,2500.f,288.f); // floor
        sc(*C,8,30,8,M.WAT,1000.f,288.f);
        for(int n=0;n<40;++n) step_world_b(w,w.materials,G,0.25f);
        // settle check: 5 more steps move very little mass.
        std::vector<float> a(C->mass_kg.begin(),C->mass_kg.end());
        for(int n=0;n<5;++n) step_world_b(w,w.materials,G,0.25f);
        float maxd=0.f; for(int i=0;i<CHUNK_N;++i) maxd=std::max(maxd,std::fabs(C->mass_kg[i]-a[i]));
        CHECK(maxd < 5.0f, "TEST1 flat-rest: pool settles (no perpetual wander)");
        CHECK(std::fabs(speciesMass(*C,M.WAT)-1000.0) < 1e-1, "TEST1 water mass conserved");
    }

    // TEST 3 — buoyancy ordering: lava under water under air settles lava<water<air by height.
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        for(int z=0;z<CHUNK_D;++z) for(int x=0;x<CHUNK_W;++x) sc(*C,x,29,z,M.STONE,2500.f,288.f);
        // deliberately inverted: air low, water mid, lava high -> must invert.
        for(int x=6;x<10;++x){ sc(*C,x,30,8,M.AIR,1.2f,288.f); sc(*C,x,31,8,M.WAT,1000.f,288.f); sc(*C,x,32,8,M.LAVA,2600.f,288.f); }
        for(int n=0;n<200;++n) step_world_b(w,w.materials,G,0.25f);
        // center-of-mass height per species
        auto comY=[&](uint16_t s){ double m=0,my=0; for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==s){ int y=(i/CHUNK_W)%CHUNK_H; m+=C->mass_kg[i]; my+=C->mass_kg[i]*y; } return m>0?my/m:0; };
        double yl=comY(M.LAVA), yw=comY(M.WAT), ya=comY(M.AIR);
        CHECK(yl <= yw + 0.5 && yw <= ya + 0.5, "TEST3 buoyancy order lava<=water<=air");
    }

    // TEST 4 — tube pinning: closed 1-cell tube [L,A,W] (lava on water through air) -> lava does NOT
    //          sink through the incompressible water; ends with air at top, lava on water (stable).
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        // 1-wide tube walls at x=7 and x=9, floor below y=30.
        for(int y=29;y<=34;++y){ sc(*C,7,y,8,M.STONE,2500.f,288.f); sc(*C,9,y,8,M.STONE,2500.f,288.f); }
        for(int z=7;z<=9;++z){ sc(*C,8,29,z,M.STONE,2500.f,288.f); }
        sc(*C,8,30,8,M.WAT,1000.f,288.f);   // water at bottom
        sc(*C,8,31,8,M.AIR,1.2f,288.f);     // air middle
        sc(*C,8,32,8,M.LAVA,2600.f,288.f);  // lava top
        for(int n=0;n<200;++n) step_world_b(w,w.materials,G,0.25f);
        // water must remain the lowest fluid; lava must NOT have fully passed below the water cell.
        CHECK(C->matIx[idx(8,30,8)]==M.WAT, "TEST4 water stays pinned at the bottom (lava can't pass)");
        CHECK(std::fabs(speciesMass(*C,M.WAT)-1000.0) < 1.0 &&
              std::fabs(speciesMass(*C,M.LAVA)-2600.0) < 1.0, "TEST4 masses conserved");
    }

    // TEST 6 — incompressible displacement: inject lava into a full water pocket; water rises
    //          elsewhere, mass conserved, no chain artifact.
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        for(int z=0;z<CHUNK_D;++z) for(int x=0;x<CHUNK_W;++x) sc(*C,x,29,z,M.STONE,2500.f,288.f);
        for(int z=6;z<11;++z) for(int y=30;y<34;++y) for(int x=6;x<11;++x) sc(*C,x,y,z,M.WAT,1000.f,288.f);
        sc(*C,8,33,8,M.LAVA,2600.f,288.f);  // lava dropped into the pool top
        double w0=speciesMass(*C,M.WAT), l0=speciesMass(*C,M.LAVA);
        for(int n=0;n<150;++n) step_world_b(w,w.materials,G,0.25f);
        CHECK(std::fabs(speciesMass(*C,M.WAT)-w0) < 1.0, "TEST6 water mass conserved under displacement");
        CHECK(std::fabs(speciesMass(*C,M.LAVA)-l0) < 1.0, "TEST6 lava mass conserved");
        CHECK(!std::isnan(speciesMass(*C,M.WAT)), "TEST6 no NaN");
    }

    // TEST 8 — gas fill vs liquid ceiling: gas released into void fills/thins; liquid does NOT fill a ceiling.
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        // sealed box x[6..10], z=8, y[30..34]; floor + ceiling + walls of stone.
        for(int x=6;x<=10;++x){ sc(*C,x,29,8,M.STONE,2500.f,288.f); sc(*C,x,35,8,M.STONE,2500.f,288.f); }
        for(int y=29;y<=35;++y){ sc(*C,5,y,8,M.STONE,2500.f,288.f); sc(*C,11,y,8,M.STONE,2500.f,288.f); }
        sc(*C,8,30,8,M.AIR,30.f,288.f);  // a lump of air on the floor with room above
        for(int n=0;n<100;++n) step_world_b(w,w.materials,G,0.25f);
        // air should have spread upward into the empty box (occupies more than its 1 starting cell).
        int airCells=0; for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==M.AIR && C->mass_kg[i]>G.eps_mass) ++airCells;
        CHECK(airCells >= 2, "TEST8 gas expands to fill available volume");
    }

    // TEST 13 — over-compression soak: a 2x-overfull cell relaxes to <= max_mass without blow-up.
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        for(int z=0;z<CHUNK_D;++z) for(int x=0;x<CHUNK_W;++x) sc(*C,x,29,z,M.STONE,2500.f,288.f);
        for(int z=6;z<11;++z) for(int x=6;x<11;++x) sc(*C,x,30,z,M.WAT, 2200.f, 288.f); // 2x over max (1100)
        bool finite=true;
        for(int n=0;n<300;++n){ step_world_b(w,w.materials,G,0.25f);
            for(int i=0;i<CHUNK_N;++i) if(!std::isfinite(C->mass_kg[i])||!std::isfinite(C->vx[i])) finite=false; }
        CHECK(finite, "TEST13 over-compression stays finite (no acoustic blow-up)");
        float mx=0; for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==M.WAT) mx=std::max(mx,C->mass_kg[i]);
        CHECK(mx <= 1100.f*1.25f, "TEST13 relaxes toward <= max_mass (within 25%)");
    }

    // TEST 15 — vacuum stability: drain a cell to ~0 while a neighbor pushes it; |u|<=dx/dt, no NaN.
    {
        World w; Mats M=stdmats(w); Chunk* C=w.ensureChunk(0,0);
        sc(*C,8,40,8,M.WAT,1e-7f,300.f);
        sc(*C,9,40,8,M.WAT,1500.f,300.f);  // dense neighbor pressure-pushes the near-empty cell
        bool ok=true;
        for(int n=0;n<200;++n){ step_world_b(w,w.materials,G,0.25f);
            for(int i=0;i<CHUNK_N;++i){ if(!std::isfinite(C->vx[i])||!std::isfinite(C->mass_kg[i])) ok=false;
                float spd=std::sqrt(C->vx[i]*C->vx[i]+C->vy[i]*C->vy[i]+C->vz[i]*C->vz[i]);
                if(spd > G.dx/0.25f + 1e-2f) ok=false; } }
        CHECK(ok, "TEST15 vacuum: |u|<=dx/dt and finite throughout");
    }

    if(failures){ std::printf("%d FAILURES\n",failures); return 1; }
    std::printf("engine_b_accept_test OK\n"); return 0;
}
```

- [ ] **Step 2: Run to verify it fails (then iterate)**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_accept_test.cpp -o /tmp/engine_b_accept_test && /tmp/engine_b_accept_test`
Expected: initially some CHECKs FAIL (the emergent behaviors need the full pipeline working together; this is where Stage-1 behavior is validated end to end).

- [ ] **Step 3: Iterate engine_b.hpp until all acceptance CHECKs pass**

This is the genuine Stage-1 convergence point. If a test fails, use `superpowers:systematic-debugging`: reproduce with the smallest world, instrument one cell's `(m,u,p,E)` across a few steps, and trace which channel is wrong. Likely tuning levers (do NOT change the model):
- TEST3/TEST4 buoyancy/pinning weak → raise `G.K` modestly (stiffer wall makes the incompressible reflect harder) but keep `c_s` sub-CFL (§G.1b): verify TEST13/TEST15 still pass after any `K` change.
- TEST1 wanders → confirm the free-slip no-penetration clamp (Task 6) and that a full pool (`m==m_rest`) has `p≈0` (no spurious pressure flux).
- TEST8 gas won't expand → confirm the EOS expansion branch (negative `p` for `χ≈1` below `m_rest`) drives the gas; the pressure flux carries it into void.
Record any `Globals` change you make in `engine_b.hpp` with a comment `// Stage-1 provisional; Stage-4 §G.2 calibrates`.

- [ ] **Step 4: Run to verify all pass**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/engine_b_accept_test.cpp -o /tmp/engine_b_accept_test && /tmp/engine_b_accept_test`
Expected: `engine_b_accept_test OK`

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git add engine_b.hpp tests/engine_b_accept_test.cpp
git commit -m "test(engine-b): Stage-1 physical acceptance tests 1,3,4,6,8,13,15 green"
```

---

### Task 9: Switchover — wire `orgeStepWorld`, delete Engine-A advection, update `run_tests.sh`, build `.so`

Point the JNI advection path at `step_world_b`, delete the now-dead Engine-A advection code and its dedicated tests, and rebuild `liborge.so`. Conduction (`PASS_CONDUCTION`) is untouched (Stage 3 subsumes it).

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp:156-158` (the `PASS_ADVECTION` branch); add `#include "engine_b.hpp"`
- Modify: `ORGE-ENGINE/sim_engine.hpp` (delete Engine-A advection passes — see list)
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (swap test roster)
- Delete: Engine-A advection test files (see list)

- [ ] **Step 1: Point the JNI advection branch at Engine B**

In `orge_jni.cpp`, add near the top includes:
```cpp
#include "engine_b.hpp"   // Engine B unified mechanical step (step_world_b)
```
Replace the advection branch (currently `advect_world(world, world.materials, dt);`) with:
```cpp
        if (passes & orge::PASS_ADVECTION) {
            orgeb::step_world_b(world, world.materials, dt);   // Engine B (replaces advect_world)
        }
```

- [ ] **Step 2: Build the `.so` and run the full Java gate BEFORE deleting Engine A**

This proves Engine B works through JNI with the existing material registration (defaultMass will be 0 until Task 11 — acceptable for this build check; the integration suite that needs defaultMass is added later). Rebuild + smoke:
```bash
cd /home/claude/ORGE-B
JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so 2>&1 | tail
```
Expected: builds with no errors (a warning about unused Engine-A symbols is fine — they're deleted next).

- [ ] **Step 3: Delete Engine-A advection code** — in `ORGE-ENGINE/sim_engine.hpp` remove these (verify each is unreferenced after Step 1):
  - `pass_a_sort` (~466–510)
  - `compute_overburden` / `overburden_at` (~392–423) and `compute_overburden_samespecies` (~434–463)
  - `BAccum` struct (~518–545) and `apply_baccum` (~1407–1452)
  - `pass_b_relax` (~579–1004)
  - `pass_bprime_displace` (~1036–1393)
  - `advect_world` (~1638–1672)
  - any now-orphaned helpers used **only** by the above (`movable`, `spread_fraction`, `Injection`/`apply_injections`/`LedgerAccum` are still used by `orge_jni.cpp` injection path — **keep those**; verify with grep before deleting anything).

Verify nothing else references a deleted symbol:
```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
grep -rn "advect_world\|pass_a_sort\|pass_b_relax\|pass_bprime_displace\|compute_overburden\|apply_baccum\|BAccum" --include=*.cpp --include=*.hpp . | grep -v "tests/" || echo "no non-test refs"
```
Expected: `no non-test refs` (only test files — handled in Step 4).

- [ ] **Step 4: Update the test roster** — `ORGE-ENGINE/tests/run_tests.sh`

Remove the Engine-A advection tests from the cheap/heavy lists and `git rm` their files (they test deleted behavior; Engine B is validated by the new physical tests):
- Remove + `git rm`: `sort_swap_test.cpp`, `viscosity_flow_test.cpp`, `bprime_evacuate_test.cpp`, `lava_water_equilibrium_test.cpp`, `push_chain_tube_test.cpp`, `u_tube_test.cpp`, `overburden_test.cpp`, `hydrostatic_test.cpp`, `relax_spread_test.cpp`, `min_mass_occupancy_test.cpp`, `spread_2d_test.cpp`, `displace_test.cpp`, `viscosity_spread_test.cpp`, `vertical_merge_test.cpp`, `unified_basics_test.cpp`.
- **Keep:** `time_dt_test.cpp`, `injection_test.cpp`, `resident_lut_test.cpp`, `correctness_test.cpp`, `stress_test.cpp`, `test_phase2b_lut.cpp` **only if** they still compile against the trimmed engine; if any references a deleted symbol, either port it to `step_world_b` or `git rm` it (note which in the commit).
- Add to the cheap tier: `engine_b_eos_test`, `engine_b_encrypt_test`, `engine_b_resolve_test`, `engine_b_decrypt_test`, `engine_b_step_test`, `engine_b_accept_test`.

- [ ] **Step 5: Run the engine cheap tier**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && ./tests/run_tests.sh`
Expected: all listed tests `OK`, summary shows 0 failures.

- [ ] **Step 6: Rebuild `.so` and commit**

```bash
cd /home/claude/ORGE-B
JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so 2>&1 | tail -3
cd ORGE-ENGINE
git add -A
git commit -m "refactor(engine-b): orgeStepWorld -> step_world_b; delete Engine-A advection passes + tests"
```

---

# STAGE 1B — Java velocity plumbing (TDD)

The engine now flows. Now thread `defaultMass` and the velocity channel through JNI ↔ marshalling ↔ SectionStore so velocity persists across scheduler dispatches and old regions default to 0.

> **Loader note:** every file in Stage 1B is in `:core` (Architectury-common, loader-agnostic). No NeoForge/Fabric module code changes; the loaders consume `:core` unchanged. The Stage-1 exit gate still builds both loaders to prove that.

---

### Task 10: `defaultMass` through the LUT ABI (register 7th array)

Engine B's EOS needs `defaultMass` populated via `orgeRegisterMaterials`. Grow the register ABI from 6 to 7 arrays.

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp` (`orgeRegisterMaterials` 17–52)
- Modify: `core/.../engine/NativeEngine.java` (native decl + `registerMaterials`)
- Modify: `core/.../engine/LutArrays.java` (pack `defaultMass`)
- Test: `core/.../engine/EngineBVelocityIT.java` (created; defaultMass assertion first)

- [ ] **Step 1: Write the failing integration test** — `core/src/test/java/net/rainbowcreation/orge/engine/EngineBVelocityIT.java`

```java
package net.rainbowcreation.orge.engine;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialTable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class EngineBVelocityIT {
    private static boolean nativeAvailable() {
        try { NativeLoader.load(); return true; } catch (Throwable t) { return false; }
    }

    @Test
    void registerWithDefaultMassDoesNotThrow() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        List<Material> lut = MaterialTable.ordered(/* a registry with water */ TestMaterials.registryWithWater());
        // Should accept the 7-array register (cond,heatCap,molar,minMass,maxMass,visc,defaultMass).
        assertDoesNotThrow(() -> engine.registerMaterials(101, lut));
    }
}
```

> If a `TestMaterials` helper doesn't exist, create a minimal one in `core/src/test/java/.../engine/TestMaterials.java` that returns a `MaterialRegistry` with a single water material (heatCap 4186, k 0.6, molar 0.018, minMass 125, maxMass 1100, visc 0.001, defaultMass 1000). Mirror an existing IT's registry construction (`ResidentLutParityIT`).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20`
Expected: FAIL/compile error — `registerMaterials` ABI mismatch or `UnsatisfiedLinkError` once the native signature changes; initially a compile error if `TestMaterials` is new.

- [ ] **Step 3a: Grow the C++ register** — `ORGE-ENGINE/orge_jni.cpp` `orgeRegisterMaterials`:

Add the 7th param and thread it:
```cpp
        jfloatArray jMinMass, jfloatArray jMaxMass, jfloatArray jVisc,
        jfloatArray jDefMass)
```
```cpp
    auto* defMass = static_cast<float*>(env->GetPrimitiveArrayCritical(jDefMass, nullptr));
```
guard it in the `if (cond && ... && visc && defMass)`, assign `m.defaultMass = defMass[i];`, and release it (mirror the others, JNI_ABORT).

- [ ] **Step 3b: Grow the Java side** — `core/.../engine/NativeEngine.java`:

native decl:
```java
private static native void orgeRegisterMaterials(
        int lutEpoch, int matCount,
        float[] cond, float[] heatCap, float[] molar,
        float[] minMass, float[] maxMass, float[] visc, float[] defaultMass);
```
`registerMaterials(int, List<Material>)`: pass `arrays.defaultMass()` as the new argument.

`core/.../engine/LutArrays.java`: add `float[] defaultMass` to the record + populate it in `pack()` from each `Material.defaultMass()` (slot 0 = VACUUM's defaultMass, which is 0).

- [ ] **Step 4: Rebuild `.so`, run the IT**

```bash
cd /home/claude/ORGE-B
JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so 2>&1 | tail -3
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20
```
Expected: PASS (skipped=0).

- [ ] **Step 5: Commit (engine + Java)**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE && git add -A && git commit -m "feat(engine-b): orgeRegisterMaterials 7th array (defaultMass) for the EOS"
cd /home/claude/ORGE-B && git add core && git commit -m "feat(engine-b): pack+register defaultMass (Java LUT ABI)"
```

---

### Task 11: `orgeStepWorld` velocity ABI (vx,vy,vz in/out)

Grow the world-step ABI so velocity round-trips Java↔native. Engine reads stored `v` (default 0), evolves it, writes it back.

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp` (`orgeStepWorld` signature + marshalling + read-in/write-back)
- Modify: `core/.../engine/NativeEngine.java` (native decl + `stepWorld` call site — temporary scratch arrays acceptable until Task 12 wires the pool)
- Test: `core/.../engine/EngineBVelocityIT.java` (extend)

- [ ] **Step 1: Write the failing test** — add to `EngineBVelocityIT`:

```java
    @Test
    void stepRoundTripsVelocity() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        NativeEngine engine = new NativeEngine();
        engine.registerMaterials(102, MaterialTable.ordered(TestMaterials.registryWithWater()));
        // A single column with one moving water cell; after one step the velocity output is finite
        // and the channel is populated (not all-NaN, not silently dropped).
        EngineStepProbe probe = EngineStepProbe.singleWaterCellMovingX(102);
        EngineStepProbe.Result r = probe.stepOnce(engine);
        assertTrue(Float.isFinite(r.vxAtPlacedCell()), "velocity output finite after step");
    }
```

> `EngineStepProbe` is a tiny test helper (create under `core/src/test/java/.../engine/`) that builds one `ColumnTask` with a moving cell, calls `engine.stepWorld(...)` with `PASS_ADVECTION`, and exposes the output velocity at the placed cell. Model its `stepWorld` call on `Scheduler.submit()`'s usage. Keep it minimal.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20`
Expected: FAIL — `stepWorld` has no velocity params / `vxAtPlacedCell` unavailable.

- [ ] **Step 3a: Grow the C++ `orgeStepWorld`** — `ORGE-ENGINE/orge_jni.cpp`:

Add input + output velocity arrays to the signature (after the existing state arrays, before injection params is fine as long as Java matches):
```cpp
        jfloatArray jVxIn, jfloatArray jVyIn, jfloatArray jVzIn,
        jfloatArray jVxOut, jfloatArray jVyOut, jfloatArray jVzOut,
```
- `GetPrimitiveArrayCritical` all six (mirror `jMass`).
- In the per-column read-in loop, set `C->vx[i]=vxIn[base+i];` etc.
- In the read-back loop, write `vxOut[base+i]=C->vx[i];` etc.
- Release all six (in/out with `0` for outputs, `JNI_ABORT` for inputs), in reverse order, mirroring the existing pattern.
- The no-LUT early-out path must also copy velocity straight through (`vxOut[i]=vxIn[i]` …).

- [ ] **Step 3b: Grow the Java native decl + call** — `core/.../engine/NativeEngine.java`:

```java
private static native double orgeStepWorld(
        int lutEpoch, int nCols, int[] cx, int[] cz,
        char[] matIx, float[] mass, float[] tIn,
        float[] vxIn, float[] vyIn, float[] vzIn,
        int passes, double dtSeconds,
        float[] tOut, float[] massOut, char[] matOut,
        float[] vxOut, float[] vyOut, float[] vzOut,
        int injCount, int[] injColumn, int[] injCell,
        char[] injSpecies, float[] injMass, float[] injTemp,
        float[] ledgerOut);
```
In `stepWorld(...)`, allocate temporary `float[nCols*CHUNK_N]` for vx/vy/vz in/out (the proper `ScratchPool` + `RegionMarshaller` wiring is Task 12) and pass them. For now, source velocity-in from zeros (Task 13 sources from SectionData).

- [ ] **Step 4: Rebuild `.so`, run IT**

```bash
cd /home/claude/ORGE-B
JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so 2>&1 | tail -3
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20
```
Expected: PASS.

- [ ] **Step 5: Commit (engine + Java)**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE && git add -A && git commit -m "feat(engine-b): orgeStepWorld velocity ABI (vx/vy/vz in+out)"
cd /home/claude/ORGE-B && git add core && git commit -m "feat(engine-b): NativeEngine.stepWorld velocity arrays (temp scratch)"
```

---

### Task 12: `RegionMarshaller` + `ScratchPool` velocity plumbing

Replace Task 11's temporary scratch with the real marshalling path so velocity flows per-column like mass/temperature.

**Files:**
- Modify: `core/.../engine/RegionMarshaller.java` (`Flat` + `flatten`/`slice`)
- Modify: `core/.../engine/ScratchPool.java` (velocity buffers)
- Modify: `core/.../engine/NativeEngine.java` (`stepWorld` uses pool + marshaller)
- Test: `core/.../engine/VelocityMarshallingTest.java` (unit)

- [ ] **Step 1: Write the failing unit test** — `core/src/test/java/net/rainbowcreation/orge/engine/VelocityMarshallingTest.java`

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N;

class VelocityMarshallingTest {
    @Test
    void flattenSliceRoundTripsVelocity() {
        char[] mat = new char[CHUNK_N]; float[] mass = new float[CHUNK_N];
        float[] t = new float[CHUNK_N];
        float[] vx = new float[CHUNK_N]; float[] vy = new float[CHUNK_N]; float[] vz = new float[CHUNK_N];
        int probe = 12345;
        vx[probe] = 1.5f; vy[probe] = -2.0f; vz[probe] = 0.25f;
        ColumnTask col = new ColumnTask(3, -7, mat, mass, t, vx, vy, vz);  // ctor grows to carry velocity
        RegionMarshaller.Flat flat = RegionMarshaller.flatten(List.of(col));
        assertEquals(1.5f, flat.vxIn()[probe]);
        // slice the same arrays back out
        List<ColumnResult> out = RegionMarshaller.slice(
                flat.matIx(), flat.mass(), flat.tIn(), flat.vxIn(), flat.vyIn(), flat.vzIn(), 1);
        assertEquals(-2.0f, out.get(0).velY()[probe]);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*VelocityMarshallingTest*' 2>&1 | tail -20`
Expected: FAIL to compile — `ColumnTask`/`ColumnResult`/`Flat` lack velocity.

- [ ] **Step 3: Add velocity to the marshalling types**

- `ColumnTask`: add `float[] velX, velY, velZ` (and update its constructor + all existing call sites — the assembler in Task 13 supplies them; existing tests/callers pass zero arrays).
- `ColumnResult`: add `float[] velX, velY, velZ` accessors (`velX()/velY()/velZ()`).
- `RegionMarshaller.Flat`: add `float[] vxIn, vyIn, vzIn`.
- `flatten`: copy each column's velocity into the combined `vxIn/vyIn/vzIn` (mirror `mass`).
- `slice`: accept `vxOut,vyOut,vzOut` params and unpack per-column into `ColumnResult`.
- `ScratchPool`: add `velXIn/velYIn/velZIn/velXOut/velYOut/velZOut` buffers (mirror mass/temp scratch).
- `NativeEngine.stepWorld`: replace the Task-11 temp arrays with `flat.vxIn()/…` and pool out-buffers; pass to the native call; slice with the out-buffers.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*VelocityMarshallingTest*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B && git add core
git commit -m "feat(engine-b): RegionMarshaller + ScratchPool velocity plumbing"
```

---

### Task 13: `SectionData` velocity channels

Store velocity per cell so it persists. Defaults to 0 (UNIFORM); promotes to FULL on first per-cell write alongside temperature/mass.

**Files:**
- Modify: `core/.../section/SectionData.java`
- Test: `core/.../section/SectionVelocityTest.java` (unit)

- [ ] **Step 1: Write the failing unit test** — `core/src/test/java/net/rainbowcreation/orge/section/SectionVelocityTest.java`

```java
package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionVelocityTest {
    @Test
    void velocityDefaultsToZeroAndPersistsPerCell() {
        SectionData s = SectionData.uniform(300f, 1000f);
        assertEquals(0f, s.velXAt(0), "velocity defaults to 0");
        s.setVelocity(42, 1.5f, -2.0f, 0.25f);   // promotes to FULL
        assertEquals(1.5f, s.velXAt(42));
        assertEquals(-2.0f, s.velYAt(42));
        assertEquals(0.25f, s.velZAt(42));
        assertEquals(0f, s.velXAt(43), "other cells still 0");
    }
}
```

> Match the actual `SectionData` factory name (`uniform(...)` per the map; if it differs, use the real one).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SectionVelocityTest*' 2>&1 | tail -20`
Expected: FAIL to compile — no `velXAt`/`setVelocity`.

- [ ] **Step 3: Add velocity to `SectionData`**

- Add `float[] velX, velY, velZ` (lazy, allocated on promotion, parallel to `temperature`/`mass`).
- Accessors `velXAt(i)/velYAt(i)/velZAt(i)` returning 0 when UNIFORM/unallocated.
- `setVelocity(i, vx, vy, vz)` promotes to FULL (reuse `promote()`), writes the three arrays.
- `velXArray()/velYArray()/velZArray()` (promote-on-demand) for engine marshalling.
- Extend `demoteIfUniform()` to also require all-zero (or all-equal) velocity before demoting.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SectionVelocityTest*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B && git add core
git commit -m "feat(engine-b): SectionData per-cell velocity channels (default 0, FULL on write)"
```

---

### Task 14: `SectionCodec` v3 — persist velocity, read v1/v2 as 0

Serialize velocity to region files; older blobs load with velocity defaulted to 0 (back-compat).

**Files:**
- Modify: `core/.../section/SectionCodec.java` (`FORMAT_VERSION`, write/read)
- Test: `core/.../section/SectionCodecVelocityTest.java` (unit)

- [ ] **Step 1: Write the failing unit test** — `core/src/test/java/net/rainbowcreation/orge/section/SectionCodecVelocityTest.java`

```java
package net.rainbowcreation.orge.section;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.NavigableMap;
import static org.junit.jupiter.api.Assertions.*;

class SectionCodecVelocityTest {
    @Test
    void v3RoundTripsVelocity() throws IOException {
        SectionData s = SectionData.uniform(300f, 1000f);
        s.setVelocity(7, 1.25f, -3.5f, 0.5f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        SectionCodec.writeColumn(new DataOutputStream(bos), columnOf(5, s));  // helper: one-section column
        NavigableMap<Integer, SectionData> back =
                SectionCodec.readColumn(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        SectionData r = back.get(5);
        assertEquals(1.25f, r.velXAt(7));
        assertEquals(-3.5f, r.velYAt(7));
    }

    @Test
    void v2BlobLoadsWithZeroVelocity() throws IOException {
        // A pre-velocity (v2) column blob must read back with velocity == 0 (back-compat).
        byte[] v2blob = LegacyBlobs.v2SingleUniformSection(5, 300f, 1000f);  // test fixture
        NavigableMap<Integer, SectionData> back =
                SectionCodec.readColumn(new DataInputStream(new ByteArrayInputStream(v2blob)));
        assertEquals(0f, back.get(5).velXAt(0), "v2 blob -> velocity defaults to 0");
    }
}
```

> Provide the small `columnOf(...)` and `LegacyBlobs.v2SingleUniformSection(...)` helpers in the test (the latter writes a `version=2` blob by hand, mirroring the documented v2 wire format).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SectionCodecVelocityTest*' 2>&1 | tail -20`
Expected: FAIL — codec writes v2 (no velocity block).

- [ ] **Step 3: Add the v3 velocity block** — `core/.../section/SectionCodec.java`

- Bump `FORMAT_VERSION` to `3`.
- After the temperature/mass (and v2 material) block, write a velocity block:
  ```
  byte hasVelocity   ; 0 = UNIFORM/all-zero, 1 = present
  [if 1] int vxLen, byte[vxLen]=deflate(velX[4096]); same for vy, vz
  ```
- On read: if `version >= 3`, read the velocity block; if `version < 3`, leave velocity at 0 (the `SectionData` default). Keep the existing v1/v2 material handling intact.

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SectionCodecVelocityTest*' 2>&1 | tail -20`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B && git add core
git commit -m "feat(engine-b): SectionCodec v3 velocity block (v1/v2 load as 0)"
```

---

### Task 15: ColumnAssembler reads velocity; Scheduler writes it back; StepValidator sanitizes it

Close the loop: assembly sources stored velocity into the engine column, write-back stores the evolved velocity, and the §9 validator sanitizes non-finite velocity (no conservation gate — velocity isn't mass).

**Files:**
- Modify: `core/.../scheduler/ColumnAssembler.java` (`SectionCells` + assembly)
- Modify: `core/.../scheduler/Scheduler.java` (`writeBackColumns` stores velocity)
- Modify: `core/.../scheduler/StepValidator.java` (`cleanVelocity`)
- Test: `core/.../engine/EngineBVelocityIT.java` (extend — full loop)

- [ ] **Step 1: Write the failing integration test** — add to `EngineBVelocityIT`:

```java
    @Test
    void velocityPersistsAcrossWriteBackAndReload() {
        Assumptions.assumeTrue(nativeAvailable(), "native liborge required");
        // Drive one scheduler step over a small region with a moving cell; after write-back the
        // SectionStore holds the evolved (finite) velocity, and a NaN injected mid-pipeline is cleaned.
        EngineLoopProbe probe = EngineLoopProbe.movingWaterColumn();
        probe.runOneStep();
        assertTrue(Float.isFinite(probe.storedVelX()), "velocity stored finite after write-back");
        assertEquals(0f, probe.storedVelXAfterNaNInput(), "non-finite velocity sanitized to 0");
    }
```

> `EngineLoopProbe` is a test harness around a real `Scheduler` + in-memory `SectionStore` (mirror an existing scheduler IT). If the existing scheduler ITs use a fake `ThermalWorld`, reuse it.

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20`
Expected: FAIL — assembler doesn't read velocity / write-back doesn't store it / no `cleanVelocity`.

- [ ] **Step 3a: `ColumnAssembler`** — extend `SectionCells` with `float[] velX, velY, velZ`; in assembly, read them from the `SectionSource` (default 0) and pass into the `ColumnTask` (velocity at engine cell index `x + 16*y + 6144*z`).

- [ ] **Step 3b: `StepValidator`** — add:
```java
public static float[] cleanVelocity(float[] v, float[] fallback) {
    float[] out = new float[v.length];
    for (int i = 0; i < v.length; i++) out[i] = Float.isFinite(v[i]) ? v[i] : (fallback==null?0f:fallback[i]);
    return out;
}
```
(Optionally clamp magnitude to a sane bound; not required for Stage 1.)

- [ ] **Step 3c: `Scheduler.writeBackColumns`** — for each written-back section, sanitize the three velocity arrays via `cleanVelocity` and store them into `SectionData` (`setVelocity` per cell, or a bulk `velXArray()` copy when promoted). Velocity write-back happens on the advection cycle alongside mass/species (it does not gate conservation).

- [ ] **Step 4: Rebuild `.so` (if engine untouched, skip), run IT**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*EngineBVelocityIT*' 2>&1 | tail -20`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE-B && git add core
git commit -m "feat(engine-b): assemble+write-back+sanitize velocity through the scheduler loop"
```

---

### Task 16: Stage-1 exit gate — full suites, both loaders, push

Prove the whole stage green on the real `.so`, both loaders build, then push (engine first, gitlink second).

**Files:** none (verification + push)

- [ ] **Step 1: Engine quick tier**

Run: `cd /home/claude/ORGE-B/ORGE-ENGINE && ./tests/run_tests.sh quick 2>&1 | tail -15`
Expected: 0 failures.

- [ ] **Step 2: Rebuild `.so` from clean, run the full Java gate**

```bash
cd /home/claude/ORGE-B
JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE-B/core/src/main/resources/natives/linux-x64/liborge.so 2>&1 | tail -3
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest 2>&1 | tail -25
```
Expected: BUILD SUCCESSFUL, **skipped=0** (confirms the real `.so` ran the integration tests).

- [ ] **Step 3: Both loaders build**

Run: `cd /home/claude/ORGE-B && JAVA_HOME=/home/claude/jdk21 ./gradlew :neoforge-1.21:build :fabric-1.21:build 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Verify-before-completion checklist** (use `superpowers:verification-before-completion`)

Confirm with command output (not assertion): cheap+quick engine tiers green; `:core:test` + `:core:integrationTest` green with skipped=0; both loaders built; `git status` clean except intended changes.

- [ ] **Step 5: Push — engine first, then parent gitlink**

```bash
cd /home/claude/ORGE-B/ORGE-ENGINE
git push origin rebuild
git rev-parse HEAD && git ls-remote origin rebuild | head -1   # verify HEAD == origin
cd /home/claude/ORGE-B
git add ORGE-ENGINE core docs   # bump engine gitlink + Java + the RATIFIED specs/plan
git commit -m "chore(engine-b): bump engine gitlink to Stage-1 energy-flux core; ratify formula doc + plan"
git push origin rebuild
```
Expected: both pushes succeed; parent gitlink points at the pushed engine HEAD. (Per `[[always-push-rebuild]]`, no need to ask.)

- [ ] **Step 6: Update memory** — append a Stage-1-complete line to `[[engine-b-velocity-field]]` (or a new `engine-b-stage1` memory) noting: energy-flux mechanical core shipped on `rebuild`, tests 1/3/4/6/8/13/15 green, velocity channel persisted through SectionCodec v3, Engine-A advection deleted, conduction still in the kernel (Stage 3 subsumes), in-game audit is the remaining gate before Stage 2.

---

## Stages 2–4 — committed roadmap (each becomes its own plan when Stage 1 lands)

These are **not** bite-sized tasks (writing them now would be false precision — they depend on Stage-1 tuning and the in-game audit). They are the committed scope, files, and acceptance gates so the full arc is visible.

### Stage 2 — Inertia + emergent sort/bubbles + reflection
- **Scope:** Validate that persisted `u` produces inertia/sloshing; add the explicit absorb-vs-reflect momentum redirect (§C.5) so an incompressible receiver redirects a donor's push to the lowest-`p` open face (not just relying on the next-tick EOS reversal).
- **Files (anticipated):** `engine_b.hpp` (Resolve: reflection redirect; possibly a second micro-pass or an in-Resolve open-face scan); new `tests/engine_b_inertia_test.cpp`, extend `engine_b_accept_test.cpp`.
- **Tests:** 2 (communicating vessels — single-driver U-tube self-levels), 5 (bubble/plume rises via circulation), 7 (sloshing basin oscillates and settles).
- **Watch:** the multi-arm/manometer equalisation gap (memory `[[orge-communicating-vessels-gap]]`) — Stage 2 should at least not regress single-driver vessels; full multi-arm may bank to a later refinement.

### Stage 3 — Thermal unification (subsume `orge_kernel.hpp`, L8)
- **Scope:** Replace the passive temperature scalar with the full energy closure — advected internal energy `E_adv = ṁ·h` and the §D.3 `ΔE_th→T'` (flow-work + viscous-dissipation accounting) — and add the diffusive Fourier channel `q = k_face·(T_i−T_j)·(A/Δx)·dt` (§C.3) **inside Resolve**, deleting the separate conduction pass (`compute_frame_to_backbuffers`/`swap_all_backbuffers`/`simulate_section_16x16x16`) and folding `PASS_CONDUCTION` into the single step.
- **Files (anticipated):** `engine_b.hpp` (CellEncrypt already carries `h`; add `q` to Resolve, energy unpack to Decrypt), `orge_jni.cpp` (collapse the two-pass branch into one `step_world_b`; retire `T_next`/back-buffer if unused), `orge_kernel.hpp` (keep `keff`/`finalize_temp` or inline), `Scheduler` (drop the separate heat cadence — already one combined call, mostly a cleanup), delete the conduction tests that test the old kernel and add `engine_b_conduction_test.cpp`.
- **Tests:** 9 (thermal convection cell forms), 10 (pure Fourier conduction matches analytic), 11 (conservation soak: energy + per-species mass invariant over thousands of steps), 14 (free-surface pressure-pulse transmission, no hard reflect).
- **Watch:** this is the biggest scope step (replaces two subsystems); the §J.3 worked conduction example is the parity anchor.

### Stage 4 — Tune + interface sharpening + perf
- **Scope:** Calibrate `K, γ, α, T_ref, k-scale, λ-scale` by bisection against their single acceptance tests in the §G.2 order (`K→γ→α→k→λ`), then a joint soak; add the slope-limiter (MUSCL/minmod) for `u`/`T` and species-label sharpening (THINC/anti-diffusion) to fix the ~1-cell upwind smear (§K #4); profile vs Engine A.
- **Files (anticipated):** `engine_b.hpp` (limiter in Resolve, sharpening in Decrypt; promote `Globals` to data-driven/tunable), a calibration harness test, `engine_b_perf_test.cpp`.
- **Tests:** 12 (perf vs Engine A combined advection+conduction), 16 (sharp-interface: edge stays within 1 cell with limiter+sharpen).
- **Gate to ship Engine B on `main`:** all of tests 1–16 green + the in-game audit passes; only then does Engine B replace Engine A on `main` (spec §8 parity strategy — keep A shipping until B passes the in-game gate).

---

## Self-Review (run against the spec)

**Spec coverage (formula spec §A–§K, design §1–§11):**
- §A state (m,T,u,s) → Task 3 (velocity channel) + existing m/T/species. ✔
- §B Encrypt (EOS, amplitude, direction, drag, vector) → Tasks 2, 4. ✔
- §C Resolve (face drive, advective, pressure flux, capacity clamp, free-slip) → Task 5. ✔ (§C.5 reflection *redirect* → Stage 2, noted.)
- §D Decrypt (mass/species, momentum→u, vacuum/CFL guards, free-slip no-penetration) → Task 6. ✔ (§D.3 energy→T closure → Stage 3, noted; Stage 1 uses passive T.)
- §E conservation → exercised by Task 5 (net dm==0) + Task 7/8 (mass-invariant). ✔
- §G tunables → provisional in Task 2 Globals; calibration → Stage 4. ✔ (acoustic-CFL §G.1b respected via soft K + tests 13/15.)
- §I/§I-bis acceptance tests → Stage-1 subset (1,3,4,6,8,13,15) in Task 8; rest mapped to Stages 2–4. ✔
- §J equivalence proof → encoded as the Task 5 (§J.4 numbers) + Resolve antisymmetry; §J.3 conduction → Stage 3. ✔
- §K guards → over-compression (test 13), vacuum (test 15) in Task 8; sharp-edge → Stage 4. ✔
- design §7/§8 integration & migration → Tasks 9–15 (JNI ABI, SectionStore, scheduler, reconciler backstop; delete Engine-A advection; keep conduction until Stage 3). ✔

**Placeholder scan:** the only literal placeholder is the deliberately-flagged dead line in Task 5 Step 3, with an explicit "delete this" note and a self-check — not a silent TODO. Test helper classes (`TestMaterials`, `EngineStepProbe`, `EngineLoopProbe`, `LegacyBlobs`, `columnOf`) are described with what they must do and which existing class to mirror; the worker creates them minimally. Globals values are provisional-by-design (§G), explicitly flagged.

**Type consistency:** `Material.defaultMass` (Task 1) used by `eos_pressure`/`chi` (Task 2). `CellEncrypt`/`encrypt_world` (Task 4) consumed by `resolve_world` (Task 5) and `decrypt_world` (Task 6). `WorldAccum`/`ChunkAccum`/`CellAccum` defined Task 5, consumed Task 6. `step_world_b(World&, MaterialLUT&, Globals&, float)` + the `(World&, MaterialLUT&, double)` overload (Task 7) called by `orge_jni.cpp` (Task 9). Java: `ColumnTask`/`ColumnResult`/`Flat` velocity fields (Task 12) used by `SectionData` (13)/`SectionCodec` (14)/`ColumnAssembler`+`Scheduler`+`StepValidator` (15). Native decls (`orgeRegisterMaterials` 7-array, `orgeStepWorld` velocity) match the C++ JNI signatures (Tasks 10, 11). Consistent.

---

*Stage 1 of 4. Next after Stage 1 lands + in-game audit: write the Stage 2 plan (inertia/reflection).*
