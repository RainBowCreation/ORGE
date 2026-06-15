# Engine-B — Unified working spec v4 (flow / force / resistance / thermal / radiation)

**Date:** 2026-06-10 · **Status:** **RATIFIED v4.1** (full redesign authorized by the user 2026-06-10;
v4.0 adversarially reviewed by a 6-lens fleet — 2 blockers + ~25 majors found and fixed; **the user
ratified all law amendments 1–9 the same day — they are applied in `../DESIGN-LAW.md`'s current text, so
every `[LAW-AMEND-n]` tag below is now a satisfied cross-reference, not a gate**).
**Subordinate to [`../DESIGN-LAW.md`](../DESIGN-LAW.md)** — where they disagree, the law wins.

> **Supersedes — and the superseded files were DELETED 2026-06-10** (user-ordered doc cleanup; text in git
> history): spec v3, the vector-map decomposition, the 2026-06-04 unified-formula, the CANONICAL pipeline
> note, FORK-DECISIONS, and the T1–T8 plan/handoffs. This file + the law + 00-MASTER-RULES + the audit
> report are the complete current doc set. Driving audit:
> [`../notes/2026-06-10-physics-math-audit-report.md`](../notes/2026-06-10-physics-math-audit-report.md);
> §12 maps every audit finding → disposition.

---

## §0 — The frame (kept from v3, units fixed)

**`force > resistance`.** Pressure is a scalar; only force moves mass. One step ENCODE → RESOLVE → DECODE.
All formulas carry **full units** *(fixes audit B-7 family)*; reference build: dx=1 m, V=1 m³, A=1 m²,
g=10 m/s², dt ∈ [0.25, 0.5] s. Notation: `ṁ` below denotes the **per-tick transferred mass [kg]** (the
flux already integrated over dt).

| mechanism | driving FORCE [N] | resistance (threshold) | rate |
|---|---|---|---|
| FLOW | `−∇P·V` (6-face surface integral of the ONE field `P`) | ≈0 (fluid) | viscosity λ |
| PUSH / yield | net `−∇P·V + m·g⃗ + external` | `τ_y` [N] *(force threshold — A-4)* | viscosity λ |
| SWAP, cross-species | buoyancy `(ρ_eff,up − ρ_eff,low)·g·V` | `k_c·min(minMass)·g + τ_y` | swap cadence (§5.3) |
| SWAP, same-species (thermal reorder) | same | **`τ_y` only — NO cohesion term** | swap cadence (§5.3) |

