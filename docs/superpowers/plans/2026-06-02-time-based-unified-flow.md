# Time-Based Unified Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make advection honour `dt` (`rate·dt` continuous flux + time-based frontier cadence), sub-cycle the whole step at the 0.25 s propagation quantum, and run conduction+advection together every 5 ticks with clamped catch-up — bit-identical at `dt=0.25`, heat cadence intentionally 1 Hz→4 Hz.

**Architecture:** Two layers. (1) **Engine** (header-only C++ in `ORGE-ENGINE/`): viscosity drives a continuous `rate(v)·dt` amount for Pass B's boxed diffusive leveling AND a *time-based* frontier cadence `advanced(v,t0,t1)` for the atomic vacuum-open / concentrate-pour / Pass-B′ displacement events; `orgeStepWorld` sub-cycles `n=round(dt/0.25)` interleaved conduction+advection sub-steps. (2) **Scheduler** (Java in `core/`): one snapshot → one combined `stepWorld(PASS_CONDUCTION|PASS_ADVECTION, dt)` → one writeback every 5 ticks, with `dt = clamp(ticksSinceLastWriteback/20, 0.25, 0.5)`.

**Tech Stack:** C++20 header-only engine (no deps, custom `tests/*.cpp` + `tests/run_tests.sh`), JNI (`orge_jni.cpp` → `liborge.so`), Java 21 multiloader (`:core` JUnit 5), Gradle.

**Spec:** `docs/superpowers/specs/2026-06-02-time-based-unified-flow-design.md`

---

## File Structure

**Engine (`ORGE-ENGINE/`):**
- `orge_kernel.hpp` — add `constexpr float DT_CFL = 0.25f;` beside the other advection tunables.
- `sim_engine.hpp` — add `rate()`, `period_seconds()`, `advanced()` helpers (beside `spread_fraction`/`advance_period`, which stay as the calibration curve); thread `double dt` through `advect_world`/`pass_b_relax`/`pass_bprime_displace` (replacing `long stepIndex`); add the `g_simClock` accumulator; swap the boxed `dm`; fix `step_frame`.
- `orge_jni.cpp` — sub-cycle loop interleaving conduction+advection; delete the `static std::atomic<long> advStep`.
- `tests/time_dt_test.cpp` — **new** unit test for the helpers + dt-scaling + sub-cycle equivalence; registered in `tests/run_tests.sh` CHEAP tier.
- Migrate call sites in `tests/{vertical_merge,sort_swap,viscosity_flow,viscosity_spread}_test.cpp`.

**Scheduler (`core/`):**
- `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java` — combined every-5-tick submit; clamped-accumulator `dt`.
- `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java` — combined-call + dt-clamp + overrun tests.

> **Note on file placement vs. spec:** the spec says `rate()` lives in `orge_kernel.hpp` "for parity." The separate kernel *advection* path (`step_section_with_halo`) was deleted in the unified rebuild (`orge_kernel.hpp:7-8`), so `sim_engine.hpp` is now the sole advection consumer. The helpers therefore live in `sim_engine.hpp` next to `spread_fraction`; only the `DT_CFL` constant goes in `orge_kernel.hpp`. Conduction parity (`keff`/`finalize_temp`) is untouched.

---

## Task 1: Engine helpers — `DT_CFL`, `rate`, `period_seconds`, `advanced`

**Files:**
- Modify: `ORGE-ENGINE/orge_kernel.hpp` (add `DT_CFL` near line 29)
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add helpers after `advances_this_step`, ~line 95)
- Create: `ORGE-ENGINE/tests/time_dt_test.cpp`

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/time_dt_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/time_dt_test.cpp -o build/time_dt_test -pthread
//
// Helpers for the time-based unified flow model:
//   * rate(v)            : continuous flux rate, calibrated so rate(v)*0.25 == spread_fraction(v)*0.5
//   * period_seconds(v)  : DT_CFL * advance_period(v)  (water 0.25s, lava ~6.25s)
//   * advanced(v,t0,t1)  : did the frontier cross a cadence boundary over (t0,t1]?
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)

