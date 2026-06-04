# Unified Flux + Jacobi Pressure Field — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the three species-branching advection passes (A sort / B relax / B' displace) into ONE per-face flux driven by a single Jacobi pressure field `p`, so Engine-A law L1 ("every cell runs the identical flow calculation") is literally true and the BFS/DFS irregularities are replaced by two GPU-portable stencils.

**Architecture:** A per-Chunk pressure field `p`, warm-started across steps, relaxed by K fixed Jacobi iterations per step (free-surface Dirichlet BC, gravity + overpack source). Then one flux stencil moves mass down `Φ = max(0,mass−min) + p`; the ONLY species-dependent line is the commit (same → merge, different → whole-cell swap or atomic dose+relocate — all L6-safe, no partial cross-species mix). Conservation stays structural via antisymmetric transfers, so an under-converged `p` can never create/destroy mass.

**Tech Stack:** C++20 header-only engine (`ORGE-ENGINE/sim_engine.hpp`), dependency-free test harness (`tests/*.cpp`, `tests/run_tests.sh`), JNI `.so` consumed by a Gradle multiloader (`:core`, `:fabric-1.21`, `:neoforge-1.21`).

**Spec:** `docs/superpowers/specs/2026-06-04-unified-flux-pressure-field-design.md` (read it first; laws in its "STOP" block are non-negotiable).

---

## Engine algorithm reference (read before Task 1)

