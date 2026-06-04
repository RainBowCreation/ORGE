# Unified Fluid Math — Design

Date 2026-06-04 · Branch `rebuild` · Engine submodule `main`.
**Status: DESIGN (math locked through §3; packaging §7 and two sub-forks still open — see "Open decisions").**
This is the math model for the *entire* fluid simulation — gravity, lateral leveling, communicating-vessels
rise, and cross-species displacement — driven by a single head-pressure field. No code yet; an implementation
plan follows separately once this is approved.

---

## 0. Goal

One model that makes a connected liquid body find its own level (U-tubes, complex cavities, manometers),
unifying what are today three separate passes, **without** reopening the spontaneous-upward-flood bug. The
model must be a local stencil (CPU now, GPU-portable later) and preserve the engine's existing conservation
and determinism guarantees.

The motivating gap: the engine has no pressure model, so a connected liquid cannot raise its free surface —
`u_tube_test` scenario A (pure water, no driver) does not self-level. This design closes that gap.

---

## 1. Locked laws

| # | Law |
|---|-----|
| **A** | **Relaxation, not snap.** Equilibrium emerges from repeated *local* face-to-face transfers over many sweeps — never a global one-shot solve. (Like the existing heat conduction.) |
| **B** | **One head-pressure field** drives everything: `Π = (m − min) + O`, where `O` is the weight of all fluid stacked above the cell. |
| **C** | **`dt` scales the AMOUNT moved, never the DISTANCE propagated. One call = one sweep.** Front speed is capped at 1 cell/dispatch (`max_mass` forbids overfilling, so a front can only advance one cell per snapshot sweep). `dt` catches up *amounts* (heat, mass between cells with headroom); it never fast-forwards the front. Under load → deterministic slow-motion, never a stall-spiral. **Implementation: delete the `n_sub = round(dt/DT_CFL)` sub-cycle in `orge_jni.cpp:149–162`; `step(dt)` runs exactly one conduction + one advection sweep, passing `dt` straight through.** This reverses the 2026-06-02 sub-cycle decision. |
| **D** | **Whole-cell, no mixing.** 1 block = 1 m³ = exactly one material. Two substances never coexist in a cell. Gravity/buoyancy = molar-mass sort (heavy sinks, light floats), whole-cell swaps only. |
| **E** | **Conservation & determinism are reused verbatim** from the existing passes: snapshot-read, single-donor CLAIM, per-donor outflow budget, deterministic FNV `hdPerm` tie-breaks. No rng, no clock. |

---

## 2. §1 — The pressure field

### 2.1 Overburden `O`

`O_i` = the **total mass of every cell stacked directly above `i`** in its vertical column, up to the surface
(or the top of the loaded world). All species count — a lava cell feels the water and air resting on it.

> **Density needs no coefficient.** A denser fluid simply has more mass per block (`max_mass` lava > water > air),
> so summing real masses already weights by density. The earlier "does `O` need a ×ρ term?" question is therefore
> resolved: **no** — `O = Σ real mass above` is already the density-weighted hydrostatic load. (§4's manometer is
> the proof.)

### 2.2 Drive pressure `Π`

```
Π_i = (m_i − min_i) + O_i
```

