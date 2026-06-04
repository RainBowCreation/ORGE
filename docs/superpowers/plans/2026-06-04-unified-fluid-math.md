# Unified Fluid Head-Pressure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the fluid engine a hydrostatic head-pressure field so a connected liquid body finds its own level (U-tubes, manometers, complex cavities) without ever spontaneously flooding upward into air.

**Architecture:** Spec `docs/superpowers/specs/2026-06-04-unified-fluid-math-design.md` (APPROVED). One new quantity — per-cell **overburden** `O` = mass of the fluid column directly above a cell — added into the drive pressure `Π = (m − min) + O`. Packaging **A+**: edit the three existing advection passes in `ORGE-ENGINE/sim_engine.hpp` (no JNI/ABI change for the hydrostatic work). `pass_b_relax` becomes head-aware; a flood-guard gate (`Π_below − Π_above > mass_below`) admits *conditional* upward flow; `pass_bprime_displace` becomes head-driven (donor = higher `Π`). Separately, law C deletes the `dt` sub-cycle in `orge_jni.cpp` (one sweep per call).

**Tech stack:** Header-only C++20 engine (`ORGE-ENGINE/`, a git submodule with remote `origin/main`). Tests are standalone `.cpp` files compiled with `g++ -std=c++20 -O2 -g -I. tests/<name>.cpp -o build/<name> -pthread`, run via `./tests/run_tests.sh [fast|full]`. The Java/loaders side (MAIN repo, branch `rebuild`) consumes a rebuilt `liborge.so`.

---

## Hard constraints (read before starting)

- **Engine code lives in the `ORGE-ENGINE/` submodule** (remote `origin/main`). Commit/push engine work there; the MAIN repo (`rebuild`) only carries the gitlink + bundled `.so`.
- **The submodule remote URL embeds a token — NEVER echo it.** Push with output redirected (`2>/dev/null`) and verify success with `git rev-parse HEAD == origin/main`.
- **Push after every commit.** Engine commits → `origin/main`; the final MAIN commit → `origin/rebuild`. The user tests from `origin/rebuild`.
- **`orge_kernel.hpp` is conduction-only** — advection work does NOT touch it, so there is no kernel-mirror to keep bit-identical.
- **Do NOT run the hours-long stress test.** Use `./tests/run_tests.sh fast` in the loop and `full` before the final gate (it runs stress only if the suite is green).
- **`JAVA_HOME=/home/claude/jdk21` for ALL gradle** (Task 7 only).
- Engine determinism is mandatory: no rng, no clock; reuse the existing snapshot-read + single-donor CLAIM + per-donor outflow budget. Conservation tests must be **2-D (≥4 horizontal faces)**, never 1-wide channels.

---

## File structure

| File | Change |
|---|---|
| `ORGE-ENGINE/sim_engine.hpp` | NEW `OverburdenMap` type + `compute_overburden()` + `overburden_at()`; `pass_b_relax` signature + pressure site + `+Y` flood guard; `pass_bprime_displace` signature + head-driven donor gate; `advect_world` tallies `O` off each snapshot and threads it. |
| `ORGE-ENGINE/orge_jni.cpp` | Delete the `n_sub` sub-cycle (law C — one sweep per call). |
| `ORGE-ENGINE/tests/overburden_test.cpp` | NEW unit test for `compute_overburden`. |
| `ORGE-ENGINE/tests/hydrostatic_test.cpp` | NEW scenario tests: flat-no-climb, two-column leveling, manometer, multi-arm, conservation. |
| `ORGE-ENGINE/tests/u_tube_test.cpp` | Scenario A flips from "documented limitation, not asserted" → asserted green gate. |
| `ORGE-ENGINE/tests/run_tests.sh` | Add `overburden_test` + `hydrostatic_test` to the CHEAP tier. |
| `ORGE-ENGINE/tests/time_dt_test.cpp` | Reconcile to the one-sweep contract (Task 7). |
| MAIN `core/src/main/resources/natives/linux-x64/liborge.so` + gitlink | Rebuilt `.so` + submodule bump (Task 8). |

---

## Task 0: Baseline — confirm green and record the RED state

**Files:** none (verification only).

- [ ] **Step 1: Build and run the cheap tier**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh fast
```
Expected: all CHEAP tests PASS (including `u_tube_test`).

- [ ] **Step 2: Record the U-tube scenario-A starting state**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/u_tube_test.cpp -o build/u_tube_test -pthread && ./build/u_tube_test | grep -i "A RESULT"
```
Expected: prints `A RESULT (documented limitation): right-arm water ABOVE bottom=0 -> water did NOT self-level`.
This `=0` is the RED we will turn green in Task 4.

- [ ] **Step 3: Confirm clean submodule tree**

Run:
```bash
git -C ORGE-ENGINE status --short
```
Expected: clean (or only `build/` noise, which is gitignored). No commit in this task.

---

