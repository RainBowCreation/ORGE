# Engine-B Canonical Pipeline (ENCRYPT→RESOLVE→DECRYPT, multiple un-mixed maps) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **⚠ READ THE SPEC FIRST, NOT THE CODE.** The code on `rebuild` has drifted from the design repeatedly.
> Before touching anything, read, in order:
> 1. `docs/superpowers/specs/2026-06-07-engine-b-CANONICAL-pipeline.md` — THE LAW
> 2. `docs/superpowers/specs/2026-06-07-engine-b-vector-map-decomposition-design.md` — the decomposition + staging
> 3. `docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md` — the per-channel MATH (§B/§C/§D, §B.1 EOS, §J.5 hydrostatic proof)
> If code and spec disagree, **the spec wins and the code is the bug.**

**Goal:** Make the live Minecraft fluid step the canonical **ENCRYPT → RESOLVE → DECRYPT** pipeline carrying
**three un-mixed per-cell vector maps** (mechanical drive, pressure, thermal), so that — on the **real material
LUT** — water levels and flows horizontally, lava sinks under water, and drained cells leave no residue or
0-mass ghosts, with grand + per-species mass and grand energy exactly conserved and no fabrication.

**Architecture:** Refactor `core/engine_b.hpp` (the closest existing match to the law) toward the spec in three
phase-ordered stages — Stage 1 hardens **ENCRYPT** (the three un-mixed maps) and re-points the live JNI path off
the stale `force_advect.hpp`; Stage 2 hardens **RESOLVE** (pressure-flux leveling + force-difference cross-species
swap); Stage 3 hardens **DECRYPT** (VACUUM relabel, min_mass cohesion, guards). Every stage ends with an
**end-to-end `step_world` acceptance test on the REAL material LUT** — never synthetic Materials. The header is
dependency-free and header-only; tests are standalone `.cpp` files in `tests/` registered in `tests/run_tests.sh`.

**Tech Stack:** C++20, header-only engine (`core/*.hpp`), JNI bridge (`jni/orge_jni.cpp`), bespoke test harness
(no framework), `g++ -std=c++20 -I. -Icore`. Parent repo `/home/claude/ORGE-B`; engine submodule
`/home/claude/ORGE-B/ORGE-ENGINE` (both on branch `rebuild`).

---

## Orientation: what exists, what drifted, what this plan changes

**The live path today (the drift):** `jni/orge_jni.cpp` → `orgeb::step_world_b(world, mats, double dt)` →
the **double-overload defined in `core/force_advect.hpp`** → `stepForceAdvect` (3 separate **local** passes,
**no resolver**). The 2026-06-07 in-game audit found this broke leveling/horizontal-flow/lava-sink. This is the
code the audit actually tested — **not** `engine_b.hpp`.

**The refactor target (`core/engine_b.hpp`):** already an ENCRYPT→RESOLVE→DECRYPT step (`step_world_b(World&,
const MaterialLUT&, const Globals&, double dt)` — the **Globals-overload**, distinct from the double-overload in
`force_advect.hpp`). Its `resolve_world` already has the antisymmetric face flux + pressure flux + a cross-species
swap. Remaining drift to fix against the canonical spec:
- **R1 (Stage 1):** `CellEncrypt` stores a single **bundled** energy vector `E=(Ex,Ey,Ez)=ρ·h·w`; RESOLVE
  *recovers* `w` via `drive_axis(E,ρ,h)`. The spec mandates **three un-mixed maps** written directly
  (`w⃗` drive, `p` pressure, `h`/`T` thermal) — representation change only, no arithmetic change (proven §J.2).