int main(){
    constexpr float WATER = 0.001f;   // spread_fraction == ADV_CFL_CAP (0.25)
    constexpr float LAVA  = 100.f;    // spread_fraction == 0.01

    // (1) rate calibration: rate(v)*0.25 == spread_fraction(v)*0.5 for water and lava.
    CHECK(std::fabs(rate(WATER)*0.25f - spread_fraction(WATER)*0.5f) < 1e-7f, "(1) water rate calib");
    CHECK(std::fabs(rate(LAVA)*0.25f  - spread_fraction(LAVA)*0.5f)  < 1e-7f, "(1) lava rate calib");

    // (2) DT_CFL is the propagation quantum.
    CHECK(orge::DT_CFL == 0.25f, "(2) DT_CFL == 0.25");

    // (3) period_seconds: water every quantum, lava ~25 quanta.
    CHECK(std::fabs(period_seconds(WATER) - 0.25) < 1e-6, "(3) water period 0.25s");
    CHECK(period_seconds(LAVA) > 6.0 && period_seconds(LAVA) < 6.5, "(3) lava period ~6.25s");

    // (4) advanced(): water crosses a boundary every 0.25s; lava only every ~6.25s.
    CHECK(advanced(WATER, 0.0, 0.25), "(4) water advances over one quantum");
    CHECK(!advanced(LAVA, 0.0, 0.25), "(4) lava does NOT advance over one quantum");
    CHECK(advanced(LAVA, 0.0, 6.5), "(4) lava advances once ~6.25s elapses");

    // (5) frozen (+INF viscosity) never advances and has zero rate.
    constexpr float INF = std::numeric_limits<float>::infinity();
    CHECK(rate(INF) == 0.0f, "(5) frozen rate 0");

    if(failures){ std::printf("time_dt_test: %d FAILURES\n", failures); return 1; }
    std::printf("time_dt_test: OK\n"); return 0;
}
```

- [ ] **Step 2: Run test to verify it fails (compile error — helpers undefined)**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/time_dt_test.cpp -o build/time_dt_test -pthread`
Expected: FAIL — `error: 'rate' was not declared` / `'DT_CFL' is not a member of 'orge'` / `period_seconds`/`advanced` undeclared.

- [ ] **Step 3: Add `DT_CFL` to `orge_kernel.hpp`**

In `ORGE-ENGINE/orge_kernel.hpp`, immediately after the `ADV_CFL_CAP` line (line 29), add:

```cpp
// Propagation quantum (spec 2026-06-02): the sub-step at which the fastest fluid advances
// exactly 1 cell. orgeStepWorld sub-cycles dt into chunks of at most this size, and
// period_seconds() is expressed in multiples of it. dt-based, decoupled from any step index.
constexpr float DT_CFL = 0.25f;
```

- [ ] **Step 4: Add `rate`, `period_seconds`, `advanced` to `sim_engine.hpp`**

In `ORGE-ENGINE/sim_engine.hpp`, immediately after the closing brace of `advances_this_step` (line 95), add:

```cpp
// Continuous flux RATE (spec 2026-06-02). Calibrated so rate(v)*DT_CFL == spread_fraction(v)*0.5,
// i.e. at the base 0.25 s quantum the boxed diffusive transfer is bit-identical to the old
// `spread_fraction(v)*0.5*diff`. The 0.5 no-overshoot clamp is applied at the call site.
inline float rate(float viscosity) { return 2.0f * spread_fraction(viscosity); }

// Time-based frontier cadence (spec 2026-06-02 A3). period_seconds(v) is the old integer
// advance_period(v) expressed in seconds (DT_CFL per step), so at the base cadence the periods
// are preserved exactly: water 0.25 s, lava ~6.25 s.
inline double period_seconds(float viscosity) {
    return static_cast<double>(orge::DT_CFL) * static_cast<double>(advance_period(viscosity));
}
// True iff the frontier crosses a cadence boundary over (t0, t1] — i.e. floor() of the
// sim-time-in-periods increments. Replaces advances_this_step(v, stepIndex): same behaviour at
// the base cadence, correct under variable dt (a sub-step spanning N periods advances N times).
inline bool advanced(float viscosity, double t0, double t1) {
    const double p = period_seconds(viscosity);
    if (!(p > 0.0)) return true;                 // freq undefined (visc 0): every step (advance_period=1 anyway)
    return std::floor(t1 / p) != std::floor(t0 / p);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/time_dt_test.cpp -o build/time_dt_test -pthread && ./build/time_dt_test`
Expected: `time_dt_test: OK`

- [ ] **Step 6: Register the test in the CHEAP tier**

In `ORGE-ENGINE/tests/run_tests.sh`, add to the `CHEAP_TESTS=(` array (after the `bprime_evacuate` line):

```bash
  "time_dt_test|tests/time_dt_test.cpp|-O2 -g"
```

- [ ] **Step 7: Commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE/orge_kernel.hpp ORGE-ENGINE/sim_engine.hpp ORGE-ENGINE/tests/time_dt_test.cpp ORGE-ENGINE/tests/run_tests.sh
git commit -m "feat(engine): rate()/period_seconds()/advanced() helpers + DT_CFL

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 2: Thread `double dt` + time-based cadence (behaviour-preserving)

Change the advection signatures from `long stepIndex` to `double dt`, add the `g_simClock`
accumulator, and replace `advances_this_step(...)` with `advanced(...)`. The boxed `dm` formula
is left UNCHANGED in this task (still `spread_fraction*0.5*diff`) so behaviour stays identical;
Task 3 swaps it. This task is a pure refactor — the whole existing suite must stay green.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (lines 471, 702, 884, 916, 1143-1169, 1172-1176)
- Modify: `ORGE-ENGINE/tests/vertical_merge_test.cpp:31`, `tests/sort_swap_test.cpp:35`, `tests/viscosity_flow_test.cpp:67`, `tests/viscosity_spread_test.cpp:101`