`(m − min)` is the local surplus (today's drive). `O` is the new term — the column weight that makes a deep cell
"feel" the load above it. Without `O`, the bottom of a full column has surplus 0 and cannot drive anything (the
U-tube bug); with `O`, the floor of a tall arm has a large `Π` that pushes toward a shorter connected arm.

### 2.3 Per-face transfer amount

Each unordered face is processed once; donor = higher `Π`. The amount moved is the **smallest of four limits**:

```
d  = Π_donor − Π_recv                       (skip the face if d ≤ 0)
dm = min( rate(visc) · dt · d ,             # what pressure wants (rate = speed knob; dt scales amount)
          0.5 · d ,                         # stability cap — never move more than half the gap per sweep
          donor_budget ,                    # donor's max outflow across ALL its faces (per-donor budget)
          recv_room )                        # = max_mass(recv) − m_recv  (incompressible; pins front to 1 cell/sweep)
```

Antisymmetric commit: donor `−dm`, receiver `+dm`. The `recv_room` cap is what makes law C true — a front can
only advance one cell per sweep because the receiver cannot exceed `max_mass`.

### 2.4 The vertical baseline (why upward flow is special)

For any vertical same-column pair (`j` directly above `i`), because `O_i = m_j + O_j`:

```
Π_i − Π_j = (m_i − min) + (m_j + O_j) − [(m_j − min) + O_j] = m_i
```

So in **any calm column, the lower cell's pressure exceeds the upper's by exactly its own mass `m_i`.** A naive
"high Π pushes to low Π" rule would therefore push water *up everywhere* (catastrophic flood) — which is why the
engine currently bans upward flow outright. §2 replaces that blunt ban with a precise gate.

### 2.5 Pressure-tally freshness (OPEN sub-fork)

`O` requires a read **up the cell's own column** — a straight vertical line, not a sideways flood-fill. Two ways:

- **(i) Re-tally every sweep (recommended).** One downward pass per `(x,z)` column each sweep → exact `Π`. Pressure
  effectively snaps down a column in one sweep (physically fine: pressure travels far faster than the fluid). Cost:
  one `O(height)` scan per column per sweep; columns independent (GPU = per-column parallel prefix-scan).
- **(ii) Incremental (`O_i = m_above + O_above`, single-upper-neighbor read).** Pure 1-neighbor stencil; `O` crawls
  down at 1 cell/sweep, perfectly uniform with law C, but the head lags a sweep or two while a column changes fast.

Recommendation: **(i)** — simpler, exact, and a per-column scan is cheap and still embarrassingly parallel.

---

## 3. §2 — The flood guard (the crux invariant)

**Invariant: a connected body rises to equalize a free surface, but flat liquid with air above it NEVER climbs.**

### 3.1 The local predicate (recommended)

On an **upward** face (`i` below `j`, same species), subtract the calm-column baseline before flowing:

```
upward flow i→j is allowed only when   (Π_i − Π_j) > m_i
effective upward drive                  d_up = (Π_i − Π_j) − m_i      (flow only the positive excess)
```

Read plainly: *let water climb only when the pressure below is **more** than a calm column would ever produce —
because that surplus can only come from a taller connected body pushing head in.* Horizontal and downward faces use
the plain `d`; only upward faces carry the `− m_i` correction.

- **Flat pool + air above** → every vertical gap is *exactly* `m_i` → `d_up = 0` → **never climbs.** ✓
- **U-tube far arm, tall arm still feeding the floor** → horizontal inflow lifts the floor's `Π` *above* the
  baseline → `d_up > 0` → **rises**, and stops the instant the two surfaces match. ✓

This is purely local (cell + its neighbor + its own column tally), so it honors laws A and C and ports to GPU as a
stencil with no global scan.

### 3.2 The exact alternative (fallback)

Flood-fill the connected body each step, compute its true free-surface height `H`, allow upward flow only into
cells below `H` (the Dwarf Fortress mechanism, made conservative/deterministic). Bulletproof for any shape, but
costs a connectivity scan per body per step and is awkward on GPU.

### 3.3 Decision

**Adopt the local predicate (3.1) and red-team it hard** (see §8); keep the exact height (3.2) as the documented
fallback if the local proxy springs a leak we cannot close. Red-team targets: mass conservation under the
single-spend budget when upward and horizontal faces fire together; convergence on multi-arm and *looped* cavities;
any shape where "locally more-than-calm" is true but no taller column actually feeds it.

---

## 4. §3 — Multiple fluids (displacement + manometer)

One field, three behaviors. At each face:

- **Same species** → mass flows / levels (§1 amount, §2 flood guard).
- **Different species** → **the higher-`Π` side displaces the loser** via the push-train. The loser cannot vanish
  or mix; it takes the **nearest cell it fits into**, hunted in a deterministic, buoyancy-preferred order (light
  fluid hunts UP first, heavy hunts DOWN first; ties broken by FNV `hdPerm`). If the immediate neighbor is full,
  the shove continues **N-deep like a train** (every cell shifts one, the last rolls into the first empty sink). If
  the whole chain is sealed with no sink, the push is **rejected — nothing moves** (incompressible).
- **Molar mass sets the resting order** — after motion, each column is layered dense-at-the-bottom.

Pressure drives the *motion*; density sets the *final stacking*. They agree at equilibrium.

### 4.1 Manometer (the case the engine cannot do today)

Today's displacement is "heavy pushes light, head-blind" — it cannot let a *light* column displace a *heavy* plug
upward. With `Π = surplus + Σ mass above`, it falls out for free. Oil = 50/block (light), water = 100/block (heavy),
U-tube joined at the floor:

| | left arm: oil | right arm: water |
|---|---|---|
| height | 6 | 3 |
| head (mass×height) | 6 × 50 = **300** | 3 × 100 = **300** |

Equal heads → balanced; the **light oil stands twice as tall** as the heavy water (heights inverse to density — a
real manometer). Start the water arm too short (height 1, head 100) and the oil floor pressure (300) beats the water
floor (100) → **light oil pushes heavy water *up* the far arm** until the heads match at height 3. Automatic, because
`O` is a sum of real masses.

---

## 5. §4 — Gravity & buoyancy from the same field

Gravity/buoyancy stays the **molar-mass sort** (law D): the existing `pass_a_sort` whole-cell vertical swap
(heavier sinks, lighter rises) and same-species downward compaction. This is *consistent with* the head field, not
a competitor: the sort establishes the dense-below-light resting order that the manometer balances around, while
`Π` drives the lateral transmission and the conditional upward rise. The Game of Flow's "merge two cells and
re-split by density" is **rejected** — it requires per-cell fluid mixtures, illegal under law D. We keep whole-cell
swaps; the sort is the correct voxel adaptation and needs no change.

---

## 6. §5 — Conservation & determinism

Carried verbatim from the existing passes (law E):

- **Snapshot-read**: every transfer reads a frozen `WorldSnapshot`; writes accumulate in a `BAccum` applied once.
- **Single-donor CLAIM**: each cell is the subject of at most one event per sweep.
- **Per-donor outflow budget**: a donor's total handout across all its faces ≤ `max(0, snapMass − min)` — prevents
  the multi-face double-spend that manufactures mass in 2-D. (Conservation tests MUST be ≥4-face / 2-D, not 1-wide
  channels — see the prior lesson.)
- **Determinism**: no rng, no clock; FNV `hdPerm` coordinate hashes break all ties; bit-reproducible across runs.
- **Antisymmetry**: donor `−dm`, receiver `+dm`, exactly.

Per-species mass is conserved bit-exact every sweep, including across chunk/section seams (storage-only boundaries).

---

## 7. §6 — GPU portability mapping

The model is a stencil by construction (the whole point of choosing relaxation):

| Quantity | CPU | GPU |
|---|---|---|
| Column overburden `O` | per-column downward scan | per-column parallel **prefix-scan** (columns independent) |
| Per-face transfer | neighbor read + min() | **stencil** kernel |
| Lateral coupling (communicating vessels) | iterate sweeps | **Jacobi** iteration (one cell/sweep info speed) |
| Conservation | single-donor CLAIM + budget | atomics or **graph-coloring** / checkerboard (cf. Game of Flow's dual-cell update) |

Notes: snapshot-read = Jacobi (race-free by construction); FP-determinism across GPU vendors needs care (fixed
reduction order). GPU is a **separate later track** — a CPU path stays mandatory (server fallback + the bit-identical
`liborge.so` reference). The math is validated on CPU first (§8) before any port.

---

## 8. §7 — Packaging: A+ vs B (OPEN — decide with user)

The math is identical either way; the question is how it lands in code.

- **A+ — edit the existing passes.** Add `O` to the `Π` used in `pass_b_relax`; add the §2 upward gate; extend
  `pass_bprime_displace` to be head-driven (currently head-blind). Lowest risk — reuses proven conservation
  machinery; can collapse to one field later. **Recommended.**
- **B — one unified field now.** Rewrite the three passes into a single head-relaxation pass. Cleaner end state, but
  a full rewrite of a just-shipped subsystem.

**Undecided — present to user after the math is approved.**

---

## 9. §8 — Test / verification plan (math-first, CPU before any GPU port)

Gates in order, using `./ORGE-ENGINE/tests/run_tests.sh [fast|full]` (NOT the hours-long stress test):

1. **`u_tube_test` scenario A → GREEN** (pure water, no driver, self-levels). The headline gate.
2. **Manometer** — two immiscible fluids settle at heights inverse to density (§4.1 numbers).
3. **Flat-no-climb** — flat liquid + air above, no driver, NEVER rises (reuse the reviewer's flood-probe scenarios).
   The flood-guard invariant.
4. **Multi-arm / overflow** — 3+ connected arms equalize; an over-tall arm overflows into the others.
5. **Complex cavity** — irregular connected body reaches a flat surface; **looped** topology converges (the local-
   predicate red-team).
6. **Strict conservation** — per-species mass bit-exact every sweep on a ≥4-face donor, across seams, over ≥100 steps.
7. **Determinism** — identical input → byte-identical output across runs.

Then the loaders/integration: `:core:test` and `:core:integrationTest` on the real `.so`, plus both loader builds.

---

## 10. Open decisions (need user sign-off)

1. **Pressure-tally freshness** (pressure field, subsection 2.5): re-tally each sweep (recommended) vs incremental
   1-cell/sweep.
2. **Flood guard** (flood-guard section, subsection 3.3): confirm local predicate (recommended, red-teamed) vs exact
   connected-height fallback.
3. **Packaging** (packaging section): A+ (edit passes, recommended) vs B (unified rewrite).

---

## Appendix — prior art (why we build, not borrow)

- **The Game of Flow (Heintz 2017)** — multi-fluid voxel CA. Stores a *mixture* per cell (array of n fluid amounts)
  → **illegal here** (law D). Horizontal-diffusion + gravity-descent only, **no pressure** → cannot do communicating
  vessels (same gap as ORGE today). Validates: per-type CA storage, density-sort gravity *concept*, dual-cell GPU
  update. Does not provide the hydrostatic rise.
- **Dwarf Fortress** — voxel/tile game that *does* communicating vessels, via an explicit pressure rule: water rises
  to the height of the tile pressure is exerted on (= connected source height), no higher. This is the §3.2 exact
  fallback; our `O` is its principled, conservative, deterministic form.
- **FLIP/PIC, SPH, CubbyFlow, Blender FLIP** — get water-finds-its-level via momentum + a **global pressure Poisson
  solve** (or an equation-of-state). Correct but heavy, not block-discrete, not bit-deterministic for parity. Our
  iterative head relaxation is the discrete, conservative, local analog of that Poisson solve.

**Conclusion:** no voxel CA in the literature does correct communicating vessels; the ones that do (DF, FLIP) all pay
for it with an explicit pressure mechanism. `O` (overburden → conditional upward flow, gated by the flood guard) is
that mechanism, built to ORGE's conservation/determinism/GPU-stencil constraints.
