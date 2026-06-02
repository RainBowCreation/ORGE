# Placement Injection — Plan 1: Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a native "displace-and-inject" operation to the engine: a per-step **list** of injections that places a species/mass at a target cell, displaces the incumbent to an escape neighbour (conserved when possible, lost-but-logged when sealed), reports per-species injected/sealed-loss deltas for the §9 gate, and applies **once** before the advection sub-cycle so the placement then flows naturally.

**Architecture:** Header-only C++20. A new `apply_injections(World&, MaterialLUT&, const Injection*, int, LedgerAccum&)` mutates the world directly (it runs *before* any snapshot/pass, so it reads/writes `Chunk` cells directly — no `BAccum`/snapshot needed). Its escape search mirrors Pass B′'s DOWN→hashed-horizontals→UP priority but **drops the "strictly lighter" molar gate** (placement is authoritative). `orgeStepWorld` gains an additive injection channel (`injCount` + 5 flat arrays) and one `ledgerOut` output; it calls `apply_injections` once, then the existing sub-cycle loop runs on the result. `injCount==0` is byte-for-byte today.

**Tech Stack:** C++20 header-only engine (`ORGE-ENGINE/`, custom `tests/*.cpp` + `tests/run_tests.sh`), JNI (`orge_jni.cpp` → `liborge.so`), Java native decl in `core/.../engine/NativeEngine.java`.

**Spec:** `docs/superpowers/specs/2026-06-02-placement-injection-displace-design.md` (Part A + A5 ABI)

---

## File Structure

**Engine (`ORGE-ENGINE/`):**
- `sim_engine.hpp` — add `struct Injection`, `struct LedgerAccum`, `find_injection_escape(...)`, and `apply_injections(...)` immediately after `apply_baccum` (~line 1150, before `advect_world`).
- `orge_jni.cpp` — extend `orgeStepWorld` signature with the injection channel + `jLedgerOut`; resolve `columnId→(cx,cz)`; call `apply_injections` once before the sub-cycle loop; write the ledger back.
- `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java` — extend the `native` decl + the `orgeStepWorld` Java wrapper to match (signature only in this plan; the Java marshalling that *feeds* injections is Plan 2).
- `tests/injection_test.cpp` — **new** cheap-tier unit test for `apply_injections` (place, displace, sealed-loss, concurrent, conservation).
- `tests/run_tests.sh` — register `injection_test` in the CHEAP tier.

**The displaced-incumbent rule (mirrors Pass B′ priority, no molar gate):** DOWN `(0,-1,0)` → 4 horizontals in a deterministic FNV-hashed order → UP `(0,+1,0)`. A neighbour is receivable iff **vacuum** (incumbent adopts it) **or same species** as the incumbent with room under `maxMass` (additive merge). First receivable wins; none ⇒ sealed-loss.

---

## Task 1: `Injection` + `LedgerAccum` + `apply_injections` trivial-place path

The minimal slice: an injection into a cell with **nothing to displace** (vacuum/empty or a frozen incumbent) just sets the cell and books the injected mass. No escape search yet.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add after `apply_baccum`, ~line 1150)
- Create: `ORGE-ENGINE/tests/injection_test.cpp`
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (add to `CHEAP_TESTS`)

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/injection_test.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread
#include "sim_engine.hpp"
#include "tests/test_harness.hpp"
#include <cmath>

using namespace orge;

// Density-ranked materials (molarMass is the gravity/buoyancy rank: lava>water>air>void).
// Fields: {heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity}.
static uint16_t addAir(World& w)   { return w.materials.add(Material{1000.f, 0.025f, 0.5f, 1.2f,   5.f,    0.0000181f}); }
static uint16_t addWater(World& w) { return w.materials.add(Material{4186.f, 0.6f,   1.0f, 1000.f, 1000.f, 0.001f}); }

static double totalMassOf(World& w, uint16_t species) {
    double t = 0.0;
    for (auto& kv : w.chunks) {
        Chunk& C = *kv.second;
        for (int i = 0; i < CHUNK_N; ++i) if (C.matIx[i] == species) t += C.mass_kg[i];
    }
    return t;
}