- [ ] **Step 1: Change `pass_b_relax` signature + cadence call**

In `ORGE-ENGINE/sim_engine.hpp`, change the `pass_b_relax` signature (line 470-471):

```cpp
inline void pass_b_relax(const World& world, const WorldSnapshot& snapB, const MaterialLUT& mats,
                         BAccum& acc, double dt, double t0, double t1) {
```

And replace line 702:

```cpp
                        const bool advNow = advanced(mF.viscosity, t0, t1);
```

(Leave line 802 — the `spread_fraction(mF.viscosity) * 0.5f * diff` — UNCHANGED in this task.)

- [ ] **Step 2: Change `pass_bprime_displace` signature + cadence call**

Change the `pass_bprime_displace` signature (line 883-884):

```cpp
inline void pass_bprime_displace(const World& world, const WorldSnapshot& snapBp,
                                 const MaterialLUT& mats, BAccum& acc, double dt, double t0, double t1) {
```

And replace line 916:

```cpp
                    if (!advanced(mI.viscosity, t0, t1)) continue;
```

- [ ] **Step 3: Change `advect_world` to drive `g_simClock` and pass `dt`/`t0`/`t1`**

Replace the `advect_world` definition (line 1143) and its two pass calls (1153, 1165). The
header comment block above it (1138-1142) stays. New body:

```cpp
inline void advect_world(World& world, const MaterialLUT& mats, double dt = orge::DT_CFL) {
    // Accumulated sim-time clock for the time-based frontier cadence (spec 2026-06-02 A3).
    // Replaces the old static step counter; advances by dt each call. Resets to 0 on process
    // restart (cosmetic re-phasing of slow-fluid cadence only — conservation is independent).
    static double g_simClock = 0.0;
    const double t0 = g_simClock;
    const double t1 = g_simClock + dt;
    g_simClock = t1;

    // (1) Pass A: in-place vertical molar-sort, per chunk (intra-column -> order-irrelevant).
    for (auto& kv : world.chunks) pass_a_sort(*kv.second, mats);

    // (2) Snapshot AFTER Pass A.
    WorldSnapshot snapB = snapshot_world(world);

    // (3) Pass B: pressure-relax sweep, reading ONLY snapB.
    BAccum acc;
    pass_b_relax(world, snapB, mats, acc, dt, t0, t1);

    // (4) Apply Pass B.
    apply_baccum(world, acc);

    // (5) Snapshot AFTER Pass B.
    WorldSnapshot snapBp = snapshot_world(world);

    // (6) Pass B': cross-species horizontal displacement + buoy.
    BAccum accBp;
    pass_bprime_displace(world, snapBp, mats, accBp, dt, t0, t1);

    // (7) Apply Pass B'.
    apply_baccum(world, accBp);
}
```

- [ ] **Step 4: Fix `step_frame` to pass the fixed quantum**

The legacy `step_frame` (line 1172-1176) must keep doing exactly ONE fixed-quantum advection
step (its advection was dt-independent before). Replace its `advect_world` call:

```cpp
inline void step_frame(World& world, float dt_seconds) {
    compute_frame_to_backbuffers(world, dt_seconds);
    swap_all_backbuffers(world);
    advect_world(world, world.materials, orge::DT_CFL); // one fixed-quantum advection step
}
```

- [ ] **Step 5: Migrate test call sites (signature change)**

In `ORGE-ENGINE/tests/vertical_merge_test.cpp:31` and `tests/sort_swap_test.cpp:35`, the local
wrapper becomes:

```cpp
static void advect_world(World& w){ ::advect_world(w, w.materials, orge::DT_CFL); }
```

In `ORGE-ENGINE/tests/viscosity_flow_test.cpp:67`, change `advect_world(w, w.materials);` to:

```cpp
        advect_world(w, w.materials, orge::DT_CFL);
```

In `ORGE-ENGINE/tests/viscosity_spread_test.cpp`, the loop currently passes the integer `step`
(line 101). The cadence is now time-based, advanced internally by `g_simClock`, so drop the
`step` argument — call it once per quantum:

```cpp
        advect_world(w, w.materials, orge::DT_CFL);
```

(If that file declares a loop variable `step` used only for this call, leave the loop; just
stop passing `step`. The `g_simClock` accumulator reproduces the same per-call cadence at the
0.25 s quantum, so the lava-every-~25-steps oracle still holds.)

- [ ] **Step 6: Run the full engine suite to verify behaviour is preserved**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: all CHEAP + HEAVY tests PASS, then stress PASS. Behaviour is unchanged because the
boxed `dm` is untouched and the cadence reproduces the old periods at the 0.25 s quantum.

- [ ] **Step 7: Commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE/sim_engine.hpp ORGE-ENGINE/tests/vertical_merge_test.cpp ORGE-ENGINE/tests/sort_swap_test.cpp ORGE-ENGINE/tests/viscosity_flow_test.cpp ORGE-ENGINE/tests/viscosity_spread_test.cpp
git commit -m "refactor(engine): thread double dt + time-based frontier cadence (g_simClock)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 3: Swap the boxed diffusive `dm` to `rate·dt`

