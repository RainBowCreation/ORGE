# §10 Phase-2b (1/3) — Engine: density displacement, wetting & three-mass model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the native engine move mass into AIR and across species by density, with a per-material
flow floor and capacity cap, so finite water spreads-then-stops, steam rises, and lava sorts — and report
the resulting per-cell species (`matOut`) so Java can place the right block.

**Architecture:** Implement in the C++ reference sim (`sim_engine.hpp`, SDL-tunable) first, port
**bit-identical** into the stateless per-section kernel (`orge_kernel.hpp`), expose `matOut` over JNI,
rebuild the committed `liborge.so`, and land the matching Java ABI declaration in lockstep. **No Java
behavior change** lands here — the reconciler still ignores `matOut` and §9 is unchanged; this plan ends
with the new engine wired in, all C++ tests green, both loaders compiling, and the existing Java suite
still passing. Plans 2 (Java behavior) and 3 (dormancy) build on it.

**Tech Stack:** C++20 header-only engine (g++, dependency-free harness `tests/test_harness.hpp`); JNI;
Java 21, Architectury multiloader (MC 1.21.11, Mojang mappings). Build env: `JAVA_HOME=/home/claude/jdk21`.

**Spec:** `docs/superpowers/specs/2026-05-30-fluid-displacement-phase2b-design.md` (Decisions 0–10, 12, 13).

---

## Two repositories — read this first

| Repo | Path | `git add` from |
|---|---|---|
| **ENGINE** | `/home/claude/ORGE/ORGE-ENGINE` (git **submodule**, own `.git`) | run git **inside** `ORGE-ENGINE/` |
| **MAIN** | `/home/claude/ORGE` (branch `rebuild`) | run git from `/home/claude/ORGE` |

ENGINE tasks `cd /home/claude/ORGE/ORGE-ENGINE` before `git`. MAIN tasks run git from `/home/claude/ORGE`.
Never `git add` across the boundary. **Push policy:** push `origin/rebuild` after every MAIN commit (the
user tests from origin). ENGINE: commit per task; merge/push the ENGINE branch + bump the MAIN gitlink at
the END (Task 11), as Phase-2a did. Commit messages end with
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Conventions for every task

- **ENGINE C++ build+test:** `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh`. Single new test:
  `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/<file>.cpp -o build/<file> -pthread && ./build/<file>`.
- **ENGINE native build:** `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`.
- **MAIN one test class:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "<FQCN>" --rerun-tasks`
- **MAIN all 3 loaders compile:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
- C++ harness: each test is a `void test_x()` using `TH_CHECK`, `TH_CHECK_MSG`, `TH_CHECK_CLOSE(got,want,rel)`,
  registered in `main()` via `run("name", test_x)`; the binary exits non-zero if any check fails.
- **Bit-identicality is sacred:** every change to `orge_kernel.hpp`'s advection MUST be mirrored in
  `sim_engine.hpp::advect_chunk`, and the existing parity test must stay green. Make the kernel change and
  the sim_engine change in the SAME task.

## The three-mass model (Spec Decision 0) — single source

Per material, three masses with `min_flow_mass ≤ default_mass ≤ max_mass`:

| field | meaning | kernel use |
|---|---|---|
| `minFlow` | flow floor (cohesion); a cell at/below this no longer **donates** | spread donor guard; gives finite spread (`coverage ≈ mass/minFlow`) |
| `fullMass` (= `default_mass`) | resting density + seed | buoyancy resting reference (unused-as-cap now) |
| `maxMass` | per-cell capacity cap | fall/merge remaining-capacity; (Java §9 per-cell bound) |

Plus a `gas` flag (1 = gas phase). **This slice:** every current material sets `maxMass == fullMass` and
`minFlow` to a per-material floor (water/lava get a real floor; air/void stay 0). Cross-species swap is
allowed only when **at least one side is gas/air** (phase differs) — so the only liquid↔liquid pair
(water↔lava) is left to §7 (Decision 5), with no new "reacts" flag needed this slice.

---

## File structure

| File | Repo | Responsibility |
|---|---|---|
| `orge_kernel.hpp` | ENGINE | `MatLUT` gains `minFlow`, `maxMass`, `gas`; `step_section_with_halo` gains `matOut` out; floor + air-destination + cross-species swap added to advection. |
| `sim_engine.hpp` | ENGINE | `MaterialLUT`/`MaterialDef` gain the same fields; `advect_chunk` mirrors the kernel bit-identically. |
| `orge_jni.cpp` | ENGINE | pin/release a new `matOut` output array. |
| `tests/test_phase2b_*.cpp` | ENGINE | new behavior tests + parity extension. |
| `core/.../engine/NativeEngine.java` | MAIN | native `orgeStep` declaration → new ABI (`matOut`). |
| `core/.../engine/StepResult.java` | MAIN | add `material()` (`char[]`) component. |
| `core/.../engine/StubEngine.java` + callers | MAIN | return `matIx` unchanged as `material` (identity), to keep the non-native path coherent. |
| `core/src/main/resources/natives/linux-x64/liborge.so` | MAIN | rebuilt artifact (Task 11). |

---

## Task 1 [ENGINE]: `MatLUT` + `MaterialDef` gain `minFlow`, `maxMass`, `gas`

**Files:**
- Modify: `orge_kernel.hpp` (`struct MatLUT`)
- Modify: `sim_engine.hpp` (`struct MaterialDef` ~L30-35, `MaterialLUT` builder)
- Test: `tests/test_phase2b_lut.cpp` (create)

- [ ] **Step 1: Write the failing test**

```cpp
// tests/test_phase2b_lut.cpp
#include "test_harness.hpp"
#include "orge_kernel.hpp"
using namespace orge;