All code lives in `ORGE-ENGINE/sim_engine.hpp`. `idx(x,y,z) = x + y*CHUNK_W + z*CHUNK_W*CHUNK_H`. Mass is in kg; gravity `g` is folded into mass units (matches today's `Π = compression + O`, so pressure has units of kg — do NOT introduce a separate `g`). The void/vacuum predicate is `is_vacuum(void_ix, mat, mass)`. Movability is `movable(Material)` (finite viscosity).

### Pressure recurrence (the Jacobi kernel)

For each movable, non-vacuum cell `i`, one Jacobi sweep computes a new `p_i` from the OLD `p` of its 6 face-neighbours:

```
S = 0 ; n = 0
for each face-neighbour nb (±x, ±y, ±z):
    if nb is OFF the loaded world      -> skip (Neumann edge)
    elif nb is VACUUM/free-surface     -> S += 0 ; n += 1            // Dirichlet p=0
    elif nb is FROZEN (!movable)       -> skip (Neumann wall: no flux through)
    else (movable fluid):
        contrib = p_nb
        if nb is directly BELOW i      -> contrib -= mass_i          // below must read HIGHER by i's weight
        if nb is directly ABOVE i      -> contrib += mass_nb         // above reads LOWER by nb's weight
        S += contrib ; n += 1
p_i_new = (n > 0) ? max(0, S / n) : 0
```

Why this is correct (proven against the existing `overburden_test` oracle): in a static single-species column with a free surface on top, the fixed point is `p_i = O_i` (mass of fluid above `i`) — exactly today's overburden. The vertical bias `±mass` encodes hydrostatic head; horizontal neighbours just average (equal at rest), which is what propagates head laterally for communicating vessels (any number of arms). Vacuum cells are pinned to `p=0`; frozen walls are skipped (reflecting). Buoyancy across species falls out because the vertical bias uses each cell's own mass.

### The unified flux (one calc per face)

Process each unordered face ONCE (for every cell, only its +x/+y/+z neighbour), reading both cells from a fresh post-warm-start snapshot. For face between `i` and neighbour `j`:

```
Φ_i = max(0, mass_i − min_i) + p_i           // p read from the relaxed field
Φ_j = max(0, mass_j − min_j) + p_j
buoy = 0
if the face is vertical (j directly above i) and species differ:
    buoy = BUOY_K * max(0, ρ_above − ρ_below)     // ρ = molarMass; >0 only when inverted (heavy on light)
drive = (Φ_i − Φ_j)                               // + the buoyancy term, signed so heavy sinks (see Task 7)
```

Donor = higher-Φ side (buoyancy can flip the vertical donor to the heavy upper cell). `dm = clamp( rate(visc_donor)·dt·|drive| , donor stays ≥ its min , receiver ≤ its max )`. Then the **commit** — the ONLY species-dependent code:

- **receiver same species** → MERGE: `recv.mass += dm`, `donor.mass −= dm`, enthalpy-mix `T` (additive `dMass`/`dEnth` channel).
- **receiver VACUUM** → atomic `min_mass` DOSE: receiver adopts donor species at `min_mass` (additive `dMass` + `filled`/`adopt`), donor loses `min_mass`. Single-donor claim. This grows a body into empty space.
- **receiver strictly-LIGHTER displaceable species (in-game `orge:air`)** → DOSE + ONE-HOP RELOCATE: override the receiver to (donor species, `min_mass`, donor `T`); the displaced lighter content (the air) is **relocated one hop** to an escape neighbour (down → hashed-horizontal → up; a vacuum, same-species-with-room, or yet-lighter cell) via the override channel, conserving it (the molar-gas work made air finite — it must NOT be destroyed). If **no one-hop escape exists**, SKIP the dose this step — `∇p` will have rearranged the field by next step (this is the Tier-2 substitution: local one-hop relocate + pressure field over steps **replaces** the multi-hop DFS chain). This is the through-air spreading that grows a body to `floor(M/min)`.
- **receiver DIFFERENT, non-lighter species (occupied)** → whole-cell **SWAP** (a conservative permutation, L6-safe): override cell `i` to (`j`'s species, `j`'s mass, `j`'s `T`) and cell `j` to (`i`'s species, `i`'s mass, `i`'s `T`). This is the molar-sort reorder (vertical, buoyancy-driven) and the dense-lump-displaces-lighter lateral reorder. There is **no partial cross-species mass mix** — L6 forbids a cell holding two species, so cross-species motion is either a min_mass dose (receiver fully adopts) or a whole-cell swap.

**The crux (cross-species commit modes):** the "one calculation" is the DRIVE (`Φ`); the commit has THREE L6-respecting modes — merge (same species), dose (into vacuum/lighter, receiver adopts), swap (whole-cell reorder). Picking dose-vs-swap is decided locally: if the donor has **surplus to spend** (`mass − min ≥ min`, i.e. it can afford a dose and stay ≥ min) AND the receiver is vacuum or strictly lighter → **dose** (spread/grow the body). Otherwise → **swap** (reorder without growth). All commits are antisymmetric per species (override writes are wholesale but each cell takes ≤1 write/step via the claim), so per-species mass is invariant regardless of `p` accuracy (L7). Claims: one-swap-per-cell (`ovSet` doubles as the claim) for swaps/doses; the existing `filled` claim for vacuum fills.

**The other crux (same-species VERTICAL is NOT pure Φ).** On a +Y same-species face a calm hydrostatic column has `Φ_i − Φ_j = (mass_i − mass_j) + (O_i − O_j) = mass_i > 0`, so naive Φ-leveling would push the whole column UPWARD. Gravity wants the opposite: pack mass DOWN. So the vertical same-species face is handled like the old engine, NOT by pure Φ:
- **Compaction (down):** if the lower cell `i` is below its `max`, pour from the upper cell `j` down into `i` (donor = upper), `dm = min(mass_j, max_i − mass_i)`, enthalpy-mix `T`. (Lift the proven logic at `sim_engine.hpp` ~482-498, the old Pass A same-species compaction.)
- **Flood-guard (up):** only push `i`→`j` (upward) when `i` carries EXCESS head beyond a calm column: `excess = (Φ_i − Φ_j) − mass_i`; if `excess ≤ eps` skip (calm column never climbs); else `dm = clamp(rate·dt·excess, donor≥min, recv≤max)`. This is the U-tube far-arm rise. (Lift `sim_engine.hpp` ~824-851, the old Pass B +Y flood-guard, swapping the live `O` read for `C.pressure`.)
Horizontal same-species faces (`f==0`, `f==2`) ARE pure Φ-leveling — that is communicating vessels.

### Constants (add to the `orge` namespace)

`ADV_PRESSURE_K` (Jacobi iterations/step, start `4`) and `BUOY_K` (vertical buoyancy coefficient, start `1.0`) are **calibration constants**, not placeholders — Task 15 tunes them against the oracle suite.

---

## File structure

- **`ORGE-ENGINE/sim_engine.hpp`** — ALL engine changes live here (header-only pattern; do not split during this delicate rewrite). New: `Chunk::pressure`, `relax_pressure()`, `advect_unified()`, two `orge` constants, rewritten `advect_world()`. Deleted (Stage 4): `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, `compute_overburden`, `compute_overburden_samespecies`, `overburden_at`, `OverburdenMap`.
- **`ORGE-ENGINE/tests/pressure_field_test.cpp`** — NEW. Unit tests for `relax_pressure` (static-column == overburden; U-tube equalizes; flat pool zero horizontal gradient).
- **`ORGE-ENGINE/tests/multi_arm_vessels_test.cpp`** — NEW. The previously-banked 3-arm equalization win (spec §7.5).
- **`ORGE-ENGINE/tests/flat_pool_no_creep_test.cpp`** — NEW. Spec §7.2 / §3 zero-creep gate.
- **`ORGE-ENGINE/tests/run_tests.sh`** — register the three new tests in the CHEAP tier.
- **Existing oracle tests (unchanged unless sign-off):** `overburden_test`, `u_tube_test`, `lava_water_equilibrium_test`, `sort_swap_test`, `displace_test`, `spread_2d_test`, `min_mass_occupancy_test`, `relax_spread_test`, `vertical_merge_test`, `push_chain_tube_test`, `bprime_evacuate_test`, `viscosity_flow_test`, `unified_basics_test`, `injection_test`, `time_dt_test`, `hydrostatic_test`.
- **Java side:** NO changes (JNI ABI / resident-LUT untouched). `core/src/main/resources/natives/linux-x64/liborge.so` is rebuilt (Stage 6).

**Strategy:** build `relax_pressure` + `advect_unified` ALONGSIDE the old passes behind a switch (`advect_world` calls the new path), keep the old functions compiling as a safety net through Stage 3, then delete them in Stage 4 once the full oracle suite is green on the new path.

---

## Stage 1 — Pressure field foundation

### Task 1: Add the pressure field storage + constants

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`Chunk` struct ~130-159; `orge` constants — find where `ADV_CFL_CAP`/`ADV_SPREAD_K` are defined in `orge_kernel.hpp` or near the top of `sim_engine.hpp`)

- [ ] **Step 1: Add `pressure` to `Chunk`.** In the `Chunk` struct, add the field next to `mass_kg`:

```cpp
    std::vector<float> mass_kg;
    std::vector<float> pressure;   // kg-units hydrostatic+overpack pressure; warm-started across steps
```

And initialise it in the `Chunk()` constructor initialiser list (after `mass_kg(CHUNK_N, 0.0f)`):

```cpp
        , mass_kg(CHUNK_N, 0.0f)
        , pressure(CHUNK_N, 0.0f)
```

- [ ] **Step 2: Add the two constants.** Locate the `orge` namespace constants (grep `ADV_CFL_CAP`). Add:

```cpp
constexpr int   ADV_PRESSURE_K = 4;     // fixed Jacobi iterations per step (spec §5; Task 15 tunes)
constexpr float BUOY_K         = 1.0f;  // vertical buoyancy coefficient (spec §4; Task 15 tunes)
```

- [ ] **Step 3: Verify it still builds.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh`
  Expected: all CHEAP tests still PASS (the field is unused so far).

- [ ] **Step 4: Commit.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp
git -C ORGE-ENGINE commit -m "feat(engine): add Chunk::pressure field + Jacobi K / buoyancy constants"
```

### Task 2: `relax_pressure` — static column reproduces overburden

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add `relax_pressure` ABOVE `advect_world`, after the snapshot helpers ~362)
- Create: `ORGE-ENGINE/tests/pressure_field_test.cpp`

- [ ] **Step 1: Write the failing test.** Create `tests/pressure_field_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>

static int failures = 0;
#define CHECK(cond, msg) do { if(!(cond)){ std::printf("FAIL: %s\n", msg); ++failures; } } while(0)

static void set_cell(Chunk& C, int x,int y,int z, uint16_t ix, float mass, float T){
    int i = idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=mass; C.T_curr[i]=T;
}
struct Mats { uint16_t VOID, WATER; };
static Mats add_mats(World& w){
    Mats m;
    m.VOID  = w.materials.add(Material{0.f,0.f,0.0f,0.f,0.f,0.0f});
    m.WATER = w.materials.add(Material{4186.f,0.6f,0.180f,125.f,1000.f,0.001f});
    return m;
}

static void test_static_column_equals_overburden(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    // A single water column x=8,z=8, y=10..14 full (1000 kg); y=15 vacuum (free surface).
    for(int y=10;y<=14;++y) set_cell(*C,8,y,8,m.WATER,1000.f,300.f);
    // Relax to convergence (many iterations to reach the fixed point for the oracle).
    for(int it=0; it<200; ++it) relax_pressure(w, w.materials);
    // p_i must equal the mass of water ABOVE i: top cell y=14 -> 0, y=13 -> 1000, ... y=10 -> 4000.
    auto P=[&](int y){ return C->pressure[idx(8,y,8)]; };
    CHECK(std::fabs(P(14) - 0.0f)    < 1.0f, "p top == 0");
    CHECK(std::fabs(P(13) - 1000.0f) < 1.0f, "p == 1000 (one cell above)");
    CHECK(std::fabs(P(12) - 2000.0f) < 1.0f, "p == 2000");
    CHECK(std::fabs(P(11) - 3000.0f) < 1.0f, "p == 3000");
    CHECK(std::fabs(P(10) - 4000.0f) < 1.0f, "p == 4000 (bottom)");
}

int main(){
    test_static_column_equals_overburden();
    if(failures){ std::printf("\n%d CHECK(s) failed\n", failures); return 1; }
    std::printf("\nall pressure_field checks passed\n"); return 0;
}
```

- [ ] **Step 2: Run it to verify it fails.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: FAIL to compile — `relax_pressure` is not declared yet.

- [ ] **Step 3: Implement `relax_pressure`.** Add ABOVE `advect_world` (and above where `advect_world` is defined; after `snapshot_world`):

```cpp
// ====== Jacobi pressure relaxation (spec 2026-06-04 §2.1) ======
//   One sweep updates every movable, non-vacuum cell's pressure from the OLD p of its 6 face
//   neighbours: free surfaces are pinned p=0 (Dirichlet), frozen walls are skipped (Neumann), and
//   a vertical neighbour carries a ±mass bias so the fixed point is hydrostatic head (p == overburden
//   in a static column) while horizontal neighbours average (communicating vessels). Reads p from a
//   per-chunk SNAPSHOT (old field) and writes the new field, so each sweep is a pure stencil
//   (GPU-portable). Pressure only sets flux DIRECTION/RATE; conservation is independent of it.
inline void relax_pressure_sweep(World& world, const MaterialLUT& mats) {
    static const int ND[6][3] = {{+1,0,0},{-1,0,0},{0,+1,0},{0,-1,0},{0,0,+1},{0,0,-1}};
    // Snapshot the OLD pressure per chunk (Jacobi, not Gauss-Seidel -> read old, write new).
    std::unordered_map<ChunkCoord, std::vector<float>, CoordHasher> oldP;
    for (auto& kv : world.chunks) oldP[ChunkCoord{kv.second->cx, kv.second->cz}] = kv.second->pressure;
    auto pAt = [&](int cx,int cz,int i)->float {
        auto it = oldP.find(ChunkCoord{cx,cz}); return (it==oldP.end()) ? 0.0f : it->second[i];
    };
    for (auto& kv : world.chunks) {
        Chunk& C = *kv.second; const int cx=C.cx, cz=C.cz;
        for (int z=0; z<CHUNK_D; ++z)
        for (int y=0; y<CHUNK_H; ++y)
        for (int x=0; x<CHUNK_W; ++x) {
            const int i = idx(x,y,z);
            const uint16_t mi = C.matIx[i];
            const float massi = C.mass_kg[i];
            if (is_vacuum(C.void_ix, mi, massi)) { C.pressure[i] = 0.0f; continue; } // Dirichlet 0
            if (!movable(mats.byIx(mi)))         { C.pressure[i] = 0.0f; continue; } // wall: inert
            float S = 0.0f; int n = 0;
            for (auto& d : ND) {
                int ncx,ncz,nlx,nly,nlz;
                if (!resolve_neighbor(cx,cz,x,y,z, d[0],d[1],d[2], ncx,ncz,nlx,nly,nlz)) continue; // edge
                const Chunk* CN = world.findChunk(ncx,ncz);
                if (!CN) continue;                                   // off-world -> Neumann edge
                const int ni = idx(nlx,nly,nlz);
                const uint16_t nm = CN->matIx[ni];
                const float nmass = CN->mass_kg[ni];
                if (is_vacuum(CN->void_ix, nm, nmass)) { S += 0.0f; n += 1; continue; } // free surface
                if (!movable(mats.byIx(nm))) continue;               // frozen wall -> skip (Neumann)
                float contrib = pAt(ncx,ncz,ni);
                if (d[1] == -1) contrib -= massi;                    // neighbour BELOW reads higher by i's weight
                else if (d[1] == +1) contrib += nmass;               // neighbour ABOVE reads lower by its weight
                S += contrib; n += 1;
            }
            C.pressure[i] = (n>0) ? std::max(0.0f, S / static_cast<float>(n)) : 0.0f;
        }
    }
}
// K fixed Jacobi sweeps per step (warm-started: caller does NOT clear C.pressure between steps).
inline void relax_pressure(World& world, const MaterialLUT& mats, int K = orge::ADV_PRESSURE_K) {
    for (int k=0; k<K; ++k) relax_pressure_sweep(world, mats);
}
```

- [ ] **Step 4: Run it to verify it passes.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: `all pressure_field checks passed`.

- [ ] **Step 5: Commit.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): relax_pressure Jacobi sweep — static column == overburden"
```

### Task 3: `relax_pressure` — U-tube equalizes + flat pool has zero horizontal gradient

**Files:**
- Modify: `ORGE-ENGINE/tests/pressure_field_test.cpp`

- [ ] **Step 1: Add two failing tests.** Append before `main()` and call them from `main()`:

```cpp
static void test_utube_pressure_equalizes(){
    // Two water columns of UNEQUAL height connected at the bottom row -> bottom pressures must
    // converge (communicating vessels). Walls on the sides; tops open (vacuum).
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    constexpr float INF = std::numeric_limits<float>::infinity();
    uint16_t WALL = w.materials.add(Material{800.f,2.f,9.f,2500.f,2500.f,INF});
    const int z=8;
    // Wall box x in [5,9], carve two arms x=6 (tall, y=10..15) and x=8 (short, y=10..12), bottom y=10 x6..8.
    for(int x=5;x<=9;++x) for(int y=9;y<=16;++y) set_cell(*C,x,y,z,WALL,2500.f,300.f);
    for(int y=10;y<=15;++y) set_cell(*C,6,y,z,m.WATER,1000.f,300.f);
    for(int x=6;x<=8;++x)   set_cell(*C,x,10,z,m.WATER,1000.f,300.f);
    for(int y=11;y<=12;++y) set_cell(*C,8,y,z,m.WATER,1000.f,300.f);
    set_cell(*C,7,10,z,m.WATER,1000.f,300.f);          // bottom connector
    set_cell(*C,6,16,z,m.VOID,0,0); set_cell(*C,8,13,z,m.VOID,0,0); // open tops
    for(int it=0; it<400; ++it) relax_pressure(w, w.materials);
    // The two bottom-of-arm cells (x=6,y=10) and (x=8,y=10) connect through x=7,y=10 -> equal pressure.
    float pL = C->pressure[idx(6,10,z)], pR = C->pressure[idx(8,10,z)];
    CHECK(std::fabs(pL - pR) < 50.0f, "U-tube bottom pressures equalize through the connector");
    CHECK(pL > 0.0f, "U-tube bottom pressure is positive (head present)");
}

static void test_flat_pool_zero_horizontal_gradient(){
    // A flat, settled, single-layer water pool: every cell has the SAME free surface above (vacuum),
    // so horizontal pressure differences must be ~0 (no creep driver). Spec §3 / §7.2.
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8, y=10;
    for(int x=4;x<=11;++x) set_cell(*C,x,y,z,m.WATER,250.f,300.f);  // one flat row on the floor
    for(int it=0; it<200; ++it) relax_pressure(w, w.materials);
    for(int x=5;x<=11;++x){
        float d = std::fabs(C->pressure[idx(x,y,z)] - C->pressure[idx(x-1,y,z)]);
        CHECK(d < 1.0f, "flat pool: adjacent horizontal pressures are equal (no creep)");
    }
}
```

Add to `main()`:

```cpp
    test_utube_pressure_equalizes();
    test_flat_pool_zero_horizontal_gradient();
```

- [ ] **Step 2: Run to verify (these should already PASS if Task 2's recurrence is right).** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: `all pressure_field checks passed`. If the U-tube test fails to converge, increase the iteration loop in the test only (the engine K stays 4 — Task 15). If the flat-pool test fails, STOP — the free-surface BC is wrong (spec §9 first open question); escalate before proceeding.

- [ ] **Step 3: Register the test in the CHEAP tier.** In `tests/run_tests.sh`, add to the `CHEAP_TESTS=(` array:

```bash
  "pressure_field_test|tests/pressure_field_test.cpp|-O2 -g"
```

- [ ] **Step 4: Run the cheap tier.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh`
  Expected: all CHEAP tests pass, including `pressure_field_test`.

- [ ] **Step 5: Commit.**

```bash
git -C ORGE-ENGINE add tests/pressure_field_test.cpp tests/run_tests.sh
git -C ORGE-ENGINE commit -m "test(engine): pressure field U-tube equalization + flat-pool zero-creep"
```

---

## Stage 2 — The unified flux (built in layers against existing oracles)

`advect_unified` is added new and grown layer by layer. Each task adds one capability and is gated by an EXISTING oracle test run against the new path. Wire the new path in Task 4 behind a default-off switch so the old passes remain the safety net.

### Task 4: `advect_unified` skeleton + horizontal same-species leveling

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add `advect_unified` after `relax_pressure`; add a switched call site)

- [ ] **Step 1: Write the failing test.** Append to `tests/pressure_field_test.cpp` and call from `main()`:

```cpp
static void test_flux_horizontal_leveling(){
    // Two adjacent same-species water cells, one heavy one light -> levels toward the middle (mass moves
    // from higher Φ to lower Φ). Drives via advect_unified with a fresh relaxed pressure.
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8, y=10;
    set_cell(*C,8,y,z,m.WATER,900.f,300.f);
    set_cell(*C,9,y,z,m.WATER,300.f,300.f);
    const float before = C->mass_kg[idx(8,y,z)] + C->mass_kg[idx(9,y,z)];
    for(int step=0; step<50; ++step){
        relax_pressure(w, w.materials);
        advect_unified(w, w.materials, orge::DT_CFL);
    }
    const float after = C->mass_kg[idx(8,y,z)] + C->mass_kg[idx(9,y,z)];
    CHECK(std::fabs(after - before) < 1e-2f, "leveling conserves total mass");
    CHECK(C->mass_kg[idx(9,y,z)] > 300.0f, "light cell gained mass (leveled toward it)");
    CHECK(C->mass_kg[idx(8,y,z)] < 900.0f, "heavy cell lost mass");
}
```

- [ ] **Step 2: Run to verify it fails.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: FAIL to compile — `advect_unified` undeclared.

- [ ] **Step 3: Implement the skeleton + leveling layer.** Add after `relax_pressure`. Reuse the existing `BAccum`, `snapshot_world`, `apply_baccum`, `resolve_neighbor`, `rate`, `is_vacuum`, `movable`:

```cpp
// ====== advect_unified — the single per-face flux (spec 2026-06-04 §2.2/§2.3) ======
//   ONE calculation per face. Φ = max(0,mass−min)+p. Donor = higher Φ. dm = clamp(rate·dt·drive,
//   donor≥min, recv≤max). Commit is the ONLY species-dependent line: same -> merge, vacuum -> atomic
//   dose, different -> whole-cell swap or dose+relocate (L6-safe). Conservative regardless of p. Grown in layers
//   across the plan; this revision handles same-species occupied<->occupied leveling.
inline void advect_unified(World& world, const MaterialLUT& mats, double dt) {
    static const int FACE_DX[3] = {+1,0,0};
    static const int FACE_DY[3] = {0,+1,0};
    static const int FACE_DZ[3] = {0,0,+1};
    const WorldSnapshot snap = snapshot_world(world);
    BAccum acc;
    for (const auto& kv : world.chunks) {
        const Chunk& C = *kv.second; const int cx=C.cx, cz=C.cz;
        const ChunkSnapshot* si = snap.find(cx,cz); if(!si) continue;
        acc.ensure(cx,cz);
        for (int z=0; z<CHUNK_D; ++z)
        for (int y=0; y<CHUNK_H; ++y)
        for (int x=0; x<CHUNK_W; ++x) {
            const int ii = idx(x,y,z);
            const uint16_t mati = si->matIx[ii];
            const float massi = si->mass[ii];
            const bool ivac = is_vacuum(C.void_ix, mati, massi);
            for (int f=0; f<3; ++f) {
                int ncx,ncz,lx,ny,lz;
                if (!resolve_neighbor(cx,cz,x,y,z, FACE_DX[f],FACE_DY[f],FACE_DZ[f], ncx,ncz,lx,ny,lz)) continue;
                const ChunkSnapshot* sj = snap.find(ncx,ncz); if(!sj) continue;
                const Chunk* CJ = world.findChunk(ncx,ncz); if(!CJ) continue;
                const int jj = idx(lx,ny,lz);
                const uint16_t matj = sj->matIx[jj];
                const float massj = sj->mass[jj];
                const bool jvac = is_vacuum(CJ->void_ix, matj, massj);
                // LAYER 1: same-species occupied<->occupied HORIZONTAL leveling only.
                if (ivac || jvac) continue;            // vacuum handled in Task 5
                if (mati != matj) continue;            // cross-species handled in Tasks 6/7
                if (f == 1) continue;                  // vertical same-species: compaction/flood-guard, Step 7
                const Material& mF = mats.byIx(mati);
                if (!movable(mF)) continue;
                const float pi = C.pressure[ii];        // relaxed pressure (read live; not snapped)
                const float pj = CJ->pressure[jj];
                const float Phi_i = std::max(0.0f, massi - mF.minMass) + pi;
                const float Phi_j = std::max(0.0f, massj - mF.minMass) + pj;
                const float drive = Phi_i - Phi_j;
                if (std::fabs(drive) <= orge::ADV_EPS_MASS) continue;
                const bool donorIsI = drive > 0.0f;
                const int dcx=donorIsI?cx:ncx, dcz=donorIsI?cz:ncz, didx=donorIsI?ii:jj;
                const int rcx=donorIsI?ncx:cx, rcz=donorIsI?ncz:cz, ridx=donorIsI?jj:ii;
                const float donorMass = donorIsI?massi:massj;
                const float recvMass  = donorIsI?massj:massi;
                const float donorT    = donorIsI? si->T[ii] : sj->T[jj];
                float frac = rate(mF.viscosity) * static_cast<float>(dt);
                if (frac > 0.5f) frac = 0.5f;
                float dm = frac * std::fabs(drive);
                dm = std::min(dm, donorMass - mF.minMass);     // donor floor
                dm = std::min(dm, mF.maxMass - recvMass);      // receiver cap
                if (dm <= orge::ADV_EPS_MASS) continue;
                acc.ensure(dcx,dcz); acc.ensure(rcx,rcz);
                acc.dMass[ChunkCoord{dcx,dcz}][didx] -= dm;
                acc.dEnth[ChunkCoord{dcx,dcz}][didx] -= dm*donorT;
                acc.dMass[ChunkCoord{rcx,rcz}][ridx] += dm;
                acc.dEnth[ChunkCoord{rcx,rcz}][ridx] += dm*donorT;
            }
        }
    }
    apply_baccum(world, acc);
}
```

- [ ] **Step 4: Run to verify it passes.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: `all pressure_field checks passed`.

- [ ] **Step 5: Commit.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_unified skeleton + same-species HORIZONTAL leveling"
```

- [ ] **Step 6: Write the vertical same-species test (compaction down + calm column stays put).** Append + call:

```cpp
static void test_flux_vertical_compaction(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8,x=8;
    // Lower cell half-full, upper cell half-full, same species: must compact DOWN (lower -> max).
    set_cell(*C,x,10,z,m.WATER,500.f,300.f);
    set_cell(*C,x,11,z,m.WATER,500.f,300.f);
    const float before = C->mass_kg[idx(x,10,z)] + C->mass_kg[idx(x,11,z)];
    for(int step=0; step<50; ++step){ relax_pressure(w,w.materials); advect_unified(w,w.materials,orge::DT_CFL); }
    CHECK(std::fabs((C->mass_kg[idx(x,10,z)]+C->mass_kg[idx(x,11,z)]) - before) < 1e-2f, "compaction conserves mass");
    CHECK(C->mass_kg[idx(x,10,z)] > 900.f, "lower cell packed toward max (compaction DOWN, not up)");
}
```

- [ ] **Step 7: Implement vertical same-species (compaction down + flood-guard up).** Add a branch for `f==1 && same-species` (insert just before the `if (f == 1) continue;` horizontal skip — i.e. handle vertical here instead of skipping). Lift the proven logic per the algorithm reference (compaction ~482-498, flood-guard ~824-851):

```cpp
                if (!ivac && !jvac && mati == matj && f == 1) {
                    // j is directly ABOVE i. Same species: compaction DOWN, else flood-guard UP.
                    const Material& mV = mats.byIx(mati);
                    if (!movable(mV)) continue;
                    if (massi < mV.maxMass) {
                        // COMPACTION: pour from upper j down into i, up to i's headroom (gravity fill).
                        const float dm = std::min(massj, mV.maxMass - massi);
                        if (dm <= orge::ADV_EPS_MASS) continue;
                        acc.ensure(cx,cz); acc.ensure(ncx,ncz);
                        acc.dMass[ChunkCoord{ncx,ncz}][jj] -= dm; acc.dEnth[ChunkCoord{ncx,ncz}][jj] -= dm*sj->T[jj];
                        acc.dMass[ChunkCoord{cx,cz}][ii]   += dm; acc.dEnth[ChunkCoord{cx,cz}][ii]   += dm*sj->T[jj];
                        continue;
                    }
                    // FLOOD-GUARD: i is full; only push UP on excess head beyond a calm column.
                    const float Phi_i = std::max(0.0f, massi - mV.minMass) + C.pressure[ii];
                    const float Phi_j = std::max(0.0f, massj - mV.minMass) + CJ->pressure[jj];
                    const float excess = (Phi_i - Phi_j) - massi;    // calm column -> 0 -> never climbs
                    if (excess <= orge::ADV_EPS_MASS) continue;
                    float frac = rate(mV.viscosity) * static_cast<float>(dt);
                    if (frac > 0.5f) frac = 0.5f;
                    float dm = frac * excess;
                    dm = std::min(dm, massi - mV.minMass);            // donor floor
                    dm = std::min(dm, mV.maxMass - massj);            // receiver cap
                    if (dm <= orge::ADV_EPS_MASS) continue;
                    acc.ensure(cx,cz); acc.ensure(ncx,ncz);
                    acc.dMass[ChunkCoord{cx,cz}][ii]   -= dm; acc.dEnth[ChunkCoord{cx,cz}][ii]   -= dm*si->T[ii];
                    acc.dMass[ChunkCoord{ncx,ncz}][jj] += dm; acc.dEnth[ChunkCoord{ncx,ncz}][jj] += dm*si->T[ii];
                    continue;
                }
```

- [ ] **Step 8: Run to verify.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test && ./tests/run_tests.sh`
  Expected: green (compaction + the earlier U-tube/flat-pool tests all pass).

- [ ] **Step 9: Commit.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_unified same-species vertical compaction + flood-guard"
```

### Task 5: Vacuum/air budding (atomic min_mass dose + single-donor claim)

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`advect_unified` — replace the `if (ivac || jvac) continue;` guard with the vacuum-fill branch)

- [ ] **Step 1: Add the failing oracle.** This capability is already covered by the existing `spread_2d_test` and `min_mass_occupancy_test` (a free body must reach `floor(M/min)` tiles through vacuum/air). Build them against the CURRENT engine first to confirm they pass on the OLD path, so you know the oracle is meaningful: Run: `cd ORGE-ENGINE && ./tests/run_tests.sh spread_2d && ./tests/run_tests.sh min_mass_occupancy`
  Expected: PASS (old path). These are the oracle you must keep green after the swap (Stage 3).

- [ ] **Step 2: Implement the vacuum-fill branch.** In `advect_unified`, replace `if (ivac || jvac) continue;` and the `if (mati != matj) continue;` lines with:

```cpp
                const bool oneVac = (ivac != jvac);
                const bool sameSp = (!ivac && !jvac && mati == matj);
                if (!oneVac && !sameSp) continue;       // cross-species occupied -> Tasks 6/7
                if (oneVac) {
                    // Atomic min_mass budding into vacuum. Donor is the fluid side; gated by ∇p
                    // pushing toward the empty cell (the fluid cell's Φ exceeds the vacuum's p=0)
                    // and donor affordability. Single-donor claim: at most one donor per vacuum/step.
                    const uint16_t fluidSp = ivac ? matj : mati;
                    const Material& mFl = mats.byIx(fluidSp);
                    if (!movable(mFl)) continue;
                    const bool donorIsI = !ivac;        // fluid side donates into the vacuum side
                    const int dcx=donorIsI?cx:ncx, dcz=donorIsI?cz:ncz, didx=donorIsI?ii:jj;
                    const int rcx=donorIsI?ncx:cx, rcz=donorIsI?ncz:cz, ridx=donorIsI?jj:ii;
                    const float donorMass = donorIsI?massi:massj;
                    const float donorT    = donorIsI? si->T[ii] : sj->T[jj];
                    // No upward vacuum-budding (gravity owns vertical): f==1 is +Y; donor below -> skip.
                    if (f==1 && donorIsI) continue;
                    const float dose = mFl.minMass;
                    if (donorMass - dose < mFl.minMass - 1e-2f) continue;   // donor must stay >= its min
                    acc.ensure(rcx,rcz);
                    auto& fr = acc.filled[ChunkCoord{rcx,rcz}];
                    if (fr[ridx]) continue;             // already claimed this step
                    fr[ridx] = 1;
                    acc.adopt[ChunkCoord{rcx,rcz}][ridx] = fluidSp;
                    acc.ensure(dcx,dcz);
                    acc.dMass[ChunkCoord{dcx,dcz}][didx] -= dose;
                    acc.dEnth[ChunkCoord{dcx,dcz}][didx] -= dose*donorT;
                    acc.dMass[ChunkCoord{rcx,rcz}][ridx] += dose;
                    acc.dEnth[ChunkCoord{rcx,rcz}][ridx] += dose*donorT;
                    continue;
                }
                // same-species leveling (Layer 1) continues below unchanged.
```

(Leave the existing Layer-1 leveling code that follows intact.)

- [ ] **Step 3: Re-run the unit test (leveling unaffected).** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test`
  Expected: still `all pressure_field checks passed`.

- [ ] **Step 4: Add a budding unit test.** Append to `tests/pressure_field_test.cpp` and call from `main()`:

```cpp
static void test_flux_buds_into_vacuum(){
    World w; Mats m = add_mats(w);
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8, y=10;
    set_cell(*C,8,y,z,m.WATER,1000.f,300.f);          // one rich cell; neighbour x=9 is vacuum
    const float before = C->mass_kg[idx(8,y,z)];
    for(int step=0; step<50; ++step){ relax_pressure(w,w.materials); advect_unified(w,w.materials,orge::DT_CFL); }
    float total=0; for(int i=0;i<CHUNK_N;++i) if(C->matIx[i]==m.WATER) total+=C->mass_kg[i];
    CHECK(std::fabs(total - before) < 1e-1f, "budding conserves total water mass");
    CHECK(C->matIx[idx(9,y,z)] == m.WATER && C->mass_kg[idx(9,y,z)] >= 125.f, "vacuum cell budded a min_mass tile");
}
```

- [ ] **Step 5: Run + commit.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh` (expect green), then:

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_unified vacuum budding (atomic dose + single-donor claim)"
```

### Task 6: Cross-species vertical gravity sort (whole-cell swap, replaces Pass A)

The gravity molar-sort becomes a whole-cell SWAP on the vertical face — a conservative permutation, L6-safe (no partial cross-species mass mix). One swap per cell per step (claim) → one-cell-per-step descent, exactly the old Pass A behavior.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`advect_unified` — the cross-species branch)

- [ ] **Step 1: Pin the gravity oracle on the old path.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh sort_swap`
  Expected: PASS. Defines "heavy sinks one cell/step".

- [ ] **Step 2: Implement the vertical swap.** In `advect_unified`, replace `if (!oneVac && !sameSp) continue; // cross-species occupied -> Tasks 6/7` with a vertical-only whole-cell swap on molar inversion:

```cpp
                if (!sameSp) {
                    // CROSS-SPECIES gravity reorder by whole-cell SWAP (L6-safe permutation; NO partial
                    // cross-species mix). VERTICAL face only here: f==1 is +Y, so j is directly ABOVE i.
                    // Swap iff the upper cell is strictly heavier (molar inversion = gravitationally
                    // unstable). A stable column (upper lighter/equal) does nothing -> no churn. Lateral
                    // cross-species SPREAD (water through air) is Task 7 (dose + one-hop relocate).
                    const Material& mI = mats.byIx(mati);
                    const Material& mJ = mats.byIx(matj);
                    if (!movable(mI) || !movable(mJ)) continue;
                    if (f != 1) continue;                              // only +Y swaps; lateral -> Task 7
                    if (!(mJ.molarMass > mI.molarMass)) continue;      // not inverted -> stable, no swap
                    // One-swap-per-cell claim: ovSet doubles as the override write-claim.
                    acc.ensure(cx,cz); acc.ensure(ncx,ncz);
                    if (acc.ovSet[ChunkCoord{cx,cz}][ii] || acc.ovSet[ChunkCoord{ncx,ncz}][jj]) continue;
                    acc.ovSet[ChunkCoord{cx,cz}][ii] = 1;
                    acc.ovSet[ChunkCoord{ncx,ncz}][jj] = 1;
                    // Whole-cell swap via the override channel (wholesale species/mass/T set in apply).
                    acc.ovSpecies[ChunkCoord{cx,cz}][ii]   = matj; acc.ovMass[ChunkCoord{cx,cz}][ii]   = massj; acc.ovT[ChunkCoord{cx,cz}][ii]   = sj->T[jj];
                    acc.ovSpecies[ChunkCoord{ncx,ncz}][jj] = mati; acc.ovMass[ChunkCoord{ncx,ncz}][jj] = massi; acc.ovT[ChunkCoord{ncx,ncz}][jj] = si->T[ii];
                    continue;
                }
```

> **`BUOY_K` note:** for v1 the swap is an unconditional molar-inversion swap (matches Pass A exactly), so `BUOY_K` is **reserved but unused** here. Keep the constant (Task 15 may introduce a soft Φ-blended swap threshold for near-neutral density pairs). No `swapT` channel is needed — the override channel already carries the swapped `T` via `ovT`.

- [ ] **Step 3: Add the gravity unit test.** Append to `tests/pressure_field_test.cpp` and call from `main()`:

```cpp
static void test_flux_heavy_sinks(){
    World w; Mats m = add_mats(w);
    uint16_t LAVA = w.materials.add(Material{1000.f,1.f,0.300f,250.f,1000.f,100.f});
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8,x=8;
    set_cell(*C,x,11,z,LAVA,1000.f,1400.f);     // heavy ABOVE
    set_cell(*C,x,10,z,m.WATER,1000.f,300.f);   // light BELOW (inverted)
    double lava0=0,water0=0; for(int i=0;i<CHUNK_N;++i){ if(C->matIx[i]==LAVA)lava0+=C->mass_kg[i]; if(C->matIx[i]==m.WATER)water0+=C->mass_kg[i]; }
    for(int step=0; step<20; ++step){ relax_pressure(w,w.materials); advect_unified(w,w.materials,orge::DT_CFL); }
    CHECK(C->matIx[idx(x,10,z)] == LAVA, "heavy lava sank to the lower cell");
    CHECK(C->matIx[idx(x,11,z)] == m.WATER, "light water rose to the upper cell");
    double lava1=0,water1=0; for(int i=0;i<CHUNK_N;++i){ if(C->matIx[i]==LAVA)lava1+=C->mass_kg[i]; if(C->matIx[i]==m.WATER)water1+=C->mass_kg[i]; }
    CHECK(std::fabs(lava1-lava0)<1e-2f && std::fabs(water1-water0)<1e-2f, "swap conserves both species exactly");
}
```

- [ ] **Step 4: Run + commit.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test && ./tests/run_tests.sh`
  Expected: green. Commit:

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_unified vertical molar-sort via whole-cell swap (replaces Pass A)"
```

### Task 7: Through-air lateral spread — dose + one-hop relocate (replaces Pass B′ minus DFS)

**This is the most intricate task in the plan** (the through-air `floor(M/min)` spread). Use `superpowers:systematic-debugging` and lean on the oracles. The Tier-2 delta vs the old Pass B′ is precise: keep the **single-hop** dose+relocate via the override channel, **drop the multi-hop DFS chain** (`∇p` over steps handles distant relocation). Reuse the existing one-hop escape finder `find_injection_escape` (sim_engine.hpp ~1490) so the displaced air is conserved, never destroyed.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`advect_unified` — add the lateral lighter-receiver branch, BEFORE the same-species leveling)