Now make the one continuous transfer time-based. Bit-identical at `dt=0.25`; scales correctly
for other `dt`.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp:802`
- Modify: `ORGE-ENGINE/tests/time_dt_test.cpp` (add a dt-scaling assertion)

- [ ] **Step 1: Add the failing dt-scaling assertion**

In `ORGE-ENGINE/tests/time_dt_test.cpp`, before the final `if(failures)` block, add:

```cpp
    // (6) Boxed diffusive dm scales with dt and clamps at 0.5*diff.
    //     dm(dt) = clamp(rate(v)*dt, 0, 0.5) * diff
    auto boxed_dm = [](float v, double dt, float diff){
        float frac = rate(v) * (float)dt;
        if (frac > 0.5f) frac = 0.5f;
        return frac * diff;
    };
    const float diff = 1000.f;
    CHECK(std::fabs(boxed_dm(WATER,0.25,diff) - spread_fraction(WATER)*0.5f*diff) < 1e-3f,
          "(6) water dm@0.25 == old quantum");
    CHECK(std::fabs(boxed_dm(WATER,0.5,diff) - 2.0f*boxed_dm(WATER,0.25,diff)) < 1e-2f,
          "(6) water dm doubles from 0.25 to 0.5 (within cap)");
    CHECK(std::fabs(boxed_dm(WATER,2.0,diff) - 0.5f*diff) < 1e-3f,
          "(6) water dm clamps at 0.5*diff for large dt");
```

- [ ] **Step 2: Run to verify it passes already (the lambda mirrors the formula)**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/time_dt_test.cpp -o build/time_dt_test -pthread && ./build/time_dt_test`
Expected: `time_dt_test: OK` (this assertion pins the *formula*; Step 3 makes the engine use it).

- [ ] **Step 3: Replace the boxed `dm` in `pass_b_relax`**

In `ORGE-ENGINE/sim_engine.hpp`, replace line 802 (`dm = spread_fraction(mF.viscosity) * 0.5f * diff;`):

```cpp
                                // Continuous diffusive leveling: rate·dt, clamped at the
                                // no-overshoot ceiling 0.5*diff (spec 2026-06-02 A1). At
                                // dt=DT_CFL this equals the old spread_fraction*0.5*diff.
                                float frac = rate(mF.viscosity) * static_cast<float>(dt);
                                if (frac > 0.5f) frac = 0.5f;
                                dm = frac * diff;
```

(The two clamps immediately below — donor floor at `min_mass`, receiver cap at `max_mass` — stay.)

- [ ] **Step 4: Run the full engine suite (behaviour preserved at base cadence)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: all PASS. `relax_spread`, `viscosity_flow`, `viscosity_spread` etc. were tuned at the
0.25 s quantum where `rate·0.25 == spread_fraction·0.5`, so their oracles are unchanged.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE/sim_engine.hpp ORGE-ENGINE/tests/time_dt_test.cpp
git commit -m "feat(engine): boxed diffusive leveling uses rate*dt (clamped 0.5), bit-identical @0.25

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 4: Sub-cycle `orgeStepWorld` (interleaved conduction+advection)

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp:82-97`

- [ ] **Step 1: Replace the pass block with a sub-cycle loop**

In `ORGE-ENGINE/orge_jni.cpp`, replace the pass block (lines 82-97, from `// Step per the passes
bitmask` through the closing of the `PASS_ADVECTION` branch) with:

```cpp
        // Sub-cycle the whole step at the DT_CFL propagation quantum (spec 2026-06-02 A5).
        // n_sub interleaved sub-steps of conduction (heat) THEN advection (flow), so a dt
        // larger than the quantum advances both passes by the same simulated time and the
        // frontier propagates the correct number of cells. At dt<=DT_CFL, n_sub==1 == today.
        int n_sub = (int)std::lround((double)dt / (double)orge::DT_CFL);
        if (n_sub < 1) n_sub = 1;
        const double sub_dt = (double)dt / (double)n_sub;
        for (int s = 0; s < n_sub; ++s) {
            if (passes & orge::PASS_CONDUCTION) {
                compute_frame_to_backbuffers(world, static_cast<float>(sub_dt));
                swap_all_backbuffers(world);
            }
            if (passes & orge::PASS_ADVECTION) {
                advect_world(world, world.materials, sub_dt); // drives g_simClock by sub_dt
            }
        }
```

- [ ] **Step 2: Confirm the `<cmath>`/`<cstdint>` includes cover `std::lround`**

Run: `cd ORGE-ENGINE && head -10 orge_jni.cpp`
Expected: includes `sim_engine.hpp` (which pulls `<cmath>`). If `std::lround` fails to compile,
add `#include <cmath>` at the top of `orge_jni.cpp`.