// A single injection into a VACUUM cell: nothing to displace -> cell becomes water@1000.
static void test_inject_into_vacuum_places() {
    World w;
    uint16_t VOID = w.materials.add(Material{0,0,0,0,0,0});   // ix 0 = void
    uint16_t AIR = addAir(w); (void)AIR;
    uint16_t WATER = addWater(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix = VOID;
    const int ci = idx(3, 70, 4);     // empty (void, mass 0) by construction

    LedgerAccum ledger((int)w.materials.size());
    Injection inj{0, 0, ci, WATER, 1000.f, 290.f};  // columnId 0, cx0/cz0 resolved by caller
    apply_injections(w, w.materials, &inj, 1, ledger);

    TH_CHECK_MSG(C->matIx[ci] == WATER, "cell became water");
    TH_CHECK_CLOSE(C->mass_kg[ci], 1000.f, 1e-6);
    TH_CHECK_CLOSE(C->T_curr[ci], 290.f, 1e-6);
    TH_CHECK_CLOSE(ledger.injected[WATER], 1000.0, 1e-6);   // booked as a source
    TH_CHECK_CLOSE(ledger.sealedLoss[WATER], 0.0, 1e-6);
}

int main() {
    th::run("inject_into_vacuum_places", test_inject_into_vacuum_places);
    return th::summary();
}
```

> **Note:** confirm the harness entry points are `th::run(name, fn)` and `th::summary()` by reading `tests/test_harness.hpp`; if the existing tests use a different runner (e.g. a plain `main` with `failures`), mirror that file's exact style. The asserts (`TH_CHECK_MSG`, `TH_CHECK_CLOSE`) are verified present (`test_harness.hpp:28-56`).

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread`
Expected: FAIL — `'Injection' was not declared`, `'LedgerAccum' was not declared`, `apply_injections` not found.

- [ ] **Step 3: Add the types + minimal `apply_injections` in `sim_engine.hpp`**

Insert immediately after `apply_baccum` (the function ending ~line 1150), before `advect_world`:

```cpp
// ----------------------------------------------------------------------------------------------
// Displace-and-inject (placement transactions, spec 2026-06-02-placement-injection-displace).
// Applied ONCE per whole step, BEFORE the advection sub-cycle (orge_jni.cpp), directly on the
// World (no snapshot needed — nothing has run yet this step). Each injection places species@mass
// at its target cell; the incumbent (if movable + non-empty) is relocated to an escape neighbour,
// else replaced (mass lost — bounded, logged). Authoritative: placement always occupies the cell.
// ----------------------------------------------------------------------------------------------
struct Injection {
    int      columnId;     // index into the step's column list (caller resolves to cx,cz)
    int      cx, cz;       // chunk coords (resolved by the JNI from columnId)
    int      cellIndex;    // idx(x,y,z) in the full-height column
    uint16_t species;      // LUT index to place (movable)
    float    mass;         // kg to place (the legitimate bucket/source seed)
    float    temperature;  // K
};

// Per-species accumulated deltas the §9 gate trusts: injected sources (+) and sealed-loss sinks (+,
// reported as a positive magnitude; the gate subtracts it). Indexed by LUT species index.
struct LedgerAccum {
    std::vector<double> injected;     // Σ placed mass per species (allowed source)
    std::vector<double> sealedLoss;   // Σ incumbent mass deleted when no escape (allowed sink)
    explicit LedgerAccum(int nSpecies)
        : injected(nSpecies, 0.0), sealedLoss(nSpecies, 0.0) {}
};

inline void apply_injections(World& world, const MaterialLUT& mats,
                             const Injection* list, int count, LedgerAccum& ledger) {
    for (int n = 0; n < count; ++n) {
        const Injection& inj = list[n];
        Chunk* C = world.findChunk(inj.cx, inj.cz);
        if (!C) continue;                                   // column not in this step
        const int ci = inj.cellIndex;
        if (ci < 0 || ci >= CHUNK_N) continue;

        const uint16_t incSpecies = C->matIx[ci];
        const float    incMass    = C->mass_kg[ci];
        const Material& incMat    = mats.byIx(incSpecies);
        const bool nothingToDisplace =
            !movable(incMat) || incMass <= orge::ADV_EPS_MASS || incSpecies == C->void_ix;

        if (nothingToDisplace) {
            C->matIx[ci]   = inj.species;
            C->mass_kg[ci] = inj.mass;
            C->T_curr[ci]  = inj.temperature;
            if (inj.species < ledger.injected.size()) ledger.injected[inj.species] += inj.mass;
            continue;
        }
        // (displacement path added in Task 2; for now, fall through to a plain place so the
        //  trivial slice is self-contained — Task 2 replaces this branch.)
        C->matIx[ci]   = inj.species;
        C->mass_kg[ci] = inj.mass;
        C->T_curr[ci]  = inj.temperature;
        if (inj.species < ledger.injected.size()) ledger.injected[inj.species] += inj.mass;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread && ./build/injection_test`
Expected: PASS (1 test, 0 failures).

- [ ] **Step 5: Register the test in the cheap tier**

In `ORGE-ENGINE/tests/run_tests.sh`, add to `CHEAP_TESTS`:

```bash
  "injection_test|tests/injection_test.cpp|-O2 -g"
```

- [ ] **Step 6: Commit**

```bash
git add ORGE-ENGINE/sim_engine.hpp ORGE-ENGINE/tests/injection_test.cpp ORGE-ENGINE/tests/run_tests.sh
git commit -m "feat(engine): injection types + apply_injections trivial-place path"
```

---

## Task 2: Displacement path — `find_injection_escape` + relocate

Now the real case: inject water into an **air** cell that has a receivable neighbour. The air is relocated; water occupies the cell; total air mass is unchanged.

**Files:**
- Modify: `ORGE-ENGINE/sim_engine.hpp` (add `find_injection_escape` before `apply_injections`; replace the Task-1 fall-through branch)
- Modify: `ORGE-ENGINE/tests/injection_test.cpp`

- [ ] **Step 1: Write the failing test**

Add to `ORGE-ENGINE/tests/injection_test.cpp` (and register in `main`):

```cpp
// Inject water into an AIR cell that has a vacuum neighbour: air is relocated, water placed,
// total air mass conserved, total water == injected.
static void test_inject_displaces_air_conserves() {
    World w;
    uint16_t VOID = w.materials.add(Material{0,0,0,0,0,0});
    uint16_t AIR = addAir(w);
    uint16_t WATER = addWater(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix = VOID;

    const int ci = idx(3, 70, 4);
    C->matIx[ci] = AIR; C->mass_kg[ci] = 1.2f; C->T_curr[ci] = 300.f;   // incumbent air
    // surround with vacuum (default) so an escape exists; explicit DOWN cell is vacuum too.

    const double air0 = totalMassOf(w, AIR);   // 1.2

    LedgerAccum ledger((int)w.materials.size());
    Injection inj{0, 0, 0, ci, WATER, 1000.f, 290.f};
    apply_injections(w, w.materials, &inj, 1, ledger);

    TH_CHECK_MSG(C->matIx[ci] == WATER, "target cell is now water");
    TH_CHECK_CLOSE(C->mass_kg[ci], 1000.f, 1e-6);
    TH_CHECK_CLOSE(totalMassOf(w, AIR), air0, 1e-6);          // air relocated, NOT deleted
    TH_CHECK_CLOSE(totalMassOf(w, WATER), 1000.0, 1e-6);      // water == injected
    TH_CHECK_CLOSE(ledger.injected[WATER], 1000.0, 1e-6);
    TH_CHECK_CLOSE(ledger.sealedLoss[AIR], 0.0, 1e-6);        // nothing lost
}
```

Note `Injection` now has the `{columnId, cx, cz, cellIndex, species, mass, temperature}` shape — update the Task-1 test's `Injection inj{0, 0, ci, ...}` literal to `{0, 0, 0, ci, ...}` (add the `cz` field) so it still compiles.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread && ./build/injection_test`
Expected: FAIL — `totalMassOf(w, AIR)` is 0 (the Task-1 fall-through overwrote the air without relocating it).

- [ ] **Step 3: Add `find_injection_escape` and the displacement branch**

Insert before `apply_injections` in `sim_engine.hpp`:

```cpp
// Escape target for a displaced incumbent during injection. Mirrors Pass B′'s priority
// (DOWN -> 4 deterministically-hashed horizontals -> UP) but reads the LIVE World (injection runs
// before any pass) and has NO "strictly lighter" molar gate — placement is authoritative, so the
// incumbent goes to the first RECEIVABLE neighbour: vacuum (adopt) or same species with room.
// Returns true + fills (ocx,ocz,oi,oVac) on success. Deterministic (FNV coord hash, no rng/clock).
inline bool find_injection_escape(World& world, const MaterialLUT& mats,
                                  int cx, int cz, int x, int y, int z,
                                  uint16_t incSpecies, float incMass, const Material& incMat,
                                  int& ocx, int& ocz, int& oi, bool& oVac) {
    static const int HD[4][3] = {{+1,0,0},{-1,0,0},{0,0,+1},{0,0,-1}};
    static const int HD_PERM[24][4] = {
        {0,1,2,3},{0,1,3,2},{0,2,1,3},{0,2,3,1},{0,3,1,2},{0,3,2,1},
        {1,0,2,3},{1,0,3,2},{1,2,0,3},{1,2,3,0},{1,3,0,2},{1,3,2,0},
        {2,0,1,3},{2,0,3,1},{2,1,0,3},{2,1,3,0},{2,3,0,1},{2,3,1,0},
        {3,0,1,2},{3,0,2,1},{3,1,0,2},{3,1,2,0},{3,2,0,1},{3,2,1,0}};
    uint32_t h = 2166136261u;
    for (int v : {cx, cz, x, y, z}) h = (h ^ static_cast<uint32_t>(v)) * 16777619u;
    const int* perm = HD_PERM[h % 24u];
    const int esc[6][3] = {
        {0,-1,0},
        {HD[perm[0]][0], 0, HD[perm[0]][2]},
        {HD[perm[1]][0], 0, HD[perm[1]][2]},
        {HD[perm[2]][0], 0, HD[perm[2]][2]},
        {HD[perm[3]][0], 0, HD[perm[3]][2]},
        {0,+1,0}};
    for (const auto& e : esc) {
        int ecx, ecz, elx, eny, elz;
        if (!resolve_neighbor(cx, cz, x, y, z, e[0], e[1], e[2], ecx, ecz, elx, eny, elz)) continue;
        Chunk* CE = world.findChunk(ecx, ecz);
        if (!CE) continue;                                  // off the loaded world -> wall
        const int ei = idx(elx, eny, elz);
        const uint16_t mate = CE->matIx[ei];
        const float    masse = CE->mass_kg[ei];
        const bool eVac = is_vacuum(CE->void_ix, mate, masse);
        const bool eSameCap = (!eVac) && (mate == incSpecies)
                            && (masse + incMass <= incMat.maxMass);
        if (!eVac && !eSameCap) continue;                   // not receivable -> next priority
        ocx = ecx; ocz = ecz; oi = ei; oVac = eVac;
        return true;
    }
    return false;
}
```

Replace the Task-1 fall-through comment block in `apply_injections` with the real displacement branch:

```cpp
        // Displacement: relocate the incumbent to an escape neighbour; place regardless (authoritative).
        const int x = ci % CHUNK_W;
        const int y = (ci / CHUNK_W) % CHUNK_H;
        const int z = ci / (CHUNK_W * CHUNK_H);
        int ecx, ecz, ei; bool eVac;
        if (find_injection_escape(world, mats, inj.cx, inj.cz, x, y, z,
                                  incSpecies, incMass, incMat, ecx, ecz, ei, eVac)) {
            Chunk* CE = world.findChunk(ecx, ecz);
            if (eVac) {
                CE->matIx[ei]   = incSpecies;               // vacuum adopts the incumbent
                CE->mass_kg[ei] = incMass;
                CE->T_curr[ei]  = C->T_curr[ci];
            } else {
                const float m0 = CE->mass_kg[ei];           // same species -> enthalpy-weighted merge
                const float mn = m0 + incMass;
                CE->T_curr[ei]  = (m0 * CE->T_curr[ei] + incMass * C->T_curr[ci]) / mn;
                CE->mass_kg[ei] = mn;
            }
            // incumbent relocation is mass-neutral (no ledger entry).
        } else {
            if (incSpecies < ledger.sealedLoss.size()) ledger.sealedLoss[incSpecies] += incMass;
        }
        C->matIx[ci]   = inj.species;
        C->mass_kg[ci] = inj.mass;
        C->T_curr[ci]  = inj.temperature;
        if (inj.species < ledger.injected.size()) ledger.injected[inj.species] += inj.mass;
```

(Delete the old "(displacement path added in Task 2 …) plain place" lines — they are now replaced.)

> **Verify `resolve_neighbor`'s signature** by reading its definition in `sim_engine.hpp` (used heavily in Pass B′ ~line 940): it is `bool resolve_neighbor(int cx,int cz,int x,int y,int z,int dx,int dy,int dz,int& ncx,int& ncz,int& nlx,int& nly,int& nlz)` — returns false off the loaded world / above-below the column. Match it exactly.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread && ./build/injection_test`
Expected: PASS (2 tests, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add ORGE-ENGINE/sim_engine.hpp ORGE-ENGINE/tests/injection_test.cpp
git commit -m "feat(engine): injection displaces incumbent to escape neighbour (conserved)"
```

---

## Task 3: Sealed-loss path (no escape ⇒ replace, log the sink)

**Files:**
- Modify: `ORGE-ENGINE/tests/injection_test.cpp`

- [ ] **Step 1: Write the failing test**

Add to `ORGE-ENGINE/tests/injection_test.cpp` (register in `main`):

```cpp
// Inject water into an air cell SEALED on all 6 sides by a frozen (immovable, full) solid: no
// escape -> water placed anyway, air mass lost, sealedLoss[AIR] == 1.2, ledger consistent.
static void test_inject_sealed_logs_loss() {
    World w;
    uint16_t VOID = w.materials.add(Material{0,0,0,0,0,0});
    uint16_t AIR = addAir(w);
    uint16_t WATER = addWater(w);
    uint16_t ROCK = w.materials.add(Material{800.f, 2.f, 5.f, 3000.f, 3000.f,
                                             std::numeric_limits<float>::infinity()}); // frozen
    Chunk* C = w.ensureChunk(0,0); C->void_ix = VOID;

    const int x=3, y=70, z=4, ci = idx(x,y,z);
    C->matIx[ci] = AIR; C->mass_kg[ci] = 1.2f; C->T_curr[ci] = 300.f;
    // Wall the 6 neighbours with frozen rock (not receivable: not vacuum, not same species).
    const int nb[6][3] = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    for (auto& d : nb) {
        const int ni = idx(x+d[0], y+d[1], z+d[2]);
        C->matIx[ni] = ROCK; C->mass_kg[ni] = 3000.f; C->T_curr[ni] = 300.f;
    }

    LedgerAccum ledger((int)w.materials.size());
    Injection inj{0, 0, 0, ci, WATER, 1000.f, 290.f};
    apply_injections(w, w.materials, &inj, 1, ledger);

    TH_CHECK_MSG(C->matIx[ci] == WATER, "placement honoured even when sealed");
    TH_CHECK_CLOSE(C->mass_kg[ci], 1000.f, 1e-6);
    TH_CHECK_CLOSE(ledger.sealedLoss[AIR], 1.2, 1e-6);       // the lost incumbent is logged
    TH_CHECK_CLOSE(ledger.injected[WATER], 1000.0, 1e-6);
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread && ./build/injection_test`
Expected: PASS (3 tests). The sealed branch was already implemented in Task 2 (`else { ledger.sealedLoss += incMass; }`), so this is a characterization test that pins the behaviour. If it fails, the escape search wrongly found a target — debug `find_injection_escape`.

- [ ] **Step 3: Commit**

```bash
git add ORGE-ENGINE/tests/injection_test.cpp
git commit -m "test(engine): injection into a sealed pocket replaces + logs sealed-loss"
```

---

## Task 4: Concurrent injections — deterministic, no double-relocate

Multiple placements in one step can touch adjacent cells. Apply the list in a stable order, and ensure a cell that an earlier injection already used as an escape target is not stomped by a later injection's escape into the same cell. The minimal guarantee: **process injections in input order; the displacement writes are visible to later injections** (we operate on the live world), so a later injection sees the relocated incumbent and finds a *different* escape — conservation holds without an explicit claim buffer because each escape merge/adopt is read-modify-write on the live world.

**Files:**
- Modify: `ORGE-ENGINE/tests/injection_test.cpp`

- [ ] **Step 1: Write the failing test**

```cpp
// Two injections into two adjacent air cells in the same step. Both place water; both displace
// their air. Total air conserved, total water == 2000, no cell double-counts.
static void test_concurrent_injections_conserve() {
    World w;
    uint16_t VOID = w.materials.add(Material{0,0,0,0,0,0});
    uint16_t AIR = addAir(w);
    uint16_t WATER = addWater(w);
    Chunk* C = w.ensureChunk(0,0); C->void_ix = VOID;

    const int a = idx(3,70,4), b = idx(5,70,4);   // two non-adjacent air cells, free neighbours
    C->matIx[a]=AIR; C->mass_kg[a]=1.2f; C->T_curr[a]=300.f;
    C->matIx[b]=AIR; C->mass_kg[b]=1.2f; C->T_curr[b]=300.f;
    const double air0 = totalMassOf(w, AIR);      // 2.4

    LedgerAccum ledger((int)w.materials.size());
    Injection list[2] = {
        {0,0,0,a, WATER,1000.f,290.f},
        {0,0,0,b, WATER,1000.f,290.f},
    };
    apply_injections(w, w.materials, list, 2, ledger);

    TH_CHECK_MSG(C->matIx[a]==WATER && C->matIx[b]==WATER, "both placed");
    TH_CHECK_CLOSE(totalMassOf(w, AIR), air0, 1e-6);         // 2.4 conserved
    TH_CHECK_CLOSE(totalMassOf(w, WATER), 2000.0, 1e-6);
    TH_CHECK_CLOSE(ledger.injected[WATER], 2000.0, 1e-6);
    TH_CHECK_CLOSE(ledger.sealedLoss[AIR], 0.0, 1e-6);
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `cd ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/injection_test.cpp -o build/injection_test -pthread && ./build/injection_test`
Expected: PASS (4 tests). Because `apply_injections` mutates the live world per-injection in order, the second injection sees the first's result — conservation holds. If a future change introduces an explicit claim buffer (spec A3), re-run this test.

- [ ] **Step 3: Commit**

```bash
git add ORGE-ENGINE/tests/injection_test.cpp
git commit -m "test(engine): concurrent same-step injections conserve (sequential live-world apply)"
```

---

## Task 5: Wire the injection channel + `ledgerOut` into `orgeStepWorld` (ABI)

Extend the JNI entry additively, resolve `columnId→(cx,cz)`, call `apply_injections` **once before** the sub-cycle loop, and write the per-species ledger back.

**Files:**
- Modify: `ORGE-ENGINE/orge_jni.cpp:22-125` (signature + body)
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java:35-41` (native decl)

- [ ] **Step 1: Extend the JNI signature**

In `ORGE-ENGINE/orge_jni.cpp`, change the function signature (after `jcharArray jMatOut`) to add the injection channel + ledger out:

```cpp
        jfloatArray jTout, jfloatArray jMassOut, jcharArray jMatOut,
        jint injCount,
        jintArray jInjColumn, jintArray jInjCell,
        jcharArray jInjSpecies, jfloatArray jInjMass, jfloatArray jInjTemp,
        jfloatArray jLedgerOut)      // [2*matCount]: injected[0..n), then sealedLoss[0..n)
```

- [ ] **Step 2: Acquire the new arrays + build the injection vector + call apply_injections**

In the body, after the existing `auto* matOut = ...` acquisition and inside the `if (cx && cz && ...)` guard, **after** the chunk-build loop and **before** the `int n_sub = ...` sub-cycle line, insert:

```cpp
        // ---- Placement injections (spec 2026-06-02-placement-injection): apply ONCE, pre-sub-cycle ----
        if (injCount > 0 && jInjColumn && jInjCell && jInjSpecies && jInjMass && jInjTemp) {
            auto* injCol = static_cast<jint*>    (env->GetPrimitiveArrayCritical(jInjColumn,  nullptr));
            auto* injCel = static_cast<jint*>    (env->GetPrimitiveArrayCritical(jInjCell,    nullptr));
            auto* injSp  = static_cast<uint16_t*>(env->GetPrimitiveArrayCritical(jInjSpecies, nullptr));
            auto* injMs  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jInjMass,    nullptr));
            auto* injTp  = static_cast<float*>   (env->GetPrimitiveArrayCritical(jInjTemp,    nullptr));
            if (injCol && injCel && injSp && injMs && injTp) {
                std::vector<Injection> injs;
                injs.reserve(injCount);
                for (int k = 0; k < injCount; ++k) {
                    const int colId = injCol[k];
                    if (colId < 0 || colId >= nCols) continue;   // out-of-range column -> drop
                    injs.push_back(Injection{colId, cx[colId], cz[colId], injCel[k],
                                             injSp[k], injMs[k], injTp[k]});
                }
                LedgerAccum ledger(matCount);
                apply_injections(world, world.materials, injs.data(), (int)injs.size(), ledger);
                if (jLedgerOut) {
                    auto* led = static_cast<float*>(env->GetPrimitiveArrayCritical(jLedgerOut, nullptr));
                    if (led) {
                        for (int i = 0; i < matCount; ++i) {
                            led[i]            = (float)ledger.injected[i];
                            led[matCount + i] = (float)ledger.sealedLoss[i];
                        }
                        env->ReleasePrimitiveArrayCritical(jLedgerOut, led, 0);
                    }
                }
            }
            if (injTp)  env->ReleasePrimitiveArrayCritical(jInjTemp,    injTp, JNI_ABORT);
            if (injMs)  env->ReleasePrimitiveArrayCritical(jInjMass,    injMs, JNI_ABORT);
            if (injSp)  env->ReleasePrimitiveArrayCritical(jInjSpecies, injSp, JNI_ABORT);
            if (injCel) env->ReleasePrimitiveArrayCritical(jInjCell,    injCel, JNI_ABORT);
            if (injCol) env->ReleasePrimitiveArrayCritical(jInjColumn,  injCol, JNI_ABORT);
        }
```

`injCount == 0` skips the whole block ⇒ the per-cell path is byte-for-byte today.

- [ ] **Step 3: Update the Java `native` decl + wrapper signature**

In `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`, change the `native` decl (line 35-41) to match the new JNI arity:

```java
    private static native double orgeStepWorld(
            int nCols, int[] cx, int[] cz,
            char[] matIx, float[] mass, float[] tIn,
            float[] lutCond, float[] lutHeatCap, float[] lutMolar,
            float[] lutMinMass, float[] lutMaxMass, float[] lutVisc,
            int passes, double dtSeconds,
            float[] tOut, float[] massOut, char[] matOut,
            int injCount,
            int[] injColumn, int[] injCell,
            char[] injSpecies, float[] injMass, float[] injTemp,
            float[] ledgerOut);
```

Update the **caller** inside `NativeEngine` (the public `stepWorld(...)` method that invokes this native method) to pass an **empty injection batch** for now (Plan 2 wires the real queue): `injCount = 0`, zero-length `int[0]`/`char[0]`/`float[0]` arrays, and a `float[2*matCount]` `ledgerOut` it can ignore. This keeps the existing `:core` callers compiling and behaviour identical until Plan 2.

> **Exact wrapper edit depends on the current `stepWorld` body** — read `NativeEngine.java` around the existing `orgeStepWorld(...)` invocation and thread the 7 new trailing args (6 empty inputs + `ledgerOut`). Do NOT change the public `OrgeEngine.stepWorld` interface signature in this plan; the injection-carrying overload is added in Plan 2.

- [ ] **Step 4: Rebuild `liborge.so` and run the engine suite + Java compile**

Run:
```bash
cd ORGE-ENGINE && ./tests/run_tests.sh           # cheap tier incl. injection_test — expect all green
./build.sh                                        # or the repo's documented .so build command
cd /home/claude/ORGE && ./gradlew :core:compileJava   # native decl arity matches JNI
```
Expected: cheap engine tests PASS; `.so` builds; `:core` compiles against the new native arity.

> **Confirm the `.so` build command** from the repo (look for `ORGE-ENGINE/build.sh` or the README/CLAUDE notes); the unified-fluid memory references rebuilding `liborge.so` and bumping the gitlink.

- [ ] **Step 5: Commit + bump the `.so` gitlink**

```bash
git add ORGE-ENGINE/orge_jni.cpp core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java
git commit -m "feat(engine): orgeStepWorld injection channel + ledgerOut (injCount=0 = identical)"
# rebuild .so, then in the MAIN repo bump the engine gitlink + commit the rebuilt lib per repo convention
```

---

## Task 6: Parity / regression sweep + full engine stress (gated)

**Files:** none (verification only).

- [ ] **Step 1: Run the heavy engine tier to confirm no advection regression**

Run: `cd ORGE-ENGINE && ./tests/run_tests.sh full`
Expected: cheap + heavy tiers green, then the full stress green (the existing conservation oracle `== 1000.0` across flow scenarios is unchanged because `apply_injections` is only invoked with `injCount>0`, never from the pure-flow tests).

- [ ] **Step 2: Confirm `injCount==0` is byte-identical**

The displace logic is never reached by the existing tests (they pass no injections). Confirm `sort_swap`, `viscosity_flow`, `bprime_evacuate`, `displace_test` outputs are unchanged from the pre-change run (the suite asserts its own oracles; a green `full` run is the gate).

- [ ] **Step 3: Commit any gitlink/.so bump not already committed; this completes Plan 1.**

---

## Self-Review (run before declaring Plan 1 done)

- **Spec coverage (Part A):** A1 `Injection` list ✓ (Task 1, Task 5). A2 displace pre-pass ✓ (Task 2) — runs before the sub-cycle (Task 5 placement). A3 concurrent ✓ (Task 4) — note: implemented as sequential live-world apply, NOT an explicit claim buffer; the spec's claim buffer is satisfied behaviourally (each escape is a live read-modify-write). **If review wants the explicit claim buffer, add it here.** A4 ledger ✓ (`LedgerAccum`, Tasks 1-3, written back in Task 5). A5 ABI ✓ (Task 5, additive, `injCount==0` fast path).
- **Placeholder scan:** no TBD/"handle edge cases"; every step has real code or an exact command.
- **Type consistency:** `Injection{columnId,cx,cz,cellIndex,species,mass,temperature}`, `LedgerAccum{injected,sealedLoss}`, `find_injection_escape(...,ocx,ocz,oi,oVac)`, `apply_injections(world,mats,list,count,ledger)` — names identical across tasks. Task-1 `Injection` literal updated to the 7-field shape in Task 2.
- **Carve-out for Plan 2:** the Java queue, the incumbent-feeding at injected cells, the §9 gate consuming `ledgerOut`, the `MaterialChangeReseed` split, and the `OrgeEngine.stepWorld` injection-carrying overload are **Plan 2** (`2026-06-02-placement-injection-2-java.md`).

---

## Execution Handoff

Plan 1 (engine) complete and saved. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks (REQUIRED SUB-SKILL: superpowers:subagent-driven-development).
2. **Inline Execution** — execute tasks in this session with checkpoints (REQUIRED SUB-SKILL: superpowers:executing-plans).

Plan 2 (Java: queue + marshal + ledger gate + reseed split) is written **after** Plan 1 lands, since it builds on the ABI this plan ships.
