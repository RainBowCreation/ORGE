# Engine-B Rebuild — Stage 1 (Force + Advect mechanical core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace engine-B's `E = ρ·h·w` energy-vector core with two clean GPU-stencil passes — **Pass A (Force→velocity)** and **Pass B (conservative Advect)** — proven mass/energy/momentum-conserving headless, while the existing conduction (`PASS_CONDUCTION`) and the Stage-2 cross-species swap are reused unchanged.

**Architecture:** New header `force_advect.hpp` is built *alongside* `engine_b.hpp` so the old path stays green until the switchover task. Pass A reads the pre-step `WorldSnapshot`, computes per-cell force `F = gravity + pressure-gradient + external`, applies the unified branchless Bingham law (`a_eff = max(0,|F|−yield)·F̂`), integrates + drags + CFL-clamps velocity, and writes **only** velocity. Pass B is the single conservation pass: antisymmetric upwind face fluxes move mass + a proportional slice of energy (`ΔE`) and momentum (`Δp`), reusing the donor-budget / receiver-room clamps and the Stage-2 PE-swap from `engine_b.hpp`. `step_world_b` is rewired to `forcePass → advectPass` (conduction stays its own pass).

**Tech Stack:** Header-only C++20, dependency-free test harness (`tests/test_harness.hpp`), `g++ -std=c++20`, `tests/run_tests.sh`, `native/build_liborge.sh` for the JNI `.so`. Spec: `docs/superpowers/specs/2026-06-06-engine-b-force-advect-conduct-design.md` (D1–D9).