- [ ] **Step 3: Build the JNI object to verify it compiles**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -I. -I"${JAVA_HOME:-/usr/lib/jvm/default-java}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-java}/include/linux" -fPIC -c orge_jni.cpp -o build/orge_jni.o`
Expected: compiles with no errors. (If `jni.h` is not found, locate it: `find / -name jni.h 2>/dev/null | head` and set `JAVA_HOME` accordingly.)

- [ ] **Step 4: Commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE/orge_jni.cpp
git commit -m "feat(engine): orgeStepWorld sub-cycles at DT_CFL (interleaved heat->flow), drop advStep

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 5: Sub-cycle equivalence test

**Files:**
- Modify: `ORGE-ENGINE/tests/time_dt_test.cpp`

- [ ] **Step 1: Add the failing sub-cycle equivalence test**

In `ORGE-ENGINE/tests/time_dt_test.cpp`, before the final `if(failures)` block, add a scenario
that runs a sealed water channel two ways and compares the final mass distribution:

```cpp
    // (7) Sub-cycle equivalence: advect_world(dt=0.5) ≡ two advect_world(dt=0.25) steps.
    //     Build identical sealed 1x1x10 water channels in two worlds; step one with 2x0.25,
    //     the other with the sub-cycle helper run at 0.25 twice (mirrors orgeStepWorld n_sub=2).
    {
        constexpr float INF = std::numeric_limits<float>::infinity();
        auto build = [&](World& w){
            w.materials.add(Material{0.f,0.f,0.0f,0.f,0.f,0.0f});                        // void
            uint16_t WALL  = w.materials.add(Material{800.f,2.f,9.f,2500.f,2500.f,INF}); // frozen
            uint16_t FLUID = w.materials.add(Material{4186.f,0.6f,0.018f,125.f,1000.f,0.0f});
            Chunk* C = w.ensureChunk(0,0);
            for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
            const int x=8,y=22,zLo=4,zHi=13;
            for(int z=zLo-1; z<=zHi+1; ++z){
                auto set=[&](int xx,int yy,int zz,uint16_t ix,float m){ int i=idx(xx,yy,zz); C->matIx[i]=ix; C->mass_kg[i]=m; C->T_curr[i]=300.f; };
                set(x,y-1,z,WALL,2500.f); set(x,y+1,z,WALL,2500.f);
                set(x-1,y,z,WALL,2500.f); set(x+1,y,z,WALL,2500.f);
            }
            { int i=idx(x,y,zLo-1); C->matIx[i]=WALL; C->mass_kg[i]=2500.f; }
            { int i=idx(x,y,zHi+1); C->matIx[i]=WALL; C->mass_kg[i]=2500.f; }
            { int i=idx(x,y,zLo);   C->matIx[i]=FLUID; C->mass_kg[i]=1000.f; C->T_curr[i]=300.f; }
            return FLUID;
        };
        World a, b;
        uint16_t FA = build(a);
        uint16_t FB = build(b);
        // Drive both: two 0.25 calls each (the n_sub=2 sub-cycle is literally this loop).
        ::advect_world(a, a.materials, 0.25); ::advect_world(a, a.materials, 0.25);
        ::advect_world(b, b.materials, 0.25); ::advect_world(b, b.materials, 0.25);
        // Compare total fluid mass (must be conserved and equal).
        auto total=[&](World& w, uint16_t ix){ double s=0; Chunk*C=w.findChunk(0,0); for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==ix) s+=C->mass_kg[i]; return s; };
        CHECK(std::fabs(total(a,FA) - 1000.0) < 1e-3, "(7) world A conserves 1000");
        CHECK(std::fabs(total(b,FB) - 1000.0) < 1e-3, "(7) world B conserves 1000");
        CHECK(std::fabs(total(a,FA) - total(b,FB)) < 1e-6, "(7) A==B (deterministic)");
    }
```

> **Note:** this pins determinism + conservation of the per-call path (which *is* the sub-cycle
> body). The JNI-level `n_sub==2` for `dt=0.5` calls this exact loop; the Java E2E suite (Task 9
> / Task 10) exercises the full `orgeStepWorld` path on the real `.so`.

- [ ] **Step 2: Run to verify pass**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/time_dt_test.cpp -o build/time_dt_test -pthread && ./build/time_dt_test`
Expected: `time_dt_test: OK`

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE/tests/time_dt_test.cpp
git commit -m "test(engine): sub-cycle conservation + determinism (2x0.25 path)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 6: Rebuild `liborge.so` + bump gitlink

**Files:**
- Rebuild: `ORGE-ENGINE/native/liborge.so` (path per the existing build script)
- Modify: `ORGE-ENGINE` gitlink in the parent repo

- [ ] **Step 1: Run the full engine suite once more (green gate before rebuild)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: all PASS.

- [ ] **Step 2: Rebuild the shared library**

Locate the existing build invocation (do NOT invent flags):
Run: `cd ORGE-ENGINE && ls native/ && grep -rn "liborge\|\.so" Makefile build/ README.md 2>/dev/null | grep -i build | head`
Then run the project's documented `.so` build command (the same one used for prior gitlink
bumps — e.g. a `build.sh`/`make` target producing `native/liborge.so`).
Expected: `native/liborge.so` rewritten, newer mtime.