void test_lut_carries_three_masses_and_gas() {
    float cond[3]={0,0.6f,0.02f}, cap[3]={0,4186,2000};
    float visc[3]={0,1000,5000}, full[3]={0,1000,3100};
    float minf[3]={0,125,400},   maxm[3]={0,1000,3100};
    uint8_t fluid[3]={0,1,1},    gas[3]={0,0,0};
    MatLUT lut{cond,cap,visc,full,fluid,minf,maxm,gas,3};
    TH_CHECK(lut.minFlow[1]==125.0f);
    TH_CHECK(lut.maxMass[2]==3100.0f);
    TH_CHECK(lut.gas[1]==0);
    TH_CHECK(lut.count==3);
}

int main(){ run("lut_carries_three_masses_and_gas", test_lut_carries_three_masses_and_gas); return th_summary(); }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_lut.cpp -o build/t_lut -pthread && ./build/t_lut`
Expected: compile FAIL — `MatLUT` has no member `minFlow`/`maxMass`/`gas` and the aggregate initialiser has too many fields.

- [ ] **Step 3: Add the fields to `MatLUT`**

In `orge_kernel.hpp`, extend the struct (append fields so existing positional initialisers in other tests still need updating — that is intentional and handled in Task-wide compile):

```cpp
struct MatLUT {
    const float*   cond;     // W/(m*K)
    const float*   heatCap;  // J/(kg*K)
    const float*   visc;     // Pa*s (advection spread rate)
    const float*   fullMass; // kg, resting density = Material.defaultMass (seed/buoyancy ref)
    const uint8_t* fluid;    // 1 = participates in advection, 0 = inert
    const float*   minFlow;  // kg, flow floor (cohesion); cell at/below this won't donate
    const float*   maxMass;  // kg, per-cell capacity cap (>= fullMass)
    const uint8_t* gas;      // 1 = gas phase (buoyancy / cross-species rules)
    int count;
};
```

- [ ] **Step 4: Mirror in `sim_engine.hpp`**

Add to `MaterialDef` (near `viscosity`/`fluidFlag`, ~L33):

```cpp
    float minFlowMass = 0.0f;  // kg, flow floor (cohesion)
    float maxMass     = 0.0f;  // kg, per-cell capacity cap (0 => fall back to defaultMass)
    bool  gasFlag     = false; // gas phase
```

Wherever `sim_engine.hpp` builds an `orge::MatLUT` to call the kernel (the parity path), populate the new
arrays from `MaterialDef`, defaulting `maxMass` to `defaultMass` when left 0:

```cpp
    // in the MatLUT-assembly helper:
    maxm[ix] = (m.maxMass > 0.0f) ? m.maxMass : m.defaultMass;
    minf[ix] = m.minFlowMass;
    gasv[ix] = m.gasFlag ? 1 : 0;
```

- [ ] **Step 5: Run to verify it passes; then build the whole suite**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_lut.cpp -o build/t_lut -pthread && ./build/t_lut`
Expected: PASS.
Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh`
Expected: any test that builds a `MatLUT` by positional aggregate now FAILS to compile — fix each by adding
the three new arrays (use `minFlow=fullMass*0`, i.e. all-zero floors, and `maxMass=fullMass`, `gas=0` so
behavior is unchanged). All tests green after.

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/test_phase2b_lut.cpp tests/
git commit -m "feat(engine): MatLUT/MaterialDef carry minFlow, maxMass, gas (three-mass model)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 [ENGINE]: capacity cap reads `maxMass`; `step_section_with_halo` gains `matOut`

**Files:**
- Modify: `orge_kernel.hpp` (`step_section_with_halo` signature + fall cap)
- Modify: `sim_engine.hpp` (`advect_chunk` fall cap)
- Test: `tests/test_phase2b_matout.cpp` (create)

- [ ] **Step 1: Write the failing test** — `matOut` defaults to `matIx` (identity) and the cap uses `maxMass`.

```cpp
// tests/test_phase2b_matout.cpp
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
using namespace orge;