> **⚠ ERRATA (2026-06-06, post-restructure — read before executing).** The repo was restructured into `core/` (engine headers), `jni/orge_jni.cpp`, `viz/` (visualizer) **after** this plan was drafted, and the dead-demo cleanup is already done + pushed (engine `06c62e6` / parent `4672f26`). Therefore, throughout this plan:
> - **Engine headers live in `core/`.** Read every bare `sim_engine.hpp` / `engine_b.hpp` / `orge_kernel.hpp` / `lut_store.hpp` path as `core/…` (e.g. "Modify `sim_engine.hpp:48`" → `core/sim_engine.hpp:48`; "Modify `engine_b.hpp:673`" → `core/engine_b.hpp:673`). The line numbers are unchanged by the move.
> - **Create `force_advect.hpp` in `core/`** (so it's part of the game-required core).
> - **Every `g++ … -I. tests/…` build command must add `-Icore`** (i.e. `-std=c++20 -O2 -g -I. -Icore tests/…`). `run_tests.sh` already has `-Icore`; just register the new tests in `CHEAP_TESTS`.
> - **`#include` lines inside source/tests stay unprefixed** (e.g. `#include "force_advect.hpp"`, `#include "engine_b.hpp"`) — they resolve via `-Icore`. Do NOT write `core/…` inside includes.
> - There is **no separate cleanup task** — that work is done. Task 0 below (yieldStress) is the first thing to execute.

**Reuse map (do NOT re-derive — call these):**
- `orgeb::chi(const Material&)` — `engine_b.hpp:38`
- `orgeb::eos_pressure(mat, mass, T, G)` — `engine_b.hpp:46` (already lowers rest density by heat)
- `orgeb::Globals` — `engine_b.hpp:21` (g, dx, V, A, eps_mass, K, gamma, alpha, T_ref)
- donor-budget + receiver-room clamp pattern — `engine_b.hpp` `resolve_world` (~`:165`–`:215`)
- Stage-2 PE-swap selection (`SwapCand`/`SwapRef`) — `engine_b.hpp` `resolve_world` (~`:245`–`:330`)
- `snapshot_world` / `WorldSnapshot` / `ChunkSnapshot` — `sim_engine.hpp:307`,`:316`,`:323`
- `idx(x,y,z)` — `sim_engine.hpp:33`; `CHUNK_W/H/D/N` — `sim_engine.hpp:22`

**New material field used this stage:** `Material.yieldStress` (added in Task 0). Defaults to 0 (pure fluid) so existing materials/tests are unaffected.

---

## File Structure

- **Create** `force_advect.hpp` — the new two-pass core (`forcePass`, `advectPass`, `stepForceAdvect`). Includes `engine_b.hpp` to reuse `chi`/`eos_pressure`/`Globals`/swap.
- **Modify** `sim_engine.hpp:48` — add `float yieldStress = 0.0f;` to `struct Material` (after `defaultMass`).
- **Modify** `engine_b.hpp:673` — rewire `step_world_b` to call the new core (Task 6).
- **Create** `tests/force_pass_test.cpp` — Pass A unit tests.
- **Create** `tests/advect_pass_test.cpp` — Pass B conservation + transport tests.
- **Create** `tests/force_advect_soak_test.cpp` — multi-step conservation soak (the headless gate).
- **Modify** `tests/run_tests.sh:36` — register the three new tests in `CHEAP_TESTS`.

---

## Task 0: Add `yieldStress` material field

**Files:**
- Modify: `sim_engine.hpp:48`
- Test: `tests/force_pass_test.cpp` (created here, first assertion only)

- [ ] **Step 1: Add the field**

In `sim_engine.hpp`, `struct Material`, immediately after the `defaultMass` line (`:48`):

```cpp
    float defaultMass = 0.0f;  // kg — EOS rest density m_0 (Engine B §B.1). Zero-inits for 6-arg aggregates.
    float yieldStress = 0.0f;  // N — Bingham yield (D7). 0 = pure fluid; +INF or large = shape-holding solid.
```

Defaulted ⇒ all existing aggregate initializers (`{hc,k,molar,min,max,visc}` and `{…,defaultMass}`) stay valid.

- [ ] **Step 2: Write a guard test that the field exists and defaults to 0**

Create `tests/force_pass_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/force_pass_test.cpp -o /tmp/force_pass_test
#include "force_advect.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
using namespace orgeb;
static int failures = 0;
#define CHECK(cond,msg) do{ if(!(cond)){ std::printf("FAIL: %s\n",msg); ++failures; } }while(0)
#define CLOSE(a,b,tol,msg) do{ if(std::fabs((double)(a)-(double)(b))>(tol)){ \
    std::printf("FAIL: %s (got %.6g want %.6g)\n",msg,(double)(a),(double)(b)); ++failures; } }while(0)

static void test_yield_field_defaults_zero(){
    Material m{4186.f, 0.6f, 0.018f, 900.f, 1000.f, 1.0e-3f};  // 6-arg: defaultMass+yieldStress default 0
    CHECK(m.yieldStress == 0.0f, "yieldStress defaults to 0 for fluids");
}

int main(){
    test_yield_field_defaults_zero();
    // (further tests appended in later tasks)
    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures ? 1 : 0;
}
```

- [ ] **Step 3: Create the stub header so the test compiles**

Create `force_advect.hpp` (minimal — fleshed out in Tasks 1–2):

```cpp
#pragma once
// Engine-B Stage-1 core: Force→Advect (spec 2026-06-06-engine-b-force-advect-conduct-design.md).
// Built ALONGSIDE engine_b.hpp; reuses chi/eos_pressure/Globals/swap from it.
#include "engine_b.hpp"
#include <cmath>
#include <algorithm>
#include <limits>
namespace orgeb {
// forcePass / advectPass / stepForceAdvect added in Tasks 1–2.
} // namespace orgeb
```

- [ ] **Step 4: Compile + run; expect PASS**

Run: `g++ -std=c++20 -O2 -g -I. tests/force_pass_test.cpp -o /tmp/force_pass_test && /tmp/force_pass_test`
Expected: `ALL PASSED`

- [ ] **Step 5: Commit**

```bash
git add sim_engine.hpp force_advect.hpp tests/force_pass_test.cpp
git commit -m "feat(engine-b): add yieldStress material field + force_advect.hpp scaffold (Stage-1 Task 0)"
```

---

## Task 1: Pass A — Force → velocity (per cell, halo reads, writes only velocity)

**Files:**
- Modify: `force_advect.hpp`
- Test: `tests/force_pass_test.cpp`

**Contract (what `forcePass` must do per cell, from the pre-step snapshot):**
1. `p_i = eos_pressure(mat_i, mass_i, T_i, G)` (own cell).
2. Acceleration `a = (0,−g,0)` + pressure-gradient `−(1/ρ_i)·∇p` (central diff over the 6 faces from neighbour `p_j`) + external (zero this stage).
3. A face whose neighbour is **frozen terrain** (`!isfinite(visc) && mass>eps`) or **absent** contributes **no** pressure-gradient term (free-slip wall, `p_j := p_i`).
4. Bingham: `Fmag=|a|·mass_i`; `excess=max(0, Fmag − yieldStress_i)`; `a_eff = (Fmag>eps)? (excess/Fmag)·a : 0`.
5. Integrate + drag: `v_new = (v_old + dt·a_eff) · 1/(1+dt·λ)`, `λ = visc_i/(ρ_i·dx²)`; frozen (`visc=∞`) ⇒ `λ=∞` ⇒ `v_new=0`.
6. CFL clamp: scale `v_new` so `|v_new|·dt ≤ dx`.
7. Vacuum cells (`mass_i ≤ eps`) keep `v=0` (nothing to move).
Writes new velocity into `Chunk::vx/vy/vz`; reads everything from `snap`.

- [ ] **Step 1: Write the failing tests** (append to `tests/force_pass_test.cpp` before `main`, and call them in `main`)

```cpp
// Helper: single-chunk world with a void@0.
static World make_world(){
    World w; w.materials.add(Material{0,0,0,0,0,0}); // void ix0
    return w;
}
static void set_cell(Chunk&C,int x,int y,int z,uint16_t ix,float m,float T,
                     float ux=0,float uy=0,float uz=0){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=ux; C.vy[i]=uy; C.vz[i]=uz;
}

// (A) Gravity accelerates an isolated fluid cell downward (−y), bounded by CFL.
static void test_gravity_pulls_down(){
    World w = make_world();
    Globals G{};
    uint16_t water = w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk& C = *w.ensureChunk(0,0);
    set_cell(C,8,40,8,water,1000.f,300.f);             // one water cell in vacuum
    forcePass(w, snapshot_world(w), w.materials, G, 0.25f);
    int i=idx(8,40,8);
    CHECK(C.vy[i] < 0.0f, "gravity gives downward velocity");
    CHECK(std::fabs(C.vy[i])*0.25f <= G.dx + 1e-4f, "CFL bounds |v|*dt <= dx");
}

// (B) A frozen (viscosity=INF) cell never moves no matter the force.
static void test_frozen_stays_put(){
    World w = make_world();
    Globals G{};
    uint16_t stone = w.materials.add(Material{800.f,2.f,0.05f,2500.f,2500.f,
                                              std::numeric_limits<float>::infinity(),2500.f,0.f});
    Chunk& C = *w.ensureChunk(0,0);
    set_cell(C,8,40,8,stone,2500.f,300.f);
    forcePass(w, snapshot_world(w), w.materials, G, 0.25f);
    int i=idx(8,40,8);
    CHECK(C.vy[i]==0.f && C.vx[i]==0.f && C.vz[i]==0.f, "frozen cell stays at rest");
}

// (C) Yield threshold: a high finite yieldStress holds against gravity until it is exceeded.
static void test_yield_holds_then_releases(){
    World w = make_world();
    Globals G{};
    // mass*g = 10*1000 = 1e4 N. yield 2e4 > that -> no motion. yield 5e3 < that -> moves.
    uint16_t hi = w.materials.add(Material{800.f,1.f,0.05f,1000.f,1000.f,5.f,1000.f,2.0e4f});
    uint16_t lo = w.materials.add(Material{800.f,1.f,0.05f,1000.f,1000.f,5.f,1000.f,5.0e3f});
    Chunk& C = *w.ensureChunk(0,0);
    set_cell(C,4,40,4,hi,1000.f,300.f);
    set_cell(C,12,40,12,lo,1000.f,300.f);
    forcePass(w, snapshot_world(w), w.materials, G, 0.25f);
    CHECK(C.vy[idx(4,40,4)]==0.f, "force below yield -> no motion");
    CHECK(C.vy[idx(12,40,12)] < 0.f, "force above yield -> moves");
}

// (D) Pressure gradient: a denser/over-full column pushes laterally into a thinner neighbour.
static void test_pressure_pushes_laterally(){
    World w = make_world();
    Globals G{};
    uint16_t water = w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk& C = *w.ensureChunk(0,0);
    // over-full cell (1000 at default 1000 vs neighbour under-full 950) at the same height.
    set_cell(C,8,40,8,water,1000.f,300.f);
    set_cell(C,9,40,8,water, 950.f,300.f);
    forcePass(w, snapshot_world(w), w.materials, G, 0.25f);
    CHECK(C.vx[idx(8,40,8)] > 0.f, "higher-pressure cell pushes toward lower-pressure (+x)");
}
```

Add to `main`: the four `test_*()` calls.

- [ ] **Step 2: Run; expect FAIL** (`forcePass` undefined)

Run: `g++ -std=c++20 -O2 -g -I. tests/force_pass_test.cpp -o /tmp/force_pass_test`
Expected: compile error `'forcePass' was not declared`.

- [ ] **Step 3: Implement `forcePass` in `force_advect.hpp`** (inside `namespace orgeb`)

```cpp
// Neighbour pressure for the pressure-gradient stencil. Free-slip wall (frozen terrain or absent
// neighbour) returns the OWN pressure p_self so the gradient term is zero across that face.
inline float neighbour_pressure(const WorldSnapshot& snap, const MaterialLUT& mats, const Globals& G,
                                int cx,int cz,int lx,int ly,int lz, float p_self){
    if (ly<0 || ly>=CHUNK_H) return p_self;                 // world top/bottom = wall
    int ncx=cx,ncz=cz, nx=lx,nz=lz;
    if(nx<0){ncx=cx-1;nx=CHUNK_W-1;} else if(nx>=CHUNK_W){ncx=cx+1;nx=0;}
    if(nz<0){ncz=cz-1;nz=CHUNK_D-1;} else if(nz>=CHUNK_D){ncz=cz+1;nz=0;}
    const ChunkSnapshot* s = snap.find(ncx,ncz);
    if(!s) return p_self;                                   // absent neighbour = wall
    int j = idx(nx,ly,nz);
    const Material& M = mats.byIx(s->matIx[j]);
    bool frozen = !std::isfinite(M.viscosity) && s->mass[j] > G.eps_mass;
    if(frozen) return p_self;                               // free-slip wall
    return eos_pressure(M, s->mass[j], s->T[j], G);
}

inline void forcePass(World& world, const WorldSnapshot& snap, const MaterialLUT& mats,
                      const Globals& G, float dt){
    for(auto& kv : world.chunks){
        Chunk& C = *kv.second;
        const ChunkSnapshot* s = snap.find(C.cx,C.cz);
        for(int z=0;z<CHUNK_D;++z) for(int y=0;y<CHUNK_H;++y) for(int x=0;x<CHUNK_W;++x){
            int i=idx(x,y,z);
            const Material& M = mats.byIx(s->matIx[i]);
            float mass = s->mass[i];
            if(mass <= G.eps_mass){ C.vx[i]=C.vy[i]=C.vz[i]=0.f; continue; } // vacuum: nothing to move
            float rho = mass / G.V;
            float p_self = eos_pressure(M, mass, s->T[i], G);
            // pressure gradient (central diff): a_press = -(1/rho)*dp/dx per axis
            float pXp=neighbour_pressure(snap,mats,G,C.cx,C.cz,x+1,y,z,p_self);
            float pXm=neighbour_pressure(snap,mats,G,C.cx,C.cz,x-1,y,z,p_self);
            float pYp=neighbour_pressure(snap,mats,G,C.cx,C.cz,x,y+1,z,p_self);
            float pYm=neighbour_pressure(snap,mats,G,C.cx,C.cz,x,y-1,z,p_self);
            float pZp=neighbour_pressure(snap,mats,G,C.cx,C.cz,x,y,z+1,p_self);
            float pZm=neighbour_pressure(snap,mats,G,C.cx,C.cz,x,y,z-1,p_self);
            float invRho = 1.0f/rho;
            float ax = -invRho*(pXp-pXm)/(2.0f*G.dx);
            float ay = -invRho*(pYp-pYm)/(2.0f*G.dx) - G.g;     // + gravity (down = -y)
            float az = -invRho*(pZp-pZm)/(2.0f*G.dx);
            // Bingham yield on the FORCE magnitude (F = m*a). Pluggable criterion seam (D8): swap the
            // next 3 lines for Mohr-Coulomb later.
            float amag = std::sqrt(ax*ax+ay*ay+az*az);
            float Fmag = amag*mass;
            float scale = 0.0f;
            if(Fmag > G.eps_mass){
                float excess = std::max(0.0f, Fmag - M.yieldStress);
                scale = excess / Fmag;                          // in [0,1]; >=yield -> partial, <yield -> 0
            }
            float ex=ax*scale, ey=ay*scale, ez=az*scale;        // a_eff
            // integrate + viscous drag (absolute); frozen visc=INF -> dragScale 0 -> v=0
            float dragScale = 0.0f;
            if(std::isfinite(M.viscosity)){
                float lambda = M.viscosity/(rho*G.dx*G.dx);
                dragScale = 1.0f/(1.0f + dt*lambda);
            }
            float vx=(s->vx[i] + dt*ex)*dragScale;
            float vy=(s->vy[i] + dt*ey)*dragScale;
            float vz=(s->vz[i] + dt*ez)*dragScale;
            // CFL clamp: |v|*dt <= dx
            float vmag=std::sqrt(vx*vx+vy*vy+vz*vz);
            float vmax=G.dx/std::max(dt,1e-6f);
            if(vmag>vmax && vmag>1e-9f){ float k=vmax/vmag; vx*=k; vy*=k; vz*=k; }
            C.vx[i]=vx; C.vy[i]=vy; C.vz[i]=vz;
        }
    }
}
```

- [ ] **Step 4: Run; expect PASS**

Run: `g++ -std=c++20 -O2 -g -I. tests/force_pass_test.cpp -o /tmp/force_pass_test && /tmp/force_pass_test`
Expected: `ALL PASSED`

- [ ] **Step 5: Commit**

```bash
git add force_advect.hpp tests/force_pass_test.cpp
git commit -m "feat(engine-b): Pass A force->velocity (gravity+pressure-grad+Bingham yield+drag+CFL) (Stage-1 Task 1)"
```

---

## Task 2: Pass B — conservative Advect (the ONLY conservation pass)

**Files:**
- Modify: `force_advect.hpp`
- Test: `tests/advect_pass_test.cpp`

**Contract (`advectPass` reads pre-step snapshot + the velocities Pass A wrote; mutates the World):**
- For each unordered same-or-cross face `(i,j)`: `u_face = ½(v_i+v_j)·n̂`; donor = upwind cell.
- `ṁ = ρ_donor·|u_face|·A·dt`, clamped to **donor remaining budget** (donor snapshot mass) and **receiver remaining room** (`max_mass − receiver mass`; vacuum receiver = ∞). Decrement both budgets.
- Carry with mass: `ΔE = ṁ·cp_donor·T_donor`; `Δp = ṁ·v_donor`.
- Apply `±ṁ, ±ΔE, ±Δp` antisymmetrically into per-cell accumulators.
- **Cross-species occupied↔occupied** faces take **no additive flux**; they are handled by the reused Stage-2 PE-swap (call the existing selection from `engine_b.hpp`). Same-species and flow-into-vacuum use the additive path.
- Commit: `mass' = mass+Σṁ`; `E' = mass·cp·T + ΣΔE`; `mom' = mass·v + ΣΔp`; then `v' = mom'/mass'`, `T' = E'/(mass'·cp)` (guard `mass' ≤ eps` ⇒ becomes vacuum, `v'=0`, `T'` retains snapshot `T`). Vacuum cell that received a single dominant species relabels to it (reuse the vacuum-claim rule from `engine_b.hpp`).

Conservation invariants the tests assert: grand mass exact; per-species mass exact; no cell exceeds its `max_mass`; grand energy (`Σ mass·cp·T`) exact under advection; a moving parcel carries its temperature (heat rides with mass).

- [ ] **Step 1: Write the failing tests**

Create `tests/advect_pass_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/advect_pass_test.cpp -o /tmp/advect_pass_test
#include "force_advect.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
using namespace orgeb;
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n",m); ++failures; } }while(0)
#define CLOSE(a,b,tol,m) do{ if(std::fabs((double)(a)-(double)(b))>(tol)){ \
    std::printf("FAIL: %s (got %.6g want %.6g)\n",m,(double)(a),(double)(b)); ++failures; } }while(0)

static void set_cell(Chunk&C,int x,int y,int z,uint16_t ix,float m,float T,
                     float ux=0,float uy=0,float uz=0){
    int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=m; C.T_curr[i]=T; C.vx[i]=ux;C.vy[i]=uy;C.vz[i]=uz; }
static double world_mass(World&w){ double t=0; for(auto&kv:w.chunks){Chunk&C=*kv.second;
    for(int i=0;i<CHUNK_N;++i) t+=C.mass_kg[i];} return t; }
static double species_mass(World&w,uint16_t sp){ double t=0; for(auto&kv:w.chunks){Chunk&C=*kv.second;
    for(int i=0;i<CHUNK_N;++i) if(C.matIx[i]==sp) t+=C.mass_kg[i];} return t; }
static double world_energy(World&w){ double e=0; for(auto&kv:w.chunks){Chunk&C=*kv.second;
    for(int i=0;i<CHUNK_N;++i){ const Material&M=w.materials.byIx(C.matIx[i]);
      e+=(double)C.mass_kg[i]*M.heatCapacity*C.T_curr[i]; } } return e; }
static double max_overshoot(World&w){ double worst=0; for(auto&kv:w.chunks){Chunk&C=*kv.second;
    for(int i=0;i<CHUNK_N;++i){ const Material&M=w.materials.byIx(C.matIx[i]); if(M.maxMass<=0)continue;
      double o=(double)C.mass_kg[i]-M.maxMass; if(o>worst)worst=o; } } return worst; }

static void one_step(World&w,const Globals&G,float dt){
    WorldSnapshot snap=snapshot_world(w);
    forcePass(w,snap,w.materials,G,dt);
    advectPass(w,snap,w.materials,G,dt);
}

// (A) grand + per-species mass exactly conserved; nothing over max; energy conserved (1 step).
static void test_conserves_one_step(){
    World w; w.materials.add(Material{0,0,0,0,0,0});
    Globals G{};
    uint16_t water=w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk&C=*w.ensureChunk(0,0);
    for(int y=40;y<44;++y) set_cell(C,8,y,8,water,1000.f,300.f);   // a short column
    double m0=world_mass(w), s0=species_mass(w,water), e0=world_energy(w);
    one_step(w,G,0.25f);
    CLOSE(world_mass(w),m0,1e-3,"grand mass conserved");
    CLOSE(species_mass(w,water),s0,1e-3,"per-species mass conserved");
    CLOSE(world_energy(w),e0,1e-1,"grand energy conserved");
    CHECK(max_overshoot(w) <= 1e-2, "no cell exceeds max_mass");
}

// (B) heat rides with mass: a hot parcel falling into a cold cell carries its temperature, it does
//     not leave a hot ghost behind (audit-#3 class).
static void test_heat_rides_with_mass(){
    World w; w.materials.add(Material{0,0,0,0,0,0});
    Globals G{};
    uint16_t water=w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk&C=*w.ensureChunk(0,0);
    set_cell(C,8,41,8,water,1000.f,500.f);          // hot cell over vacuum
    double e0=world_energy(w);
    for(int n=0;n<20;++n) one_step(w,G,0.25f);
    // mass moved down; wherever the mass now is, energy is conserved and no empty cell holds 500 K mass.
    CLOSE(world_energy(w),e0,1e0,"energy conserved as the parcel falls");
    int itop=idx(8,41,8);
    CHECK(C.mass_kg[itop] <= 1.0f || std::fabs(C.T_curr[itop]-500.f) < 50.f,
          "vacated cell carries ~no mass (no hot ghost)");
}

// (C) momentum rides with mass: a parcel given +x velocity moves in +x (mass appears to the +x side).
static void test_momentum_advects(){
    World w; w.materials.add(Material{0,0,0,0,0,0});
    Globals G{};
    uint16_t water=w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk&C=*w.ensureChunk(0,0);
    set_cell(C,4,200,8,water,1000.f,300.f, 3.0f,0,0);  // high up (no floor), moving +x
    double before_right = C.mass_kg[idx(5,200,8)];
    one_step(w,G,0.25f);
    CHECK(C.mass_kg[idx(5,200,8)] > before_right, "mass advects toward +x");
}

int main(){
    test_conserves_one_step();
    test_heat_rides_with_mass();
    test_momentum_advects();
    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures?1:0;
}
```

- [ ] **Step 2: Run; expect FAIL** (`advectPass` undefined)

Run: `g++ -std=c++20 -O2 -g -I. tests/advect_pass_test.cpp -o /tmp/advect_pass_test`
Expected: compile error `'advectPass' was not declared`.

- [ ] **Step 3: Implement `advectPass`** in `force_advect.hpp`

Build per-cell accumulators, do one antisymmetric face pass with donor-budget + receiver-room clamps, carry `ΔE`/`Δp`, then commit. Reuse the Stage-2 swap by selecting it exactly as `resolve_world` does and skipping additive flux on swap cells; apply the swap as a full snapshot exchange in the commit. (Mirror the structure of `engine_b.hpp:resolve_world`+`decrypt_world`, but the face flux is driven by `½(v_i+v_j)·n̂` instead of the energy-vector drive, and the carried cargo is `ΔE = ṁ·cp·T` + `Δp = ṁ·v` instead of `dmT`/`dpx`.)

```cpp
struct FaceAccum { double dm=0, dE=0, dpx=0, dpy=0, dpz=0; uint16_t inSp=0; float inMass=0; };

inline void advectPass(World& world, const WorldSnapshot& snap, const MaterialLUT& mats,
                       const Globals& G, float dt){
    static const int DX[6]={+1,-1,0,0,0,0}, DY[6]={0,0,+1,-1,0,0}, DZ[6]={0,0,0,0,+1,-1};
    // per-chunk accumulators + budgets + room (mirror engine_b.hpp resolve_world)
    std::unordered_map<ChunkCoord,std::vector<FaceAccum>,CoordHasher> acc;
    std::unordered_map<ChunkCoord,std::vector<float>,CoordHasher> budget, room;
    for(auto&kv:world.chunks){
        const ChunkSnapshot* s=snap.find(kv.second->cx,kv.second->cz);
        std::vector<FaceAccum> a(CHUNK_N);
        std::vector<float> b(CHUNK_N), r(CHUNK_N);
        for(int i=0;i<CHUNK_N;++i){
            b[i]=std::max(0.0f,s->mass[i]);
            const Material& M=mats.byIx(s->matIx[i]);
            bool vac = s->mass[i] <= G.eps_mass;
            r[i]= vac ? std::numeric_limits<float>::infinity() : std::max(0.0f, M.maxMass - s->mass[i]);
        }
        ChunkCoord key{kv.second->cx,kv.second->cz};
        acc.emplace(key,std::move(a)); budget.emplace(key,std::move(b)); room.emplace(key,std::move(r));
    }
    auto AT=[&](auto&map,int cx,int cz){ auto it=map.find(ChunkCoord{cx,cz}); return it==map.end()?nullptr:&it->second; };

    // NOTE: cross-species swap selection — reuse engine_b.hpp's SwapCand/SwapRef machinery verbatim.
    // For brevity here, compute `swapPartner[chunk][i]` exactly as resolve_world does (PE gate + chi
    // absorb/reflect + greedy conflict-free) and skip additive flux on any cell with a partner.

    for(auto&kv:world.chunks){
        Chunk& C=*kv.second; const ChunkSnapshot* s=snap.find(C.cx,C.cz);
        auto* ai=AT(acc,C.cx,C.cz); auto* bi=AT(budget,C.cx,C.cz);
        for(int z=0;z<CHUNK_D;++z) for(int y=0;y<CHUNK_H;++y) for(int x=0;x<CHUNK_W;++x){
            int i=idx(x,y,z);
            // if(swapPartner i) continue;   // (after wiring the swap)
            for(int f=0;f<6;++f){
                int nx=x+DX[f],ny=y+DY[f],nz=z+DZ[f];
                if(ny<0||ny>=CHUNK_H) continue;
                int ncx=C.cx,ncz=C.cz,lx=nx,lz=nz;
                if(nx<0){ncx=C.cx-1;lx=CHUNK_W-1;} else if(nx>=CHUNK_W){ncx=C.cx+1;lx=0;}
                if(nz<0){ncz=C.cz-1;lz=CHUNK_D-1;} else if(nz>=CHUNK_D){ncz=C.cz+1;lz=0;}
                const ChunkSnapshot* sj=snap.find(ncx,ncz); if(!sj) continue;
                int j=idx(lx,ny,lz);
                bool iFirst=(ncx>C.cx)||(ncx==C.cx&&ncz>C.cz)||(ncx==C.cx&&ncz==C.cz&&j>i);
                if(!iFirst) continue;
                const Material& Mi=mats.byIx(s->matIx[i]); const Material& Mj=mats.byIx(sj->matIx[j]);
                bool frozI=!std::isfinite(Mi.viscosity)&&s->mass[i]>G.eps_mass;
                bool frozJ=!std::isfinite(Mj.viscosity)&&sj->mass[j]>G.eps_mass;
                if(frozI||frozJ) continue;                       // free-slip wall
                bool occI=s->mass[i]>G.eps_mass, occJ=sj->mass[j]>G.eps_mass;
                bool crossOcc = occI&&occJ&&(s->matIx[i]!=sj->matIx[j]);
                if(crossOcc) continue;                           // handled by Stage-2 swap, no additive flux
                // face normal n̂ points from i to j (outward sign of i)
                float nxs=(float)DX[f],nys=(float)DY[f],nzs=(float)DZ[f];
                float uface=0.5f*((s->vx[i]+sj->vx[j])*nxs+(s->vy[i]+sj->vy[j])*nys+(s->vz[i]+sj->vz[j])*nzs);
                if(std::fabs(uface)<=1e-9f) continue;
                bool iIsDonor = uface>0.0f;                      // +u_face => i flows out toward j
                int dcx,dcz,di,rcx,rcz,ri; const ChunkSnapshot* sd; const ChunkSnapshot* sr;
                if(iIsDonor){ dcx=C.cx;dcz=C.cz;di=i;sd=s; rcx=ncx;rcz=ncz;ri=j;sr=sj; }
                else        { dcx=ncx;dcz=ncz;di=j;sd=sj; rcx=C.cx;rcz=C.cz;ri=i;sr=s; }
                const Material& Md=mats.byIx(sd->matIx[di]);
                float rho_d=sd->mass[di]/G.V;
                float mdot=rho_d*std::fabs(uface)*G.A*dt;
                float* bd=&(*AT(budget,dcx,dcz))[di]; float* rr=&(*AT(room,rcx,rcz))[ri];
                mdot=std::min({mdot, *bd, *rr}); if(mdot<=0.f) continue;
                *bd-=mdot; *rr-=mdot;
                double dE=(double)mdot*Md.heatCapacity*sd->T[di];
                double dpx=(double)mdot*sd->vx[di], dpy=(double)mdot*sd->vy[di], dpz=(double)mdot*sd->vz[di];
                FaceAccum& Ad=(*AT(acc,dcx,dcz))[di]; FaceAccum& Ar=(*AT(acc,rcx,rcz))[ri];
                Ad.dm-=mdot; Ad.dE-=dE; Ad.dpx-=dpx; Ad.dpy-=dpy; Ad.dpz-=dpz;
                Ar.dm+=mdot; Ar.dE+=dE; Ar.dpx+=dpx; Ar.dpy+=dpy; Ar.dpz+=dpz;
                if(mdot>Ar.inMass){ Ar.inMass=mdot; Ar.inSp=sd->matIx[di]; }   // vacuum-claim dominant donor
            }
        }
    }
    // commit
    for(auto&kv:world.chunks){
        Chunk& C=*kv.second; const ChunkSnapshot* s=snap.find(C.cx,C.cz);
        auto* a=AT(acc,C.cx,C.cz);
        for(int i=0;i<CHUNK_N;++i){
            // if(swapPartner i){ apply full snapshot exchange; continue; }
            const FaceAccum& F=(*a)[i];
            double m0=s->mass[i]; const Material& M0=mats.byIx(s->matIx[i]);
            double mNew=m0+F.dm;
            if(mNew<=G.eps_mass){ C.mass_kg[i]=0.f; C.vx[i]=C.vy[i]=C.vz[i]=0.f; /*T retained*/ continue; }
            double E0=m0*M0.heatCapacity*(double)s->T[i];
            double px0=m0*s->vx[i], py0=m0*s->vy[i], pz0=m0*s->vz[i];
            // species: vacated->refilled vacuum adopts dominant donor species (vacuum-claim)
            uint16_t sp = (m0<=G.eps_mass && F.inMass>0.f) ? F.inSp : s->matIx[i];
            const Material& M = mats.byIx(sp);
            double Enew=E0+F.dE, pxN=px0+F.dpx, pyN=py0+F.dpy, pzN=pz0+F.dpz;
            C.matIx[i]=sp;
            C.mass_kg[i]=(float)mNew;
            C.vx[i]=(float)(pxN/mNew); C.vy[i]=(float)(pyN/mNew); C.vz[i]=(float)(pzN/mNew);
            C.T_curr[i]=(float)(Enew/(mNew*M.heatCapacity));
        }
    }
}
```

> **Implementer note:** wire the Stage-2 swap before the conservation soak (Task 4). Until then the two
> `// if(swapPartner …)` lines stay commented and cross-species occupied faces are simply no-flux —
> the Task-2 tests use only same-species + vacuum, so they pass without the swap. Task 4 turns it on.

- [ ] **Step 4: Run; expect PASS**

Run: `g++ -std=c++20 -O2 -g -I. tests/advect_pass_test.cpp -o /tmp/advect_pass_test && /tmp/advect_pass_test`
Expected: `ALL PASSED`

- [ ] **Step 5: Commit**

```bash
git add force_advect.hpp tests/advect_pass_test.cpp
git commit -m "feat(engine-b): Pass B conservative advect (antisymmetric flux, dE/dp carry, budget/room clamps) (Stage-1 Task 2)"
```

---

## Task 3: Wire the Stage-2 cross-species swap into Pass B

**Files:**
- Modify: `force_advect.hpp`
- Test: `tests/advect_pass_test.cpp` (append)

- [ ] **Step 1: Write the failing test** (append + call in `main`)

```cpp
// (D) lava under water: a heavier parcel above a lighter one swaps down, conserving BOTH species
//     and the grand total (the parcel is exchanged, never converted).
static void test_cross_species_swap_conserves(){
    World w; w.materials.add(Material{0,0,0,0,0,0});
    Globals G{};
    // water lighter (1000), lava heavier (3000); both compressible enough to swap (chi>=0.5 lower cell).
    uint16_t water=w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1100.f,1.0e-3f,1000.f,0.f});
    uint16_t lava =w.materials.add(Material{1000.f,1.5f,0.090f,2900.f,3100.f,10.f,   3000.f,0.f});
    Chunk&C=*w.ensureChunk(0,0);
    set_cell(C,8,40,8,water,1000.f,300.f);   // lighter BELOW
    set_cell(C,8,41,8,lava ,3000.f,1400.f);  // heavier ABOVE -> wants to sink
    double mw0=species_mass(w,water), ml0=species_mass(w,lava), m0=world_mass(w);
    for(int n=0;n<10;++n) one_step(w,G,0.25f);
    CLOSE(species_mass(w,water),mw0,1e-2,"water mass conserved across swap");
    CLOSE(species_mass(w,lava ),ml0,1e-2,"lava mass conserved across swap");
    CLOSE(world_mass(w),m0,1e-2,"grand mass conserved across swap");
    CHECK(C.matIx[idx(8,40,8)]==lava, "heavier lava ended up lower");
}
```

- [ ] **Step 2: Run; expect FAIL** (lava stays above / per-species drifts because swap is still commented out)

Run: `g++ -std=c++20 -O2 -g -I. tests/advect_pass_test.cpp -o /tmp/advect_pass_test && /tmp/advect_pass_test`
Expected: `FAIL: heavier lava ended up lower`.

- [ ] **Step 3: Enable the swap**

Port the swap selection from `engine_b.hpp:resolve_world` (the `SwapCand`/`SwapRef` block, ~`:245`–`:330`) into `advectPass`: build `swapPartner` before the additive loop, uncomment the two `if(swapPartner i)` guards (skip additive flux on swap cells; in commit, exchange the two cells' entire snapshot contents — `matIx,mass,T,vx,vy,vz`). The PE gate (`dPE = g·(m_i−m_j)·(y_j−y_i) < 0`) and the `chi(lower) >= 0.5` absorb/reflect test are copied verbatim — a swap is a pure permutation, so per-species + grand totals are exact by construction.

- [ ] **Step 4: Run; expect PASS** (and re-run Tasks 1–2 tests — still green)

Run:
```
g++ -std=c++20 -O2 -g -I. tests/advect_pass_test.cpp -o /tmp/advect_pass_test && /tmp/advect_pass_test
g++ -std=c++20 -O2 -g -I. tests/force_pass_test.cpp  -o /tmp/force_pass_test  && /tmp/force_pass_test
```
Expected: both `ALL PASSED`.

- [ ] **Step 5: Commit**

```bash
git add force_advect.hpp tests/advect_pass_test.cpp
git commit -m "feat(engine-b): wire Stage-2 PE-swap into Pass B for cross-species (permutation, per-species exact) (Stage-1 Task 3)"
```

---

## Task 4: Rewire `step_world_b` + headless conservation soak (the gate)

**Files:**
- Modify: `engine_b.hpp:673` (`step_world_b`)
- Create: `tests/force_advect_soak_test.cpp`
- Modify: `tests/run_tests.sh:36` (register the 3 new tests)

- [ ] **Step 1: Write the failing soak test**

Create `tests/force_advect_soak_test.cpp` — a 1-bucket-falls scenario run for 400 steps asserting, every step: grand mass constant (±1e-2), per-species constant, energy constant (±1e0), no cell over `max_mass`, all values finite. (Use the helpers from `advect_pass_test.cpp`; call `stepForceAdvect(w, w.materials, dt)`.) Include a same-species "500 beside 1000 water levels out" assertion (open bug #7): after 400 steps the two adjacent water columns' heights differ by ≤ 1 cell.

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/force_advect_soak_test.cpp -o /tmp/force_advect_soak_test
#include "force_advect.hpp"
#include <cstdio>
#include <cmath>
using namespace orgeb;
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n",m); ++failures; } }while(0)
// ... (copy world_mass/species_mass/world_energy/max_overshoot/set_cell helpers) ...
int main(){
    World w; w.materials.add(Material{0,0,0,0,0,0});
    uint16_t water=w.materials.add(Material{4186.f,0.6f,0.018f,900.f,1000.f,1.0e-3f,1000.f,0.f});
    Chunk&C=*w.ensureChunk(0,0);
    /* set up: 1000 kg column 4 tall beside a 1-tall 1000 kg cell, all at y=40 floor over frozen base */
    // ... build scene ...
    double m0=world_mass(w), e0=world_energy(w);
    for(int n=0;n<400;++n){
        stepForceAdvect(w,w.materials,0.25);
        CHECK(std::fabs(world_mass(w)-m0)<1e-2, "grand mass constant each step");
        CHECK(max_overshoot(w)<=1e-2, "no overshoot each step");
    }
    CHECK(std::fabs(world_energy(w)-e0)<1e0, "energy constant over soak");
    // #7 leveling: adjacent same-species columns equalize within 1 cell — assert here
    std::printf(failures ? "\n%d FAILED\n" : "\nALL PASSED\n", failures);
    return failures?1:0;
}
```

- [ ] **Step 2: Run; expect FAIL** (`stepForceAdvect` undefined)

Run: `g++ -std=c++20 -O2 -g -I. tests/force_advect_soak_test.cpp -o /tmp/force_advect_soak_test`
Expected: compile error `'stepForceAdvect' was not declared`.

- [ ] **Step 3: Add `stepForceAdvect` and rewire `step_world_b`**

In `force_advect.hpp`:
```cpp
inline void stepForceAdvect(World& world, const MaterialLUT& mats, double dt){
    static const Globals G{};
    WorldSnapshot snap = snapshot_world(world);
    forcePass (world, snap, mats, G, (float)dt);
    advectPass(world, snap, mats, G, (float)dt);
    world.simClock += dt;
}
```
In `engine_b.hpp:681` replace the body of the default-globals `step_world_b` so the live path calls the new core:
```cpp
inline void step_world_b(World& world, const MaterialLUT& mats, double dt){
    stepForceAdvect(world, mats, dt);   // Stage-1: Force->Advect core (energy-vector core retired)
}
```
(Conduction continues to run as its own pass via `step_frame`/`compute_frame_to_backbuffers`; this stage does not touch it.)

- [ ] **Step 4: Run the soak + the full cheap tier**

Run:
```
g++ -std=c++20 -O2 -g -I. tests/force_advect_soak_test.cpp -o /tmp/force_advect_soak_test && /tmp/force_advect_soak_test
./tests/run_tests.sh fast
```
Expected: soak `ALL PASSED`; cheap tier green. **If old energy-vector tests (`engine_b_encrypt/resolve/decrypt/step/accept`) now fail because they asserted the retired `E=ρ·h·w` internals, delete or port them** — they tested the old core. Record which were removed in the commit message.

- [ ] **Step 5: Register the new tests + commit**

Add to `CHEAP_TESTS` in `tests/run_tests.sh`:
```
  "force_pass_test|tests/force_pass_test.cpp|-O2 -g"
  "advect_pass_test|tests/advect_pass_test.cpp|-O2 -g"
  "force_advect_soak_test|tests/force_advect_soak_test.cpp|-O2 -g"
```
```bash
git add force_advect.hpp engine_b.hpp tests/force_advect_soak_test.cpp tests/run_tests.sh
git commit -m "feat(engine-b): rewire step_world_b to Force->Advect core + headless conservation soak gate (Stage-1 Task 4)"
```

---

## Task 5: Rebuild the `.so`, run the Java suites, push

**Files:**
- Modify: engine submodule gitlink (parent repo), `.so` artifact

- [ ] **Step 1: Rebuild the native library**

Run: `./native/build_liborge.sh`
Expected: builds `liborge.so` with no errors (the JNI ABI is unchanged — velocity already in the snapshot; no new array).

- [ ] **Step 2: Run the engine suite at `full`**

Run: `./tests/run_tests.sh full`
Expected: cheap + heavy green, stress green.

- [ ] **Step 3: Run the Java core + integration suites on the real `.so`**

Run (from the parent repo root `/home/claude/ORGE-B`): the `:core:test` and `:core:integrationTest` gradle tasks per `orge-tiered-tests`.
Expected: `:core` green, integration green, both loaders build (skipped=0).

- [ ] **Step 4: Commit the engine submodule + bump the parent gitlink**

```bash
# in ORGE-ENGINE submodule
git add -A && git commit -m "feat(engine-b): Stage-1 Force->Advect mechanical core (D1-D9 partial: A+B)" && git push origin rebuild
# in parent repo
cd /home/claude/ORGE-B && git add ORGE-ENGINE && git commit -m "chore(engine-b): bump engine gitlink to Stage-1 Force->Advect core" && git push origin rebuild
```

- [ ] **Step 5: Update the in-game audit checklist**

Append to the engine-B in-game audit checklist: Stage-1 acceptance = *1 bucket falls, stays bounded, grand mass constant; two adjacent same-species columns level (#7)*. Mark in-game audit as the gate before Stage 2.

---

## Stage roadmap (each becomes its own plan when reached)

- **Stage 2 — Pass C conduction with the D9 multiplier.** Move conduction into the new core as Pass C with `k_face = k_base·(1+C·a_i)(1+C·a_j)`, `a = 1/(1+μ/μ_ref+yield/yield_ref)`; keep the audit-#3 discrete-maximum-principle clamp; conduct energy antisymmetrically; derive `T`. Gate: energy conserved, ONI 1/25/625 reproduced, water+lava equalise fast, sand stays solid-like.
- **Stage 3 — Bingham calibration + Mohr-Coulomb seam.** Calibrate `yieldStress` per material; dam-burst, sand angle-of-repose, avalanche rate-limit; swap `yields()` for the pressure-dependent shear criterion.
- **Stage 4 — Velocity int16 persistence (D4).** Quantize `vx/vy/vz` to int16 in the SectionStore region format (Java side); active cells only; drop for dormant.
- **Stage 5 — Global EOS/gravity/quantization calibration + GPU-dispatch portability pass.** Tune `K,γ,α,μ_ref,yield_ref,C`; confirm the three passes are authored as GPU-portable stencils; in-game audit.

---

## Self-Review

- **Spec coverage:** D1 (inertial — velocity integrated in Pass A), D2 (local EOS — `eos_pressure`, no Poisson), D5 (3-pass split — A+B here, C in Stage 2), D6 (emergent buoyancy — pressure-gradient only, no molar force term), D7 (unified Bingham — `yieldStress` field + `max(0,|F|−yield)`, no phase branch), D8 (net-force criterion + seam noted) all have Stage-1 tasks. D3 (energy advected) is realized by `ΔE` carry in Pass B + `T'=E'/(m'·cp)` commit. D9 (heat multiplier) and D4 (int16 persistence) are explicitly deferred to Stages 2/4 — noted in the roadmap, not silently dropped.
- **Placeholders:** none — every code step shows full code; the one delegated piece (swap port in Task 3) points to exact `engine_b.hpp` line ranges and is gated by a concrete failing test.
- **Type consistency:** `forcePass`/`advectPass`/`stepForceAdvect` signatures match across Tasks 1/2/4; `FaceAccum` fields (`dm,dE,dpx,dpy,dpz,inSp,inMass`) are used consistently; `Material.yieldStress` defined in Task 0 and read in Tasks 1/3.
