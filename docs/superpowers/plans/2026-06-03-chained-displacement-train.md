# Plan — chained displacement push-train (lava/water equilibrium fix)

Date 2026-06-03 · Engine submodule `main` HEAD `397ee4c` · Main branch `rebuild`.
Root cause already found (see `/tmp/orge-handoff-task1-lava-water-displacement-fix.md`).
This plan IMPLEMENTS the fix. **Engine-only change, ONE file: `ORGE-ENGINE/sim_engine.hpp`.**

## Problem (confirmed RED)
`pass_bprime_displace` relocates a displaced lighter neighbour `j` in ONE hop, and accepts
only **vacuum** or **same-species-with-room** as the escape (`sim_engine.hpp:1062-1065`). When
`j`'s only escape is yet another strictly-lighter movable species (water capped by `orge:air`,
or a molar-sorted tube), the event aborts → lava never displaces water UP (Bug 2), and the
unresolved frontier freezes lava↔lava leveling (Bug 1, downstream).

RED repro (`tests/lava_water_equilibrium_test.cpp`): BUG scenario stuck `[water 1000, lava 500,
lava 400]`; CONTROL A `[300,300,300]` and CONTROL C `450/450` already pass. Tube repro
(`tests/push_chain_tube_test.cpp`) prints "row changed? NO".

## Design decisions (user, 2026-06-03)
1. **Cadence = whole train on the slowest link.** Gate the entire event on
   `advanced(maxVisc, t0, t1)` where `maxVisc` = max viscosity over the donor `i` and every
   relay cell that MOVES. Atomic: all cells shift together when the slow link's cadence fires.
2. **Walled dead-end = reject the whole train.** No reachable sink → no move this step
   (no compression).

## NOT in scope / non-constraints
- `orge_kernel.hpp` is conduction-only (keff/finalize_temp + tunables); it does NOT contain the
  advection passes. **No kernel mirroring** — leave `orge_kernel.hpp` untouched.
- No JNI/ABI change (`orge_jni.cpp` untouched). No Java change.
- Bit-identical-to-old-engine is NOT a goal (behavior intentionally changes). The goal is:
  new repros GREEN, existing conservation/spread tests still GREEN.

## The algorithm — N-deep chained displacement
Generalize the current 2-cell event into an N-cell atomic chain. The length-1 chain (`j → sink`
directly) MUST reduce to today's exact behavior so existing displace/spread tests stay green.

Donor `i`: movable, supported (below not vacuum), `massi > minMass`, unclaimed. Chosen
horizontal neighbour `j`: strictly-lighter (`molar_j < molar_i`) DIFFERENT movable species with
real mass, unclaimed. (Same selection as today, lines ~967-1019.)

**Build the chain** — DFS from `j` for a path `[c1=j, c2, …, cn=sink]`:
- At each node `c_k`, scan its escape neighbours in the EXISTING priority order
  `esc[6] = {DOWN, hashed-4-horizontals, UP}` (per-cell FNV hash of `c_k` coords — keep it,
  it is what makes the engine deterministic run-to-run).
- Reject any candidate that is `i`, any cell already on the current path (cycle guard), or any
  cell already set in the global `claim` buffer.
- A candidate `e` is a **SINK** iff: vacuum (`is_vacuum`) OR same-species as `c_k` with room
  (`masse + mass_{c_k} <= maxMass`). Prefer an immediate sink: scan all `esc` dirs first for a
  sink; if found, terminate (`cn = e`, record sink mode), chain DONE.
- Else a candidate `e` is a **RELAY** iff strictly-lighter movable than `c_k`
  (`molar_e < molar_{c_k}`) with real mass (`> ADV_EPS_MASS`). Recurse into `e` (in esc
  priority order); first relay whose subtree reaches a sink wins.
- If no dir yields a sink or a sink-terminating relay → this node fails (backtrack).
- Cap depth at `MAX_CHAIN_DEPTH` (= 64, safety; molar strictly decreases per relay hop so real
  chains are short). If `j` yields no chain → try `i`'s next push dir; none → `i` does nothing.

**Cadence gate (after a chain is found):** `maxVisc = max(viscosity)` over `{ i, c1..c_{n-1} }`
(the donor + all relays that move; the sink only absorbs). If `!advanced(maxVisc, t0, t1)` →
skip without claiming (train waits this sub-step).

**Affordability (unchanged):** `dose = mI.minMass`; require `massi - dose >= mI.minMass` and
`dose <= mI.maxMass` and `dose > ADV_EPS_MASS`; else skip. Relays move their WHOLE snapshot
content (like `j` does today).

**Commit (atomic, antisymmetric, all reads from `snapBp`):** let `(S_k, M_k, T_k)` be `c_k`'s
snapshot species/mass/temp.
- `i`: `dMass -= dose; dEnth -= dose*T_i`.
- `c1` (=j): override → `(species_i, dose, T_i)`.
- each relay `c_k`, `2 <= k <= n-1`: override → `(S_{k-1}, M_{k-1}, T_{k-1})` (adopts the cell
  behind it).