- [ ] **Step 1: Pin the oracles on the old path.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh displace && ./tests/run_tests.sh bprime_evacuate && ./tests/run_tests.sh spread_2d && ./tests/run_tests.sh min_mass_occupancy`
  Expected: PASS. These define density-driven displacement + through-air `floor(M/min)` spread — the behaviors this task must reproduce. They are the binding gates (re-run in Stage 3).

- [ ] **Step 2: Implement the lateral dose + one-hop relocate.** In `advect_unified`, ABOVE the same-species leveling and AFTER the Task-6 vertical-swap branch, add the horizontal lighter-receiver spread. The donor `i` must have surplus (`massi − min ≥ min`, can afford a dose and stay ≥ min); the neighbour `j` must be strictly lighter movable (air); gate the push by `∇p` (only toward a `j` whose `Φ` is strictly lower, so a flat pool never creeps — spec §7.2):

```cpp
                // LATERAL through-air spread: donor i (with surplus) doses min_mass into a strictly
                // lighter movable neighbour j, relocating j's displaced content ONE hop (no DFS). f==0
                // / f==2 are the horizontal faces; f==1 (vertical) into-air is gravity's job (Task 6).
                if (!sameSp && f != 1) {
                    const Material& mId = mats.byIx(mati);
                    const Material& mJd = mats.byIx(matj);
                    if (!movable(mId) || !movable(mJd)) continue;
                    // donor must be the heavier/sourcing side i with a strictly-lighter neighbour j.
                    if (!(mJd.molarMass < mId.molarMass)) continue;       // density-only push direction
                    const float dose = mId.minMass;
                    if (massi - dose < mId.minMass - 1e-2f) continue;     // donor keeps >= its min
                    // ∇p / Φ gate: only push toward a lower-Φ lighter cell (no flat-pool creep).
                    const float Phi_i = std::max(0.0f, massi - mId.minMass) + C.pressure[ii];
                    const float Phi_j = std::max(0.0f, massj - mJd.minMass) + CJ->pressure[jj];
                    if (Phi_i <= Phi_j) continue;
                    // Claim i (donor) and j (becomes donor species). ovSet doubles as the write-claim.
                    acc.ensure(cx,cz); acc.ensure(ncx,ncz);
                    if (acc.ovSet[ChunkCoord{cx,cz}][ii] || acc.ovSet[ChunkCoord{ncx,ncz}][jj]
                        || acc.filled[ChunkCoord{ncx,ncz}][jj]) continue;
                    // Relocate j's displaced lighter content ONE hop (reuse the injection escape finder;
                    // allowLighter=false so it terminates — no lighter-into-lighter chains).
                    int ocx,ocz,oi,oMode;
                    if (!find_injection_escape(world, mats, ncx,ncz, lx,ny,lz,
                                               matj, massj, mJd, /*allowLighter=*/false,
                                               ocx,ocz,oi,oMode)) continue;   // no one-hop escape -> ∇p next step
                    if (acc.ovSet[ChunkCoord{ocx,ocz}][oi] || acc.filled[ChunkCoord{ocx,ocz}][oi]) continue;
                    // Commit: write the escape target with j's relocated content, then override j to the
                    // donor species @ dose, then debit the donor. All claim-gated, all conservative.
                    if (oMode == 0) {                                  // escape into VACUUM
                        acc.ovSet[ChunkCoord{ocx,ocz}][oi] = 1;
                        acc.ovSpecies[ChunkCoord{ocx,ocz}][oi] = matj;
                        acc.ovMass[ChunkCoord{ocx,ocz}][oi]   = massj;
                        acc.ovT[ChunkCoord{ocx,ocz}][oi]      = sj->T[jj];
                    } else if (oMode == 1) {                           // escape MERGES into same species
                        acc.dMass[ChunkCoord{ocx,ocz}][oi] += massj;
                        acc.dEnth[ChunkCoord{ocx,ocz}][oi] += massj * sj->T[jj];
                    } else { continue; }                              // oMode 2 (lighter) excluded by allowLighter=false
                    acc.ovSet[ChunkCoord{ncx,ncz}][jj] = 1;           // j becomes donor species @ dose
                    acc.ovSpecies[ChunkCoord{ncx,ncz}][jj] = mati;
                    acc.ovMass[ChunkCoord{ncx,ncz}][jj]    = dose;
                    acc.ovT[ChunkCoord{ncx,ncz}][jj]       = si->T[ii];
                    acc.ovSet[ChunkCoord{cx,cz}][ii] = 1;             // donor i: massi - dose, same species
                    acc.ovSpecies[ChunkCoord{cx,cz}][ii] = mati;
                    acc.ovMass[ChunkCoord{cx,cz}][ii]    = massi - dose;
                    acc.ovT[ChunkCoord{cx,cz}][ii]       = si->T[ii];
                    continue;
                }
                if (!sameSp) continue;   // any remaining cross-species face: nothing this step (∇p next)