Same-species pairs break no interface, so cohesion does not apply — this is what lets a 126 N thermal
buoyancy clear the gate (worked: §7.3) *(review blocker #2; fixes audit C-11)*.
`ρ_eff = (m/V)·(1 − β·(T − T_ref,global))` `[LAW-AMEND-6]` is read **only by the swap machinery** (gate,
cadence, PASS-R0 choice); mass itself never changes.

---

## §1 — State & material data

### §1.1 Persisted per cell (law #7 extensive set — replaces v3 §9.1's manifest; *fixes B-12*)
`{ matIx, mass_kg, momentum (px,py,pz) [kg·m/s], enthalpy E [J], P [Pa], swapReady, void_ix }`.
Intensive derived every tick, never stored: `u⃗ = p⃗/m`, `T = h⁻¹(E/m)` (§8.1).
- `P` is the **one** persisted pressure field. (A second device buffer for `P` exists only as a
  tick-boundary copy; **within a sweep the relaxation is in-place per color** — §3.2.)
- `swapReady` = the §5.3 cadence accumulator: one float + a partner key; reset when either cell's payload
  changes (key mismatch ⇒ reset). `void_ix` = engine bookkeeping (free-list slot index), **no physical
  meaning**. Both are bookkeeping extensions of law #7's enumerated state → listed in `[LAW-AMEND-6]`.
The frozen manifest (fields + knobs, units + legal ranges) is **§1.3**; INV-3 = struct/knob diff against
§1.3 must be empty.

### §1.2 Material LUT — fixed schema (law #8 + `[LAW-AMEND-6]`), REALISTIC data *(fixes MAT-1/2/5, B-11, C-5)*

Schema: `cp [J/kgK] · k [W/mK] · M [kg/mol] · minMass · defaultMass · maxMass [kg/cell] · μ [Pa·s] ·
τ_y [N] · ε · β [1/K] · latentHeatMin/Max [J/kg] · phase quadruple · T_ref,gas [K] (gas rows)`
(`τ_y` = a force threshold [N]; the move gate compares `max(τ_y,i, τ_y,j)` N vs N — `[LAW-AMEND-v42-A4]`).

| material | min/default/max | cp | k | μ | τ_y | ε | β | M | phase (K→target, L J/kg) |
|---|---|---|---|---|---|---|---|---|---|
| water | 125 / 1000 / 1000 | 4186 | 0.6 | 1.0e-3 | 0 | 0.96 | 2.1e-4 | 0.018 | 273→ice L=3.34e5 · 373→steam L=2.256e6 |
| ice | 917 / 917 / 917 | 2108 | 2.2 | ∞ | ∞ | 0.97 | 5e-5 | 0.018 | — · 273→water L=3.34e5 |
| lava | 330 / **2650** / 2650 | 1150 | 1.5 | 5.0e2 | 0 | 0.95 | 5e-5 | 0.065 | **1275→stone L=4.0e5** · — |
| stone | 2700 / **2700** / 2700 | 800 | 2.5 | ∞ | ∞ | 0.90 | 2e-5 | 0.065 | — · 1450→lava L=4.0e5 |
| air | 1.0 / 1.2 / 1000 | 1005 | 0.026 | 1.8e-5 | 0 | 0 | (gas: EOS) | 0.029 | — · — (T_ref 288) |
| steam | **0.06 / 0.6 / 1000** | 2080 | 0.025 | 1.3e-5 | 0 | 0 | (gas: EOS) | 0.018 | 373→water L=2.256e6 · — (T_ref 373) |

- **χ is defined HERE** *(review: it had no definition in the authoritative set)*:
  `χ = (maxMass − defaultMass)/(maxMass − minMass)`, **guard `χ ≡ 0` when maxMass == minMass**.
  Gas classification: χ > 0.999. Margins: air χ = 0.99980, steam χ = 0.99946 — both clear, barely; the
  0.999 cutoff and these margins are pinned here so a band edit cannot silently demote a gas.
- Reality anchors: **lava 2650 < stone 2700** (melt lighter than its solid — un-inverts MAT-1);
  **lava→stone at 1275 K** = basalt solidus (review: 1000 K was ~300 K too cold and stretched crusting
  times; freeze/melt hysteresis now 175 K ≈ real liquidus–solidus gap); steam gets a real **gas band**
  (kills its χ=0/0; a boiled 1000 kg cell is *in-band* — §8.4); ice 917 < water (floats — data ready;
  solid flotation remains gated on the deferred granular stage); gas ε=0 ⇒ radiatively transparent
  *absorbers* (§8.3; steam's real IR absorption is a noted simplification).

### §1.3 Frozen manifest (CI-diffed; INV-3)
**Persisted fields:** §1.1's seven (+ the `P` tick-boundary copy).
**Knobs:** `ω` SOR factor [1.0–1.9] · `κ` dynamic-pressure stiffness [Pa·s/sweep], bound `κ ≤ ρ̄·dx²/dt`,
applied **once per tick (first sweep only)** · `N_relax` sweeps/tick [1–32] (clamped, default 4) · `vel_damp` [0–2 s⁻¹] ·
`ε_mass` = 1e-6 kg · `k_c` = 3 (cohesion, cross-species swaps only) · `t_swap_min` = 0.5 s ·
`α_eos` gas-EOS relaxation gain (0–1] · `T_sky` = 270 K · `T_ref,global` = 288 K (ρ_eff only) ·
per-gas `T_ref,gas` (LUT column). **Joint stability constraint** `ω·(1+α_eos) < 2` — the gas relaxation's
combined SOR+EOS update diverges otherwise (the independent `ω`/`α_eos` ranges admit divergent pairs)
`[LAW-AMEND-v42-A5]`. Retired symbols (must not reappear): `own_weight_head, p_surf, swap_kv,
swap_threshold, head_relax, p_ac_scale, (1−χ) relaxation factor, T_curr/T_next, persisted vx/vy/vz`.
`LADDER_BETA`, `LADDER_REST_DEADBAND`, `GAS_CHI_MIN` are **T4-transient gas-ladder knobs, exempt from INV-3**
until T4's flux-intent rebuild deletes them — the manifest CI-diff does not fail on them meanwhile
`[LAW-AMEND-v42-A5 / owner T4]`.

---

## §2 — EOS (pressure from own state)

### §2.1 Gas EOS — LIVE *(fixes B-3/C-1/PC-2)* `[LAW-AMEND-7]`
```
p_abs = (m/M)·R·T/V          R = 8.314 J/(mol·K)
P0    = (m0/M)·R·T_ref,gas/V # per-gas rest pressure at its own rest state (LUT column)
p_eos = p_abs − P0           # GAUGE: 0 at the gas's own rest state
```
Air rest: (1.2/0.029)·8.314·288 ≈ 99.1 kPa ⇒ gauge 0. Steam rest at 373 K: ≈ 103.4 kPa ⇒ gauge 0
*(per-gas T_ref — review: a global 288 K left resting steam pushing +24 kPa forever)*.
**Cross-gas faces compare ABSOLUTE pressure** (`p_eos + P0` each side) so per-gas gauge offsets cancel
`[LAW-AMEND-v42-A2 / owner T4]` — to be implemented at **hetero-gas (different-species) faces on the EOS
anchor** in T4 (same-gas faces stay gauge-cancelled); INV-AL needs an air|steam absolute-P variant once it
lands.
> **GW-1 note — `[T4-OPEN cross-gas-abs-P]` / ST7 force-frame escalation is MOOTED for the force frame-mix.**
> Once every face reads the **single relaxed `P`** (GW-1), there is no per-gas gauge frame to reconcile *in
> the force* — the one `P` field carries the cross-gas ΔP directly. So the force never mixes two gas frames.
> **HOWEVER, resting hetero air|steam absolute-P equilibrium remains DEFERRED:** INV-ATMOS / INV-AL is
> **air-only**, and GW-1 does **not** solve a resting air|steam interface (the relaxation source still uses
> each gas's own gauge anchor). Do NOT over-claim a resting two-gas interface as solved.
Compressed air at the in-game-observed 835 kg/cell: **p_eos ≈ 69 MPa** — the missing pushback.
`p_eos` is **never SUMMED with `P` as a second pressure for the same cell** — exactly one pressure number
acts per cell; it enters the force only through the §3.1 relaxation that sets `P`, and a *gas* cell's own
boundary face uses the **anchored relaxed `P`** (`liqf`, the hydrostatic half-cell ghost — identical to a
liquid boundary face), per GW-1 (the per-cell EOS gauge anchor in the force is retired) `[LAW-AMEND-v42-A1]`
*(review: the v4.0
"(+ gas p_eos)" face read was a drift-test-(a) double-count; deleted)*.
~~A gas cell's relaxed `P` is a **force-dead diagnostic** — every gas face uses its EOS anchor, not `P`; the
relaxed `P` is computed and persisted but never read by the force, retained as a tracked diagnostic~~
`[LAW-AMEND-v42-A8]` **SUPERSEDED-by-GW-1 (ratified 2026-06-15).** Replaced by:
A gas cell's force is driven by the **single relaxed `P`** (LAW #1/#2), identically to a liquid: every face
reads `P` (the hydrostatically-anchored relaxed field), and the live gas EOS enters **only** as the §3.1
relaxation **source** (`α_eos·(p_eos,i − P_i)`) that drives `P` toward the gauge EOS. The standalone per-cell
gauge **face anchor is retired** — there is exactly one pressure representation per cell. Because `P` is
iteratively relaxed and persisted (LAW #1), the cell-to-cell pressure coupling is **implicit**: it carries the
EOS pushback without an explicit acoustic-CFL ceiling, which is the standard low-Mach / pressure-projection
integration. The real EOS is unchanged — a 700×-compressed pocket still relaxes `P` to its ~69 MPa and pushes
back; only the *time integration* of the coupling changes (implicit, not explicit). At a **gas|vacuum**
boundary the force reads the relaxed field's **hydrostatic anchor** (not raw absolute `p_abs`), so a resting
atmosphere does not rail into the vacuum (the ST7 failure mode of driving from raw absolute pressure)
`[LAW-AMEND-v42-A8 → GW-1]`. (GW-1 LANDED, engine commit fc62565: INV-GAS sealed pocket improved from
2.51× rest to 1.219×, 0.49% closed-form error; conservation exact; liquids byte-identical. The tall-column
INV-ATMOS gate is NOT thereby satisfied — see §4/§11.)

### §2.2 Incompressibles (max == default): no EOS branch exists *(fixes B-11)*
`p_eos ≡ 0`; the compression branch is **removed**, not divided-by-zero. Their pressure is carried entirely
by `P` (the constraint force); their hard wall is receiver-room = 0. Law #9's "compresses via EOS" applies
to gases only `[LAW-AMEND-7]`.

---

## §3 — Pressure: ONE field `P` (the law's single-`P` exit — **deletes A+B**)

> Discharges the law's labeled debt: one persisted `P`, 6-face local, same rule all directions.
> *(Fixes B-9/B-10; supersedes Fork-1. The v4.0 draft's stencil failed review — surface cells read 0 —
> the anchor below is the repaired, worked-through version.)*

### §3.1 The update (red–black relaxation, 1-hop, GPU-native)
Per sweep, for every **non-solid** cell `i` (solid faces are closed: excluded from `n_open`; solids carry
no `P` and shield below — the shelf behavior):
```
contribution of face f→j:
  fluid neighbor:      Φ_f = P_j      + ρ̄_f · g⃗·(r⃗_i − r⃗_j)        ρ̄_f = ½(ρ_i + ρ_j)
  gas/vacuum neighbor: Φ_f = p_eos,j  + ρ̄_f · g⃗·(r⃗_i − r⃗_j)        (vacuum: p_eos,j = 0)

target_i = ( Σ_f Φ_f ) / n_open                     [+ α_eos·(p_eos,i − P_i) if i is gas]
P_i ← (1−ω)·P_i + ω·target_i        [− κ·divU_i, first sweep of the tick only]
```
- **`divU` in the κ term reads the persisted (pre-ENCODE snapshot) velocity** — the post-ENCODE `−g·dt`
  contribution is **excluded** (it is not a real divergence; including it would source a phantom `−g·dt`
  floor divergence) `[LAW-AMEND-v42-A5]`.
- **The gas-face anchor carries the hydrostatic offset too** — this is the load-bearing repair. A full
  water cell with an open top face gets `Φ_top = 0 + ½(1000+1.2)·10·1 ≈ 5006 Pa` — the half-cell own-fill
  head, *emerging from the same isotropic face rule*, not a bolted-on B.
- **Worked fixed points** (the review demanded these be derived, not asserted):
  - *Open-top column, depth H:* `P(cell k from surface) = ρg·(k−½)·dx` — **cell-center exact**
    hydrostatics. Bottom cell at H=8: **75,000 Pa**; reconstructed floor-FACE pressure
    `P_bottom + ρ_bottom·g·dx/2 = 80,000 Pa = ρgH`. Both numbers are pinned in INV-P1 so the audit's B-9
    (formula vs probe target one cell-weight apart) cannot recur.
  - *Walled [1000|500] pair, open tops, shared lateral face:* fixed point ≈ **[4172 | 3339] Pa**
    (anchors 5006/2506; solve the 2×2 average system). ΔP ≈ 833 Pa drives lateral flow toward the lighter
    cell; the gradient vanishes exactly at equal mass ⇒ equilibrium [750|750]. **B's leveling job is
    inside `P`** — same-level fill differences level through the one isotropic rule (the v4.0 claim, now
    with the algebra that actually produces it).
- **Gas cells participate fully** *(review: pinning gas P to p_eos left a resting atmosphere with
  unbalanced gravity, vel_damp silently fabricating heat)*: a gas cell uses the same stencil plus the
  source term `α_eos·(p_eos,i − P_i)`. The resting atmosphere relaxes to its own ~12 Pa/cell hydrostatic
  stratification (gravity balanced by ∇P, not by damping); a compressed pocket's P tracks its EOS within
  ~1/α_eos sweeps. "Rest" in INV-AL means this relaxed stratified state.
- **Fully sealed fluid regions** (no gas/vacuum face anywhere) are pure-Neumann: `P` there is defined up
  to a constant; only ∇P acts, and the κ·divU term keeps the level bounded. Invariants on absolute `P`
  therefore always specify open-top geometry (INV-P1) or a gauge anchor.

### §3.2 Convergence, dataflow, cost — honest *(fixes B-8)*
- **Diffusive: O(H²) sweeps** (audit fit ≈ 23.4·H² at 1 sweep/tick; "O(H)" is retracted everywhere).
  Mitigations in order: `N_relax` sweeps/tick (linear cost; the T2 plan's "several iterations" precedent —
  distinct from v3-§9.3-FORK-4's *full-step sub-cycling* ban, which stands); SOR `ω ≈ 1.5–1.8`;
  **banked:** per-chunk geometric multigrid (true O(H)) if deep oceans miss the perf gate.
  - **`N_relax`: legal [1–32] (clamped), default 4** `[GW-2, ratified 2026-06-15; engine commit 60e5a8c]`.
    For gas-dominated tall columns the relaxation may be run to higher sweep counts to damp long-wavelength
    acoustic transients; the smoother stays **1-hop red–black, GPU-native** (the canonical GPU stencil) —
    **NOT multigrid** (coarse-grid aggregation reintroduces the non-local column-sum the law's A+B debt
    exists to delete, and serialises poorly on GPU). Cost scales linearly in sweeps; tune for *accuracy*,
    not stability (stability is GW-1's implicit `P`).
  - **Multigrid reconcile (GW-2):** per-chunk geometric multigrid **STAYS BANKED** as a last-resort *perf*
    lever for deep oceans, but GW-2's higher sweep cap is the **PREFERRED** lever for the gas column —
    multigrid's coarse-grid aggregation reintroduces the non-local column-sum the A+B debt deletes and
    serialises poorly on GPU, whereas the [1–32] red–black sweeps stay 1-hop GPU-native. The two do not
    contradict: raise sweeps first; multigrid only if a deep-ocean perf gate is still missed.
- **Dataflow (normative — review found the v4.0 wording reproduced weighted Jacobi):** within a sweep the
  **black half-sweep MUST read the red half-sweep's freshly written values** (in-place per-color writes are
  race-free: a 3D-parity cell's 6-face stencil touches only the opposite color). A read-old/write-new
  double-buffer across both halves is weighted Jacobi — checkerboard-undamped at ω=1 and **divergent** for
  ω>1 — and is **forbidden**. With Gauss–Seidel dataflow, red–black genuinely damps the odd-even mode
  (smoothing factor ≈ 0.25/sweep); v3-§5's checkerboard artifact and its mislabeled "ρg·dx ≈ 20000"
  ceiling (actually 2ρg·dx) are retired together.
- **Stability:** `κ ≤ ρ̄·dx²/dt` (pseudo-acoustic CFL, c² = κ/(ρ̄·dt)); κ applied once per tick.
  One definition; the v3 rate-vs-increment dt ambiguity is closed *(B-10)*. FORK-7 is retired (nothing
  derives c² from the removed incompressible EOS).

---

## §4 — Force & momentum (RESOLVE), with units

```
F⃗_i·dt = Σ_faces −p̄_f·A·dt·n̂_out
  interior fluid|fluid face:    p̄_f = ½(P_i + P_j) + ¼(ρ_j − ρ_i)·g⃗·(r⃗_f − r⃗_i)   # ¼Δρ density-diff correction [LAW-AMEND-v42-A6]
  boundary face (gas/vacuum/solid against fluid i): p̄_f = P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)  # the cell's own half-cell hydrostatic ghost — the boundary condition itself, NOT ½(P_i + Φ_f) [LAW-AMEND-v42-A1]
        + Σ_faces ±ṁ_f·u⃗_donor           # advected momentum [LAW-AMEND-4]; ṁ = per-tick mass [kg]
(gravity m·g⃗·dt + external impulse: ONCE, in ENCODE [LAW-AMEND-3])
```
- The face-value model is **each side's own half-cell hydrostatic extrapolation meets at the shared face**:
  the interior `¼(ρ_j − ρ_i)·g⃗·(r⃗_f − r⃗_i)` correction makes the two sides' extrapolations agree
  `[LAW-AMEND-v42-A6]`, and a boundary face carries the cell's own ghost `P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)` rather
  than an average with the far side (an average leaves `−m·g/2` on every free surface — a permanent
  vel_damp heat pump) `[LAW-AMEND-v42-A1]`.
- The force reads **only `P`** (and its boundary anchor). Every term is kg·m/s.
- `vel_damp` defined: DECODE applies `u⃗ ← u⃗/(1 + vel_damp·dt)`; removed KE is deposited as heat in the
  same cell (`ΔE = ½m(‖u‖²−‖u'‖²)`) — books closed. With §3.1's stratified-gas rest state this deposits
  ~0 at rest (no perpetual gravity→damping→heat pump — review).
- Velocity invariants live HERE: `‖u⃗'‖ ≤ dx/dt`; void floor `m < ε_mass ⇒ u⃗=0`, **mass AND E ledgered to
  the boundary ledger** (law #9 has no de-minimis clause — nothing silently vanishes; magnitude ≤ ~1 J,
  ~1e-6 kg per event).
- **No-penetration clamp (sanctioned 1-hop snapshot read in DECODE) `[LAW-AMEND-v42-A3]`:** DECODE may read
  the **1-hop snapshot neighbor** to zero a velocity component pointing into a wall or a cross-species
  no-flux face. The clamp zeroes velocity AND its derived momentum (`p = v·m`, so the matching momentum
  component is zeroed too); **mass and E are untouched**; order-independent, snapshot-read, never transport.
  The kinetic energy removed is a sanctioned **NO-DEPOSIT (ND-18)** — not deposited as heat (a resting
  pool's into-floor numerical jitter would otherwise fake-boil it every tick).
- **A-11 (future feature, NOT implemented):** genuine HIGH-SPEED impact (into-surface speed above a jitter
  floor `v_impact_min`) *should* deposit the clamped KE as heat — deferred future feature, gated so resting
  pools never self-heat; no code now `[LAW-AMEND-v42-A11 / FUTURE]`.
- Solid faces: closed (no flux, no force exchange; shielding via the §3.1 stencil). The v3 one-sided
  wall-reaction kick is deleted; no transient is ever read as a physical impulse.

---

## §5 — Movement gates

### §5.1 Yield (threshold)
Net force vs `max(τ_y,i, τ_y,j)` [N vs N] — `τ_y` is a **force threshold [N]** `[LAW-AMEND-v42-A4]`. Stacked
chains, **direction-projected** *(review: v4.0 had the sign backwards vs its cited source)*:
`F_out = F_in + m_own·(g⃗·n̂_chain) − τ_y` — weight ADDS down-chain, subtracts up-chain, contributes 0
sideways (down-accumulate / sideways-Pascal-transmit — normative here; the historical source, decomp §8.3,
is deleted).
**Granular/sand is the universal cell with a FINITE `τ_y`** riding THIS SAME force-vs-yield gate (per
law #0 / P-0): there is **NO separate granular sub-system and NO dedicated owner task** — the stacked-chain
`F_out = F_in + m_own·(g⃗·n̂_chain) − τ_y` is the per-cell *restatement* of what the pressure field + yield
gate already do emergently (hydrostatic pressure at a column base already equals the overburden weight), not
a separate pass to implement `[LAW-AMEND-v42-A13]`. A true **angle of repose** (shear-aware/directional
yield, ~34°) is a deferred future feature — a scalar `τ_y` gives hold-or-slump (flat-ish piles), not sloped
dunes `[LAW-AMEND-v42-A14 / FUTURE]`.
Solids: τ_y = ∞ ⇒ never swap, never yield; dense-solid-on-liquid statics stay deferred (granular stage).

### §5.2 Cohesion (min_mass) — deterministic legalization *(fixes B-13)*
Proposal `f` (donor D → receiver R, same species) is legalized: `f ← min(f, maxMass − R, D)`; then if
`0 < D − f < min`: `f ← D − min` if `D − min > 0`, else `f ← D` if `R + D ≤ maxMass`, else `f ← 0`.
**The `0 < D − f < min` full-merge branch (`f ← D`) is superseded by INV-NOSUBMIN (A-10)** — once that
invariant lands, a cell can NEVER become sub-min (the donor-side gate prevents sub-min at the source), so
the branch is **unreachable** `[LAW-AMEND-v42-A7]` (the prior "HELD until T5" framing is dropped). The
room/capacity clamp (`f ← min(f, maxMass − R, D)`) is unchanged.
*(B-13 closed by ratification; all cohesion-rule semantics resolve into this file alone.)*
Total and deterministic (review-verified). **Worked (user-ratified 2026-06-10 — rule wins, replacing the
old "blocked" example):** 130-onto-875 → f = 5 → [125 | 880]; 250-onto-875 → f = 125 → [125 | 1000]. Composition with R1's σ-scaling: legalize per-face
first, then σ; σ's donor budget is `m − minMass` (or `m` when a single full-drain face is the only
outflow), so post-scale donors never land in (0, min).

### §5.3 Viscosity = rate everywhere; swap cadence *(discharges the Fork-4 bank; fixes C-6)*
Flux rate: `λ = μ/(ρ·dx²)` [1/s], damping `1/(1+dt·λ)` (kept). **Swap cadence:**
```
t_swap = max( t_swap_min , (μ_i + μ_j) / (Δρ_eff·g·dx) )      # units: Pa·s / (kg/m³·m/s²·m) = s ✓
swapReady += dt / t_swap   ;   eligible to fire when ≥ 1 (carry remainder)
```
`t_swap_min = 0.5 s` is a **seconds** floor, not a tick floor *(review: v4.0's `max(dt,…)` re-introduced
the dt-quantization it claimed to fix)* — water/lava (physical 0.03 s) fires every 0.5 s at any dt
⇒ sink speed 2 m/s at dt=0.25 **and** dt=0.5 (INV-DT passes). Slow regime example: rhyolitic lava
μ=1e5 ⇒ t_swap ≈ 6 s — slowly but always overturns (RT honored). `swap_kv·√μ` is DELETED from the
threshold; threshold = §0's cohesion (cross-species) + yield only. The dead `dPE<−1e-3` co-gate is gone.

---

## §6 — Mass flux: RESOLVE as five GPU micro-passes *(fixes B-1, B-15 — review-hardened)*

### §6.1 Pass structure (each a full-grid kernel; 1-hop reads of snapshot or prior-pass buffers; no atomics)
**Normative order within step_world:** ENCODE → `2·N_relax` pressure half-sweeps (§3) → **R0 → R1 → R1.5 →
R2** → DECODE. "Snapshot" = the **post-ENCODE** state (gravity + external already in momentum; T, p_eos
cached). Kernel count: `6 + 2·N_relax` (= 10 at N_relax=2, 14 at N_relax=4).
- **R0 (swap intent):** each cell picks ≤1 partner. Deterministic priority: (1) vertical down, else up
  (buoyant/§7 swaps and §6.3 displacement-swaps), else the lateral §6.3 face; (2) strongest
  `Δρ_eff·g·V − R_pair`; (3) lower coordinate-parity. Under-ready pairs (`swapReady < 1`) are not chosen.
  Writes `swapChoice_i`.
- **R1 (mutuality + flux intent + donor scale):** reads partner's `swapChoice` (1-hop) ⇒
  `swapMutual_i = (choice_i = j ∧ choice_j = i)`. **A cell with `swapMutual` zeroes ALL six flux
  intents** — the whole-cell flux-XOR-swap *(review: v4.0's per-face skip still needed 2-hop reads — this
  form is decidable 1-hop by both endpoints and any lateral neighbor)*. Otherwise compute legalized
  per-face outflows `f_k` (§5.2) and the **donor scale `σ_i = min(1, budget_i/Σ_k f_k)`**,
  `budget_i = m_i − minMass_i` (full-drain exception per §5.2). Writes `(f_k, σ_i, swapMutual_i)`.
- **R1.5 (receiver scale)** *(review blocker: multi-DONOR room composition was the open dual of B-1)*:
  each cell reads its 6 neighbors' `(f_toward_me, σ)` (1-hop) ⇒
  `ρ'_i = min(1, room_i / Σ inbound f·σ)`. Writes `ρ'_i`.
- **R2 (commit):** a face fluxes **iff neither endpoint is swapMutual**; flux = `f_k(donor)·σ_donor·
  ρ'_receiver` — both sides read identical buffer values ⇒ bit-exact antisymmetry. Mutual swaps permute
  **snapshot payloads** (§7.1). Conduction `q` + radiation `q_rad` accumulate here (flux-limited, §8.2).
  An unrequited chooser neither swaps nor fluxes that tick (stalls once; deterministic; harmless).
**Σ outflows ≤ budget and Σ inflows ≤ room, structurally** — INV-DB and INV-RR guard both.

### §6.2 Receiver room
`room = maxMass − m` (gas: large band; incompressible: 0 at rest). **INV-NOOVERMAX
`[LAW-AMEND-v42-A12]`: over-max is forbidden from ANY path** — the prior "single exemption" for the §8.4
freeze relabel is **RETIRED**; the freeze must evict its excess in the SAME pass (atomic freeze-evict, §8.4),
never sit over-max even transiently. water→steam is in-band by §1.2's steam band (review: the v4.0 "two
transients" count was wrong).

### §6.3 Cross-species movement = swap/displacement ONLY *(fixes B-14)*
No partial cross-species flux exists. Species crosses cells only as (a) a §7 buoyant swap, or (b) a
**displacement-swap**: liquid under pressure enters a gas/vacuum neighbor by swapping volumes — the gas
payload relocates into the donor's vacated volume (a pure permutation, handled as an R0 face-swap candidate
with driver `P_donor − Φ_anchor` and priority after vertical swaps). Every kg keeps its label; per-species
conservation is structural. (Gas mass can also compress into a gas neighbor via ordinary same-species flux
+ EOS.)

### §6.4 Sub-min residue *(fixes submin-deletes-mass / vacuum-relabel-E-hole)*
**INV-NOSUBMIN `[LAW-AMEND-v42-A10]` — sub-min is prevented at the source, not cleaned afterward.** The prior
"`0 < m < min`: never deleted — DECODE flags it; next RESOLVE drains it" wording is **SUPERSEDED**: no cell
ever rests at `0 < m < min(species)` *from any path* (flow, relabel, empty-refill). A **relabel** (phase
change or empty-cell adoption) is **FORBIDDEN when the carried mass `< min(target)`** — the cell keeps its
species/mass/E (keep-E ⇒ blocking conserves energy exactly) until it legally clears the target min. An
**empty-refill** requires a **donor-side gate**: a donor may not deliver `< target-min` into an empty cell
**except on a full-drain** (the donor's whole mass moves, leaving the donor at 0, so the empty cell receives
a legal `≥ min` quantum). The empty-refill donor-side gate + thermal guard land in **T4/T7**
`[LAW-AMEND-v42-A10]`. Only `m < ε_mass` relabels to VACUUM with **mass and E both ledgered** (≤1e-6 kg,
~1 J). Decomp §3's `m < max(ε, min_mass) → VACUUM` rule is dead.

---

## §7 — The swap: full payload, energy-closed

**§7.1 Payload:** permute `(mass, matIx, E, momentum)` — per-species mass AND momentum AND energy exact
by construction *(fixes B-16)*. `swapReady` resets on both cells.
**§7.2 Energy:** released PE `|ΔPE| = (m_h − m_l)·g·dx` deposits as heat, half each cell — books closed.
**§7.3 Gate (worked):** cross-species: `Δρ_eff·g·V > k_c·min(minMass)·g + τ_y` — lava-over-water:
16,500 N > 3,750 N ✓. Same-species: `Δρ_eff·g·V > τ_y` — 80 °C-under-20 °C water: Δρ_eff = 12.6 kg/m³ ⇒
126 N > 0 ✓ **convection fires** (cadence t_swap ≈ max(0.5, 2e-3/126) = 0.5 s) *(review blocker closed;
INV-CONV is now satisfiable)*. Gas–gas: no swap path needed (EOS + flux reorder gases).

---

## §8 — Thermal: conduction + radiation + advection; latent-heat phase change

### §8.1 Enthalpy curves `[LAW-AMEND-8]` *(fixes B-19/B-20/PC-4)*
Per material `E = m·h(T)`, h piecewise linear (slope cp) whose **inverse T(E/m) has a plateau of width
`L`** at each phase threshold (a "mushy" cell pinned at the threshold while latent heat is paid — water
boils pinned at 373 K until 2.256 MJ/kg). **Continuity anchoring (normative — review: without it the
relabel fabricates MJ-scale E):** phase-paired curves share one reference along the chain,
`h_target(T*) ≡ h_donor(T*) + L` at each plateau's far edge (ice→water→steam chained), so the relabel
re-base is the **identity on E — ΔE ≡ 0 by construction** (asserted in INV-LAT). T is continuous across
every transition: no cp-ratio jump, no flip-flop, no 185 K condensate, no 753 K superheat. Cross-cp
advection: `E_adv = ṁ·h_donor(T_donor)`, priced by the receiver's curve *(B-20)*. E is internal energy;
gas compression work remains out of scope (PC-3, accepted minor).

### §8.2 Conduction — bound + conservative limiter *(fixes B-2)*
`q = k_face·(T_i − T_j)·(A/dx)·dt`, `k_face = 2k_ik_j/(k_i+k_j)`. Required: `dt ≤ m·cp·dx/(Σ_f k_face·A)`;
full cells have 4–6 orders of margin; thinned cells would violate it ⇒ the **discrete maximum principle is
enforced by scaling the offending FACE fluxes themselves** (symmetrically on both sides — antisymmetric,
**grand-E exact**; review: v4.0's post-hoc T-clip silently created/destroyed energy) until
`T_next ∈ [stencil min, stencil max]`. Mirrored in `[LAW-AMEND-5]`. The 2026-06-04 §E "conservation ⇒ no
blow-ups" claim is retracted.

### §8.3 Radiation — the third channel `[LAW-AMEND-5]` *(fixes C-3/PC-6 and C-4's lava–water path)*
```
q_rad = ε_eff·σ_SB·(T_i⁴ − T_j⁴)·A·dt        σ_SB = 5.67e-8 W/(m²K⁴)
fires on: (a) condensed↔transparent faces (ε_eff = ε_cond; gas neighbor ABSORBS — antisymmetric);
          (b) vacuum faces (partner T_sky = 270 K; boundary-ledgered);
          (c) condensed↔condensed faces with ΔT > 300 K (ε_eff = ε_i·ε_j) — the film-boiling/radiative-gap
              surrogate (review: without (c) the lava|water quench stayed at the audit's ~days while the
              text claimed minutes; physically a vapor film + radiation do dominate such contacts).
```
Stability: rides §8.2's flux limiter (linearized h_rad ≈ 4εσT̄³ ≈ 560 W/m²K for lava — full cells safe).
**Honest budgets (review-corrected; LUT numbers):**
- Open-air lava crusting: 1373→1275 K sensible = 2650·1150·98 ≈ **2.99e8 J** at ~165 kW/m² avg ≈ 30 min;
  then the 1275 K plateau (1.06 GJ at 142 kW/m²) ≈ 2.1 h ⇒ **≈ 2.6 sim-h per exposed face, ≈ 26 min for a
  fully-exposed block** (was 72 days). Whole-1 m³-cell solidification is intrinsically hours-scale; real
  lava "skins over" in minutes only because the real crust is cm-thin (stated, like §10's whole-cell rule).
- Lava|water quench via (c): ε_eff ≈ 0.91 ⇒ ~183 kW/m² ⇒ water 288→373 K (3.56e8 J) in **≈ 32 sim-min**;
  the full vaporization plateau adds ≈ 3.4 h/face. Guarded by INV-QUENCH.
- **Gas-absorber turnover (stated dependency):** 190 kW/m² into a 1.2 kg air cell is +79 K/tick — the
  absorber saturates at the clamp hull in ~14 ticks unless buoyant turnover replaces it (open columns: yes,
  ~1 parcel/7 s carrying ~1.3 MJ; **sealed roofs: radiation equilibrates and shuts off — lava under a
  ceiling crusts at conduction rates, accepted**). Second INV-RAD case tests the sealed-roof shutoff.
- `T_sky` is exchange-only on vacuum faces; world heat *input* (sun/biome) remains the parent project's
  pinned-source system, not this spec.

### §8.4 Phase change × mass bands *(fixes MAT-2/ES-7)*
Relabel never teleports mass. Boiled 1000 kg water → **steam at 1000 kg, inside steam's band** (≤ max
1000): legal state; `p_abs = (1000/0.018)·8.314·373 ≈ 172 MPa` ⇒ the relaxation + flux expand it violently
over following ticks, CFL- and room-capped — **the steam explosion emerges from the EOS**, bounded,
conservative. Freezing water → ice: 1000 > 917 — per **INV-NOOVERMAX `[LAW-AMEND-v42-A12 / owner T7]`**
over-max is no longer tolerated even transiently: the cell relabels to ice at maxMass and **evicts the
surplus `(m − maxMass_ice)` in the SAME pass** to a legal neighbor (mass + E carried, obeying **both**
bounds — receiver ends `≤ max`, and an **empty** receiver ends `≥ min` per A-10), or **DEFERS the freeze**
(the cell stays cold water and retries next tick) if no legal target exists. keep-E ⇒ deferral conserves
energy exactly; the prior "drain next RESOLVE" transient-overshoot exemption is retired. Lava→stone:
2650/2700 = **98.1%** under-full (stated).

---

## §9 — Numerics, dt-invariance, GPU layout

- **dt-invariance:** every rate × dt; every cadence in **seconds** (t_swap_min = 0.5 s — review: never
  floor a cadence at dt). The CFL cap dx/dt is the stability bound, not a physics rate; its consequence is
  stated honestly: depth-blind efflux above `h* = (dx/dt)²/2g` (**0.2 m at dt=0.5, 0.8 m at dt=0.25**) and
  2–4 m/s kinematics — the documented genre tradeoff (TS-4/PC-5/ES-1 disposition).
- **Order-independence (INV-7):** all passes read snapshot or prior-pass buffers; red–black half-sweeps
  are each order-independent under §3.2's per-color in-place rule.
- **Buffers (SoA):** persisted `m, px,py,pz, E, P(+copy), matIx, swapReady, void_ix`; transient
  `swapChoice, swapMutual, f[6], σ, ρ'`. No atomics in any pass; **boundary/conservation ledgers are
  per-thread-block partials reduced in a separate (audit-only, async) kernel — outside the hot path**
  (review: "no atomics" + per-chunk ledgers needed this stated).
- **Perf gate:** ≤ 2 ms / 1M cells / step on a mid GPU at N_relax ≤ 4 (14 kernels); CPU fallback = same
  passes via OpenMP; DESIGN §3 client-compute topology unchanged.

---

## §10 — Realistic vs performant: the honest ledger

| aspect | v4 status | accepted remaining gap |
|---|---|---|
| hydrostatic statics | cell-center exact (75 kPa @ H=8 center; 80 kPa floor-face) | establishment O(H²) sweeps (N_relax/SOR mitigate; multigrid banked) |
| gas pressure | 1 atm rest; ~69 MPa at 700× — realistic | isothermal EOS (no adiabatic heating) |
| kinematics | ≤ dx/dt everywhere | 5–10× slow-motion fronts; depth-blind gushes above h* — genre baseline, stated |
| leveling | correct equilibrium; in-field (no B patch) | monotone (sloshing needs the staged momentum work) |
| phase change | latent plateaus, T-continuous, emergent bounded steam explosion | whole-cell fronts; freeze 9.1% supersaturation transient |
| lava lifecycle | crusts in open air ≈ 2.6 h/face (26 min fully exposed); quenches vs water ≈ ½ h to boiling | real crusts are cm-thin/minutes; sealed-roof lava stays conduction-slow |
| convection | gas: EOS buoyancy; liquid: β-swap (126 N gate-clears, fires ~2 s/cell) | single-cell Rayleigh cells at 1 m |
| capillarity | min_mass surrogate (12.5 cm films) | correct simplification (Bond ≫ 1) |
| solids in liquid | static (τ_y = ∞); ice/lava/stone densities now consistent | sinking stone / floating ice = granular stage |

---

## §11 — Invariants (run before enshrined; real LUT; assert MOVED)

| id | invariant |
|---|---|
| INV-P1 | open-top H=8/16 column: bottom CELL-CENTER `P → ρg(H−½)dx` ±0.1% (75,000 / 155,000 Pa); floor-face reconstruction `→ ρgH` (80,000 / 160,000). Same tolerance both H. *(B-9 pinned)* |
| INV-P2 | walled [1000|500], open tops: P fixed point ≈ [4172|3339] Pa, levels to [750|750], no upward leak, 1500.0000 kg exact |
| INV-GAS | air pocket sealed under 2 m water: m stabilizes at **1.20 ± 0.05×** rest (= 1 + ρgh/P0); P ≈ overburden *(review: ≤2× was 4× too loose)* |
| INV-AL | relaxed stratified rest atmosphere + pool: surface air `‖u‖ < 0.01·dx/dt` for 5000 ticks; no monotonic heating of static cells + an air\|steam interface variant: two resting gases at true equilibrium read NO spurious gradient (cross-gas absolute-P, A-2(i)) |
| INV-ATMOS | **CURRENTLY DEFERRED / GW-OPEN — NOT satisfied** (engine commit 3ce9d45). Gated by `atmos_probe`. **Stability:** seed UNIFORM 1.2, run ≥ 5000 ticks at dt=0.5 — no cell oscillates >1% tick-to-tick at settle; the bottom does not exceed a bounded multiple of rest density; the top does not drain below the gas floor. **Well-balanced kept stable:** seed BAROMETRIC, run ≥ 2000 ticks — every cell stays within ε of its seeded/permanent-hold mass. **Realistic readout:** at settle `/orge` reads ≈ 1 ATM absolute at sea level, dropping ≈ 12 Pa/block. **Conservation:** per-species air mass exact every tick — this sub-clause ALREADY HOLDS. GW-1 (fc62565) + GW-2 (60e5a8c) LANDED and are necessary improvements but NOT sufficient: the 200-tall acoustic air column does not yet converge — a separate EOS-anchor→non-hydrostatic-P→residual-y-drive ratchet keeps the bottom densifying (out of GW-1/GW-2 scope; cure = a gas drive-velocity limiter / boundary-ghost fix / deferred θ y-rest-target restructure). Do NOT mark satisfied until the column converges. |
| INV-UNIVERSAL | the core mechanic contains NO `switch(state)` / `if(isGas/isLiquid/…)` branch; state behavior comes only from material data (τ_y, χ, viscosity, min/max), never the species name — a state-branch in the core is a regression (acceptance test for every task). *(law #0 / P-0)* |
| INV-NOSUBMIN | no cell rests at `0 < m < min(species)` from any path (flow, relabel, empty-refill); relabel forbidden when `m < min(target)`, empty-refill donor-side gated. *(A-10)* |
| INV-NOOVERMAX | no cell rests at `m > max(species)`; the water→ice (lower-max) freeze evicts its surplus same-pass under both bounds, or defers. Twin of INV-NOSUBMIN: `min ≤ m ≤ max ∨ m = 0`. *(A-12)* |
| INV-DB | 6-way diverging donor: Σ outflows ≤ budget every tick |
| INV-RR | 6-donor converging receiver: m ≤ maxMass every tick *(the B-1 dual)* |
| INV-COND | thinned 1e-6 kg cell in a 300/400 K stencil: T ∈ [300,400]; **grand E exact through the limiter**; full-cell golden bit-identical |
| INV-RAD | (a) 1373 K lava, open top, T_sky 270: face loss 185–195 kW/m², E+ledger exact; (b) sealed-roof case: q_rad → ~0 within ~20 ticks, E exact |
| INV-QUENCH | lava|water contact face: water reaches 373 K within ≤ 45 sim-min; E exact *(§8.3c)* |
| INV-LAT | heated water pins at 373 K until +2.256e6 J/kg then relabels AT 373 K with **ΔE ≡ 0**; reverse symmetric |
| INV-STEAM | boiled cell: p_abs > 100 MPa, expands ≤ 1 cell/tick, settles ≤ maxMass everywhere, per-species exact |
| INV-SWAP2 | swap permutes (m, matIx, E, p⃗); pair momentum/energy exact incl. ΔPE→heat |
| INV-CONV | 80 °C water cell under 20 °C column overturns (same-species gate, §7.3 worked numbers) |
| INV-DT | same scene at dt=0.25 vs 0.5: settled states agree; swap sink speed (m/s) equal ±10% — cadence floor is in seconds, so CFL-saturated pairs are NOT exempt |
| INV-7 | forward/reverse iteration bit-identical (5-pass + red–black per-color structure) |

## §12 — Audit-finding disposition map

- **Fixed by this spec:** B-1 (§6.1 σ) + its review-found dual (§6.1 ρ′/INV-RR) · B-2 (§8.2) · B-9/B-10
  (§3, INV-P1 pinned) · B-11 (§2.2) · B-12 (§1.1) · B-13 (§5.2; user call pending) · B-14 (§6.3) · B-15
  (§6.1 whole-cell XOR) · B-16 (§7) · B-17 (§6.4) · B-8 (§3.2 retraction; the unexplained 175× residual
  disparity dissolves with the new operator — INV-P1's same-tolerance-both-H clause re-tests it) · B-20/21
  (§8.1, §4) · B-22 (MASTER-RULES + INDEX banners) · C-5/MAT-1, MAT-5, MAT-6 (§1.2, §2.1) · C-6/MAT-4
  (§5.3) · C-10/TS-3 (§9) · C-11/PC-1 (§0/§7.3 same-species gate — review-blocker closed) · C-15 (§3.1
  anchor explicit) · MAT-2/ES-7 (§8.4) · checkerboard family (§3.2 dataflow).
- **Fixed via the RATIFIED law amendments (applied 2026-06-10):** B-4/5/6/7 (1–4) · radiation + clamp (5)
  · schema fields + bookkeeping state (6) · gas EOS/law-#9 (7) · enthalpy curves / B-18/B-19 (6+8) · the
  law's working-spec pointer (9). Nothing remains gated.
- **Accepted & documented:** speed cap/slow motion + depth-blind gushes (§9, §10) · capillarity (C-14) ·
  no adiabatic heating (PC-3) · whole-cell fronts + freeze supersaturation (§8.4) · sealed-roof lava
  conduction-slow (§8.3) · solids-in-liquid statics (granular stage) · steam IR transparency (§1.2 note).
- **Retired claims/symbols:** §1.3 list + O(H), A=ρg·k·dx-as-target, §E stability "proof", FORK-7, NOTE-B.

## §13 — Decisions log (v4.1)

DEC-v4-A single-`P` hydrostatic-consistent relaxation, **gas faces carry the anchor + offset** (the
review-repaired keystone); red–black **Gauss–Seidel dataflow normative**. · DEC-v4-B live gauge gas EOS,
per-gas T_ref, absolute-P comparison across gas pairs; `(1−χ)` deleted. · DEC-v4-C RESOLVE = **5**
micro-passes (R1.5 receiver scale added by review); whole-cell flux-XOR-swap. · DEC-v4-D cross-species =
permutation only. · DEC-v4-E viscosity → cadence with a **seconds** floor. · DEC-v4-F enthalpy curves,
**chain-anchored** (ΔE≡0 relabels). · DEC-v4-G radiation on transparent faces + vacuum + **ΔT>300 K
condensed contacts** (film-boiling surrogate). · DEC-v4-H realistic LUT (lava 2650 < stone 2700; lava
solidus 1275 K; steam band; ice row).

---

*Implementation order: §3 single-P (+INV-P1/P2/AL) → §2 gas EOS (+INV-GAS) → §6 micro-passes
(+INV-DB/RR) → §8.2 limiter (+INV-COND) → §8.3 radiation (+INV-RAD/QUENCH) → §8.1/8.4 latent+steam
(+INV-LAT/STEAM) → §7 payload/cadence (+INV-SWAP2/DT) → §0 ρ_eff convection (+INV-CONV). Every stage:
grand + per-species mass AND energy exact; in-game audit final gate.*