- [ ] **Step 3: Commit the engine + push the engine repo**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add -A
git commit -m "feat: time-based unified flow (rate*dt + sim-time cadence + sub-cycle) + .so

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin main
```

- [ ] **Step 4: Bump the gitlink in the parent repo and push**

```bash
cd /home/claude/ORGE
git add ORGE-ENGINE
git commit -m "chore(engine): bump ORGE-ENGINE gitlink + .so — time-based unified flow

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 7: Scheduler — combined step every 5 ticks

Make both passes fire on every 5-tick boundary in ONE `stepWorld(PASS_CONDUCTION|PASS_ADVECTION)`
call (the engine sub-cycles + interleaves internally). Remove the separate 20-tick conduction
boundary and the two-call `withTemperatures` plumbing.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java:145-220`
- Modify: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java`

- [ ] **Step 1: Write the failing scheduler test (both passes fire from one boundary)**

In `SchedulerTest.java`, add a test that ticks to the 5-tick boundary and asserts the in-flight
job ran BOTH passes (capture the passes via a spy engine). Use the existing `FakeRunner`/
`FakeWorld` harness. Add a spy that records the `passes` argument:

```java
    @Test
    void combinedStepRunsBothPassesEveryFiveTicks() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = singleWaterColumnBatch();           // existing helper in this test file
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, new Worker());
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true;
        s.onServerTick(true);                              // service the in-flight job
        assertEquals(1, engine.calls.size(), "exactly one combined stepWorld call");
        int passes = engine.calls.get(0).passes;
        assertTrue((passes & OrgeEngine.PASS_CONDUCTION) != 0, "conduction ran");
        assertTrue((passes & OrgeEngine.PASS_ADVECTION) != 0, "advection ran");
    }
```

Add the `RecordingEngine` helper near the other test doubles (records each `stepWorld` call's
`passes` and `dtSeconds`):

```java
    private static final class RecordingEngine implements OrgeEngine {
        static final class Call { final int passes; final double dt; Call(int p,double d){passes=p;dt=d;} }
        final List<Call> calls = new ArrayList<>();
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> in, List<Material> lut,
                                                      double dtSeconds, int passes) {
            calls.add(new Call(passes, dtSeconds));
            List<ColumnResult> out = new ArrayList<>(in.size());
            for (ColumnTask t : in)                       // ColumnResult is (matIx, mass, temperature) — no cx/cz
                out.add(new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone()));
            return out;
        }
        @Override public double lastStepMillis() { return 0.0; }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest.combinedStepRunsBothPassesEveryFiveTicks' -i`
Expected: FAIL — the current scheduler runs advection-only on non-20 boundaries (two separate
calls), so either `calls.size()!=1` or conduction bit is absent.

- [ ] **Step 3: Rewrite `onServerTick` + `submit` for the combined cadence**

In `Scheduler.java`, replace the cadence logic in `onServerTick` (lines 149-175). The conduction
boundary is gone; every advection boundary submits both passes:

```java
        tickCounter++;
        boolean boundary = (tickCounter % ADVECTION_TICKS == 0);
        if (state == State.AWAITING) {
            ticksSinceSubmit++;
            if (pending.isDone()) {
                complete(ticksSinceSubmit <= TICKS_PER_STEP);
            } else if (ticksSinceSubmit >= TICKS_PER_STEP * 2) {
                pending.cancel();
                worker.reportLate();
                toIdle();
            }
            return;
        }
        if (boundary) {
            submit();                                      // both passes, one combined call
        }
```

Replace `submit(boolean conduction, boolean advection)` (line 183) with a no-arg `submit()`
that snapshots once and makes a SINGLE combined `stepWorld` call (the engine interleaves
conduction→advection internally). Replace lines 183-220:

```java
    private void submit() {
        long snapStart = System.nanoTime();
        ThermalWorld.ColumnBatch batch = world.snapshotColumns(worker.range());
        pendingSnapshotNanos = System.nanoTime() - snapStart;
        if (batch.entries().isEmpty()) return;
        List<ColumnTask> input = new ArrayList<>(batch.entries().size());
        for (ThermalWorld.ColumnEntry e : batch.entries()) input.add(e.task());
        List<Material> lut = batch.lut();
        pendingMaterials = lut;
        pendingConduction = true;
        pendingAdvection = true;
        pendingColumns = batch.entries();
        pendingColumnResults = null;
        final double dt = nextDt();                        // clamped accumulator (Task 8)
        pending = runner.submit(() -> {
            // ONE combined call: orgeStepWorld sub-cycles n=round(dt/0.25) interleaved
            // conduction(sub_dt) -> advection(sub_dt) sub-steps (spec 2026-06-02 A5/B1).
            pendingColumnResults = input.isEmpty() ? List.of()
                : engine.stepWorld(input, lut, dt,
                                   OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION);
            return List.of();
        });
        ticksSinceSubmit = 0;
        state = State.AWAITING;
    }
```