## Task 1: `compute_overburden` + unit test

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add type + functions; place them right ABOVE `pass_a_sort`, i.e. before line 377, so all passes can call them)
- Create: `ORGE-ENGINE/tests/overburden_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/overburden_test.cpp`:
```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/overburden_test.cpp -o build/overburden_test -pthread
// Unit test for compute_overburden: O[i] = mass of the fluid column directly above cell i,
// summed through species changes, RESET by a wall (frozen) or a vacuum gap.
// NOTE: use the SAME include/namespace preamble as tests/u_tube_test.cpp. The existing tests
// reference World/Chunk/idx/advect_world BARE — replicate exactly what u_tube_test.cpp does
// (add the same `using namespace ...;` line IF and ONLY IF that file has one).
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int g_fail = 0;
#define CHECK(c,msg) do{ if(!(c)){ std::printf("FAIL: %s\n", msg); ++g_fail; } }while(0)
static bool fclose_(float a,float b,float rel,float abs_){ float d=std::fabs(a-b); return d<=abs_ || d<=rel*std::fabs(b); }

static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float mass,float T){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=mass; C.T_curr[i]=T;
}

int main(){
    World w;
    // Materials: void(0); WATER movable; AIR movable(light); WALL frozen.
    w.materials.add(Material{0,0,0,0,0,0});                       // 0 = void
    uint16_t WATER = w.materials.add(Material{4186,0.6f,0.180f,125,1000,0.001f});
    uint16_t AIR   = w.materials.add(Material{1000,0.02f,0.029f,1,50,0.0f});
    uint16_t WALL  = w.materials.add(Material{800,2.0f,9.0f,2500,2500, std::numeric_limits<float>::infinity()});
    Chunk* C = w.ensureChunk(0,0); C->void_ix = 0;
    const int x=3, z=3;

    // Column (top->bottom): y=10 AIR(20), y=9 WATER(1000), y=8 WATER(1000),
    //   y=7 WALL, y=6 WATER(500), y=5 vacuum, y=4 WATER(800)
    set_cell(*C,x,10,z,AIR,20,300);
    set_cell(*C,x, 9,z,WATER,1000,300);
    set_cell(*C,x, 8,z,WATER,1000,300);
    set_cell(*C,x, 7,z,WALL,2500,300);
    set_cell(*C,x, 6,z,WATER,500,300);
    // y=5 left as default vacuum (mat 0, mass 0)
    set_cell(*C,x, 4,z,WATER,800,300);

    WorldSnapshot snap = snapshot_world(w);
    OverburdenMap O = compute_overburden(w, snap, w.materials);

    auto Oc = [&](int y){ return overburden_at(O, 0,0, idx(x,y,z)); };

    CHECK(fclose_(Oc(10),   0.0f, 1e-4f, 1e-3f), "top AIR cell has no overburden");
    CHECK(fclose_(Oc(9),   20.0f, 1e-4f, 1e-3f), "below AIR: O = air(20)");
    CHECK(fclose_(Oc(8), 1020.0f, 1e-4f, 1e-3f), "two down: O = air(20)+water(1000)");
    CHECK(fclose_(Oc(7), 2020.0f, 1e-4f, 1e-3f), "the WALL cell still gets the load above it");
    CHECK(fclose_(Oc(6),    0.0f, 1e-4f, 1e-3f), "below the WALL: column RESET to 0");
    CHECK(fclose_(Oc(5),  500.0f, 1e-4f, 1e-3f), "the vacuum gap carries the water(500) above it");
    CHECK(fclose_(Oc(4),    0.0f, 1e-4f, 1e-3f), "below the vacuum gap: column RESET to 0");

    if(g_fail){ std::printf("overburden_test: %d FAILED\n", g_fail); return 1; }
    std::printf("overburden_test: OK\n"); return 0;
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/overburden_test.cpp -o build/overburden_test -pthread
```
Expected: **compile error** — `compute_overburden` / `OverburdenMap` / `overburden_at` not declared. (That is the failing state.)

- [ ] **Step 3: Implement `compute_overburden`**

In `ORGE-ENGINE/sim_engine.hpp`, immediately BEFORE `inline void pass_a_sort(` (line 377), insert:
```cpp
// ====== Overburden (hydrostatic head) — spec 2026-06-04 §1 ======
//   O[i] = total mass of the fluid column directly ABOVE cell i, summed THROUGH species
//   changes (water under air under ...). A WALL (frozen, !movable) or a VACUUM gap BREAKS
//   the column: nothing presses through a solid lid or across an empty gap, so the running
//   sum resets to 0 for the cells below it. Computed off a WorldSnapshot so it is consistent
//   with each pass's snapshot-antisymmetry contract. Per-(x,z) column, top-down — mirrors
//   pass_a_sort's loop nest; columns are independent (GPU = per-column prefix scan).
using OverburdenMap = std::unordered_map<ChunkCoord, std::vector<float>, CoordHasher>;

inline OverburdenMap compute_overburden(const World& world, const WorldSnapshot& snap,
                                        const MaterialLUT& mats) {
    OverburdenMap O;
    for (const auto& kv : snap.chunks) {
        const ChunkCoord cc = kv.first;
        const ChunkSnapshot& cs = kv.second;
        const Chunk* C = world.findChunk(cc.cx, cc.cz);
        const uint16_t void_ix = C ? C->void_ix : 0;
        std::vector<float> col(CHUNK_N, 0.0f);
        for (int z = 0; z < CHUNK_D; ++z) {
            for (int x = 0; x < CHUNK_W; ++x) {
                float acc = 0.0f;                          // fluid mass stacked above current cell
                for (int y = CHUNK_H - 1; y >= 0; --y) {
                    const int i = idx(x, y, z);
                    col[i] = acc;                          // overburden ON cell i
                    const uint16_t mix = cs.matIx[i];
                    const float mm = cs.mass[i];
                    const bool vac = is_vacuum(void_ix, mix, mm);
                    if (!vac && movable(mats.byIx(mix))) acc += mm;  // fluid: rests on cell below
                    else acc = 0.0f;                       // wall or vacuum gap: column breaks
                }
            }
        }
        O.emplace(cc, std::move(col));
    }
    return O;
}

inline float overburden_at(const OverburdenMap& O, int cx, int cz, int i) {
    auto it = O.find(ChunkCoord{cx, cz});
    return (it == O.end()) ? 0.0f : it->second[i];
}
```