```

> **Conservation + claim caveat (read before coding):** `find_injection_escape` reads the LIVE world, but the escape target may already be claimed by an earlier event this sweep — hence the `ovSet`/`filled` re-checks above. The three writes (escape target, `j`, donor `i`) are wholesale overrides, each claim-gated, so per-species mass is conserved exactly (donor loses `dose`; `j` gains `dose` of donor species; `j`'s old `massj` moves to the escape target). If any of the three cells is pre-claimed, the WHOLE event must abort (do NOT do a partial write) — restructure as a pre-check of all three claims before any write if the snippet's early-`continue`s leave a partial commit. Verify with the conservation assertion in Step 3.

- [ ] **Step 3: Add a through-air spread unit test.** Append + call:

```cpp
static void test_flux_water_spreads_through_air(){
    World w; Mats m = add_mats(w);
    uint16_t AIR = w.materials.add(Material{1000.f,0.02f,0.029f,1.f,50.f,0.0f});
    Chunk* C = w.ensureChunk(0,0);
    for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8, y=10;
    // A floor row of air with one rich water cell; water should bud into the air toward floor(M/min).
    for(int x=4;x<=12;++x) set_cell(*C,x,y,z,AIR,1.f,300.f);
    set_cell(*C,8,y,z,m.WATER,1000.f,300.f);          // 1000 kg, min 125 -> should reach ~8 tiles
    double water0=0, air0=0; for(int i=0;i<CHUNK_N;++i){ if(C->matIx[i]==m.WATER)water0+=C->mass_kg[i]; if(C->matIx[i]==AIR)air0+=C->mass_kg[i]; }
    for(int step=0; step<400; ++step){ relax_pressure(w,w.materials); advect_unified(w,w.materials,orge::DT_CFL); }
    int waterCells=0; double water1=0, air1=0;
    for(int i=0;i<CHUNK_N;++i){ if(C->matIx[i]==m.WATER){ waterCells++; water1+=C->mass_kg[i]; } if(C->matIx[i]==AIR)air1+=C->mass_kg[i]; }
    std::printf("water spread to %d cells; water=%.2f air=%.2f\n", waterCells, water1, air1);
    CHECK(std::fabs(water1-water0)<1.0f, "water conserved spreading through air");
    CHECK(std::fabs(air1-air0)<1.0f, "air conserved (displaced, not destroyed)");
    CHECK(waterCells >= 6, "water budded toward floor(M/min) (~8 tiles) through air");
}
```

- [ ] **Step 4: Run + commit.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/pressure_field_test.cpp -o build/pressure_field_test -pthread && ./build/pressure_field_test && ./tests/run_tests.sh`
  Expected: green. If `waterCells` stalls below 6, the `∇p` budding is under-driving — this is spec §9's `floor(M/2min)` risk; diagnose before papering over. Commit:

```bash
git -C ORGE-ENGINE add sim_engine.hpp tests/pressure_field_test.cpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_unified through-air spread (dose + one-hop relocate, no DFS)"
```

---

## Stage 3 — Swap the orchestration; run the full oracle suite

### Task 8: Rewrite `advect_world` to the new path

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`advect_world` ~1638-1672)

- [ ] **Step 1: Replace the body of `advect_world`.** Keep the per-World clock advance; replace the three-pass body with relax+flux:

```cpp
inline void advect_world(World& world, const MaterialLUT& mats, double dt = orge::DT_CFL) {
    const double t0 = world.simClock;
    const double t1 = world.simClock + dt;
    world.simClock = t1;
    (void)t0; (void)t1;                     // cadence now folded into rate()*dt; kept for ABI parity
    // (1) Relax the pressure field (K fixed Jacobi sweeps, warm-started across calls).
    relax_pressure(world, mats);
    // (2) One unified flux sweep: gravity + leveling + displacement + vessels, species is cargo.
    advect_unified(world, mats, dt);
}
```

> **Viscosity cadence note:** the old engine rate-limited the atomic-dose FRONTIER via `advanced(visc, t0, t1)` (a viscous fluid opens a new tile less often, full dose when it does). Leveling/compaction/swap already throttle by AMOUNT (`rate·dt`), but the vacuum dose (Task 5) and through-air dose (Task 7) are FIXED `min_mass` doses — without a cadence gate a viscous fluid doses every step (too fast). Thread `t0=world.simClock` (pre-advance) and `t1` into `advect_unified` and gate ONLY the two dose branches with `if (!advanced(mDonor.viscosity, t0, t1)) continue;`. Keep the 3-arg `advect_unified(world, mats, dt)` convenience overload (every-step, used by the unit tests) delegating to the 5-arg form with `t0=0, t1=dt`. The binding oracles are `viscosity_flow_test` / `viscosity_spread_test` (greened in Task 9/10).