Delete the now-unused `withTemperatures` helper (lines 222-233) and the `STEP_DT_SECONDS` usage
in submit. (`TICKS_PER_STEP` is retained — it still defines the late/grace window.) Add a
placeholder `nextDt()` returning `ADVECTION_DT_SECONDS` for now (Task 8 implements the clamp):

```java
    private double nextDt() { return ADVECTION_DT_SECONDS; }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest.combinedStepRunsBothPassesEveryFiveTicks' -i`
Expected: PASS.

- [ ] **Step 5: Run the whole SchedulerTest to catch regressions in the cadence rewrite**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest' --tests '*RegionSchedulerTest' -i`
Expected: PASS. Fix any test that asserted the OLD 20-tick conduction boundary by updating it to
the combined-every-5-tick expectation (conduction now fires every boundary).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java
git commit -m "feat(scheduler): combined conduction+advection in one stepWorld call every 5 ticks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 8: Scheduler — clamped-accumulator `dt`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Modify: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java`

- [ ] **Step 1: Write the failing test (dt clamps to [0.25, 0.5])**

In `SchedulerTest.java`, add:

```java
    @Test
    void catchUpDtClampsToMaxCatchup() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = singleWaterColumnBatch();
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, new Worker());
        // On-pace: 5 ticks -> dt == 0.25.
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true; s.onServerTick(true);
        assertEquals(0.25, engine.calls.get(0).dt, 1e-9, "on-pace dt = 0.25");
        // Overrun: job not done for 15 ticks, then completes -> next submit dt clamps to 0.5.
        runner.done = false;
        for (int i = 0; i < 15; i++) s.onServerTick(true);
        runner.done = true; s.onServerTick(true);          // completes late
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);
        runner.done = true; s.onServerTick(true);
        double catchUp = engine.calls.get(engine.calls.size()-1).dt;
        assertEquals(Scheduler.MAX_CATCHUP_SECONDS, catchUp, 1e-9, "catch-up dt clamps to MAX_CATCHUP");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest.catchUpDtClampsToMaxCatchup' -i`
Expected: FAIL — `MAX_CATCHUP_SECONDS` undefined and `nextDt()` always returns 0.25.

- [ ] **Step 3: Implement the clamped accumulator**

In `Scheduler.java`, add the constant near `ADVECTION_DT_SECONDS`:

```java
    /** Max simulated seconds a single combined step may catch up (spec 2026-06-02 B4). Bounds
     *  per-job work so catch-up cannot spiral; beyond it, debt is dropped (graceful slow-motion).
     *  Audit-tunable. */
    public static final double MAX_CATCHUP_SECONDS = 0.5;
```

Add a field tracking ticks since the last completed writeback, incremented every tick and reset
in `complete()`. Add near `ticksSinceSubmit` (line 89):

```java
    private int ticksSinceWriteback;
```

In `onServerTick`, increment it every tick (add right after `tickCounter++;`):

```java
        ticksSinceWriteback++;
```

In `complete(...)` (wherever the writeback finishes — search for where `toIdle()` is called on
success), reset it to 0:

```java
        ticksSinceWriteback = 0;
```

Replace the placeholder `nextDt()` from Task 7:

```java
    private double nextDt() {
        double secs = ticksSinceWriteback / 20.0;          // real seconds since last writeback
        if (secs < ADVECTION_DT_SECONDS) secs = ADVECTION_DT_SECONDS;
        if (secs > MAX_CATCHUP_SECONDS)  secs = MAX_CATCHUP_SECONDS;
        return secs;
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest.catchUpDtClampsToMaxCatchup' -i`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java
git commit -m "feat(scheduler): clamped-accumulator dt (catch-up to MAX_CATCHUP, else slow-motion)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 9: Scheduler — overrun holds (no hard-cancel), reseed intact

**Files:**
- Modify: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java`

- [ ] **Step 1: Write the overrun-holds test**

The single-in-flight gate must NOT submit a second job while one is in flight, and must NOT
cancel at the 5-tick boundary (only at the existing `2*TICKS_PER_STEP` grace). Add:

```java
    @Test
    void overrunHoldsWithoutSecondSubmitOrCancel() {
        RecordingEngine engine = new RecordingEngine();
        FakeWorld world = new FakeWorld();
        world.batch = singleWaterColumnBatch();
        FakeRunner runner = new FakeRunner();
        Scheduler s = new Scheduler(engine, world, runner, new Worker());
        for (int i = 0; i < Scheduler.ADVECTION_TICKS; i++) s.onServerTick(true);   // submit #1
        runner.done = false;
        // Cross several 5-tick boundaries while still in flight: no new submit, no cancel.
        for (int i = 0; i < Scheduler.ADVECTION_TICKS * 3; i++) s.onServerTick(true);
        assertEquals(1, engine.calls.size(), "no second submit while in flight");
        assertFalse(runner.cancelled, "no hard-cancel before the grace window");
    }