- **R2 (Stage 2):** the cross-species swap is gated on `chi(Mlow) < 0.5` (line ~331). The spec replaces this
  **stale `chi` gate** with a **force-difference threshold swap** (audit #2: lava won't sink).
- **R3 (Stage 2):** the receiver-room clamp hard-caps every cell at `max_mass`. With the **real LUT**
  (`water.max_mass == default_mass == 1000`) this forbids the micro-overshoot that the `^γ` pressure ramp needs to
  build hydrostatic pressure-at-depth → **no leveling** (audit #1). The spec wants overshoot permitted and relaxed
  by `p` next tick (§C.5 + §G.1b), with `K` calibrated so a 1-cell overshoot balances `ρgΔy`.
- **R4 (Stage 3):** DECRYPT must relabel drained cells to VACUUM + restore min_mass cohesion (audit #3).

**Decision — refactor in place, do NOT gut to a no-op resolve.** §5 of the decomposition design describes the
stage shape for a *from-scratch* phase build ("no-op RESOLVE + minimal pass-through DECRYPT" at stage 1). We are
instead refactoring `engine_b.hpp`, which **already** has a runnable, conserving resolve+decrypt. Gutting them to
rebuild would discard working, tested conservation code and add risk. We honor the spec's *intent* — "the build
carries a runnable, conserving `step_world` from stage 1" and "every stage ends with a real-LUT end-to-end test" —
by keeping the working resolve/decrypt live through Stage 1 (representation refactor only) and hardening RESOLVE
(Stage 2) and DECRYPT (Stage 3) in place. This deviation is intentional and documented here; if the reviewer
prefers the literal no-op-resolve staging, stop and re-plan before Stage 2.

**The real material LUT (canonical values — USE THESE in every acceptance test, never synthetic `chi=0.5`):**
From `core/src/main/resources/data/orge/orge/materials/*.json`. `Material` aggregate order is
`{heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass, yieldStress}`.

| material | heatCap | thermCond | molar | minMass | **maxMass** | viscosity | **defaultMass** | note |
|---|---|---|---|---|---|---|---|---|
| VOID  | 0     | 0     | 0     | 0    | 0    | +INF  | 0    | matIx 0 |
| water | 4186  | 0.6   | 0.018 | 125  | **1000** | 0.001 | **1000** | max==default ⇒ incompressible |
| lava  | 1450  | 1.5   | 0.060 | 400  | **3100** | 100   | **3100** | max==default ⇒ incompressible |
| air   | 1005  | 0.026 | 0.002 | 1.0  | 1000 | 2e-5  | 1.2  | max≫default ⇒ compressible gas |
| stone | 840   | 2.5   | 0.060 | 2500 | 2500 | +INF  | 2500 | frozen terrain (solid: min=max=default, visc=INF) |
| steam | 2080  | 0.025 | 0.001 | 0.6  | 0.6  | 1e-4  | 0.6  | max==default |

`chi(water) = (1000−1000)/(1000−125) = 0` (incompressible); `chi(air) = (1000−1.2)/(1000−1.0) ≈ 1.0` (gas).
**Solids** (stone) have no `min/max_mass` in JSON — the loader sets `min=max=default` and `viscosity=+INF`.

**Shared real-LUT test helper:** Stage 1 Task 0 creates `tests/engine_b_real_lut.hpp` so every acceptance test
uses these exact values. Do not copy synthetic values into tests.

**Build / run:**
- One test (fast iterate): `./tests/run_tests.sh <name>` (e.g. `./tests/run_tests.sh engine_b_accept`)
- Cheap tier (the loop): `./tests/run_tests.sh fast`
- Full (cheap+heavy+stress, only if green): `./tests/run_tests.sh full`
- Add a new test: append a `"name|tests/name.cpp|-O2 -g"` line to `CHEAP_TESTS` in `tests/run_tests.sh`.
- Rebuild the `.so` for in-game (Stage 3 gate): see Stage 3 Task 14.

**Conventions for new tests** (match `tests/engine_b_encrypt_test.cpp`): `#include` the engine header, a local
`CHECK(cond,msg)` macro that increments a `failures` counter, `int main()` returns `failures?1:0` and prints
`"<name> OK"` on success.

**Baseline (verified before this plan):** `./tests/run_tests.sh fast` → 16/16 PASS.

---

# STAGE 1 — ENCRYPT: three un-mixed maps + re-point the live path

**Stage goal:** `CellEncrypt` carries the three un-mixed maps (drive `w⃗`, pressure `p`, thermal `h`/`T`) written
directly — no bundled `E`. RESOLVE reads `w⃗` directly. The live JNI path runs engine_b.hpp's
ENCRYPT→RESOLVE→DECRYPT (off `force_advect.hpp`). **End-to-end acceptance on the REAL LUT:** a rest scene stays at
rest; grand + per-species mass + grand energy conserved; no fabrication; no `max_mass` overshoot.

---

### Task 0: Shared real-LUT test fixture

**Files:**
- Create: `ORGE-ENGINE/tests/engine_b_real_lut.hpp`

- [ ] **Step 1: Write the fixture header**

```cpp
#pragma once
// Real material LUT for Engine-B acceptance tests — values copied verbatim from
// core/src/main/resources/data/orge/orge/materials/*.json. The canonical spec §6 forbids
// synthetic Materials in acceptance tests (a synthetic chi=0.5 water masked the dead engine
// last build). Material aggregate order:
//   {heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass, yieldStress}
#include "engine_b.hpp"
#include <limits>

namespace rlut {
inline constexpr float INF = std::numeric_limits<float>::infinity();

// Indices are assigned in add() order: VOID must be 0 (Chunk::void_ix default).
struct RealLut {
    orgeb::Globals G;          // provisional globals (Stage 4 calibrates K/gamma/...)
    uint16_t VOID, WATER, LAVA, AIR, STONE, STEAM;
};

inline RealLut make_real_lut(World& w) {
    RealLut M;
    M.VOID  = w.materials.add(Material{   0.f,  0.f,   0.000f,    0.f,    0.f, INF,      0.f});
    M.WATER = w.materials.add(Material{4186.f,  0.6f,  0.018f,  125.f, 1000.f, 0.001f, 1000.f});
    M.LAVA  = w.materials.add(Material{1450.f,  1.5f,  0.060f,  400.f, 3100.f, 100.f,  3100.f});
    M.AIR   = w.materials.add(Material{1005.f,  0.026f,0.002f,    1.f, 1000.f, 2e-5f,    1.2f});
    M.STONE = w.materials.add(Material{ 840.f,  2.5f,  0.060f, 2500.f, 2500.f, INF,    2500.f});
    M.STEAM = w.materials.add(Material{2080.f,  0.025f,0.001f,    0.6f,   0.6f, 1e-4f,    0.6f});
    return M;
}

// Grand mass over all chunks.
inline double grand_mass(const World& w) {
    double t = 0.0;
    for (auto& kv : w.chunks) for (int i = 0; i < CHUNK_N; ++i) t += kv.second->mass_kg[i];
    return t;
}
// Mass of one species over all chunks.
inline double species_mass(const World& w, uint16_t sp) {
    double t = 0.0;
    for (auto& kv : w.chunks) for (int i = 0; i < CHUNK_N; ++i)
        if (kv.second->matIx[i] == sp) t += kv.second->mass_kg[i];
    return t;
}
// Grand internal+kinetic energy: sum m*c*T + 1/2 m|u|^2 over all chunks.
inline double grand_energy(const World& w, const MaterialLUT& mats) {
    double e = 0.0;
    for (auto& kv : w.chunks) {
        const Chunk& C = *kv.second;
        for (int i = 0; i < CHUNK_N; ++i) {
            float m = C.mass_kg[i];
            if (m <= 0.f) continue;
            float c = mats.byIx(C.matIx[i]).heatCapacity;
            float kin = 0.5f * (C.vx[i]*C.vx[i] + C.vy[i]*C.vy[i] + C.vz[i]*C.vz[i]);
            e += (double)m * (c * C.T_curr[i] + kin);
        }
    }
    return e;
}
} // namespace rlut
```

- [ ] **Step 2: Sanity-compile the fixture**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -I. -Icore -fsyntax-only -x c++ -include tests/engine_b_real_lut.hpp /dev/null`
Expected: no output, exit 0 (header compiles; if it errors, fix the include/types before proceeding).

- [ ] **Step 3: Commit**

```bash
cd ORGE-ENGINE
git add tests/engine_b_real_lut.hpp
git commit -m "test(engine-b): shared real-LUT fixture for canonical-pipeline acceptance tests"
```

---

### Task 1: Replace the bundled `E` vector with the three un-mixed maps in ENCRYPT

**Files:**
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (`struct CellEncrypt`, `encrypt_cell`, `drive_axis`, the `W` recovery
  sites in `resolve_world`)
- Test: `ORGE-ENGINE/tests/engine_b_encrypt_test.cpp` (rewrite the `E`-based assertions to map-based)

**What changes (representation only — arithmetic is identical, proven §J.2):** `CellEncrypt` stops storing
`Ex,Ey,Ez`. It stores the drive map `wx,wy,wz` **directly** (`= u_g · dragScale`, the same product RESOLVE used to
recover). `p`, `h`, `rho`, `ugx/ugy/ugz` stay (they are the pressure map, thermal amplitude, density, and the
momentum carrier). RESOLVE reads `wx/wy/wz` directly instead of `drive_axis(E,ρ,h)`.

- [ ] **Step 1: Rewrite the encrypt assertions in the test (failing test first)**

Replace the four assertion blocks in `tests/engine_b_encrypt_test.cpp` (the `e.Ey`, `e.Ex`, `frozen E==0`,
`void E==0` blocks) with the drive-map equivalents:

```cpp
    // Gravity body impulse: a resting water cell gains u_g=(0,-g*dt,0); drive w follows u_g (damped).
    {
        CellEncrypt e = encrypt_cell(water, /*mass*/1000.f, /*T*/288.f, 0.f,0.f,0.f, G, /*dt*/0.5f);
        CHECK(std::fabs(e.ugy - (-G.g * 0.5f)) < 1e-4f, "gravity impulse u_g.y = -g*dt");
        CHECK(e.ugx == 0.f && e.ugz == 0.f, "gravity is y-only");
        CHECK(e.wy < 0.f, "drive w aims downward under gravity");   // was: e.Ey < 0
        CHECK(e.wx == 0.f && e.wz == 0.f, "drive is y-only at rest");
    }
    // Frozen cell: dragScale 0 => drive w == 0 (still has a partition, just no advective drive).
    {
        CellEncrypt e = encrypt_cell(frozen, 2500.f, 288.f, 0.f,0.f,0.f, G, 0.5f);
        CHECK(e.wx == 0.f && e.wy == 0.f && e.wz == 0.f, "frozen (visc=INF) emits zero drive");
    }
    // Light cell drives the SAME as a heavy cell at equal velocity (w is velocity, not momentum);
    // the air-pushes-water fix lives in RESOLVE (mdot = rho_donor*W*A*dt), not in the drive map.
    {
        Material air{1005.f, 0.026f, 0.002f, 1.f, 1000.f, 2e-5f, 1.2f};
        CellEncrypt ea = encrypt_cell(air,   1.2f,   288.f, 1.f,0.f,0.f, G, 0.25f);
        CellEncrypt ew = encrypt_cell(water, 1000.f, 288.f, 1.f,0.f,0.f, G, 0.25f);
        CHECK(ea.rho < ew.rho, "air rho < water rho (the air-pushes-water fix is rho in RESOLVE)");
    }
    // Void / massless cell: zero drive, zero pressure.
    {
        Material vac{0.f,0.f,0.f,0.f,0.f,0.f,0.f};
        CellEncrypt e = encrypt_cell(vac, 0.f, 288.f, 0.f,0.f,0.f, G, 0.5f);
        CHECK(e.wx==0.f && e.wy==0.f && e.wz==0.f && e.p==0.f, "void emits zero drive and zero pressure");
    }
```

- [ ] **Step 2: Run the test to verify it FAILS to compile**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_encrypt`
Expected: COMPILE FAILED — `CellEncrypt` has no member `wx`/`wy`/`wz`.

- [ ] **Step 3: Change `struct CellEncrypt` to the three un-mixed maps**

In `core/engine_b.hpp`, replace the `CellEncrypt` struct (the `Ex,Ey,Ez` comment block) with:

```cpp
// Per-cell Encrypt output — the THREE un-mixed maps (canonical spec §1), written directly (no bundled
// E=rho*h*w). RESOLVE reads these; it never un-bundles. Decision DEC-1.
//   (1) mechanical drive  w = (wx,wy,wz)  = u_g * dragScale   (face-drive source, §C.1)
//   (2) pressure modifier p (scalar EOS)                      (pressure flux, §C.2)
//   (3) thermal           h (amplitude) + T (read from snapshot for conduction, §C.3)
// Also exposed (the stored partition RESOLVE may read, DEC-D4): rho, u_g (momentum carrier).
struct CellEncrypt {
    float wx = 0.f, wy = 0.f, wz = 0.f;   // (1) mechanical drive velocity (m/s)
    float p  = 0.f;                        // (2) EOS pressure
    float h  = 0.f;                        // (3) stagnation specific energy (thermal amplitude, §B.2)
    float ugx = 0.f, ugy = 0.f, ugz = 0.f; // gravity-updated velocity (momentum carrier, §C.2/§D.2)
    float rho = 0.f;                       // m / V (cached for RESOLVE mdot)
};
```

- [ ] **Step 4: Change `encrypt_cell` to write `w` directly instead of `E`**

Replace the tail of `encrypt_cell` (from the `// E = rho * h * w_drive` comment through `return e;`) with:

```cpp
    // (1) Mechanical drive w = u_g * dragScale (§B.4). Viscous drag damps it; frozen (mu=INF) -> 0.
    //     This is the un-mixed drive map written DIRECTLY — no rho*h bundling (canonical §1, DEC-1).
    e.wx = e.ugx * dragScale;
    e.wy = e.ugy * dragScale;
    e.wz = e.ugz * dragScale;
    return e;
}
```

(Leave the lines above it that compute `e.rho`, `e.p`, `e.h`, `e.ugx/ugy/ugz`, and `dragScale` unchanged.)

- [ ] **Step 5: Delete `drive_axis` and read `w` directly in RESOLVE**

In `core/engine_b.hpp`, delete the `drive_axis` helper (the `inline float drive_axis(...)` function and its
comment). Then in `resolve_world`, replace every `drive_axis(eX->cells[k].Ex, eX->cells[k].rho, eX->cells[k].h, G)`
(and the `Ey`/`Ez`, and the `ej` neighbour variants — there are sites in the vacuum-claim pre-pass and in the main
additive loop) with the stored component:
- `drive_axis(eX->cells[k].Ex, ...)` → `eX->cells[k].wx`
- `...Ey...` → `eX->cells[k].wy`
- `...Ez...` → `eX->cells[k].wz`

So the main-loop `wix/wiy/wiz` and `wjx/wjy/wjz` become:

```cpp
                float wix = ei->cells[i].wx, wiy = ei->cells[i].wy, wiz = ei->cells[i].wz;
                float wjx = ej->cells[j].wx, wjy = ej->cells[j].wy, wjz = ej->cells[j].wz;
```

and the vacuum-claim pre-pass `wvx/wvy/wvz` and `wnx/wny/wnz` likewise read `.wx/.wy/.wz`. Confirm no leftovers:

Run: `grep -n "drive_axis\|\.Ex\|\.Ey\|\.Ez" core/engine_b.hpp`
Expected: no matches.

- [ ] **Step 6: Run the encrypt test — verify it PASSES**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_encrypt`
Expected: `engine_b_encrypt_test OK`.

- [ ] **Step 7: Run the whole cheap tier — representation change must be byte-equivalent**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh fast`
Expected: ALL TESTS PASSED (16/16). The change is representation-only; any regression here is a transcription bug
in Step 5 — fix it before committing.

- [ ] **Step 8: Commit**

```bash
cd ORGE-ENGINE
git add core/engine_b.hpp tests/engine_b_encrypt_test.cpp
git commit -m "refactor(engine-b): ENCRYPT writes 3 un-mixed maps (drive/pressure/thermal), drop bundled E (DEC-1)"
```

---

### Task 2: Stage-1 real-LUT rest-conservation acceptance test (end-to-end `step_world`)

**Files:**
- Create: `ORGE-ENGINE/tests/engine_b_stage1_rest_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (register the new test in `CHEAP_TESTS`)

This is the Stage-1 end-to-end gate from §5: a rest scene on the REAL LUT stays at rest, conserved, no fabrication.

- [ ] **Step 1: Write the failing acceptance test**

```cpp
// Build: g++ -std=c++20 -O2 -g -I. -Icore tests/engine_b_stage1_rest_test.cpp -o build/engine_b_stage1_rest_test
// Stage-1 end-to-end gate (canonical spec §5/§6): on the REAL material LUT, a resting scene stays at
// rest and conserves grand+per-species mass and grand energy, with no fabrication and no max_mass overshoot.
#include "engine_b_real_lut.hpp"
#include <cstdio>
#include <cmath>

static int failures = 0;
#define CHECK(cond,msg) do{ if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } }while(0)

static void setc(Chunk& C, int x,int y,int z, uint16_t sp, float m, float T){
    int i=idx(x,y,z); C.matIx[i]=sp; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=C.vy[i]=C.vz[i]=0.f;
}

int main(){
    using namespace orgeb;
    World w;
    rlut::RealLut M = rlut::make_real_lut(w);
    Chunk* C = w.ensureChunk(0,0);

    // A settled water pool on a stone floor, with air above. y grows up; floor at y=29.
    for (int x=4;x<12;++x) for (int z=4;z<12;++z) {
        setc(*C, x,29,z, M.STONE, 2500.f, 290.f);      // floor
        setc(*C, x,30,z, M.WATER, 1000.f, 290.f);      // full water cell (at rest density => p=0)
        setc(*C, x,31,z, M.WATER, 1000.f, 290.f);
        setc(*C, x,32,z, M.AIR,      1.2f, 290.f);      // air at rest density
    }

    double m0 = rlut::grand_mass(w);
    double wat0 = rlut::species_mass(w, M.WATER), air0 = rlut::species_mass(w, M.AIR);
    double e0 = rlut::grand_energy(w, w.materials);

    for (int t=0;t<40;++t) step_world_b(w, w.materials, M.G, 0.5);

    double m1 = rlut::grand_mass(w);
    double wat1 = rlut::species_mass(w, M.WATER), air1 = rlut::species_mass(w, M.AIR);
    double e1 = rlut::grand_energy(w, w.materials);

    CHECK(std::fabs(m1 - m0) < 1e-3, "grand mass conserved at rest");
    CHECK(std::fabs(wat1 - wat0) < 1e-2, "per-species water mass conserved (no fabrication, no leak)");
    CHECK(std::fabs(air1 - air0) < 1e-2, "per-species air mass conserved");
    CHECK(std::fabs(e1 - e0) <= 1e-3 * std::fabs(e0) + 1.0, "grand energy bounded (no blow-up)");

    for (int i=0;i<CHUNK_N;++i){
        float m = C->mass_kg[i];
        CHECK(std::isfinite(m) && std::isfinite(C->T_curr[i]), "no NaN/Inf");
        CHECK(m <= w.materials.byIx(C->matIx[i]).maxMass + 1e-2f, "no max_mass overshoot");
    }
    int wetCells=0; for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==M.WATER && C->mass_kg[i]>M.G.eps_mass) ++wetCells;
    CHECK(wetCells == 8*8*2, "settled water neither spreads nor vanishes (occupies its 128 cells)");

    if(failures){ std::printf("%d FAILURES\n", failures); return 1; }
    std::printf("engine_b_stage1_rest_test OK\n"); return 0;
}
```

- [ ] **Step 2: Register the test in `tests/run_tests.sh`**

Add to the `CHEAP_TESTS=( ... )` array (after the `engine_b_step_test` line):

```bash
  "engine_b_stage1_rest_test|tests/engine_b_stage1_rest_test.cpp|-O2 -g"
```

- [ ] **Step 3: Run it**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage1_rest_test`
Expected: **likely PASS** (at rest density water has `p=0`, gravity balanced by the floor wall reaction). If a
check FAILS, that is a genuine real-LUT rest-stability bug — diagnose with `superpowers:systematic-debugging`
before proceeding. Most likely culprit: the air column (`1.2 kg`, expansion branch `p<0`) drifting. If air drifts
but *conserves*, relax `wetCells` to water-only and **record the air drift as a Stage-2 item** (expansion is
Stage 2) rather than forcing it green here.

- [ ] **Step 4: Commit**

```bash
cd ORGE-ENGINE
git add tests/engine_b_stage1_rest_test.cpp tests/run_tests.sh
git commit -m "test(engine-b): Stage-1 real-LUT rest+conservation end-to-end gate (canonical §5/§6)"
```

---

### Task 3: Re-point the live JNI path onto the canonical pipeline (off `force_advect.hpp`)

**Files:**
- Modify: `ORGE-ENGINE/core/force_advect.hpp` (the double-overload `step_world_b(World&, const MaterialLUT&, double)`)
- Modify: `ORGE-ENGINE/jni/orge_jni.cpp` (the `⚠ DRIFT` comment block at the `PASS_ADVECTION` site)

The JNI calls `orgeb::step_world_b(world, world.materials, dt)` — the **double-overload**, which lives in
`force_advect.hpp` and routes to `stepForceAdvect` (the stale 3-local-pass drift). We make the double-overload call
the canonical Globals-overload instead. We do **not** delete `force_advect.hpp` yet (`force_pass_test`,
`advect_pass_test`, `force_advect_soak_test` still include it); we only change where the live overload routes.

- [ ] **Step 1: Re-point the double-overload to the canonical step**

In `core/force_advect.hpp`, replace the body of the double-overload (the `inline void step_world_b(World& world,
const MaterialLUT& mats, double dt){ stepForceAdvect(...); }` near line 426) with:

```cpp
// LIVE PATH (canonical pipeline): route the double-overload to engine_b.hpp's ENCRYPT->RESOLVE->DECRYPT
// Globals-overload (the resolver). The stale 3-local-pass stepForceAdvect is RETIRED from the live path
// (2026-06-07 canonical spec — it had no resolver and broke in-game leveling). Globals{} are provisional
// (Stage 4 §G.2 calibrates). stepForceAdvect remains only for the force_pass/advect_pass unit tests.
inline void step_world_b(World& world, const MaterialLUT& mats, double dt){
    static const orgeb::Globals G{};
    orgeb::step_world_b(world, mats, G, dt);
}
```

- [ ] **Step 2: Update the JNI drift comment to reflect the re-point**

In `jni/orge_jni.cpp`, replace the `⚠ DRIFT:` comment block above `orgeb::step_world_b(world, world.materials, dt);`
(lines ~176–181) with:

```cpp
            // LIVE PATH: canonical ENCRYPT->RESOLVE->DECRYPT (engine_b.hpp resolver), per
            // docs/superpowers/specs/2026-06-07-engine-b-CANONICAL-pipeline.md. The double-overload
            // step_world_b now routes here (off the retired force_advect.hpp stepForceAdvect).
            orgeb::step_world_b(world, world.materials, dt);   // Engine B canonical advection step
```

- [ ] **Step 3: Build the cheap tier — confirm the re-point compiles and conserves**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh fast`
Expected: ALL TESTS PASSED. `force_advect_soak_test` exercises `stepForceAdvect` *directly* (not via the overload),
so it still passes. If it fails, read its failure — its *live-path* section may assert old force-advect behaviour;
if so, that section now tests retired code and should be **migrated to the Stage-1 rest test** (Task 2) rather than
kept green against dead behaviour. Record the decision in the commit message.

- [ ] **Step 4: Commit**

```bash
cd ORGE-ENGINE
git add core/force_advect.hpp jni/orge_jni.cpp
git commit -m "fix(engine-b): re-point live JNI path to canonical resolver (off stale force_advect stepForceAdvect)"
```

- [ ] **Step 5: Stage-1 checkpoint — full suite + push engine + bump parent gitlink**

```bash
cd ORGE-ENGINE
./tests/run_tests.sh full          # expected: ALL TESTS PASSED
git push origin rebuild
cd /home/claude/ORGE-B
git add ORGE-ENGINE
git commit -m "chore(engine-b): bump gitlink to Stage-1 canonical-ENCRYPT + live-path re-point"
git push origin rebuild
```

Expected: full suite green; both pushes succeed (per `[[always-push-rebuild]]`: push both repos after every commit).

---

# STAGE 2 — RESOLVE: pressure-flux leveling + force-difference cross-species swap

**Stage goal:** On the REAL LUT — **water actually levels and flows horizontally** (audit #1); **lava actually
sinks under water** (audit #2); buoyancy order lava<water<air; incompressible displacement conserves; conduction
relaxes toward Fourier. Every fluid test **asserts displacement actually happened** (not just "bounded").

> **Stage-2 central risk (read before Task 4):** with the real LUT, `water.max_mass == default_mass == 1000`.
> A resting full water cell sits exactly at `m_rest`, so EOS `p = 0`. The current receiver-room clamp
> (`room = max(0, max_mass − mass)`) is then **0** for any full water cell → no inflow → the column can never build
> the micro-overshoot that the `^γ` ramp turns into hydrostatic pressure-at-depth → **no leveling**. This is R3.
> The spec's resolution (§C.5 "transient overshoot relaxes next tick via p" + §G.1b soft pseudo-compressibility +
> §G.1 "tune K so a 1-cell water column's p balances ρgΔy"): **permit transient overshoot above `max_mass` for
> fluids and let `p` push it back next tick**, rather than hard-clamping at `max_mass`. Task 5 implements this;
> Task 6 calibrates `K`. Do them as a `superpowers:systematic-debugging` loop driven by the Task 4 failing test.

---

### Task 4: Failing real-LUT leveling + horizontal-flow test (audit #1)

**Files:**
- Create: `ORGE-ENGINE/tests/engine_b_stage2_leveling_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (register in `CHEAP_TESTS`)

- [ ] **Step 1: Write the failing test (assert it MOVED)**

```cpp
// Build: g++ -std=c++20 -O2 -g -I. -Icore tests/engine_b_stage2_leveling_test.cpp -o build/...
// Stage-2 audit #1: on the REAL LUT, water levels (a tall column beside a short one equalizes) and
// flows horizontally. The gate is "it MOVED toward level", not merely "mass conserved".
#include "engine_b_real_lut.hpp"
#include <cstdio>
#include <cmath>
#include <cstdlib>
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n", m); ++failures; } }while(0)
static void setc(Chunk& C,int x,int y,int z,uint16_t sp,float m,float T){
    int i=idx(x,y,z); C.matIx[i]=sp; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=C.vy[i]=C.vz[i]=0.f; }
static int water_height(const Chunk& C,int x,int z,uint16_t W,float eps){
    int h=0; for(int y=0;y<CHUNK_H;++y) if(C.matIx[idx(x,y,z)]==W && C.mass_kg[idx(x,y,z)]>eps) ++h; return h; }

int main(){
    using namespace orgeb;
    World w; rlut::RealLut M = rlut::make_real_lut(w);
    // K is the calibration target (Task 6): allow override via env for the sweep.
    if (const char* k = std::getenv("ORGE_K")) M.G.K = (float)std::atof(k);
    Chunk* C = w.ensureChunk(0,0);
    for(int x=0;x<CHUNK_W;++x) for(int z=0;z<CHUNK_D;++z) setc(*C,x,20,z,M.STONE,2500.f,290.f); // floor
    // 6-cell-tall water column on the LEFT (x=2..5), 1 cell of water on the RIGHT (x=8..11).
    for(int x=2;x<=5;++x) for(int z=6;z<=9;++z) for(int y=21;y<=26;++y) setc(*C,x,y,z,M.WATER,1000.f,290.f);
    for(int x=8;x<=11;++x) for(int z=6;z<=9;++z) setc(*C,x,21,z,M.WATER,1000.f,290.f);
    // air everywhere above the water (rest density) so water has somewhere to push the air.
    for(int x=0;x<CHUNK_W;++x) for(int z=0;z<CHUNK_D;++z) for(int y=21;y<CHUNK_H;++y)
        if(C->matIx[idx(x,y,z)]==M.VOID) setc(*C,x,y,z,M.AIR,1.2f,290.f);

    double m0 = rlut::species_mass(w,M.WATER);
    int leftH0 = water_height(*C,3,7,M.WATER,M.G.eps_mass);   // ~6
    int rightH0= water_height(*C,9,7,M.WATER,M.G.eps_mass);   // ~1

    for(int t=0;t<400;++t) step_world_b(w,w.materials,M.G,0.5);

    double m1 = rlut::species_mass(w,M.WATER);
    int leftH1 = water_height(*C,3,7,M.WATER,M.G.eps_mass);
    int rightH1= water_height(*C,9,7,M.WATER,M.G.eps_mass);

    CHECK(std::fabs(m1-m0) < 1e-1, "water mass conserved during leveling");
    CHECK(leftH1 < leftH0, "tall column DROPS (water left the left side)");
    CHECK(rightH1 > rightH0, "short column RISES (water reached the right side) — horizontal flow happened");
    CHECK(std::abs(leftH1 - rightH1) <= 1, "columns EQUALIZED to within 1 cell (leveling)");
    if(failures){ std::printf("%d FAILURES\n",failures); return 1; }
    std::printf("engine_b_stage2_leveling_test OK\n"); return 0;
}
```

- [ ] **Step 2: Register + run — confirm it FAILS the right way**

Add `"engine_b_stage2_leveling_test|tests/engine_b_stage2_leveling_test.cpp|-O2 -g"` to `CHEAP_TESTS`, then run it.
Expected: FAIL on "tall column DROPS" / "columns EQUALIZED" (water does not level because the full-cell room clamp
is 0). Mass-conserved should PASS. **If it unexpectedly passes, leveling already works — skip Task 5's clamp change
but keep Task 6's calibration check.**

- [ ] **Step 3: Commit the failing test**

```bash
cd ORGE-ENGINE
git add tests/engine_b_stage2_leveling_test.cpp tests/run_tests.sh
git commit -m "test(engine-b): Stage-2 real-LUT leveling/horizontal-flow gate (audit #1, RED)"
```

---

### Task 5: Permit transient overshoot so pressure-at-depth can form (R3 leveling fix)

**Files:**
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (`struct Globals`; `resolve_world` — the `recvRoom` init and the per-face
  capacity clamp)

**The fix (spec §C.5 + §G.1b):** replace the hard receiver-room clamp at `max_mass` with an **overshoot-tolerant**
band: a fluid receiver may accept inflow up to `max_mass · (1 + overfill)` within a tick (the `^γ` EOS ramp then
generates a large restoring `p` that pushes the excess back out next tick — the pseudo-compressible mechanism that
*is* hydrostatic pressure-at-depth). Frozen terrain and vacuum are unchanged. `overfill` is a new `Globals` knob.

- [ ] **Step 1: Add the `overfill` knob to `Globals`**

In `core/engine_b.hpp`, add to `struct Globals` (after `eps_mass`):

```cpp
    float overfill = 0.02f;   // fluids may transiently exceed max_mass by this fraction within a tick; the
                              // ^gamma EOS ramp generates the restoring p that becomes hydrostatic pressure-
                              // at-depth (§C.5 "overshoot relaxes next tick via p", §G.1b soft compressibility).
                              // Stage-4 §G.2 calibrates jointly with K so a 1-cell overshoot balances rho*g*dy.
```

- [ ] **Step 2: Allow the overshoot band in the `recvRoom` initialization**

In `resolve_world`, in the `recvRoom` init loop, change the occupied-cell room from `M.maxMass - mass` to the
overshoot band (vacuum stays `INF`):

```cpp
        for (int i=0;i<CHUNK_N;++i) {
            const Material& Mr = mats.byIx(s->matIx[i]);
            bool vacuum = (s->mass[i] <= G.eps_mass);
            // Overshoot-tolerant ceiling: fluids may transiently exceed max_mass; the EOS ^gamma ramp
            // restores it next tick (this is what builds hydrostatic pressure-at-depth — R3/audit #1).
            // Frozen terrain (visc=INF, real mass) does NOT compress: keep its hard wall at max_mass.
            float ceil = (!std::isfinite(Mr.viscosity) && s->mass[i] > G.eps_mass)
                       ? Mr.maxMass
                       : Mr.maxMass * (1.0f + G.overfill);
            r[i] = vacuum ? std::numeric_limits<float>::infinity()
                          : std::max(0.0f, ceil - s->mass[i]);
        }
```

- [ ] **Step 3: Apply the same overshoot ceiling at the per-face capacity clamp**

In the additive loop's capacity clamp block (where `float room = std::max(0.0f, Mr.maxMass - sr->mass[ri]);`),
change to:

```cpp
                        bool rFrozen = !std::isfinite(Mr.viscosity) && sr->mass[ri] > G.eps_mass;
                        float ceil = rFrozen ? Mr.maxMass : Mr.maxMass * (1.0f + G.overfill);
                        float room = std::max(0.0f, ceil - sr->mass[ri]);
```

(Leave the `recvVacuum`/`Mdonor` logic above it unchanged; only the `room` ceiling changes.)

- [ ] **Step 4: Run the leveling test**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage2_leveling_test`
Expected: closer to PASS. "tall column DROPS" and "short column RISES" should now pass. "EQUALIZED to within 1 cell"
may still fail if `K` is mis-tuned (overshoot too soft = sluggish, too stiff = oscillates) — that is Task 6. If mass
conservation now FAILS, the overshoot let a cell exceed the donor/budget bookkeeping — re-check that the donor
outflow budget (`bd` block) is unchanged and still single-spends against snapshot mass.

- [ ] **Step 5: Run the cheap tier — guard against regressions (esp. overshoot in the rest test)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh fast`
Expected: `engine_b_stage1_rest_test` still green (a 2% transient overshoot must relax — the rest test's
`max_mass + 1e-2` overshoot assertion FAILS if cells stay overfilled; if so, the relaxation is too slow → tune `K`
up in Task 6, or tighten `overfill`). Other tests green.

- [ ] **Step 6: Commit**

```bash
cd ORGE-ENGINE
git add core/engine_b.hpp
git commit -m "fix(engine-b): RESOLVE permits transient fluid overshoot so pressure-at-depth forms (R3/audit #1)"
```

---

### Task 6: Calibrate `K` so leveling equalizes without oscillating

**Files:**
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (`Globals::K` default + a comment recording the calibration)

- [ ] **Step 1: Sweep `K` against the leveling test (bisection, §G.2)**

The leveling test already reads `ORGE_K` from the environment (Task 4 Step 1). Sweep:

Run: `cd ORGE-ENGINE && for k in 3e2 1e3 3e3 1e4; do echo "K=$k"; ORGE_K=$k ./tests/run_tests.sh engine_b_stage2_leveling_test 2>&1 | grep -E "OK|FAIL"; done`
Expected: identifies the `K` where "EQUALIZED to within 1 cell" passes AND (cross-check) the Stage-1 rest overshoot
assertion holds. Spec target (§G.1): a 1-cell water overshoot's `p` balances `ρ·g·Δy = 1000·10·1 = 10000 Pa`. With
the guarded EOS denom (`max−m_rest → 1e-6`), even a tiny overshoot yields large `p`, so expect the working `K` to
be **smaller** than the current `3.0e3`. Pick the smallest `K` that equalizes within ~400 ticks without the
left/right heights oscillating (overshooting past each other tick-to-tick). Bounded above by acoustic CFL `c_s ≤ 2`
(§G.1b) — do not exceed `K` that makes the rest test blow up.

- [ ] **Step 2: Set the calibrated `K` and record why**

Edit `Globals::K`'s default to the chosen value and update its comment:

```cpp
    float K = /*chosen*/ 1.0e3f;  // EOS stiffness. Calibrated (§G.1/§G.2) so a 1-cell water overshoot's p
                                  // balances rho*g*dy=10000 Pa: the Stage-2 leveling test equalizes a 6-vs-1
                                  // column within ~400 ticks with no tick-to-tick height oscillation, and the
                                  // Stage-1 rest overshoot relaxes within a few ticks. Bounded above by the
                                  // acoustic CFL c_s<=2 m/s (§G.1b).
```

- [ ] **Step 3: Run both Stage gates (no env override now)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage2_leveling_test && ./tests/run_tests.sh engine_b_stage1_rest_test`
Expected: both OK.

- [ ] **Step 4: Run the cheap tier**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh fast`
Expected: ALL TESTS PASSED. (`engine_b_accept_test` uses its own synthetic Globals; if it now fails it is asserting
behaviour tied to the old `K` — update its tolerances, not the physics. It gets retired onto the real LUT in
Task 12 regardless.)

- [ ] **Step 5: Commit**

```bash
cd ORGE-ENGINE
git add core/engine_b.hpp
git commit -m "calib(engine-b): tune EOS K so real-LUT water leveling equalizes without oscillation (§G.1)"
```

---

### Task 7: Replace the stale `chi` swap gate with a force-difference threshold (audit #2)

**Files:**
- Create: `ORGE-ENGINE/tests/engine_b_stage2_lava_sink_test.cpp`
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (the swap-candidate gate in `resolve_world`; add `swap_threshold` to `Globals`)
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

**The fix (canonical §2, DEC-3):** the swap is fired by a **force-difference threshold** (heavy-on-light: the upper
cell pushes down harder than the lower by more than a hysteresis threshold, `≈ (ρ_i − ρ_j)·g·V > threshold`),
**NOT** gated on `chi(Mlow) ≥ 0.5`. Delete the `chi` line; keep everything else about the swap (the energy-lowering
PE check, the GPU-safe gather / conflict-free selection, one-swap-per-cell, flux-XOR-swap).

- [ ] **Step 1: Write the failing lava-sink test**

```cpp
// Build: g++ -std=c++20 -O2 -g -I. -Icore tests/engine_b_stage2_lava_sink_test.cpp -o build/...
// Stage-2 audit #2 (canonical §2, DEC-3): lava placed ABOVE water SINKS (heavy-on-light swap), and
// per-species mass is exactly conserved (a swap is a pure permutation). Force-difference gate, NOT chi.
#include "engine_b_real_lut.hpp"
#include <cstdio>
#include <cmath>
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n", m); ++failures; } }while(0)
static void setc(Chunk& C,int x,int y,int z,uint16_t sp,float m,float T){
    int i=idx(x,y,z); C.matIx[i]=sp; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=C.vy[i]=C.vz[i]=0.f; }
static int lowest_y(const Chunk& C,uint16_t sp,float eps){
    for(int y=0;y<CHUNK_H;++y) for(int x=0;x<CHUNK_W;++x) for(int z=0;z<CHUNK_D;++z)
        if(C.matIx[idx(x,y,z)]==sp && C.mass_kg[idx(x,y,z)]>eps) return y;
    return -1; }

int main(){
    using namespace orgeb;
    World w; rlut::RealLut M = rlut::make_real_lut(w);
    Chunk* C = w.ensureChunk(0,0);
    // A water pool (y=21..24) with a lava cell sitting on TOP (y=25). Stone floor at y=20.
    for(int x=6;x<=9;++x) for(int z=6;z<=9;++z){
        setc(*C,x,20,z,M.STONE,2500.f,290.f);
        for(int y=21;y<=24;++y) setc(*C,x,y,z,M.WATER,1000.f,290.f);
        setc(*C,x,25,z,M.LAVA,3100.f,1400.f);
    }
    double wat0=rlut::species_mass(w,M.WATER), lav0=rlut::species_mass(w,M.LAVA);
    int lavaLow0 = lowest_y(*C,M.LAVA,M.G.eps_mass);   // ~25
    int watLow0  = lowest_y(*C,M.WATER,M.G.eps_mass);  // ~21

    for(int t=0;t<200;++t) step_world_b(w,w.materials,M.G,0.5);

    double wat1=rlut::species_mass(w,M.WATER), lav1=rlut::species_mass(w,M.LAVA);
    int lavaLow1 = lowest_y(*C,M.LAVA,M.G.eps_mass);
    int watLow1  = lowest_y(*C,M.WATER,M.G.eps_mass);

    CHECK(std::fabs(wat1-wat0)<1e-1 && std::fabs(lav1-lav0)<1e-1, "swap conserves per-species mass (permutation)");
    CHECK(lavaLow1 < lavaLow0, "lava SANK (its lowest cell is deeper than it started)");
    CHECK(watLow1 > watLow0 || lavaLow1 <= watLow0, "water ended up ABOVE the lava (denser lava reached bottom)");
    if(failures){ std::printf("%d FAILURES\n",failures); return 1; }
    std::printf("engine_b_stage2_lava_sink_test OK\n"); return 0;
}
```

- [ ] **Step 2: Register + run — confirm FAIL (lava pins on water under the stale `chi` gate)**

Add the `CHEAP_TESTS` line, then run it. Expected: FAIL on "lava SANK" — the current code has
`if (chi(Mlow) < 0.5f) continue;` and `chi(water) = 0`, so the swap is skipped and lava pins on top.

- [ ] **Step 3: Add `swap_threshold` to `Globals`**

```cpp
    float swap_threshold = 1.0e3f;  // cross-species swap hysteresis (N). A vertical heavy-on-light pair swaps
                                    // when (rho_upper - rho_lower)*g*V exceeds this (canonical §2, DEC-3).
                                    // Stage-4 calibration; provisional. Replaces the stale chi>=0.5 gate.
```

- [ ] **Step 4: Replace the `chi`-gate with the force-difference threshold**

In `resolve_world`'s swap-candidate loop, find the block:

```cpp
                const Material& Mlow = (y < ny) ? Mi : Mj;          // displaced (lower) cell's material
                if (chi(Mlow) < 0.5f) continue;                    // incompressible => reflect, no swap
                cands.push_back(SwapCand{C.cx,C.cz,i, ncx,ncz,j, -dPE});
```

Replace it (the `dPE` energy-lowering check above it stays) with:

```cpp
                // Force-difference threshold swap (canonical §2, DEC-3) — NOT the stale chi>=0.5 gate.
                // Swap a vertical pair when the UPPER parcel pushes down harder than the LOWER by more than
                // a hysteresis threshold: heavy-on-light, ~ (rho_upper - rho_lower)*g*V > swap_threshold.
                // (mIi/mJj are snapshot masses; V=G.V=1 so mass==rho*V and force diff == (mUp-mLow)*g.)
                float mUp  = (y > ny) ? mIi : mJj;                  // mass of the upper cell
                float mLow = (y > ny) ? mJj : mIi;                  // mass of the lower cell
                float forceDiff = (mUp - mLow) * G.g;               // (rho_up - rho_low)*g*V
                if (forceDiff <= G.swap_threshold) continue;        // not heavy-enough-on-light => no swap
                cands.push_back(SwapCand{C.cx,C.cz,i, ncx,ncz,j, forceDiff});  // priority = force difference
```

If `Mlow` (or the now-unused `dPE`) triggers an unused-variable warning, remove its declaration. Keep the `chi`
helper itself — the EOS still uses it.

- [ ] **Step 5: Run the lava-sink test + the leveling test (the swap must not break same-species leveling)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage2_lava_sink_test && ./tests/run_tests.sh engine_b_stage2_leveling_test`
Expected: both OK. If leveling regressed, the swap is firing on same-height or same-species pairs — verify the
`mj == mi` skip and the `dPE` height-difference check are still upstream of the new gate.

- [ ] **Step 6: ⚠ Adversarial review of the swap (highest-risk conservation code, canonical §2)**

The swap is "the one piece that looks like a scatter" and "gets adversarial code review before it ships." Before
committing, invoke `superpowers:requesting-code-review` on the swap selection + Decrypt application, specifically
checking: (a) per-species mass exact (permutation), (b) one-swap-per-cell-per-tick holds, (c) flux-XOR-swap (a
swapping cell takes no additive flux), (d) no double-move across the shared face. Fix any finding before Step 7.

- [ ] **Step 7: Run the cheap tier + commit**

```bash
cd ORGE-ENGINE
./tests/run_tests.sh fast      # expected: ALL TESTS PASSED
git add core/engine_b.hpp tests/engine_b_stage2_lava_sink_test.cpp tests/run_tests.sh
git commit -m "fix(engine-b): cross-species swap uses force-difference threshold, not stale chi gate (audit #2, DEC-3)"
```

---

### Task 8: Stage-2 checkpoint — full suite, push, gitlink

- [ ] **Step 1: Full suite**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: ALL TESTS PASSED.

- [ ] **Step 2: Push engine + bump parent gitlink + push parent**

```bash
cd ORGE-ENGINE && git push origin rebuild
cd /home/claude/ORGE-B && git add ORGE-ENGINE
git commit -m "chore(engine-b): bump gitlink to Stage-2 RESOLVE (leveling + force-difference swap)"
git push origin rebuild
```

---

# STAGE 3 — DECRYPT: VACUUM relabel, min_mass cohesion, guards + in-game gate

**Stage goal:** No sub-min residue, no 0.0-mass ghost cells (audit #3); the Java `inject=orge:air` flood is gone;
conservation + bounds still hold; then the **full in-game re-audit** is the final gate.

---

### Task 9: Failing residue/ghost test (audit #3)

**Files:**
- Create: `ORGE-ENGINE/tests/engine_b_stage3_cleanup_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the failing test**

```cpp
// Build: g++ -std=c++20 -O2 -g -I. -Icore tests/engine_b_stage3_cleanup_test.cpp -o build/...
// Stage-3 audit #3 (canonical §3): after fluid drains, no cell is left labelled-but-near-empty
// (sub-min residue) and no 0.0-mass cell is left labelled a fluid (ghost). Drained cells must be VACUUM.
#include "engine_b_real_lut.hpp"
#include <cstdio>
#include <cmath>
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n", m); ++failures; } }while(0)
static void setc(Chunk& C,int x,int y,int z,uint16_t sp,float m,float T){
    int i=idx(x,y,z); C.matIx[i]=sp; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=C.vy[i]=C.vz[i]=0.f; }

int main(){
    using namespace orgeb;
    World w; rlut::RealLut M = rlut::make_real_lut(w);
    Chunk* C = w.ensureChunk(0,0);
    // A single water cell perched high with air below — it falls and drains its origin cell.
    for(int x=0;x<CHUNK_W;++x) for(int z=0;z<CHUNK_D;++z){
        setc(*C,x,10,z,M.STONE,2500.f,290.f);
        for(int y=11;y<40;++y) setc(*C,x,y,z,M.AIR,1.2f,290.f);
    }
    setc(*C,8,38,8,M.WATER,1000.f,290.f);

    for(int t=0;t<120;++t) step_world_b(w,w.materials,M.G,0.5);

    int ghosts=0, residue=0;
    for(int i=0;i<CHUNK_N;++i){
        uint16_t mat=C->matIx[i]; float m=C->mass_kg[i];
        const Material& Mt=w.materials.byIx(mat);
        bool isFluid = (mat!=M.VOID) && std::isfinite(Mt.viscosity);
        if(isFluid && m <= M.G.eps_mass) ++ghosts;                          // labelled fluid, ~0 mass
        if(isFluid && m > M.G.eps_mass && m < Mt.minMass - 1e-3f) ++residue; // labelled fluid below min_mass
    }
    CHECK(ghosts==0,  "no 0.0-mass ghost cells (drained cells relabel to VACUUM)");
    CHECK(residue==0, "no sub-min-mass residue (min_mass cohesion restored or relabelled to VACUUM)");
    if(failures){ std::printf("%d FAILURES (ghosts/residue present)\n",failures); return 1; }
    std::printf("engine_b_stage3_cleanup_test OK\n"); return 0;
}
```

- [ ] **Step 2: Register + run — confirm FAIL**

Add the `CHEAP_TESTS` line and run. Expected: FAIL on ghosts and/or residue (the current DECRYPT vacuum guard uses
`eps_mass`, not `min_mass`, so a cell drained to between `eps` and `min_mass` lingers as a labelled fragment).

- [ ] **Step 3: Commit the RED test**

```bash
cd ORGE-ENGINE && git add tests/engine_b_stage3_cleanup_test.cpp tests/run_tests.sh
git commit -m "test(engine-b): Stage-3 residue/ghost cleanup gate (audit #3, RED)"
```

---

### Task 10: DECRYPT — relabel drained cells to VACUUM at the min_mass floor (audit #3)

**Files:**
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (`decrypt_world` vacuum guard)

**The fix (canonical §3):** raise the DECRYPT drain floor from `eps_mass` to `max(eps_mass, min_mass)` for the
cell's own species, and on drain set the cell to VACUUM (`void_ix`, `mass=0`, `u=0`). This kills both 0.0-mass
ghosts and sub-min residue in one change, and stops the Java `inject=orge:air` flood (no 0-mass labelled cells
linger for Java to re-air).

- [ ] **Step 1: Change the DECRYPT vacuum guard to the min_mass floor**

In `decrypt_world`, replace the `// §D.4 vacuum guard.` block:

```cpp
            // §D.4 vacuum guard.
            if (mNew < G.eps_mass) {
                C.matIx[i]   = C.void_ix;
                C.mass_kg[i] = 0.f;
                C.T_curr[i]  = s->T[i];
                C.vx[i]=C.vy[i]=C.vz[i]=0.f;
                continue;
            }
```

with a min_mass-floor drain:

```cpp
            // §D.4 + canonical §3 cleanup: a fluid drained below its own min_mass floor cannot persist as a
            // labelled fragment (sub-min residue) or a 0.0-mass ghost. Relabel it to VACUUM. The mass that
            // LEFT this cell is conserved by the antisymmetric flux that removed it (it went to the receiver);
            // the tiny remainder (< min_mass) is released to VACUUM, which the Java reconciler reconciles (L7).
            // This also stops the Java inject=orge:air flood (no 0-mass labelled cells linger).
            float drainFloor = std::max(G.eps_mass, mats.byIx(mi).minMass);
            if (mNew < drainFloor) {
                C.matIx[i]   = C.void_ix;
                C.mass_kg[i] = 0.f;
                C.T_curr[i]  = s->T[i];     // keep a sane T (passive)
                C.vx[i]=C.vy[i]=C.vz[i]=0.f;
                continue;
            }
```

- [ ] **Step 2: Run the Stage-3 cleanup test**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage3_cleanup_test`
Expected: OK (ghosts==0, residue==0).

- [ ] **Step 3: Re-check conservation — the min_mass drain releases tiny mass to VACUUM**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_stage1_rest_test && ./tests/run_tests.sh engine_b_stage2_leveling_test`
Expected: still OK. **Watch the per-species mass assertions:** releasing sub-`min_mass` remainders to VACUUM is a
small per-species mass *decrease*. The rest test's `< 1e-2` and the leveling test's `< 1e-1` tolerances should
absorb the rare drain. If either now fails on per-species mass, the drain is firing too often (a full column
shedding cells) — that means leveling is over-draining cells below `min_mass`, a leveling-rate bug; diagnose with
`superpowers:systematic-debugging` before forcing the tolerance.

- [ ] **Step 4: Run the cheap tier + commit**

```bash
cd ORGE-ENGINE && ./tests/run_tests.sh fast
git add core/engine_b.hpp
git commit -m "fix(engine-b): DECRYPT relabels sub-min-mass drained cells to VACUUM (audit #3 residue/ghosts)"
```

---

### Task 11: Mark the deferred solid-yield seam (DEC-4) and verify the ENCRYPT Bingham no-op

**Files:**
- Modify: `ORGE-ENGINE/core/engine_b.hpp` (`encrypt_cell` — add the `// DEFERRED:` seam marker)

Per `[[engine-b-deferred-solid-yield]]` / DEC-4: `yield_stress` is a data axis + a branchless line, **0 for all
fluids ⇒ a no-op** in the fluid core. Static yield against post-RESOLVE load (sand angle-of-repose, dam burst) is a
later granular stage. We only place the loud seam marker so the next engineer knows it is intentional, not missing.

- [ ] **Step 1: Add the deferred-yield seam comment in `encrypt_cell`**

In `core/engine_b.hpp`, immediately above the gravity-impulse lines in `encrypt_cell` (`e.ugx = ux; e.ugy = uy -
dt * G.g; ...`), add:

```cpp
    // DEFERRED (canonical §4, DEC-4, [[engine-b-deferred-solid-yield]]): the Bingham yield_stress term
    // belongs HERE on the net drive force, but ENCRYPT only sees gravity+external — the dominant real load
    // on a buried solid (pressure-at-depth) is computed later in RESOLVE, so ENCRYPT cannot yield against it.
    // In the fluid core yield_stress is 0 for all fluids => this is a NO-OP. Static yield against
    // post-RESOLVE load (sand avalanche, dam burst) is a later granular stage (a DECRYPT-side gate),
    // out of scope here. Do NOT add a yield branch to make a "solid" hold — that is the deferred work.
```

- [ ] **Step 2: Compile + cheap tier (comment-only; must not change behaviour)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh fast`
Expected: ALL TESTS PASSED, byte-identical behaviour.

- [ ] **Step 3: Commit**

```bash
cd ORGE-ENGINE && git add core/engine_b.hpp
git commit -m "docs(engine-b): mark deferred solid-yield seam in ENCRYPT (DEC-4, no-op in fluid core)"
```

---

### Task 12: Retrofit `engine_b_accept_test` onto the REAL LUT (kill the synthetic-LUT trap)

**Files:**
- Modify: `ORGE-ENGINE/tests/engine_b_accept_test.cpp` (replace the drifted synthetic materials with `rlut::make_real_lut`)

The existing accept test hardcodes **drifted** values (`WAT maxMass=1100`, `LAVA defaultMass=2600`) that violate
the canonical `max_mass == default_mass` law — exactly the synthetic LUT §6 forbids. Re-point it at the shared real
fixture so it cannot mask the engine again.

- [ ] **Step 1: Replace the material setup block**

Add `#include "engine_b_real_lut.hpp"` at the top with the other includes. Replace the
`M.VOID/STONE/WAT/AIR/LAVA = w.materials.add(Material{...})` block (lines ~38–42) with:

```cpp
    rlut::RealLut RL = rlut::make_real_lut(w);
    M.VOID=RL.VOID; M.STONE=RL.STONE; M.WAT=RL.WATER; M.AIR=RL.AIR; M.LAVA=RL.LAVA;
    const orgeb::Globals& G = RL.G;   // use the fixture globals (delete the local `Globals G;` if present)
```

Where a test placed `M.LAVA, 2600.f` it must now place `M.LAVA, 3100.f` (the real lava default/max); water stays
`1000.f`. Keep each test's per-TEST scene; only the material values and Globals change.

- [ ] **Step 2: Run the accept test and reconcile expectations to the real LUT**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh engine_b_accept`
Expected: some `DEFER`/`CHECK` mass numbers shift (lava 2600→3100). Update each assertion's expected mass to the
real value. Any test that now **passes a previously-DEFERred** behaviour (buoyancy order, leveling, lava sink)
should be promoted from `DEFER` to `CHECK` — that is the Stage-2/3 work landing. Any that newly *fails* a `CHECK`
is a real regression — diagnose, do not weaken.

- [ ] **Step 3: Run the cheap tier + commit**

```bash
cd ORGE-ENGINE && ./tests/run_tests.sh fast
git add tests/engine_b_accept_test.cpp
git commit -m "test(engine-b): accept_test uses the REAL material LUT (max==default), promote landed DEFERs"
```

---

### Task 13: Stage-3 checkpoint — full suite, push, gitlink

- [ ] **Step 1: Full suite (cheap + heavy + stress)**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: ALL TESTS PASSED.

- [ ] **Step 2: Push engine + bump parent gitlink + push parent**

```bash
cd ORGE-ENGINE && git push origin rebuild
cd /home/claude/ORGE-B && git add ORGE-ENGINE
git commit -m "chore(engine-b): bump gitlink to Stage-3 DECRYPT cleanup (residue/ghosts fixed)"
git push origin rebuild
```

---

### Task 14: Rebuild the native `.so` and run the Java integration suites

- [ ] **Step 1: Locate the `.so` build command for this repo**

Run: `cd /home/claude/ORGE-B && grep -rn "liborge\|orgeStepWorld\|loadLibrary\|g++ .*-shared\|add_library" --include=*.gradle --include=*.gradle.kts --include=*.sh --include=CMakeLists.txt . | grep -iv '/build/' | head -20`
Expected: reveals how the engine shared library is built (a gradle task, a CMake target, or a `g++ -shared` line).
Use that exact command — do not invent one. (Per memory, prior runs produced `liborge.so` from the engine `jni/`
sources.)

- [ ] **Step 2: Rebuild the `.so`**

Run the discovered build command. Expected: a fresh shared library with a newer mtime than the engine sources you
just changed.

- [ ] **Step 3: Run the Java core + integration suites on the real `.so`**

Run: `cd /home/claude/ORGE-B && ./gradlew :core:test :core:integrationTest 2>&1 | tail -30`
Expected: green, `skipped=0` on the integration suite (it must run against the real library, not the stub). If a
Java test fails, read it: per-cell contracts may have shifted with the new resolver — reconcile against the
canonical spec, not by loosening a conservation assertion.

- [ ] **Step 4: Build both loaders**

Run: `cd /home/claude/ORGE-B && ./gradlew build 2>&1 | tail -15` (or the established per-loader build tasks)
Expected: both NeoForge and Fabric loaders build.

- [ ] **Step 5: Commit any Java-side reconciliation + push**

```bash
cd /home/claude/ORGE-B
git add -A
git commit -m "chore(engine-b): rebuild .so for canonical pipeline; reconcile Java integ to new resolver"
git push origin rebuild
```

---

### Task 15: In-game re-audit (the FINAL gate, canonical §6)

> **This gate is the user's, not headless.** The headless tests pass on the real LUT, but the last build was
> "headless-green but the in-game gate FAILED." Do **not** claim the pipeline done until the user re-audits.

- [ ] **Step 1: Write the in-game audit checklist to `/tmp`**

Create `/tmp/orge-engine-b-canonical-ingame-audit-checklist.md` listing the four findings as PASS/FAIL items:
1. **Leveling / horizontal flow:** place a tall water column beside a low one (or a 1-bucket pour on a flat floor)
   → water spreads and **equalizes**, then **stops** (no perpetual wander); mass looks constant.
2. **Lava sinks under water:** drop lava onto a water pool → lava **descends** below the water (does not pin on top).
3. **No residue / no ghosts:** after fluid drains/falls, **no** sub-visible water film lingers and **no**
   `inject=orge:air` log flood; broken/drained cells read as air/void cleanly.
4. **(don't regress):** no mass fabrication (1 bucket stays ~1 bucket), no cells over `max_mass`.

- [ ] **Step 2: Ask the user to run the in-game audit**

Tell the user the headless gates (Stages 1–3) are green on the real LUT and the `.so` is rebuilt, and ask them to
run the four-item checklist in-game. **Stop here and wait for their findings** — do not mark the work complete.

- [ ] **Step 3: On findings, loop**

For each in-game failure, reproduce headless-first (extend the matching Stage-2/3 real-LUT test to encode the
failure), fix against the canonical spec, conserve grand + per-species, adversarially review the swap/conservation
path if touched, then push (engine + gitlink) per `[[always-push-rebuild]]`.

---

## Self-Review (run against the spec before handing off)

**Spec coverage** (canonical pipeline + decomposition §1–§6):
- DEC-1 three un-mixed maps → Stage 1 Task 1 ✓
- DEC-2 pressure is a RESOLVE flux (not local ∇p) → preserved (engine_b.hpp already fluxes pressure; the drive map
  carries no ∇p term) ✓; the stale local-EOS `force_advect.hpp` is retired from the live path (Task 3) ✓
- DEC-3 force-difference swap, GPU-safe gather, one-swap-per-cell, flux-XOR-swap, adversarial review → Stage 2 Task 7 ✓
- DEC-4 deferred solid yield, loud seam marker → Stage 3 Task 11 ✓
- DEC-5 stage by pipeline phase, each a runnable conserving step with a real-LUT end-to-end test → Stages 1/2/3
  end-to-end gates (Tasks 2, 4+7, 9) ✓
- Audit #1 leveling/horizontal flow → Stage 2 Tasks 4–6 ✓
- Audit #2 lava sinks → Stage 2 Task 7 ✓
- Audit #3 residue/ghosts + inject flood → Stage 3 Tasks 9–10 ✓
- Audit #4 no fabrication / no overshoot → asserted in every stage's gate ✓
- §6 real-LUT, never synthetic → Task 0 fixture + Task 12 retrofit of the old accept test ✓
- JNI re-pointed off `force_advect.hpp` → Stage 1 Task 3 ✓
- Persist `u` → already on disk/in snapshot (confirmed `Chunk::vx/vy/vz`, `ChunkSnapshot::vx/vy/vz`); no task needed ✓

**Known open items folded into the plan (not gaps):**
- The leveling-vs-`max==default` tension (R3) is the Stage-2 central risk, resolved by Task 5 (overshoot band) +
  Task 6 (`K` calibration), both citing §C.5/§G.1/§G.1b. If Task 5's mechanism proves insufficient (e.g. the
  guarded-denom `p` is too stiff to tune), escalate to the user before inventing a different pressure model — the
  spec says the resolver must make leveling emerge; do not reintroduce a local-EOS shortcut.
- The Stage-1 deviation from §5's literal "no-op RESOLVE" (refactor-in-place vs gut-and-rebuild) is documented in
  Orientation; flagged for the reviewer at the Stage-1 boundary.

**Type/name consistency:** `CellEncrypt{wx,wy,wz,p,h,ugx,ugy,ugz,rho}` used identically in Task 1 (definition),
RESOLVE reads, and the encrypt test. `Globals` gains `overfill` (Task 5), `K` retuned (Task 6), `swap_threshold`
(Task 7) — all referenced consistently. `rlut::make_real_lut` / `grand_mass` / `species_mass` / `grand_energy`
defined once (Task 0) and used across Tasks 2, 4, 7, 9, 12.

**Placeholder scan:** no "TBD"/"add error handling"/"similar to Task N" — every code step shows the code; the one
discovery-driven task (Task 6 calibration) gives the concrete sweep set, the spec target, and the directional
expectation, not a blank.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-07-engine-b-canonical-pipeline.md`. Two execution
options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration
(REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`). Strongly recommended here: the swap (Task 7) and
the conservation gates demand the two-stage review this provides.

**2. Inline Execution** — execute tasks in this session with checkpoints (REQUIRED SUB-SKILL:
`superpowers:executing-plans`).

Which approach?