- [ ] **Step 2: Build the whole cheap tier.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh`
  Expected: cheap tier runs. EXPECT SOME FAILURES here — this is the integration moment. Record which oracle tests fail (e.g. `u_tube`, `lava_water_equilibrium`, `injection`). Do NOT fix blindly.

- [ ] **Step 3: Commit the swap (even if red) on a checkpoint.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp
git -C ORGE-ENGINE commit -m "feat(engine): advect_world uses relax_pressure + advect_unified (old passes still present)"
```

### Task 9: Green the cheap-tier oracles

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (targeted fixes); possibly existing test files (only with sign-off)

- [ ] **Step 1: For each failing cheap oracle, diagnose with systematic-debugging.** Use `superpowers:systematic-debugging`. For each of `u_tube_test`, `lava_water_equilibrium_test`, `push_chain_tube_test`, `injection_test`, `viscosity_flow_test`, `time_dt_test`, `unified_basics_test`, `hydrostatic_test`, `overburden_test`: run `./tests/run_tests.sh <name>`, read the dump, and form a hypothesis BEFORE editing.

- [ ] **Step 2: Classify each failure.** Two legitimate outcomes per spec §9:
  - **Engine bug** — the unified model should reproduce the behavior but doesn't (e.g. K too low for U-tube convergence per step → the field needs warm-start continuity; verify `pressure` is NOT cleared between calls). Fix the engine.
  - **Behavior change requiring sign-off** — the test encodes OLD two-pass behavior the unified model intentionally changes. STOP, document the diff, and get user sign-off before editing the test (handoff rule).