void test_matout_identity_when_no_change() {
    std::vector<uint16_t> mat(SEC_N,1);     // all water
    std::vector<float> mass(SEC_N,1000.0f), T(SEC_N,300.0f);
    std::vector<float> haloT(FACE*FACES,300.0f), haloMass(FACE*FACES,1000.0f);
    std::vector<uint16_t> haloMat(FACE*FACES,1);
    float cond[2]={0,0.6f},cap[2]={0,4186},visc[2]={0,1000},full[2]={0,1000};
    float minf[2]={0,0},maxm[2]={0,1000}; uint8_t fluid[2]={0,1},gas[2]={0,0};
    MatLUT lut{cond,cap,visc,full,fluid,minf,maxm,gas,2};
    std::vector<float> Tout(SEC_N), massOut(SEC_N);
    std::vector<uint16_t> matOut(SEC_N, 9);   // sentinel; engine must overwrite
    step_section_with_halo(mat.data(),mass.data(),T.data(),haloT.data(),haloMat.data(),
                           haloMass.data(),lut,0.25f,PASS_ADVECTION,Tout.data(),massOut.data(),matOut.data());
    for (int i=0;i<SEC_N;++i) TH_CHECK(matOut[i]==1); // identity: still water everywhere
}

int main(){ run("matout_identity", test_matout_identity_when_no_change); return th_summary(); }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_matout.cpp -o build/t_mo -pthread && ./build/t_mo`
Expected: compile FAIL — `step_section_with_halo` takes no `matOut` argument.

- [ ] **Step 3: Add `matOut` to the signature + init; switch fall cap to `maxMass`**

In `orge_kernel.hpp`, change the signature and the init/fall:

```cpp
inline void step_section_with_halo(
    const uint16_t* matIx, const float* mass, const float* Tin,
    const float* haloT, const uint16_t* haloMat, const float* haloMass,
    const MatLUT& lut, float dt, int passes,
    float* Tout, float* massOut, uint16_t* matOut)        // <-- new out
{
    constexpr float inv_dx2 = 1.0f;
    for (int i = 0; i < SEC_N; ++i) { Tout[i]=Tin[i]; massOut[i]=mass[i]; matOut[i]=matIx[i]; } // identity
    ...
    // in the FALL block, replace fullMass with maxMass for remaining capacity:
        float cap = lut.maxMass[matIx[below]] - massOut[below];
```

- [ ] **Step 4: Mirror in `sim_engine.hpp`**

In `advect_chunk`, the fall cap line becomes (defaulting to defaultMass via the maxMass(0)→default rule
already handled in the def): `float cap = matCap(C.matIx[below]) - C.mass_kg[below];` where `matCap(ix)`
returns `mats.byIx(ix).maxMass>0 ? .maxMass : .defaultMass`. (sim_engine tracks species in `C.matIx`
directly, so it has no separate `matOut` array — the kernel's `matOut` corresponds to sim_engine's live
`C.matIx`. Parity compares the kernel's `matOut` against sim_engine's `C.matIx` after the step.)

- [ ] **Step 5: Fix all kernel call sites for the new arg, then run**

Every existing test/JNI call to `step_section_with_halo` must pass a `matOut` array now. Update them
(allocate `std::vector<uint16_t> matOut(SEC_N)` and pass `matOut.data()`).
Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_matout.cpp -o build/t_mo -pthread && ./build/t_mo`
Expected: PASS.
Run: `./tests/run_tests.sh` → all green (cap value unchanged since maxMass==fullMass everywhere today).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/
git commit -m "feat(engine): step_section_with_halo emits matOut; fall cap reads maxMass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 [ENGINE]: `minFlow` floor — finite water spreads then STOPS

**Files:**
- Modify: `orge_kernel.hpp` (horizontal spread donor guard)
- Modify: `sim_engine.hpp` (`advect_chunk` horizontal spread)
- Test: `tests/test_phase2b_floor.cpp` (create)

- [ ] **Step 1: Write the failing test** — a finite slug of water on a floor spreads to ≈ `mass/minFlow`
  cells and then holds (no infinite thinning).

```cpp
// tests/test_phase2b_floor.cpp
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
using namespace orge;
// 1-D row of water in y=0 plane, no halo flux (void halo), step many times; once every
// occupied cell is at/below minFlow it must stop donating -> mass count stabilises.
void test_finite_water_stops_at_floor() {
    const int WATER=1; float minflow=125.0f;
    std::vector<uint16_t> mat(SEC_N,0); std::vector<float> mass(SEC_N,0), T(SEC_N,300);
    // one full cell (1000 kg) at (8,0,8)
    int c=sidx(8,0,8); mat[c]=WATER; mass[c]=1000.0f;
    std::vector<float> haloT(FACE*FACES,300); std::vector<float> haloMass(FACE*FACES,0);
    std::vector<uint16_t> haloMat(FACE*FACES,0);
    float cond[2]={0,0.6f},cap[2]={0,4186},visc[2]={0,1000},full[2]={0,1000};
    float minf[2]={0,minflow},maxm[2]={0,1000}; uint8_t fluid[2]={0,1},gas[2]={0,0};
    MatLUT lut{cond,cap,visc,full,fluid,minf,maxm,gas,2};
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    auto countOccupied=[&](std::vector<float>&m){int n=0;for(float v:m)if(v>ADV_EPS_MASS)++n;return n;};
    for(int s=0;s<200;++s){
        step_section_with_halo(mat.data(),mass.data(),T.data(),haloT.data(),haloMat.data(),
            haloMass.data(),lut,0.25f,PASS_ADVECTION,Tout.data(),mo.data(),matOut.data());
        mass=mo; for(int i=0;i<SEC_N;++i){ if(matOut[i]) mat[i]=matOut[i]; } T=Tout;
    }
    int occ=countOccupied(mass);
    TH_CHECK_MSG(occ<=10 && occ>=6, "finite water covers ~mass/minFlow cells, not the whole plane");
    double total=0; for(float v:mass) total+=v;
    TH_CHECK_CLOSE(total,1000.0,1e-3); // mass conserved (no halo loss)
}
int main(){ run("finite_water_stops_at_floor", test_finite_water_stops_at_floor); return th_summary(); }
```

> NOTE: this test also depends on Task 4 (water must wet adjacent AIR to spread at all). Author the test
> now (RED), implement the floor here, and it goes GREEN only after Task 4. That is intentional — keep it
> in the suite; mark Task 3 done when the floor logic compiles and the existing tests stay green, and
> confirm this test passes at the end of Task 4.

- [ ] **Step 2: Run to verify it fails** (water doesn't spread into air yet → it stays in one cell).

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_floor.cpp -o build/t_fl -pthread && ./build/t_fl`
Expected: FAIL (occ==1; water never left its cell).

- [ ] **Step 3: Add the floor to the horizontal-spread donor guard**

In `orge_kernel.hpp` (2b-i), a cell at/below its floor must not donate:

```cpp
        float m = massOut[i]; if (m <= ADV_EPS_MASS) continue;
        if (m <= lut.minFlow[matIx[i]]) continue;   // floor: too shallow to keep flowing
```

And clamp the per-neighbour transfer so a donor never drops below its floor:

```cpp
            float diff = m - mNb;
            if (diff <= 0.0f) continue;
            float dm = frac * 0.5f * diff;
            float donatable = m - lut.minFlow[matIx[i]];   // never drain below the floor
            if (donatable <= 0.0f) continue;
            if (dm > donatable) dm = donatable;
```

- [ ] **Step 4: Mirror in `sim_engine.hpp`** (same two guards in the horizontal-spread loop, using
  `mats.byIx(mix).minFlowMass`).

- [ ] **Step 5: Run** — compile + `./tests/run_tests.sh`. The floor test stays RED until Task 4; all
  pre-existing tests stay GREEN (floors are 0 in every existing fixture → no change). Confirm both.

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/test_phase2b_floor.cpp
git commit -m "feat(engine): minFlow floor on the spread donor (finite, stopping spread)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 [ENGINE]: wetting — fluid falls/spreads INTO air, adopts species via `matOut`

**Files:**
- Modify: `orge_kernel.hpp` (fall + horizontal spread: allow air destinations; set `matOut`)
- Modify: `sim_engine.hpp` (`advect_chunk`: same; sets `C.matIx[dst]`)
- Test: reuse `tests/test_phase2b_floor.cpp` (now GREEN) + `tests/test_phase2b_wet.cpp` (create)

- [ ] **Step 1: Write the failing test** — water falls into the air cell below and that cell becomes water
  (`matOut`), mass conserved.

```cpp
// tests/test_phase2b_wet.cpp
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
using namespace orge;
void test_water_falls_into_air_and_wets_it() {
    const int WATER=1;
    std::vector<uint16_t> mat(SEC_N,0); std::vector<float> mass(SEC_N,0),T(SEC_N,300);
    int top=sidx(8,5,8); mat[top]=WATER; mass[top]=1000.0f; T[top]=290.0f; // floats above air
    std::vector<float> haloT(FACE*FACES,300),haloMass(FACE*FACES,0);
    std::vector<uint16_t> haloMat(FACE*FACES,0);
    float cond[2]={0,0.6f},cap[2]={0,4186},visc[2]={0,1000},full[2]={0,1000};
    float minf[2]={0,125},maxm[2]={0,1000}; uint8_t fluid[2]={0,1},gas[2]={0,0};
    MatLUT lut{cond,cap,visc,full,fluid,minf,maxm,gas,2};
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    step_section_with_halo(mat.data(),mass.data(),T.data(),haloT.data(),haloMat.data(),
        haloMass.data(),lut,0.25f,PASS_ADVECTION,Tout.data(),mo.data(),matOut.data());
    int below=sidx(8,4,8);
    TH_CHECK_MSG(mo[below]>0.0f, "water fell into the air cell below");
    TH_CHECK_MSG(matOut[below]==WATER, "the wetted air cell adopted the water species");
    TH_CHECK_CLOSE((double)mo[top]+mo[below],1000.0,1e-3); // conserved
}
int main(){ run("water_wets_air", test_water_falls_into_air_and_wets_it); return th_summary(); }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_wet.cpp -o build/t_wet -pthread && ./build/t_wet`
Expected: FAIL — fall guard still requires `isFluid(below) && matIx[below]==matIx[i]`, so air is rejected.

- [ ] **Step 3: Allow AIR (empty/void) as a destination, set the species**

Define an `isAir` helper and a `wetDeposit` that adopts the donor species. In `orge_kernel.hpp` advection
prologue:

```cpp
    auto isAir = [&](int cell){ return matIx[cell]==0 || massOut[cell] <= ADV_EPS_MASS; };
    // deposit that, into an empty cell, sets matOut to the donor species
    auto depositSpecies = [&](int dst, float dm, float Ts, uint16_t species){
        if (matOut[dst]==0 || massOut[dst] <= ADV_EPS_MASS) matOut[dst]=species;
        float md=massOut[dst], mn=md+dm;
        if (mn > ADV_EPS_MASS) Tout[dst]=(md*Tout[dst]+dm*Ts)/mn;
        massOut[dst]=mn;
    };
```

FALL block — accept a same-species fluid OR an air cell:

```cpp
        int below = sidx(x,y-1,z);
        bool sameFluid = isFluid(matIx[below]) && matIx[below]==matIx[i];
        bool intoAir   = isAir(below);
        if (!sameFluid && !intoAir) continue;
        float cap = lut.maxMass[matIx[i]] - massOut[below];   // capacity at the donor's species cap
        if (cap <= 0.0f) continue;
        float dm = (m < cap) ? m : cap;
        depositSpecies(below, dm, Tout[i], matIx[i]);
        massOut[i] -= dm;
        if (massOut[i] <= ADV_EPS_MASS) { massOut[i]=0.0f; Tout[i]=0.0f; /* matOut[i] left; Java drains via mass<=0 */ }
```

HORIZONTAL spread (2b-i) — accept same-species OR air as the neighbour; when wetting air use
`depositSpecies` on the interior neighbour and set `matOut`:

```cpp
            bool sameFluid = isFluid(matNb) && matNb==matIx[i];
            bool intoAir   = interior ? isAir(sidx(nx,ny,nz)) : (matNb==0 || mNb<=ADV_EPS_MASS);
            if (!sameFluid && !intoAir) continue;
            float effNbMass = intoAir ? 0.0f : mNb;          // air reads as 0 mass
            float diff = m - effNbMass;
            if (diff <= 0.0f) continue;
            float donatable = m - lut.minFlow[matIx[i]];
            if (donatable <= 0.0f) continue;
            float dm = frac * 0.5f * diff; if (dm > donatable) dm = donatable;
            dM[i] -= dm; dE[i] -= dm*Tout[i];
            if (interior) { int j=sidx(nx,ny,nz); dM[j]+=dm; dE[j]+=dm*Tout[i];
                            if (matOut[j]==0 || massOut[j]<=ADV_EPS_MASS) matOut[j]=matIx[i]; }
            // boundary wetting across a seam is handled by the neighbour section's own pass.
```

> Note: the interior wet via `dM[]`/`dE[]` sets `matOut[j]` immediately so a later neighbour in the same
> pass sees the new species; the mass itself is applied in the existing `dM` finalize loop.

- [ ] **Step 4: Mirror in `sim_engine.hpp`** — same air-destination logic; sim_engine writes `C.matIx[dst]`
  directly when an empty cell is wetted (its analogue of `matOut`). Keep the antisymmetric-flux structure.

- [ ] **Step 5: Run** — the wet test, the Task-3 floor test (now GREEN), and the full suite.

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_wet.cpp -o build/t_wet -pthread && ./build/t_wet && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_floor.cpp -o build/t_fl -pthread && ./build/t_fl`
Expected: both PASS.
Run: `./tests/run_tests.sh` → all green (including the bit-identical parity test: kernel `matOut` vs sim_engine `C.matIx`).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/test_phase2b_wet.cpp
git commit -m "feat(engine): wetting — fluid falls/spreads into air and adopts species via matOut

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 [ENGINE]: cross-species vertical SWAP (buoyancy + sorting) with hysteresis

**Files:**
- Modify: `orge_kernel.hpp` (new cross-species vertical exchange, interior + seam)
- Modify: `sim_engine.hpp` (`advect_chunk` mirror)
- Test: `tests/test_phase2b_swap.cpp` (create)

- [ ] **Step 1: Write the failing tests** — (a) steam below air rises; (b) lava below water rises above
  water (water sinks); (c) water above lava is STABLE (no swap — left to §7); (d) no checkerboard.

```cpp
// tests/test_phase2b_swap.cpp
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
using namespace orge;
// LUT: 0 void/air, 1 water(1000,liquid), 2 lava(3100,liquid), 3 steam(0.6,gas)
static MatLUT makeLut(float* C,float* H,float* V,float* F,uint8_t* FL,float* MN,float* MX,uint8_t* G){
    return MatLUT{C,H,V,F,FL,MN,MX,G,4};
}
void test_steam_rises_through_air() {
    float C[4]={0,0.6f,0.02f,0.02f},H[4]={0,4186,2000,2000},V[4]={0,1000,5000,50};
    float F[4]={0,1000,3100,0.6f}; uint8_t FL[4]={0,1,1,1};
    float MN[4]={0,125,400,0.6f},MX[4]={0,1000,3100,0.6f}; uint8_t G[4]={0,0,0,1};
    MatLUT lut=makeLut(C,H,V,F,FL,MN,MX,G);
    std::vector<uint16_t> mat(SEC_N,0); std::vector<float> mass(SEC_N,0),T(SEC_N,400);
    int lo=sidx(8,5,8); mat[lo]=3; mass[lo]=0.6f;     // steam with air above
    std::vector<float> hT(FACE*FACES,400),hM(FACE*FACES,0); std::vector<uint16_t> hMt(FACE*FACES,0);
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    step_section_with_halo(mat.data(),mass.data(),T.data(),hT.data(),hMt.data(),hM.data(),
        lut,0.25f,PASS_ADVECTION,Tout.data(),mo.data(),matOut.data());
    int up=sidx(8,6,8);
    TH_CHECK_MSG(matOut[up]==3 && mo[up]>0.0f, "steam moved up into the air cell");
    TH_CHECK_MSG(mo[lo]<=ADV_EPS_MASS, "the lower cell vacated (air)");
}
// (b) lava below water: lava rises. (c) water above lava: no swap. (d) stability over N steps.
// ... analogous bodies; see test names in main().
int main(){
    run("steam_rises", test_steam_rises_through_air);
    // run("lava_sorts_under_water", ...); run("water_over_lava_stable", ...); run("no_checkerboard", ...);
    return th_summary();
}
```

> The implementer writes the (b)/(c)/(d) bodies to the same harness; (c) asserts `matOut`/mass unchanged
> for a water-over-lava column (stable ordering — denser already below), and (d) steps a random
> air/steam field N=100 times and asserts the swap count per step trends to 0 (no permanent oscillation).

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_swap.cpp -o build/t_sw -pthread && ./build/t_sw`
Expected: FAIL — no cross-species vertical exchange exists; steam stays put.

- [ ] **Step 3: Add the cross-species vertical SWAP** (interior; seam handled in Step 4)

After the existing same-species fall/spread, add a vertical exchange that swaps full-cell contents when
the ordering is density-inverted, gated to phase-different pairs (Decision 5). Use the current-density
comparison and a hysteresis ratio:

```cpp
    // (2c) CROSS-SPECIES vertical displacement (Spec Decision 0/4/8). Full-cell swap toward the
    // stable ordering (denser below). Only when at least one side is gas/air (phase differs), so the
    // single liquid<->liquid pair (water/lava) is left to §7. One swap per cell per step (claim).
    constexpr float SWAP_HYST = 1.05f;  // require >5% inversion to swap (anti-flicker)
    std::vector<uint8_t> swapped(SEC_N, 0);
    auto density = [&](int cell)->float {
        if (matIx[cell]==0 || massOut[cell] <= ADV_EPS_MASS) return /*AIR_DENSITY*/ lut.fullMass[0] > 0 ? lut.fullMass[0] : 1.2f;
        return massOut[cell];                  // current density = current mass (1 m^3)
    };
    auto phaseDiffers = [&](int a,int b){
        bool ga = (matIx[a]==0) || lut.gas[matIx[a]];   // air counts as gas-side
        bool gb = (matIx[b]==0) || lut.gas[matIx[b]];
        return ga != gb || matIx[a]==0 || matIx[b]==0;  // any air, or gas-vs-nongas
    };
    for (int z=0; z<SEC; ++z) for (int y=1; y<SEC; ++y) for (int x=0; x<SEC; ++x) {
        int lo=sidx(x,y-1,z), up=sidx(x,y,z);
        if (swapped[lo] || swapped[up]) continue;
        if (!phaseDiffers(lo,up)) continue;        // liquid<->liquid different species -> §7, skip
        float dLo=density(lo), dUp=density(up);
        if (dLo >= dUp*SWAP_HYST) continue;        // already stable (denser below) or within hysteresis
        // inversion: lighter is below -> swap full-cell contents (species+mass+temp)
        std::swap(matOut[lo], matOut[up]);
        std::swap(massOut[lo], massOut[up]);
        std::swap(Tout[lo], Tout[up]);
        swapped[lo]=swapped[up]=1;
    }
```

> `lut.fullMass[0]` is the air/void resting density label; ensure the Java LUT sets index-0
> `fullMass = AIR_DENSITY (1.2)` (Plan 2). Until then the `>0 ? : 1.2f` fallback keeps the kernel correct.

- [ ] **Step 4: Seam swap + mirror in `sim_engine.hpp`**

Cross-section vertical swaps at the section's bottom/top plane use the halo's `(mat,mass,T)` and the same
antisymmetric discipline as the existing seam-spread: a section only writes its own cell, applying the
half it owns; the neighbour applies the mirror on its pass. Mirror the entire (2c) block into
`sim_engine.hpp::advect_chunk` (interior swaps over `C.matIx/mass_kg/T_curr`; seam swaps across
`SECTION_EDGE` planes), keeping it bit-identical.

- [ ] **Step 5: Run** — the swap tests + full suite (including parity).

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh`
Expected: all green; the swap tests pass; the no-checkerboard test confirms swaps decay to 0.

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/test_phase2b_swap.cpp
git commit -m "feat(engine): cross-species full-cell density swap (buoyancy + sorting) w/ hysteresis

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 [ENGINE]: settle-detection helper (for Plan 3 dormancy) — engine reports max |Δ|

**Files:**
- Modify: `orge_kernel.hpp` (no behavior change; document that callers diff `mass/Tin` vs `massOut/Tout`)
- Test: `tests/test_phase2b_settle.cpp` (create)

- [ ] **Step 1: Write the test** — a settled (uniform, no-gradient) field returns `massOut==mass` and
  `Tout==Tin` within ε after a step, so the Java scheduler can detect "nothing moved" by a cheap diff.

```cpp
// tests/test_phase2b_settle.cpp  — proves the dormancy signal is well-defined at the engine boundary.
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
#include <cmath>
using namespace orge;
void test_settled_field_reports_no_change() {
    // a flat full-water pool with matching halo: nothing should move.
    std::vector<uint16_t> mat(SEC_N,1); std::vector<float> mass(SEC_N,1000.0f),T(SEC_N,300.0f);
    std::vector<float> hT(FACE*FACES,300.0f),hM(FACE*FACES,1000.0f); std::vector<uint16_t> hMt(FACE*FACES,1);
    float C[2]={0,0.6f},H[2]={0,4186},V[2]={0,1000},F[2]={0,1000},MN[2]={0,125},MX[2]={0,1000};
    uint8_t FL[2]={0,1},G[2]={0,0}; MatLUT lut{C,H,V,F,FL,MN,MX,G,2};
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    step_section_with_halo(mat.data(),mass.data(),T.data(),hT.data(),hMt.data(),hM.data(),
        lut,0.25f,PASS_ADVECTION,Tout.data(),mo.data(),matOut.data());
    float dMax=0,tMax=0; for(int i=0;i<SEC_N;++i){ dMax=std::max(dMax,std::fabs(mo[i]-mass[i])); tMax=std::max(tMax,std::fabs(Tout[i]-T[i])); }
    TH_CHECK_MSG(dMax < 1e-3f, "settled pool moved no mass");
    TH_CHECK_MSG(tMax < 1e-3f, "settled pool changed no temperature");
}
int main(){ run("settled_no_change", test_settled_field_reports_no_change); return th_summary(); }
```

- [ ] **Step 2: Run** — Expected PASS already (the existing rules don't perturb a flat field). If it fails,
  it reveals a spurious motion bug introduced by Tasks 3-5 — fix that bug before proceeding (this test is
  the guard that the new rules respect equilibrium, which dormancy depends on).

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_settle.cpp -o build/t_se -pthread && ./build/t_se`

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/test_phase2b_settle.cpp orge_kernel.hpp
git commit -m "test(engine): settled field reports no change (dormancy signal is well-defined)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7 [ENGINE]: `orge_jni.cpp` — pin/release `matOut`

**Files:**
- Modify: `orge_jni.cpp`
- Reference (template): the existing `massOut` plumbing added in Phase-2a — `matOut` follows it exactly.

- [ ] **Step 1: Extend the JNI method to allocate + return `matOut`**

Mirror the `massOut` handling for a new `uint16_t`/`char[]` (or `short[]`) output: declare a new
`jcharArray`/`jshortArray` out-param (matching the Java declaration in Task 8), `GetPrimitiveArrayCritical`
to pin it, pass `matOut.data()` to `step_section_with_halo`, then `ReleasePrimitiveArrayCritical` in
**reverse order** of acquisition (last-pinned released first), exactly as the Phase-2a release order does
for `massOut`. Read the current `massOut` block and replicate it for `matOut`.

- [ ] **Step 2: Build the native lib to verify it compiles**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
Expected: builds with no error; `build/liborge.so` produced.

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_jni.cpp
git commit -m "feat(engine): JNI returns matOut alongside massOut

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8 [MAIN]: `NativeEngine.orgeStep` ABI + `StepResult.material`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StepResult.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java` (native decl + caller)
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/StubEngine.java` (identity `material`)
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/StepResultTest.java` (create/extend)

- [ ] **Step 1: Write the failing test** — `StepResult` carries a `material()` array.

```java
@Test
void stepResultCarriesMaterial() {
    char[] mat = new char[]{1, 0, 1};
    StepResult r = new StepResult(new float[]{300f,0f,300f}, new float[]{1000f,0f,1000f}, mat);
    assertArrayEquals(mat, r.material());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.StepResultTest" --rerun-tasks`
Expected: compile FAIL — `StepResult` has no `material` component / 3-arg ctor.

- [ ] **Step 3: Add `material` to `StepResult`**

```java
public record StepResult(float[] temperature, float[] mass, char[] material) {
    // back-compat ctor: identity material is unknown -> null (callers that don't wet pass null)
    public StepResult(float[] temperature, float[] mass) { this(temperature, mass, null); }
}
```

- [ ] **Step 4: Update `NativeEngine.orgeStep` declaration + caller, and `StubEngine`**

Add the `matOut` output array to the hand-declared native method signature (a `char[]`/`short[]` matching
the JNI), allocate it per section, and build `StepResult(tempOut, massOut, matOut)`. In `StubEngine`,
return `new StepResult(t.temperature().clone(), t.mass().clone(), toCharMatIx(t.matIx()))` (identity: the
stub moves nothing, so species == input `matIx`).

- [ ] **Step 5: Run — the new test, then both loaders compile, then the full core suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.StepResultTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — existing callers use the 2-arg ctor (`material()==null`), no behavior change.

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/ core/src/test/java/net/rainbowcreation/orge/engine/
git commit -m "feat(engine-abi): StepResult.material + NativeEngine.orgeStep matOut (lockstep w/ liborge)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 9 [MAIN]: scheduler threads `material` through (no behavior change)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
  (`writeBackResults` already builds `new StepResult(cleanT, cleanM)` — keep `material` available for Plan 2)
- Test: existing `SchedulerMassTest` must stay green.

- [ ] **Step 1: Confirm the seam** — `Scheduler.writeBackResults` reconstructs `StepResult(cleanT, cleanM)`
  before `writeBack`/`reconcile`. Change it to preserve the engine's `material`:
  `world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()));` so Plan 2's reconciler can read
  it. `world.writeBack` ignores `material` today (writes T+mass only), so no behavior change.

- [ ] **Step 2: Run** — `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` → all green.

- [ ] **Step 3: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java
git commit -m "refactor(scheduler): preserve engine material[] on writeBack (Plan-2 seam)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 10 [ENGINE]: extend the bit-identical parity test to `matOut`

**Files:**
- Modify: the existing kernel-vs-`sim_engine` parity test under `tests/`
- (No source change — this is the correctness gate for Tasks 2-5.)

- [ ] **Step 1: Extend parity** — run a non-uniform fluid+air+steam field through BOTH
  `step_section_with_halo` (capturing `matOut`/`massOut`/`Tout`) and `sim_engine::advect_chunk` (capturing
  `C.matIx`/`mass_kg`/`T_curr`), and assert all three arrays match cell-for-cell after one step AND after
  N steps. This proves wetting + swap are bit-identical across the two surfaces.

- [ ] **Step 2: Run** — `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh` → all green.

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add tests/
git commit -m "test(engine): parity covers matOut + wetting + swap (kernel == sim_engine)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11 [ENGINE→MAIN]: rebuild `liborge.so`, integrate, bump the MAIN gitlink

**Files:**
- Build: `ORGE-ENGINE/build/liborge.so`
- Replace: `core/src/main/resources/natives/linux-x64/liborge.so`
- Submodule: merge the ENGINE work branch → ENGINE `main`, push; bump the MAIN gitlink.

- [ ] **Step 1: Rebuild the native lib**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
Expected: fresh `liborge.so`.

- [ ] **Step 2: Copy into MAIN resources + verify identity**

```bash
cp /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
md5sum /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```
Expected: identical md5s.

- [ ] **Step 3: Smoke-test the native path end-to-end**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green, including any test that loads the native engine (the new ABI matches; `matOut` flows
back). If a test asserts the OLD 2-array native return, update it to the 3-array shape.

- [ ] **Step 4: Integrate the ENGINE submodule (mirror Phase-2a)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git checkout main && git merge --ff-only <work-branch> && git push origin main
cd /home/claude/ORGE
git add ORGE-ENGINE core/src/main/resources/natives/linux-x64/liborge.so
git commit -m "build(engine): bundle Phase-2b liborge.so (displacement + wetting + matOut); bump gitlink

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

- [ ] **Step 5: Final gate** — both loaders compile + full suite:

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava :core:test`
Expected: all green. **End state of Plan 1:** new engine bundled + wired; no Java *behavior* change yet
(reconciler still ignores `matOut`, §9 unchanged). Water does not visibly spread until Plan 2.

---

## Self-review notes (coverage vs spec)

- Decision 0 (three masses): Tasks 1-3 (`minFlow`/`maxMass` fields + floor + cap).
- Decision 1/4 (air destination, cross-material): Tasks 4-5 (wetting + swap).
- Decision 2 (gas path general — branch on `gas`/air, never the `air` identity): Task 5 `phaseDiffers`.
- Decision 3 (`matOut`): Tasks 2,4,5,7,8.
- Decision 5 (§7 preemption — no liquid↔liquid swap): Task 5 `phaseDiffers` gate.
- Decision 8 (full-cell swap + hysteresis + one-swap-per-cell): Task 5.
- Decision 10 (ABI lockstep, rebuild .so): Tasks 7,8,11.
- Decision 13 (settle signal for dormancy): Task 6.
- **Deferred to Plan 2:** reconciler `air→fluid`, per-species §9, boil-volume exemption, material JSON
  `min_flow_mass`/`max_mass`/`gas` + air `fullMass=1.2`. **Deferred to Plan 3:** dormancy, wake, buffers.
- **Not yet covered (flag):** the cross-seam §9 transient (spec risk 9) — unchanged from Phase-2a; revisit
  in Plan 3 if it bites.