- sink `cn`: receives `(S_{n-1}, M_{n-1}, T_{n-1})` — if vacuum → override adopt
  `(S_{n-1}, M_{n-1}, T_{n-1})`; if same-species → additive merge
  `dMass += M_{n-1}; dEnth += M_{n-1}*T_{n-1}`.
- Claim `i` and every `c1..cn`.

Per-species conservation (proves it balances): species_i `{i:-dose, c1:+dose}=0`; `S_1
{c1:-M_1, c2:+M_1}=0`; `S_k {c_k:-M_k, c_{k+1}:+M_k}=0`; `S_{n-1} {c_{n-1}:-M_{n-1},
cn:+M_{n-1}}=0`. n=2 reduces to today's 3-cell event exactly.

Implementation note: a clean way is a recursive lambda `buildChain(node, pathSoFar) -> optional
path` reading `snapBp` + `claim`, returning the ordered cell list + sink mode; then a single
commit loop. Keep all the existing helpers (`resolve_neighbor`, `hdPerm`, `is_vacuum`,
`ensureClaim`, the per-step `HDi` rotation for `i`'s push-dir scan).

## Tasks

### Task 1 — Track the RED repros (engine submodule, local commit)
`git add ORGE-ENGINE/tests/lava_water_equilibrium_test.cpp tests/push_chain_tube_test.cpp`
(they are currently untracked and would be lost on a fresh checkout). Do NOT add to
`run_tests.sh` yet (still RED). Commit in the engine submodule (not pushed — engine pushes
batch at ship).
Verify: both build with `g++ -std=c++20 -O2 -g -I. tests/<name>.cpp -o build/<name> -pthread`;
equilibrium prints "2 FAILURES"; tube prints "row changed? NO".

### Task 2 — Implement the chained walk in `pass_bprime_displace` (the meat)
Replace the single-escape block (~lines 1021-1112) with the chain build + cadence gate + atomic
multi-cell commit per the algorithm above. `sim_engine.hpp` ONLY.
GREEN gates:
- `tests/lava_water_equilibrium_test.cpp`: BUG → lava reaches c0 + lava cells equalise; CONTROL
  A and C still pass; all conservation `CHECK`s hold every step.
- `tests/push_chain_tube_test.cpp`: row shifts one toward the vacuum sink (heaviest occupies 2
  cells); wall-blocked variant (if present) does NOT move (reject-on-dead-end).
- Existing tier stays green: `displace_test`, `spread_2d_test`, `viscosity_spread_test`,
  `min_mass_occupancy_test`, `relax_spread_test`, `correctness_test` (heavy) and the cheap tier
  (`unified_basics`, `sort_swap`, `viscosity_flow`, `bprime_evacuate`, `time_dt`, `injection`,
  `resident_lut`).
Then add `lava_water_equilibrium_test` to the cheap tier in `run_tests.sh` (it converges in
<100 steps) and `push_chain_tube_test` as a named/cheap test. Commit (engine, local).

### Task 3 — Full verification
- `./ORGE-ENGINE/tests/run_tests.sh full` (cheap + heavy; do NOT run the hours-long stress).
- Rebuild the bundled `.so`: `./native/build_liborge.sh`; refresh
  `core/src/main/resources/natives/linux-x64/liborge.so`.
- `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest` (real `.so`).
- Both loaders build (`:fabric:build :neoforge:build` or the repo's usual).

### Task 4 — Ship
- Commit + push engine `origin/main`; note the new ENGINE sha.
- Commit the refreshed `.so` + bump the engine gitlink on `rebuild`; push `origin/rebuild`
  (after EVERY commit — user tests from origin/rebuild).

## Risk register
- **Conservation under chains** — every commit is per-species antisymmetric (proof above); the
  test's per-step `mass_of` CHECKs are the guard. Watch the same-species-merge sink (additive)
  vs override (species change) split — mixing them double-counts.
- **n=2 must equal today** — if any existing displace/spread test regresses, the length-1 chain
  diverged from the old 3-cell event; reconcile.
- **Cost** — DFS bounded by depth cap + strictly-decreasing molar; do not remove the cycle
  guard or the cap.
- **Determinism** — keep the FNV `hdPerm` escape order and the per-step `g_bprimeRot` push-dir
  rotation; introduce no rng/clock.
- **Cadence** — `maxVisc` over donor + relays only (not the sink); a frozen (INF visc) cell can
  never be a relay (it is immovable, fails the movable check) so it can't poison the max.

## Hard constraints (verbatim)
- `JAVA_HOME=/home/claude/jdk21` for ALL gradle.
- Push `origin/rebuild` after EVERY main-repo commit. Engine pushes batch at ship (Task 4).
- Engine submodule remote URL embeds a token — NEVER echo it.
- Do NOT run the hours-long stress test; use `run_tests.sh full`.
- `movable()` governs FLOW only, never placement/seed.