- [ ] **Step 3: Apply fixes, re-run the cheap tier after each.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh` until green.

- [ ] **Step 4: Commit each fix separately** with a message naming the oracle and the cause, e.g.:

```bash
git -C ORGE-ENGINE add -A
git -C ORGE-ENGINE commit -m "fix(engine): U-tube self-levels under unified flux (warm-start p continuity)"
```

### Task 10: Green the heavy-tier oracles

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp`

- [ ] **Step 1: Run the heavy tier.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh quick` (cheap + heavy + short stress).
  Expected: identify failures among `correctness`, `relax_spread`, `min_mass_occupancy`, `spread_2d`, `displace`, `viscosity_spread`.

- [ ] **Step 2: Diagnose + fix each** (systematic-debugging), same engine-bug vs sign-off classification as Task 9. The likely hot spots: `spread_2d`/`min_mass_occupancy` (the `∇p`-driven budding must reach `floor(M/min)` — if it stalls at `floor(M/2min)`, the flat-pool BC is letting the field go flat too early; this is spec §9's fallback trigger — escalate before adding a separate frontier field).

- [ ] **Step 3: Re-run `./tests/run_tests.sh quick` until green. Commit each fix.**

```bash
git -C ORGE-ENGINE add -A
git -C ORGE-ENGINE commit -m "fix(engine): <oracle> green under unified flux — <cause>"
```

---

## Stage 4 — Delete the old passes

### Task 11: Remove the dead three-pass machinery

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp`

- [ ] **Step 1: Delete the now-unused functions and types.** Remove `pass_a_sort`, `pass_b_relax`, `pass_bprime_displace`, `compute_overburden`, `compute_overburden_samespecies`, `overburden_at`, `OverburdenMap`, and the `bprimeRot` field from `World` if no longer referenced. Keep `snapshot_world`/`WorldSnapshot`/`BAccum`/`apply_baccum` (used by `advect_unified`). Update the top-of-file invariant comment and the `advect_world` header comment to describe relax+flux.

- [ ] **Step 2: Build.** Run: `cd ORGE-ENGINE && ./tests/run_tests.sh quick`
  Expected: all green (deletions only; behavior unchanged). Fix any dangling references the compiler flags.

- [ ] **Step 3: Commit.**

```bash
git -C ORGE-ENGINE add sim_engine.hpp
git -C ORGE-ENGINE commit -m "refactor(engine): delete Pass A/B/B' + overburden (subsumed by unified flux)"
```

---

## Stage 5 — New behavior wins (the point of Tier 2)

### Task 12: Multi-arm communicating vessels (the banked gap)

**Files:**
- Create: `ORGE-ENGINE/tests/multi_arm_vessels_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the test.** Three water arms of unequal height sharing one bottom channel must equalize surface heights (the previously-banked case, spec §7.5). Create `tests/multi_arm_vessels_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/multi_arm_vessels_test.cpp -o build/multi_arm_vessels_test -pthread
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n",m); ++failures; } }while(0)
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float ms,float T){ int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=ms; C.T_curr[i]=T; }
static int surface_height(const Chunk& C,int x,int z,uint16_t WATER){ for(int y=CHUNK_H-1;y>=0;--y) if(C.matIx[idx(x,y,z)]==WATER) return y; return -1; }
int main(){
    constexpr float INF=std::numeric_limits<float>::infinity();
    World w;
    uint16_t VOID=w.materials.add(Material{0,0,0,0,0,0});
    uint16_t WALL=w.materials.add(Material{800,2,9,2500,2500,INF});
    uint16_t WATER=w.materials.add(Material{4186,0.6f,0.180f,125,1000,0.001f});
    (void)VOID;
    Chunk* C=w.ensureChunk(0,0); for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8;
    // Wall slab x in [3,11], y in [9,20]; carve a bottom channel y=10 x4..10 and three arms x=4,7,10.
    for(int x=3;x<=11;++x) for(int y=9;y<=20;++y) set_cell(*C,x,y,z,WALL,2500,300);
    for(int x=4;x<=10;++x) set_cell(*C,x,10,z,WATER,1000,300);            // bottom channel
    for(int y=11;y<=18;++y) set_cell(*C,4,y,z,WATER,1000,300);           // tall arm
    for(int y=11;y<=13;++y) set_cell(*C,7,y,z,WATER,1000,300);           // short arm
    for(int y=11;y<=15;++y) set_cell(*C,10,y,z,WATER,1000,300);          // medium arm
    for(int x=4;x<=10;x+=3) for(int y=19;y<=19;++y) set_cell(*C,x,y,z,VOID,0,0); // open tops
    for(int step=0; step<3000; ++step) advect_world(w,w.materials,orge::DT_CFL);
    int h4=surface_height(*C,4,z,WATER), h7=surface_height(*C,7,z,WATER), h10=surface_height(*C,10,z,WATER);
    std::printf("surfaces: arm4=%d arm7=%d arm10=%d\n",h4,h7,h10);
    CHECK(std::abs(h4-h7)<=1 && std::abs(h4-h10)<=1, "three arms equalize to within 1 cell");
    if(failures){ std::printf("\n%d failed\n",failures); return 1; }
    std::printf("\nmulti-arm vessels equalize\n"); return 0;
}
```

- [ ] **Step 2: Run it.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/multi_arm_vessels_test.cpp -o build/multi_arm_vessels_test -pthread && ./build/multi_arm_vessels_test`
  Expected: PASS. If arms do NOT equalize, the lateral pressure coupling is too weak — increase `ADV_PRESSURE_K` (Task 15 territory) and re-run; if it still fails, this is a genuine design gap to escalate, not a silent skip.