> Verify `is_vacuum(void_ix, matIx, mass)` and `movable(const Material&)` are declared above line 377 (they are used by `pass_bprime_displace` already). If `is_vacuum`/`movable` live BELOW 377, move the overburden block to just below their definitions instead.

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/overburden_test.cpp -o build/overburden_test -pthread && ./build/overburden_test
```
Expected: `overburden_test: OK`

- [ ] **Step 5: Register the test in the cheap tier**

In `ORGE-ENGINE/tests/run_tests.sh`, add to the `CHEAP_TESTS=(` array (after the `u_tube_test` line):
```bash
  "overburden_test|tests/overburden_test.cpp|-O2 -g"
```

- [ ] **Step 6: Run the cheap tier to confirm no regression**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh fast
```
Expected: all PASS including `overburden_test`.

- [ ] **Step 7: Commit and push the submodule**

Run:
```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/overburden_test.cpp tests/run_tests.sh
git -C ORGE-ENGINE commit -q -m "feat(engine): compute_overburden — per-cell hydrostatic head O (spec 2026-06-04 §1)"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 2: Thread `O` through `advect_world` → `pass_b_relax` (plumbing only, behavior unchanged)

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` — `pass_b_relax` signature (line 490) + `advect_world` (line 1469)

This task adds the parameter and computes `O`, but does NOT yet use it in the pressure. Goal: prove the plumbing is byte-neutral (every existing test stays green) before changing physics.

- [ ] **Step 1: Add the `O` parameter to `pass_b_relax`’s signature**

`ORGE-ENGINE/sim_engine.hpp` line 490–491, change to:
```cpp
inline void pass_b_relax(const World& world, const WorldSnapshot& snapB, const MaterialLUT& mats,
                         BAccum& acc, double dt, double t0, double t1,
                         const OverburdenMap& O) {
```
(The body does not reference `O` yet — that is Task 3. Mark it `(void)O;` at the top of the body to silence the unused-parameter warning if `-Werror` is on; the suite does not use `-Werror`, so this is optional.)

- [ ] **Step 2: Compute `O` and pass it in `advect_world`**

`ORGE-ENGINE/sim_engine.hpp`, in `advect_world` (lines 1482–1496), change to:
```cpp
    // (2) Snapshot AFTER Pass A.
    WorldSnapshot snapB = snapshot_world(world);
    OverburdenMap O_B = compute_overburden(world, snapB, mats);     // head off the post-A state

    // (3) Pass B: pressure-relax sweep, reading ONLY snapB.
    BAccum acc;
    pass_b_relax(world, snapB, mats, acc, dt, t0, t1, O_B);

    // (4) Apply Pass B.
    apply_baccum(world, acc);

    // (5) Snapshot AFTER Pass B.
    WorldSnapshot snapBp = snapshot_world(world);

    // (6) Pass B': cross-species horizontal displacement + buoy.
    BAccum accBp;
    pass_bprime_displace(world, snapBp, mats, accBp, dt, t0, t1);
```
(Pass B' still has its old signature — Task 5 adds its `O`.)

- [ ] **Step 3: Run the full cheap + heavy tier to prove byte-neutrality**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: ALL tests PASS (cheap + heavy + stress). `O` is computed but unused, so behavior is identical to Task 1.

- [ ] **Step 4: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add sim_engine.hpp
git -C ORGE-ENGINE commit -q -m "refactor(engine): thread overburden O into pass_b_relax/advect_world (unused, byte-neutral)"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 3: Make `pass_b_relax`’s pressure head-aware (horizontal communicating vessels)

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` — the pressure site (lines 724–729)
- Create: `ORGE-ENGINE/tests/hydrostatic_test.cpp` (first scenario)
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the failing test (two columns, connected at the floor, level horizontally)**

Create `ORGE-ENGINE/tests/hydrostatic_test.cpp`:
```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread
// Hydrostatic head scenarios. Scenario 1 (this task): two water columns joined ONLY at the
// floor with DIFFERENT heights must transmit head along the floor (Pass B head-aware pressure).
// NOTE: replicate the include/namespace preamble of tests/u_tube_test.cpp (symbols are used
// bare there — add the same `using namespace ...;` line ONLY if that file has one).
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int g_fail = 0;
#define CHECK(c,msg) do{ if(!(c)){ std::printf("FAIL: %s\n", msg); ++g_fail; } }while(0)
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float mass,float T){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=mass; C.T_curr[i]=T;
}
struct Mats{ uint16_t WATER, AIR, WALL; };
static Mats add_mats(World& w){
    w.materials.add(Material{0,0,0,0,0,0});
    Mats m;
    m.WATER = w.materials.add(Material{4186,0.6f,0.180f,125,1000,0.001f});
    m.AIR   = w.materials.add(Material{1000,0.02f,0.029f,1,50,0.0f});
    m.WALL  = w.materials.add(Material{800,2.0f,9.0f,2500,2500, std::numeric_limits<float>::infinity()});
    return m;
}
static double mass_of(Chunk& C, uint16_t ix){ double s=0; for(int i=0;i<CHUNK_N;++i) if(C.matIx[i]==ix) s+=C.mass_kg[i]; return s; }

static void scenario1_floor_transmit(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    const int z=8;
    // Floor channel y=20 across x=4,5,6 (all water, connected). Left "tower" x=4 y=21,22 water.
    // Right side x=6 has only the floor cell. Wall pillars at x=3 and x=7 (containment).
    for(int x=4;x<=6;++x) set_cell(*C,x,20,z,m.WATER,1000,300);
    set_cell(*C,4,21,z,m.WATER,1000,300);
    set_cell(*C,4,22,z,m.WATER,1000,300);
    set_cell(*C,3,20,z,m.WALL,2500,300); set_cell(*C,7,20,z,m.WALL,2500,300);
    set_cell(*C,3,21,z,m.WALL,2500,300); set_cell(*C,7,21,z,m.WALL,2500,300);
    set_cell(*C,3,22,z,m.WALL,2500,300); set_cell(*C,7,22,z,m.WALL,2500,300);
    const double w0 = mass_of(*C, m.WATER);
    for(int s=0;s<400;++s){
        advect_world(w, w.materials);
        CHECK(std::fabs(mass_of(*C,m.WATER)-w0) < 1e-1, "S1 water conserved every step");
    }
    // The left tower (x=4) is taller -> its floor head must push water RIGHT along the floor,
    // so the right floor cell (x=6,y=20) stays full and the left tower DROPS at least one cell.
    const float leftTop = C->matIx[idx(4,22,z)]==m.WATER ? C->mass_kg[idx(4,22,z)] : 0.0f;
    CHECK(leftTop < 1000.0f - 1.0f, "S1 left tower dropped (head transmitted along floor)");
    std::printf("S1 floor-transmit: leftTop(4,22)=%.1f  (expect < 1000)\n", leftTop);
}

int main(){
    scenario1_floor_transmit();
    if(g_fail){ std::printf("hydrostatic_test: %d FAILED\n", g_fail); return 1; }
    std::printf("hydrostatic_test: OK\n"); return 0;
}
```

- [ ] **Step 2: Run it to verify it fails (or is inert) on the head-blind engine**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread && ./build/hydrostatic_test
```
Expected: FAIL on "S1 left tower dropped" (today's `pressure = max(0, mass-min)` is equal across the full floor cells, so no head transmits and the tower never drops).

- [ ] **Step 3: Add `O` into the pressure**

`ORGE-ENGINE/sim_engine.hpp` lines 724–729, replace with:
```cpp
                        // pressure Π = max(0, mass - min_mass) + overburden O (spec 2026-06-04 §1).
                        // O is positional head (column weight above); it is NOT donatable mass,
                        // so the donor-floor clamps below stay keyed on raw mass.
                        const float minF = mF.minMass;
                        const float Oi = ivac ? 0.0f : overburden_at(O, cx, cz, ii);
                        const float Oj = jvac ? 0.0f : overburden_at(O, ncx, ncz, jj);
                        const float pi = ivac ? 0.0f : (std::max(0.0f, massi - minF) + Oi);
                        const float pj = jvac ? 0.0f : (std::max(0.0f, massj - minF) + Oj);
                        const float diff = std::fabs(pi - pj);
                        if (pi == pj) continue;
```

> The donor selection `donorIsI = (pi > pj)` (line 732) now picks the higher-head side automatically. The donor-floor clamps (`donorMass - minF`, lines 818/828) stay as-is — they correctly floor on *mass*, not on Π.

- [ ] **Step 4: Run the new test + full conservation suite**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread && ./build/hydrostatic_test
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: `hydrostatic_test: OK`, and ALL existing tests still PASS (conservation bit-exact). If a heavy conservation test fails, STOP — the head must not break the per-donor budget; debug before proceeding.

- [ ] **Step 5: Register `hydrostatic_test` in the cheap tier**

In `ORGE-ENGINE/tests/run_tests.sh`, add to `CHEAP_TESTS=(`:
```bash
  "hydrostatic_test|tests/hydrostatic_test.cpp|-O2 -g"
```

- [ ] **Step 6: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/hydrostatic_test.cpp tests/run_tests.sh
git -C ORGE-ENGINE commit -q -m "feat(engine): pass_b_relax pressure is head-aware (Π = surplus + overburden) — horizontal communicating vessels"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 4: The `+Y` flood guard — conditional upward flow (the crux)

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` — remove the blanket `+Y` suppression (line 709); add the guarded upward-flow branch after the pressure site
- Modify: `ORGE-ENGINE/tests/hydrostatic_test.cpp` — add flat-no-climb scenario
- Modify: `ORGE-ENGINE/tests/u_tube_test.cpp` — assert scenario A

- [ ] **Step 1: Write the failing tests**

(1a) In `ORGE-ENGINE/tests/hydrostatic_test.cpp`, add this scenario function and call it from `main()` before the final report:
```cpp
static void scenario2_flat_no_climb(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    const int z=8;
    // A flat, FULL water pool (2 cells deep, 5 wide) with VACUUM directly above. No driver.
    // The invariant: it must NEVER climb. Track the max water height reached.
    for(int x=4;x<=8;++x){ set_cell(*C,x,20,z,m.WATER,1000,300); set_cell(*C,x,21,z,m.WATER,1000,300); }
    for(int x=3;x<=9;++x){ set_cell(*C,x,19,z,m.WALL,2500,300); } // floor
    set_cell(*C,3,20,z,m.WALL,2500,300); set_cell(*C,9,20,z,m.WALL,2500,300);
    set_cell(*C,3,21,z,m.WALL,2500,300); set_cell(*C,9,21,z,m.WALL,2500,300);
    const double w0 = mass_of(*C, m.WATER);
    int maxY = 21;
    for(int s=0;s<300;++s){
        advect_world(w, w.materials);
        CHECK(std::fabs(mass_of(*C,m.WATER)-w0) < 1e-1, "S2 water conserved");
        for(int x=4;x<=8;++x) for(int y=22;y<26;++y)
            if(C->matIx[idx(x,y,z)]==m.WATER && C->mass_kg[idx(x,y,z)]>1.0f) maxY = std::max(maxY,y);
    }
    CHECK(maxY == 21, "S2 flat pool NEVER climbed into the vacuum above (flood guard)");
    std::printf("S2 flat-no-climb: maxWaterY=%d  (expect 21)\n", maxY);
}
```
Add `scenario2_flat_no_climb();` in `main()`.

(1b) In `ORGE-ENGINE/tests/u_tube_test.cpp` scenario A (around line 85–87), replace the print-only `Rabove` block with an assertion. Change:
```cpp
        int Rabove = (C->matIx[idx(8,21,z)]==m.WATER)+(C->matIx[idx(8,22,z)]==m.WATER)+(C->matIx[idx(8,23,z)]==m.WATER);
        std::printf("A RESULT (documented limitation): right-arm water ABOVE bottom=%d  -> %s\n\n",
            Rabove, (Rabove>=1)?"water rose in far arm":"water did NOT self-level (expected; pure water has no driver)");
```
to:
```cpp
        int Rabove = (C->matIx[idx(8,21,z)]==m.WATER)+(C->matIx[idx(8,22,z)]==m.WATER)+(C->matIx[idx(8,23,z)]==m.WATER);
        std::printf("A RESULT: right-arm water ABOVE bottom=%d\n\n", Rabove);
        // Spec 2026-06-04: pure water now self-levels via the hydrostatic head + flood guard.
        // The left arm started 3 tall (y20,21,22) + bottom; the far arm MUST rise at least 1 cell.
        CHECK(Rabove >= 1, "A: pure water self-levels — far arm rises (hydrostatic head)");
```
(Confirm `CHECK` is the macro this file already uses for scenario B; if it is named differently, match it.)

- [ ] **Step 2: Run to verify both fail**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/u_tube_test.cpp -o build/u_tube_test -pthread && ./build/u_tube_test | grep -i "A:"
```
Expected: the new `A:` assertion FAILS (`Rabove` still 0 — `+Y` flow is still blanket-suppressed). `scenario2_flat_no_climb` currently passes vacuously (nothing climbs because nothing can) — that is fine; it becomes a real guard once upward flow is enabled.

- [ ] **Step 3: Remove the blanket `+Y` suppression**

`ORGE-ENGINE/sim_engine.hpp` line 709 — DELETE this line:
```cpp
                        if (f == 1 && sameSpecies) continue;
```

- [ ] **Step 4: Add the guarded upward-flow branch**

`ORGE-ENGINE/sim_engine.hpp`, immediately AFTER the pressure site’s `if (pi == pj) continue;` (the line you wrote in Task 3 Step 3), insert:
```cpp
                        // +Y same-species: Pass A owns vertical COMPACTION (gravity fills the
                        // lower cell to max). Pass B may drive UPWARD flow only when the cell
                        // below carries EXCESS head beyond a calm column — the flood guard
                        // (spec 2026-06-04 §2). In any calm same-species column, pi - pj ==
                        // mass_below exactly, so the strict ">" lets a flat pool NEVER climb,
                        // while a U-tube floor over-pressurised by a taller arm DOES rise.
                        if (f == 1 && sameSpecies) {
                            const float excess = (pi - pj) - massi;   // i = below, j = above
                            if (excess <= orge::ADV_EPS_MASS) continue;       // no excess -> no climb
                            float frac = rate(mF.viscosity) * static_cast<float>(dt);
                            if (frac > 0.5f) frac = 0.5f;
                            float dmUp = frac * excess;
                            dmUp = std::min(dmUp, massi - minF);              // donor floor at min_mass
                            dmUp = std::min(dmUp, mF.maxMass - massj);        // receiver cap at max_mass
                            if (dmUp <= orge::ADV_EPS_MASS) continue;
                            float& dbUp = donorBudget(cx, cz, ii, massi, minF);
                            if (dbUp <= orge::ADV_EPS_MASS) continue;
                            dmUp = std::min(dmUp, dbUp);
                            if (dmUp <= orge::ADV_EPS_MASS) continue;
                            dbUp -= dmUp;
                            const float Ti = si->T[ii];
                            acc.ensure(cx, cz); acc.ensure(ncx, ncz);
                            acc.dMass[ChunkCoord{cx,cz}][ii]    -= dmUp;
                            acc.dEnth[ChunkCoord{cx,cz}][ii]    -= dmUp * Ti;
                            acc.dMass[ChunkCoord{ncx,ncz}][jj]  += dmUp;
                            acc.dEnth[ChunkCoord{ncx,ncz}][jj]  += dmUp * Ti;
                            continue;   // +Y handled on the hydrostatic path; skip the generic branch
                        }
```

> This intercepts only `f==1 && sameSpecies` (vertical, same fluid). Horizontal faces (`f==0`,`f==2`), vacuum faces, and vertical cross-species faces all stay on the existing path. `si`, `ncx`, `ncz`, `jj`, `minF`, `mF`, `massi`, `massj`, and the `donorBudget` lambda are all in scope here (confirmed in the code map).

- [ ] **Step 5: Run the new tests + full suite**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: `u_tube_test` scenario A now PASSES (`Rabove >= 1`); `hydrostatic_test` S2 PASSES (`maxWaterY=21` — flat pool never climbed); all conservation + existing tests still PASS. If S2 fails (the pool climbed), the flood guard `> massi` baseline is wrong — debug before proceeding.

- [ ] **Step 6: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/hydrostatic_test.cpp tests/u_tube_test.cpp
git -C ORGE-ENGINE commit -q -m "feat(engine): +Y flood guard — conditional upward hydrostatic flow; u_tube scenario A self-levels"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 5: Head-driven `pass_bprime_displace` (the manometer)

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` — `pass_bprime_displace` signature (914) + thread `O` in `advect_world` (Task 2 step 2 left it without) + the donor gate (line 1035)
- Modify: `ORGE-ENGINE/tests/hydrostatic_test.cpp` — add manometer scenario

> **Highest-risk task.** Replacing the molar "heavier pushes lighter" gate with a head gate changes cross-species displacement broadly. Run the full displace/equilibrium suite and treat any newly-failing assertion as: *is the new head-driven outcome physically correct?* If yes, update the assertion (with a comment); if no, the head gate is wrong.

- [ ] **Step 1: Write the failing test (manometer — light tall column pushes a heavy plug up)**

In `ORGE-ENGINE/tests/hydrostatic_test.cpp`, add OIL to `Mats`/`add_mats` and a scenario:
```cpp
// In Mats: add `uint16_t OIL;`  In add_mats, after WATER:
//   m.OIL = w.materials.add(Material{2000,0.15f,0.090f,50,500,0.001f}); // lighter than water (molar 0.09<0.18)
static void scenario3_manometer(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    const int z=8;
    // U-tube joined at the floor (y=20). Left arm x=4 (y20..25) filled with OIL (light, tall).
    // Right arm x=6 (y20..25): only a short WATER plug at the floor. Wall divider x=5 y21..25.
    for(int y=20;y<=25;++y){ set_cell(*C,4,y,z,m.OIL,500,300); }
    set_cell(*C,5,20,z,m.OIL,500,300);            // floor junction (oil)
    set_cell(*C,6,20,z,m.WATER,1000,300);          // heavy plug at the right floor
    for(int y=21;y<=25;++y) set_cell(*C,5,y,z,m.WALL,2500,300); // divider
    // containment walls
    for(int y=20;y<=26;++y){ set_cell(*C,3,y,z,m.WALL,2500,300); set_cell(*C,7,y,z,m.WALL,2500,300); }
    for(int x=3;x<=7;++x) set_cell(*C,x,19,z,m.WALL,2500,300);
    const double oil0=mass_of(*C,m.OIL), wat0=mass_of(*C,m.WATER);
    for(int s=0;s<800;++s){
        advect_world(w, w.materials);
        CHECK(std::fabs(mass_of(*C,m.OIL)-oil0)<1e-1, "S3 oil conserved");
        CHECK(std::fabs(mass_of(*C,m.WATER)-wat0)<1e-1, "S3 water conserved");
    }
    // The tall LIGHT oil column out-heads the short HEAVY plug -> water is pushed UP the far arm.
    int waterAbove = (C->matIx[idx(6,21,z)]==m.WATER)+(C->matIx[idx(6,22,z)]==m.WATER);
    CHECK(waterAbove >= 1, "S3 manometer: light tall oil pushes heavy water UP the far arm");
    std::printf("S3 manometer: waterAbove=%d  (expect >=1)\n", waterAbove);
}
```
Add `scenario3_manometer();` in `main()`.

- [ ] **Step 2: Run to verify it fails**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread && ./build/hydrostatic_test
```
Expected: FAIL on "S3 manometer" — today’s gate (line 1035) only lets a *heavier* species push a lighter one, so light oil can never displace heavy water.

- [ ] **Step 3: Add `O` to `pass_bprime_displace`’s signature and wiring**

`ORGE-ENGINE/sim_engine.hpp` line 914–915, change to:
```cpp
inline void pass_bprime_displace(const World& world, const WorldSnapshot& snapBp,
                                 const MaterialLUT& mats, BAccum& acc, double dt, double t0, double t1,
                                 const OverburdenMap& O) {
```
In `advect_world` (Task 2’s edited block), add the `O_Bp` tally + pass it:
```cpp
    // (5) Snapshot AFTER Pass B.
    WorldSnapshot snapBp = snapshot_world(world);
    OverburdenMap O_Bp = compute_overburden(world, snapBp, mats);   // head off the post-B state

    // (6) Pass B': cross-species horizontal displacement + buoy.
    BAccum accBp;
    pass_bprime_displace(world, snapBp, mats, accBp, dt, t0, t1, O_Bp);
```

- [ ] **Step 4: Replace the head-blind donor gate**

`ORGE-ENGINE/sim_engine.hpp` line 1035, replace:
```cpp
                        if (!(mJ.molarMass < mI.molarMass)) continue; // strictly lighter
```
with:
```cpp
                        // Head-driven displacement (spec 2026-06-04 §3): i displaces j when i
                        // carries the higher hydrostatic head, REGARDLESS of which is denser —
                        // this is what lets a tall LIGHT column push a HEAVY plug (manometer).
                        // Molar mass still sets the resting order (Pass A); it no longer gates
                        // who pushes.
                        const float Pi_i = std::max(0.0f, massi - mI.minMass) + overburden_at(O, cx, cz, ii);
                        const float Pi_j = std::max(0.0f, massj - mJ.minMass) + overburden_at(O, jcx, jcz, jj);
                        if (Pi_i <= Pi_j) continue;   // i must out-head j to displace it
```
(`jcx`/`jcz` come from the `resolve_neighbor` call at line 1019; `ii` is `i`’s cell index, `jj` is `j`’s.)

- [ ] **Step 5: Run the manometer test + the FULL displacement suite**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread && ./build/hydrostatic_test
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: `hydrostatic_test: OK` (manometer passes). Then scrutinize `displace_test`, `bprime_evacuate_test`, `push_chain_tube_test`, `lava_water_equilibrium_test`, `min_mass_occupancy_test`:
- If all PASS → done.
- If one FAILS, read the failing assertion. The previous behavior (heavy pushes light) is a *subset* of head-driven when the heavy side has the taller/denser column, so most should still pass. For any genuine change, decide if the new head-driven end-state is correct (it should match real physics); if so, update the assertion **with a comment explaining the head-driven rationale**, and note it for the reviewer.

- [ ] **Step 6: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/hydrostatic_test.cpp
git -C ORGE-ENGINE commit -q -m "feat(engine): head-driven pass_bprime_displace (donor = higher Π) — manometer works"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 6: Flood-guard red-team — multi-arm, looped cavity, determinism

**Files:**
- Modify: `ORGE-ENGINE/tests/hydrostatic_test.cpp` — add three scenarios the spec §8 requires (the local flood-guard predicate's convergence risk lives here).

> No production code changes — this task hardens the test net around Tasks 4–5. If any scenario fails, the local predicate (spec §3.3) has a leak; fall back to the exact connected-height guard documented in the spec before continuing.

- [ ] **Step 1: Add the three scenarios**

In `ORGE-ENGINE/tests/hydrostatic_test.cpp`, add and call from `main()`:
```cpp
static void scenario4_multi_arm(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    const int z=8;
    // THREE arms (x=4,6,8) on a shared floor channel (y=20, x=4..8). Only the middle arm is
    // tall (x=6, y21,22,23). All three must equalise to the same height; mass conserved.
    for(int x=4;x<=8;++x) set_cell(*C,x,20,z,m.WATER,1000,300);
    set_cell(*C,6,21,z,m.WATER,1000,300); set_cell(*C,6,22,z,m.WATER,1000,300); set_cell(*C,6,23,z,m.WATER,1000,300);
    set_cell(*C,5,20,z,m.WATER,1000,300); set_cell(*C,7,20,z,m.WATER,1000,300); // floor continuity
    // dividers between arms (x=5 and x=7 above the floor) + containment
    for(int y=21;y<=24;++y){ set_cell(*C,5,y,z,m.WALL,2500,300); set_cell(*C,7,y,z,m.WALL,2500,300); }
    for(int y=20;y<=24;++y){ set_cell(*C,3,y,z,m.WALL,2500,300); set_cell(*C,9,y,z,m.WALL,2500,300); }
    for(int x=3;x<=9;++x) set_cell(*C,x,19,z,m.WALL,2500,300);
    const double w0=mass_of(*C,m.WATER);
    for(int s=0;s<1200;++s){ advect_world(w,w.materials);
        CHECK(std::fabs(mass_of(*C,m.WATER)-w0)<1e-1,"S4 water conserved"); }
    // the two short arms must have risen above their floor (head spread from the tall middle arm)
    int leftRose  = (C->matIx[idx(4,21,z)]==m.WATER);
    int rightRose = (C->matIx[idx(8,21,z)]==m.WATER);
    CHECK(leftRose && rightRose, "S4 multi-arm: both short arms rose toward the tall one");
    std::printf("S4 multi-arm: leftRose=%d rightRose=%d\n", leftRose, rightRose);
}

static void scenario5_looped_cavity(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix=0;
    const int z=8;
    // A closed RING of water cells (a loop): floor y=20 x4..8, two side columns x=4 & x=8 up to
    // y=23, top y=23 x4..8. Hollow centre (walls). Seed the loop full on one side only and
    // confirm it converges (no oscillation / no mass drift) — the looped-topology red-team.
    auto W=[&](int x,int y){ set_cell(*C,x,y,z,m.WATER,1000,300); };
    for(int x=4;x<=8;++x){ W(x,20); }                 // floor
    W(4,21); W(4,22); W(8,21); W(8,22);               // sides
    for(int x=4;x<=8;++x){ set_cell(*C,x,23,z,m.WATER,1.0f,300); } // top ring nearly empty
    // hollow centre + containment as walls
    for(int y=21;y<=22;++y) for(int x=5;x<=7;++x) set_cell(*C,x,y,z,m.WALL,2500,300);
    for(int x=3;x<=9;++x){ set_cell(*C,x,19,z,m.WALL,2500,300); set_cell(*C,x,24,z,m.WALL,2500,300); }
    for(int y=19;y<=24;++y){ set_cell(*C,3,y,z,m.WALL,2500,300); set_cell(*C,9,y,z,m.WALL,2500,300); }
    const double w0=mass_of(*C,m.WATER);
    for(int s=0;s<1500;++s){ advect_world(w,w.materials);
        CHECK(std::fabs(mass_of(*C,m.WATER)-w0)<1e-1,"S5 looped water conserved (no drift/oscillation)"); }
    std::printf("S5 looped-cavity: converged, mass drift < 0.1 over 1500 steps\n");
}

static void scenario6_determinism(){
    // Same seed, two runs, byte-identical end-state (no rng/clock in the head model).
    auto run=[&](){
        World w; Mats m = add_mats(w);
        Chunk* C = w.ensureChunk(0,0); C->void_ix=0; const int z=8;
        for(int x=4;x<=6;++x) set_cell(*C,x,20,z,m.WATER,1000,300);
        set_cell(*C,4,21,z,m.WATER,1000,300); set_cell(*C,4,22,z,m.WATER,1000,300);
        for(int y=20;y<=22;++y){ set_cell(*C,3,y,z,m.WALL,2500,300); set_cell(*C,7,y,z,m.WALL,2500,300); }
        for(int s=0;s<300;++s) advect_world(w,w.materials);
        std::vector<float> out(CHUNK_N); for(int i=0;i<CHUNK_N;++i) out[i]=C->mass_kg[i]; return out;
    };
    auto a=run(); auto b=run();
    bool same=true; for(int i=0;i<CHUNK_N;++i) if(a[i]!=b[i]){ same=false; break; }
    CHECK(same, "S6 determinism: two identical runs are byte-identical");
    std::printf("S6 determinism: %s\n", same?"byte-identical":"DIVERGED");
}
```
Add `scenario4_multi_arm(); scenario5_looped_cavity(); scenario6_determinism();` in `main()`.

- [ ] **Step 2: Run the hydrostatic suite (full)**

Run:
```bash
cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/hydrostatic_test.cpp -o build/hydrostatic_test -pthread && ./build/hydrostatic_test
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: `hydrostatic_test: OK` (S1–S6 all pass), full suite green. If S5 drifts/oscillates or S4 fails to equalise, the local flood-guard predicate leaks on that topology — STOP and escalate to the exact-height fallback (spec §3.3) before the JNI task.

- [ ] **Step 3: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add tests/hydrostatic_test.cpp
git -C ORGE-ENGINE commit -q -m "test(engine): flood-guard red-team — multi-arm, looped cavity, determinism (spec §8)"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 7: Delete the `dt` sub-cycle in the JNI shim (law C — one sweep per call)

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp` — lines 149–164
- Modify (if needed): `ORGE-ENGINE/tests/time_dt_test.cpp` — reconcile any sub-cycle-equivalence assertion to the one-sweep contract

- [ ] **Step 1: Inspect the current dt test contract**

Run:
```bash
cd ORGE-ENGINE && grep -n "n_sub\|sub_dt\|0.5\|0.25\|advect_world\|orgeStepWorld\|dt" tests/time_dt_test.cpp | head -40
```
Read the assertions. Classify each: (a) **dt scales AMOUNT** (e.g. boxed leveling moves ~2× at `dt=0.5` vs `0.25`) — KEEP, still true. (b) **sub-cycle / multi-cell-equivalence** (e.g. `advect(0.5)` front == `advect(0.25)` twice) — these break under law C and must be replaced with the one-sweep expectation (a single sweep advances the front exactly ONE cell regardless of `dt`).

- [ ] **Step 2: If a sub-cycle-equivalence assertion exists, write its one-sweep replacement (failing first)**

Replace any such assertion with this contract (adapt cell coords to the test’s fixture):
```cpp
    // Law C (spec 2026-06-04): ONE sweep per call. dt scales the AMOUNT moved, never the
    // DISTANCE. A single advect_world advances a frontier by AT MOST one cell, for ANY dt.
    {
        World a; /* ...seed a 1-wide water front with empty cells ahead... */
        advect_world(a, a.materials, 0.5);   // big dt, ONE sweep
        // front advanced exactly one cell (the 2nd-ahead cell is still empty):
        CHECK(/* cell at front+2 is still vacuum */, "law C: dt=0.5 advances the front only 1 cell");
    }
```
(If `time_dt_test` has NO sub-cycle-equivalence assertion — i.e. it only tests `advect_world` amount-scaling, which is unchanged — skip Steps 2 and re-run it unchanged in Step 4.)

- [ ] **Step 3: Delete the sub-cycle in `orge_jni.cpp`**

`ORGE-ENGINE/orge_jni.cpp`, replace lines 149–164 with:
```cpp
        // ONE sweep per call (spec 2026-06-04 law C): dt scales the AMOUNT moved, not the
        // DISTANCE propagated. No sub-cycling -> per-call cost is constant regardless of how
        // far behind real-time we are (no death-spiral). Under load -> deterministic slow-motion.
        if (passes & orge::PASS_CONDUCTION) {
            compute_frame_to_backbuffers(world, static_cast<float>(dt));
            swap_all_backbuffers(world);
        }
        if (passes & orge::PASS_ADVECTION) {
            advect_world(world, world.materials, dt);
        }
```
If the now-removed `std::lround` was the only use, no header change is needed (`<cmath>` comes transitively via `sim_engine.hpp`); just ensure no dangling `n_sub`/`sub_dt` references remain.

- [ ] **Step 4: Run the engine suite (the .so/Java gate is Task 8)**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: ALL engine tests PASS, including the reconciled `time_dt_test`. (The JNI shim is not exercised by these C++ tests, but `orge_jni.cpp` must still compile — it is compiled into the `.so` in Task 7.)

- [ ] **Step 5: Commit and push**

Run:
```bash
git -C ORGE-ENGINE add orge_jni.cpp tests/time_dt_test.cpp
git -C ORGE-ENGINE commit -q -m "feat(engine): delete dt sub-cycle — one sweep per call (law C, spec 2026-06-04)"
git -C ORGE-ENGINE push -q origin HEAD:main 2>/dev/null
[ "$(git -C ORGE-ENGINE rev-parse HEAD)" = "$(git -C ORGE-ENGINE rev-parse origin/main)" ] && echo "PUSHED origin/main"
```
Expected: `PUSHED origin/main`.

---

## Task 8: Rebuild `.so`, bump gitlink, Java/loader gate, push `rebuild`

**Files:**
- Modify: MAIN `core/src/main/resources/natives/linux-x64/liborge.so` (rebuilt, gitignored — stage with `-f`)
- Modify: MAIN gitlink for `ORGE-ENGINE`

- [ ] **Step 1: Rebuild the native library**

Run:
```bash
cd /home/claude/ORGE && ./native/build_liborge.sh core/src/main/resources/natives/linux-x64/liborge.so
ls -la core/src/main/resources/natives/linux-x64/liborge.so
```
Expected: build succeeds; `.so` mtime is now.

- [ ] **Step 2: Run the Java core + integration tests on the real `.so`**

Run:
```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest
```
Expected: BUILD SUCCESSFUL; `integrationTest` shows `skipped=0` (the real `.so` loaded). If a live-pipeline test asserts an old spread distance at `dt=0.5` (two cells), update it to the one-sweep expectation (one cell) — this is the visible consequence of law C, and is correct.

- [ ] **Step 3: Build both loaders**

Run:
```bash
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 ./gradlew :fabric-1.21:build :neoforge-1.21:build
```
Expected: BUILD SUCCESSFUL (no signature drift).

- [ ] **Step 4: Stage, commit, and push `rebuild`**

Run:
```bash
cd /home/claude/ORGE
git add ORGE-ENGINE   # gitlink bump to the new engine HEAD
git add -f core/src/main/resources/natives/linux-x64/liborge.so
git commit -q -m "feat(fluid): unified hydrostatic head-pressure — engine bump + rebuilt liborge.so

U-tubes/manometers self-level via overburden head + flood guard; one
sweep per call (law C). Spec docs/superpowers/specs/2026-06-04-unified-fluid-math-design.md.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -q origin rebuild 2>&1 | tail -1
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/rebuild)" ] && echo "PUSHED origin/rebuild"
```
Expected: `PUSHED origin/rebuild`.

- [ ] **Step 5: Final full engine gate**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh full
```
Expected: cheap + heavy + stress ALL PASS. This is the in-engine sign-off; the in-game audit by the user is the final real-world gate.

---

## Post-implementation

- **In-game audit (user’s gate):** from `origin/rebuild`, verify a U-tube self-levels, a manometer settles with the light fluid standing taller, a flat pool never climbs, and fluid flow stays smooth under load (no stutter-spiral).
- **Update memory** `orge-unified-fluid` and `orge-communicating-vessels-gap`: the banked "pure-water self-leveling (no driver)" item is now CLOSED; record the engine HEAD, `.so` md5, and MAIN `rebuild` HEAD.
- **Banked / not in this plan:** the GPU client-worker port (§6 of the spec) is a separate future track — the math is now CPU-validated, which was its prerequisite.