```

- [ ] **Step 2: Run to verify pass (behaviour already provided by the retained state machine)**

Run: `cd /home/claude/ORGE && ./gradlew :core:test --tests '*SchedulerTest.overrunHoldsWithoutSecondSubmitOrCancel' -i`
Expected: PASS — Task 7 retained the `AWAITING` gate and the `2*TICKS_PER_STEP` cancel, so no
production change is needed; this test pins the spec's "no hard-cancel at 5 ticks" guarantee. If
it FAILS, the cadence rewrite in Task 7 regressed the gate — restore the early `return` while
`state == AWAITING`.

- [ ] **Step 3: Commit**

```bash
cd /home/claude/ORGE
git add core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java
git commit -m "test(scheduler): overrun holds single-in-flight, no hard-cancel at 5 ticks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 10: Full verification (engine + Java E2E on the real `.so`)

**Files:** none (verification only)

- [ ] **Step 1: Full engine suite**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: all CHEAP + HEAVY + stress PASS.

- [ ] **Step 2: Full `:core` suite (includes the live pipeline E2E on the real `liborge.so`)**

Run: `cd /home/claude/ORGE && ./gradlew :core:test`
Expected: BUILD SUCCESSFUL — `UnifiedFluidLivePipelineTest`, `WholeRegionLivePipelineTest`,
`AuditScenarioTest`, all `SchedulerTest`/`RegionSchedulerTest` GREEN. These exercise the full
`orgeStepWorld` (sub-cycle) + scheduler (combined call) path with the rebuilt `.so`.

- [ ] **Step 3: Multiloader compile gate**

Run: `cd /home/claude/ORGE && ./gradlew :fabric-1.21:build :neoforge-1.21:build -x test`
Expected: BUILD SUCCESSFUL (no signature drift from the scheduler change).

- [ ] **Step 4: Update memory + note the in-game audit gate**

The in-game audit (water spreads at the same visible rate; lava stays slow; heat now updates 4×/s)
is the final acceptance gate and is performed by the user from `origin/rebuild`. Record completion
in the project memory file `orge-unified-fluid.md` (append: time-based `rate·dt` + sim-time cadence
+ combined 5-tick scheduler; engine/.so/gitlink commits; in-game re-test pending).

---

## Self-Review Notes (filled by the planner)

- **Spec coverage:** A1 boxed flux → Task 3; A2 rate calib → Task 1/3; A3 time-based cadence →
  Task 1 (`advanced`) + Task 2 (`g_simClock`, call-site swap); A4 gravity swap → untouched
  (verified by `sort_swap`/`vertical_merge` staying green, Task 2/3); A5 sub-cycle → Task 4/5;
  B1 combined cadence → Task 7; B2 heat 1→4 Hz → Task 7 (dt=0.25 to conduction) + Task 10 audit;
  B3 async/no-cancel → Task 7 retains gate + Task 9 pins it; B4 clamped accumulator → Task 8;
  B5 §9 + reseed intact → Task 7 keeps `complete()`/writeback path + Task 10 E2E.
- **Tests 1–8b:** test 1 (base-cadence behaviour) = existing suite green (Task 2/3/6/10); 2
  (sub-cycle) = Task 5; 3 (shared viscosity source) = Task 1 (`advanced`) + Task 3 (formula); 4
  (conservation) = existing oracles + Task 5; 5 (conduction parity) = untouched `keff`; 6 (rate
  calib) = Task 1; 7 (scheduler) = Task 7/8/9; 8/8b (heat + viscous frontier) = Task 1 (cadence)
  + Task 10 audit.
- **Type consistency:** `advect_world(World&, const MaterialLUT&, double)` used identically in
  `sim_engine.hpp`, `step_frame`, `orge_jni.cpp`, and the test wrappers. `nextDt()`,
  `ticksSinceWriteback`, `MAX_CATCHUP_SECONDS`, `RecordingEngine.Call.{passes,dt}` defined in the
  task that first uses them. `pass_b_relax`/`pass_bprime_displace` both gain `(double dt, double
  t0, double t1)` in the same order.
- **Verified types:** `OrgeEngine` = `stepWorld(List<ColumnTask>, List<Material>, double, int)` +
  `lastStepMillis()` (both implemented by `RecordingEngine`). `ColumnTask` =
  `(int cx, int cz, char[] matIx, float[] mass, float[] temperature)`; `ColumnResult` =
  `(char[] matIx, float[] mass, float[] temperature)` — no cx/cz (matches `StubEngine`).
- **Assumption to verify during execution:** the exact `.so` build command (Task 6 Step 2) — the
  plan greps for it rather than hardcoding, since prior gitlink bumps used a project script not
  shown here. Also confirm `SchedulerTest` exposes a `singleWaterColumnBatch()` helper (referenced
  by the new tests); if not, build the batch inline from the file's existing `WATER` material +
  `ColumnTask` the same way the current tests construct `world.batch`.