- [ ] **Step 3: Register in the CHEAP tier** (it's a convergence test but bounded): add to `run_tests.sh` `CHEAP_TESTS` — if it runs >2s, put it in `HEAVY_TESTS` instead:

```bash
  "multi_arm_vessels_test|tests/multi_arm_vessels_test.cpp|-O2 -g"
```

- [ ] **Step 4: Commit.**

```bash
git -C ORGE-ENGINE add tests/multi_arm_vessels_test.cpp tests/run_tests.sh
git -C ORGE-ENGINE commit -m "test(engine): multi-arm communicating vessels equalize (banked gap closed)"
```

### Task 13: Flat-pool no-creep end-to-end gate

**Files:**
- Create: `ORGE-ENGINE/tests/flat_pool_no_creep_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh`

- [ ] **Step 1: Write the test.** A settled flat pool run for many steps must not drift its centroid (spec §7.2). Create `tests/flat_pool_no_creep_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/flat_pool_no_creep_test.cpp -o build/flat_pool_no_creep_test -pthread
#include "sim_engine.hpp"
#include <cstdio>
#include <cmath>
#include <limits>
static int failures=0;
#define CHECK(c,m) do{ if(!(c)){ std::printf("FAIL: %s\n",m); ++failures; } }while(0)
static void set_cell(Chunk& C,int x,int y,int z,uint16_t ix,float ms,float T){ int i=idx(x,y,z); C.matIx[i]=ix; C.mass_kg[i]=ms; C.T_curr[i]=T; }
static double centroid_x(const Chunk& C,uint16_t WATER){ double sx=0,sm=0; for(int x=0;x<CHUNK_W;++x)for(int y=0;y<CHUNK_H;++y)for(int z=0;z<CHUNK_D;++z){int i=idx(x,y,z); if(C.matIx[i]==WATER){ sx+=x*C.mass_kg[i]; sm+=C.mass_kg[i]; }} return sm>0?sx/sm:0; }
int main(){
    constexpr float INF=std::numeric_limits<float>::infinity();
    World w;
    uint16_t VOID=w.materials.add(Material{0,0,0,0,0,0}); (void)VOID;
    uint16_t WALL=w.materials.add(Material{800,2,9,2500,2500,INF});
    uint16_t WATER=w.materials.add(Material{4186,0.6f,0.180f,125,1000,0.001f});
    Chunk* C=w.ensureChunk(0,0); for(int s=0;s<SECTIONS_Y;++s) C->sectionLoaded[s]=1;
    const int z=8, y=10;
    for(int x=2;x<=13;++x) set_cell(*C,x,9,z,WALL,2500,300);          // floor
    for(int x=2;x<=13;++x) set_cell(*C,x,y,z,WATER,250.f,300.f);      // flat, settled pool (2*min each)
    const double cx0 = centroid_x(*C,WATER);
    for(int step=0; step<2000; ++step) advect_world(w,w.materials,orge::DT_CFL);
    const double cx1 = centroid_x(*C,WATER);
    std::printf("centroid x: before=%.4f after=%.4f\n", cx0, cx1);
    CHECK(std::fabs(cx1-cx0) < 0.05, "flat pool centroid does not drift (no creep)");
    if(failures){ std::printf("\n%d failed\n",failures); return 1; }
    std::printf("\nflat pool stable\n"); return 0;
}
```

- [ ] **Step 2: Run it.** Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/flat_pool_no_creep_test.cpp -o build/flat_pool_no_creep_test -pthread && ./build/flat_pool_no_creep_test`
  Expected: PASS. If it drifts, the `∇p` budding is asymmetric — spec §9 fallback (separate amortized frontier field) is on the table; escalate.

- [ ] **Step 3: Register in CHEAP tier + commit.**

```bash
# add line to run_tests.sh CHEAP_TESTS:
#   "flat_pool_no_creep_test|tests/flat_pool_no_creep_test.cpp|-O2 -g"
git -C ORGE-ENGINE add tests/flat_pool_no_creep_test.cpp tests/run_tests.sh
git -C ORGE-ENGINE commit -m "test(engine): flat-pool no-creep gate (spec §7.2)"
```

---

## Stage 6 — Rebuild the .so, Java gate, push both

### Task 14: Rebuild `liborge.so` and run the Java gate

**Files:**
- Modify: `core/src/main/resources/natives/linux-x64/liborge.so` (rebuilt artifact)

- [ ] **Step 1: Rebuild the native library.** Run:
  `JAVA_HOME=/home/claude/jdk21 ./ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so`
  Expected: builds with no errors; prints the output path.

- [ ] **Step 2: Run the Java gate.** Run:
  `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest`
  Expected: BUILD SUCCESSFUL; `:core:integrationTest` reports **skipped=0** (proves the real `.so` loaded). If integration tests fail on a behavior the unified model changed, classify engine-bug vs sign-off as in Task 9.

- [ ] **Step 3: Build both loaders.** Run:
  `JAVA_HOME=/home/claude/jdk21 ./gradlew :fabric-1.21:build :neoforge-1.21:build`
  Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Commit the rebuilt artifact (engine submodule first, then parent).**

```bash
git -C ORGE-ENGINE log --oneline -1            # confirm engine commits are in place
git -C ORGE-ENGINE push origin main 2>/dev/null
git -C ORGE-ENGINE rev-parse HEAD; git -C ORGE-ENGINE rev-parse origin/main   # must match
git add ORGE-ENGINE core/src/main/resources/natives/linux-x64/liborge.so
git commit -m "feat(fluid): unified flux + Jacobi pressure field — engine bump + rebuilt liborge.so"
git push origin main
git rev-parse HEAD; git rev-parse origin/main   # must match (per [[always-push-rebuild]])
```

### Task 15: Calibrate `ADV_PRESSURE_K` and `BUOY_K`

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (the two constants), `.so`, parent gitlink

- [ ] **Step 1: Sweep K.** For `ADV_PRESSURE_K` in {2,4,6,8}: set the constant, run `cd ORGE-ENGINE && ./tests/run_tests.sh quick` AND `./build/multi_arm_vessels_test`. Record the smallest K that keeps ALL oracles green (multi-arm convergence is the binding constraint; flat-pool/lava-water are the no-churn constraints).

- [ ] **Step 2: Verify `BUOY_K`.** Confirm `sort_swap` and `lava_water_equilibrium` both pass at the chosen `BUOY_K=1.0`; if heavy-sink is too aggressive (over-churns a near-neutral pair) or too weak (fails to sort), adjust and re-run those two oracles only.

- [ ] **Step 3: Rebuild `.so` + Java gate + push** (repeat Task 14 Steps 1-4 with a commit message naming the tuned constants).

- [ ] **Step 4: Update memory.** Append a line to `/home/claude/.claude/projects/-home-claude-ORGE/memory/MEMORY.md` pointing at a new `orge-unified-flux-pressure.md` memory recording: model replaced (relax_pressure + advect_unified), the tuned K/BUOY_K, the closed multi-arm gap, and the engine/parent commit hashes. Link `[[orge-unified-fluid]]`, `[[orge-communicating-vessels-gap]]`, `[[always-push-rebuild]]`.

### Task 16: In-game audit (final gate)

- [ ] **Step 1: Hand off for the in-game audit.** The headless suites have repeatedly missed live-air bugs (`[[orge-air-sink-fix]]`). Report to the user that the unified flux is ready for an in-game test: pour water (spreads to `floor(M/min)`, no creep, crosses chunk seams), pour lava into water (displaces, sinks, no churn, freezes to obsidian at contact), and a built U-tube (self-levels; multi-arm equalizes). This is the real completion gate — do not claim "done" before it.

---

## Self-review notes (for the executor)

- **Spec coverage:** §2.1 pressure → Tasks 2-3; §2.2 flux/Φ → Tasks 4-7; §2.3 commit (merge/dose/swap) → Tasks 4 (merge + vertical compaction/flood-guard) / 5 (vacuum dose) / 6 (vertical swap) / 7 (dose+relocate); §2.4 conservation → asserted in every flux unit test; §3 frontier-via-∇p → Tasks 5/7/10/13; §4 gravity fold-in → Task 6 (vertical swap) + Task 4 (vertical compaction) + Task 11 deletion; §5 Law-C fixed-K → Tasks 2/8/15; §6 deletions → Task 11; §7 regression oracles → Tasks 9/10 (existing) + 12/13 (new); §8 GPU shape → structural (stencil kernels, no BFS/DFS after Task 11); §9 open questions → Tasks 7/10 (floor(M/min) stall), 13 (creep), 15 (K/BUOY_K), 9/10 (sign-off); §10 gate → Tasks 14-16.
- **The riskiest tasks are 7 and 9-10** (the through-air dose+one-hop-relocate semantics, and greening the displacement/vessels/`min_mass_occupancy` oracles). Use `superpowers:systematic-debugging` there and escalate the two spec-§9 fallbacks (`floor(M/2min)` stall, flat-pool creep) rather than papering over them.
- **No new `BAccum` channels needed:** the swap (Task 6) and dose+relocate (Task 7) commits reuse the EXISTING override channel (`ovSet`/`ovSpecies`/`ovMass`/`ovT`, applied wholesale in `apply_baccum` ~1421-1437) and the `filled`/`adopt` vacuum channel. The only cross-cutting risk is **claim coordination** in Task 7 (a 3-cell atomic commit must abort fully if any cell is pre-claimed) — verify with the Task 7 Step 3 conservation assertion.
