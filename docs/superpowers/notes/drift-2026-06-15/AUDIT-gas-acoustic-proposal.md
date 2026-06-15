# AUDIT — gas-acoustic-column-stability proposal (GW-1/GW-2/GW-3)

**Target:** `docs/superpowers/notes/2026-06-15-gas-acoustic-column-stability-proposed-amendments.md`
**Method:** read against DESIGN-LAW.md (v4.2) + spec v4; verified every claim against EXECUTABLE code in
`ORGE-ENGINE/core/engine_b.hpp`; built+ran `tests/atmos_probe.cpp`; checked all numbers in Python.
**Verdict at a glance:** diagnosis is CORRECT and well-supported by the code and the repro; GW-1 is
numerically/physically sound and law-strengthening; GW-2 is sound and honest; GW-3 is correctly diagnosed
but the proposal MISSES one real interaction (cross-species swap-cohesion collapse). Overall:
**ratify-with-changes.**

---

## Claim 1 — Diagnosis premise (force reads EOS gauge anchor, relaxed gas P is force-dead). CONFIRMED.

`resolve_world`, gas cell `i` (engine_b.hpp:993-998):
```
const bool  i_gas  = is_gas(Mi);
const float P_i    = C.P[i];
const float a_i    = i_gas ? gas_anchor_v42(Mi, si->T[i], ei->cells[i].p_eos4, G)  // EOS gauge anchor
                           : ei->cells[i].p_eos4;
```
- For a gas cell, the per-face `pbar` is built from `a_i` (own EOS anchor) and `a_j` (neighbor EOS anchor),
  NOT from `P`:
  - gas|gas face (:1098): `pbar = a_j + (mjm/V)·ghalf·DY` — `a_j = (mjm<minMass)?0:p_eos4` (:1057).
  - gas-i|liquid-j (:1104): `pbar = ownf` where `ownf = a_i − rho_i·ghalf·DY` (:1023).
  - world/solid/absent faces (:1028,1053): `pbar = ownf` (a_i).
  - gas|vacuum (:1043): `pbar = 0` (ST7 rail-to-zero).
- The liquid branch (:1112-1117) is the ONLY place `C.P[i]`/`Cf->P[fj]` enters the force. So for gas, the
  relaxed `P_i` is read into the variable but never used by the impulse — **A-8 holds exactly: relaxed gas
  `P` is computed-but-force-dead.**
- The relaxed gas `P` IS computed via the implicit α_eos source in `relax_pressure_world` (:663-666):
  ```
  float Pn = (ci == PR_GAS) ? ((1−ω)·Pi + ω·(target + aeos·cx.anchor[i]))/(1 + ω·aeos)
                            : (1−ω)·Pi + ω·target;
  ```
  i.e. the spec §3.1 `α_eos·(p_eos,i − P_i)` source, made implicit. This `P` carries the spatial coupling but
  the FORCE never reads it for gas.

⇒ The proposal's central structural claim is **literally true in the executable code**: a gas cell carries
TWO pressure representations — the spatially-relaxed `P` (force-dead) and the per-cell EOS gauge anchor `a_i`
(live, fed explicitly into the §4 face force every tick) — and the explicit cell-to-cell coupling is via
`a_i`/`a_j`, not via the iteratively-relaxed `P`.

## Claim 2 — Acoustic-CFL number. CONFIRMED, physically sound.

- `R·T/M = 8.314·288/0.029 = 82566.6 Pa/(kg/m³)` → `c = √82567 = 287.34 m/s`. ✅ (matches probe's
  printed `s=82567`).
- `CFL = c·dt/dx = 287.34·0.5/1 = 143.7 ≈ 144`. ✅
- Explicit-stable step `dt = dx/c = 0.00348 s` ⇒ ~144 substeps/tick. ✅
- `p_abs(835 kg) = (835/0.029)·8.314·288 = 68.9 MPa ≈ 69 MPa`. ✅
- The physics is correct: an EXPLICIT (forward-in-time, neighbor-pressure-read) discretization of the
  acoustic/pressure-wave operator is unconditionally unstable for CFL>1; at CFL≈144 it must limit-cycle.
  Routing the gas force through the iteratively-relaxed, persisted `P` makes the spatial pressure coupling
  IMPLICIT (a relaxation/projection solve, no explicit time-integration of the wave), which removes the hard
  CFL ceiling. This is exactly standard low-Mach / pressure-projection / SIMPLE-class atmosphere integration.
  **Numerically sound, not hand-waving** — with one honest caveat the proposal itself states (CAUTION on
  GW-1): implicit removes the *hard* ceiling but does NOT make it exact at finite sweeps; the relaxation
  must converge enough to damp the ~144-cell wavelength → that is GW-2's job. The GW-1/GW-2 pairing is
  correctly identified.

## Claim 3 — Empirical repro (atmos_probe). CONFIRMED — output supports "slow-growing instability."

Built clean (`g++ -std=c++20 -I. -Icore -Itests -O2 tests/atmos_probe.cpp`). Real LUT, H=200, dt=0.5.

**UNIFORM 1.2 seed** (total conserved at **240.000 every tick** — LAW #9 holds, not a conservation bug):
- t=0..~110: looks flat, bottom creeps 1.200→1.204, top y200 pins at **1.000** (= air minMass) by t=4.
- onset ~t=200-400: y1 reaches 6.8, y2 10.5 by t=400; fully chaotic by t=600.
- t≈1760-2000: **bottom oscillates chaotically tick-to-tick** (y1 swings e.g. 7.9→46.7→54.3→20.6 across
  consecutive samples; observed peaks to ~69-84 kg), **top y200 drained to 0.002**. It LIMIT-CYCLES, it does
  not settle into a static dense layer. ✅ matches the proposal's table.

**BAROMETRIC seed** (total conserved at **237.131 every tick** — that IS the barometric seed sum
`Σ 1.2·exp(−(y−1)/Hscale) = 237.131`, verified in Python; NOT a mass loss):
- holds flat from the bottom for ~**140-180 ticks** (y1-y50 stay at seeded values through t=140), then
  destabilises from the top down, fully churning by t=300-400. ✅ matches "holds ~150 ticks then destabilises."

⇒ Three proposal findings all supported: (1) well-balanced (barometric holds → residual negligible); (2)
slow-growing numerical instability / limit cycle, NOT static drift; (3) Way-2's per-cell θ does not stabilise
the multi-cell column. **"Repair well-balancedness is NOT the fix" is correct.**

## Claim 4 — GW-1 law-compliance. CORRECT; assessment is sound; the ST7/T4-OPEN "moot" claim is largely right.

- **Quoted A-8 text matches the spec verbatim** (spec §2.1:113-115). ✅
- **Strengthens LAW #1:** collapsing the gas's two pressure representations (force-dead `P` + live gauge
  anchor) into the one relaxed `P` moves toward "ONE number per cell." ✅ Today's two-representation state is
  a genuine drift-test-(a) *smell* (a second pressure object doing force work alongside `P`); GW-1 removes it.
- **Keeps LAW #2 shape:** proposed text says gas reads `P` "identically to a liquid" → same
  `½(P_i+P_j)+¼Δρ…` interior face term and the boundary anchor. ✅ shape-compatible (liquid branch
  :1112-1117 already implements exactly this).
- **ST7 rail-to-vacuum trap correctly identified and avoided:** the proposed text explicitly drives the
  vacuum-boundary face from the *hydrostatically-anchored relaxed `P`*, never raw `p_abs`. The current code's
  vacuum rail-to-0 (:1043) and the documented ST8 failure modes (:1058-1096) are exactly the "drive gas into
  vacuum at full p_abs" instability; GW-1's anchored-`P` drive is the correct dodge. ✅
- **Mooting [T4-OPEN cross-gas-abs-P]/ST7-W2-3:** PARTIALLY OVERREACH-FREE / mostly correct. The T4-OPEN
  blocker (engine_b.hpp:1058-1096) is the per-cell *frame-mix*: a gas computes its faces in its own per-gas
  GAUGE (offset by P0), so an air|steam face can't be reconciled without injecting a P0-scale self-force.
  GW-1 routes ALL faces through the one relaxed `P` (a single field, no per-gas P0 offset in the force) ⇒
  there is no per-gas gauge frame left to reconcile in the force; cross-gas ΔP is carried by `P` itself. So
  the claim "mooted" is structurally defensible. CAVEAT: the relaxation that *fills* `P` still uses each
  gas's own gauge anchor (`cx.anchor[i]`, the α_eos source); whether a *resting air|steam interface* relaxes
  to the correct absolute-P match (vs each gas's own gauge-0) is NOT proven by atmos_probe (single-gas air
  column). The proposal does not over-claim here (its INV-ATMOS is air-only), but the user should know the
  hetero-gas absolute-P question is *deferred/untested*, not *solved* — "mooted" applies to the FORCE
  frame-mix, not to a verified air|steam equilibrium.

## Claim 4b — A-8 reversal. The RIGHT call; A-8 was the source of the explicit coupling.

- A-8 made the gas force read the per-cell EOS anchor and held the relaxed `P` dead. That IS the explicit
  inter-cell acoustic coupling (the force reads `a_i`/`a_j` directly, integrated explicitly each tick).
  Reversing it to drive from the implicit relaxed `P` is the correct structural fix. ✅
- **Why A-8 existed (the other side):** A-8 / [A1] anchored the gas to its EOS so a resting atmosphere
  wouldn't have gravity unbalanced by `∇P` (spec §3.1:157-161 "pinning gas P to p_eos left a resting
  atmosphere with unbalanced gravity, vel_damp silently fabricating heat") AND so the vacuum boundary
  wouldn't rail (ST7). GW-1 preserves BOTH protections: the EOS still enters as the α_eos relaxation source
  (so `P` tracks the real EOS / 69 MPa pushback), and the vacuum face drives from the anchored `P` (so no
  rail). So GW-1 does not lose what A-8 was protecting — it relocates the EOS from "force anchor" to
  "relaxation source," which is where the law's single-`P` design wants it. **A-8 reversal does not miss a
  reason A-8 exists.** ✅

## Claim 5 — GW-2 (N_relax [1-8]→[1-32]). Sound; multigrid rejection defensible; cost caveat honest.

- Current cap confirmed: `std::clamp(G.N_relax, 1, 8)` (engine_b.hpp:581); spec §1.3:81 `[1–8]`. Proposal
  quote accurate. ✅
- A pure knob-range edit (INV-3 frozen-manifest range; no schema/field change). ✅
- Multigrid rejection: consistent with the law's deletion of the A+B non-local column-sum debt — multigrid's
  coarse-grid aggregation reintroduces a non-local reduction and serialises poorly on a 1-hop GPU stencil.
  The reasoning is law-faithful. ⚠ MINOR TENSION: spec §3.2:170 and §11:409 explicitly **bank** "per-chunk
  geometric multigrid (true O(H))" as a future mitigation if deep oceans miss the perf gate. GW-2 *rejects*
  multigrid for gas columns. These aren't contradictory (GW-2 argues for the gas-column case, the spec banks
  it for deep-liquid-ocean perf), but the user should note GW-2 narrows a previously-banked option and may
  want the two notes reconciled.
- Cost caveat honest: "linear cost per sweep; kernel count grows; fallback = bounded gas-only acoustic
  substep" — correctly flagged as second-choice, not proposed. The §6.1 kernel count is `6 + 2·N_relax`
  (spec :268), so N_relax=32 ⇒ 70 kernels/step vs 14 at N_relax=4 — a 5× pressure-solve cost. Honest. ✅

## Claim 6 — GW-3 (χ decouple + lower gas minMass). Correctly diagnosed; arithmetic right; ONE MISSED CONSEQUENCE.

- **Arithmetic verified** (Python):
  - current `χ_air = (1000−1.2)/(1000−1.0) = 0.99980`; `χ_steam = (1000−0.6)/(1000−0.06) = 0.99946`. ✅
    matches spec/code (`is_gas`: `chi > 0.999`, engine_b.hpp:155-167).
  - proposed `χ = (max−default)/max`: `χ_air = 998.8/1000 = 0.99880` → **below 0.999, DECLASSIFIES air**. ✅
    proposal's self-flag is correct.
  - NOTE the proposal under-states: proposed `χ_steam = 999.4/1000 = 0.99940` → steam STILL clears 0.999.
    So under the new formula+old cutoff, **air declassifies but steam survives** — a split the proposal's
    CAUTION (which says only "air 0.99880 < 0.999") does not spell out. The cutoff/formula sub-decision must
    fix air without accidentally leaving steam classified differently. Minor, but the user should see it.
- **The 1.0-pin churn-seed is REAL and confirmed in code AND probe.** The cohesion pre-pass
  (engine_b.hpp:1503-1541) "snaps residual up to `min_d` (= minMass)" for a donor that cannot fully drain
  this tick. For air (minMass=1.0) this pins cells at exactly 1.000 — visible all over the probe output
  (y10=1.000, y25=1.000, y50=1.000, top y200=1.000). This is the discrete kick GW-3 names. ✅
- **"Gas has no cohesion floor (LAW #0 τ_y=0; minMass is a liquid concept)" is law-faithful.** LAW #0:
  gas ⇒ τ_y=0; the law nowhere requires a gas minMass floor. ✅
- **INV-NOSUBMIN interaction correct:** a gas at m<ε is the empty/vacuum case (relabel-to-vacuum fires as
  m→0, the existing empty-cell path), not a sub-min violation; with target.min=ε, "relabel forbidden if
  m<target.min" still holds. ✅ Engine relabel guards use `mass >= byIx(tgt).minMass` (:2930,:3104) — with
  ε they don't block. Fine.
- **Displacement-swap min quanta UNAFFECTED (point in GW-3's favour):** the §6.3 displacement quantum
  (engine_b.hpp:1707,1715-1718) uses the *liquid's* minMass (`min_L = ML.minMass`, the displacing liquid),
  not the gas's. Lowering gas minMass does NOT break it. ✅ (proposal didn't note this, but it's safe.)
- ⚠ **MISSED CONSEQUENCE (the real gap): cross-species swap COHESION collapses.** `swap_resistance`
  (engine_b.hpp:205-209) cross-species cohesion = `swap_kc · min(minMass_i, minMass_j) · g`. The code comment
  (:64-66) explicitly tunes `kc=3 so cohesion(water,air) ≈ 3·1.0·10 = 30 N` — "blocks a sub-~30 N leveling
  micro-swap, allows a settled 100 N water/air inversion." Setting air minMass→ε≈1e-6 makes
  `min(125, 1e-6)=1e-6` ⇒ `cohesion(water,air) ≈ 3e-5 N ≈ 0`. **Every water|air micro-swap transient that the
  30 N floor currently blocks would now fire.** GW-3 does not flag this. It may be benign or even desirable
  (less air-interface stickiness) but it is a behaviour change at every liquid|gas interface and should be
  evaluated (and the `kc` tuning re-derived) before lowering gas minMass. This is the one substantive item
  GW-3 misses.

## Claim 7 — Overall.

- **INV-ATMOS well-formed and falsifiable.** Concrete thresholds (≥5000 ticks, no cell oscillates >1%
  tick-to-tick, bounded bottom, top not drained below floor, barometric permanent hold ≥2000 ticks,
  per-species mass exact, ≈1 ATM dropping ≈12 Pa/block). All measurable against atmos_probe. ✅ The current
  state FAILS it (bottom 25-84 churning, top 0.002) — a real RED gate. Good.
- **Ratify order sensible:** GW-1+GW-2 together (stability needs both: implicit coupling + enough sweeps to
  damp the 144-cell wavelength), GW-3 after the cutoff sub-decision. ✅ With the addition that GW-3 should
  also resolve the swap-cohesion collapse above.
- **Pure-design (needs user pen) vs ship-as-code:**
  - GW-1 = **reverses ratified A-8** → needs user pen (law/spec amendment). The code change itself is
    mechanical (route gas faces through `P` + relaxation source already exists) but the DESIGN reversal is
    the user's.
  - GW-2 = knob-range only (INV-3 manifest range) → still a spec edit (frozen manifest is design), but
    low-stakes; needs user pen for the manifest range.
  - GW-3 = χ-formula semantics (LAW #8 semantics, not the field list) + LUT minMass values → needs user pen
    for the formula/cutoff decision; the LUT value + the swap-cohesion re-tune are code but gated on the
    design decision.

### Errors / overreach / missing, for the user

1. **(GW-3, substantive) cross-species swap-cohesion collapse** when gas minMass→ε:
   `cohesion(water,air)` drops 30 N → ~0. Unflagged. Re-derive `swap_kc` / decouple the swap-cohesion floor
   from gas minMass (e.g. clamp the cohesion term's minMass to a liquid-only floor, or use a separate
   cohesion field) as part of GW-3.
2. **(GW-3, minor) χ-formula declassifies air but not steam** under the proposed `(max−default)/max` + old
   0.999 cutoff. The CAUTION should say both. Option (a) (an explicit gas flag from `max≫default`, decoupled
   from any formula) is the cleanest and the auditor concurs it removes the fragility entirely.
3. **(GW-1, nuance not error) hetero-gas absolute-P is deferred-not-solved.** "Moots T4-OPEN" is true for the
   FORCE frame-mix, but a verified resting air|steam absolute-P equilibrium is NOT demonstrated by the
   air-only probe. INV-ATMOS should keep (or the user should add) an air|steam variant before declaring the
   cross-gas problem closed.
4. **(GW-2, minor) multigrid tension** with the spec's banked per-chunk multigrid (§3.2/§11). Not a
   contradiction; reconcile the two notes so a future deep-ocean perf fix isn't blocked by GW-2's blanket
   rejection.
5. **No conservation regression in the proposed direction** is provable from the probe (240.000 / 237.131
   constant today; GW-1 changes only the force's pressure *consumer*, a permutation-neutral change to which
   `P` is read — mass flux machinery is untouched). The proposal's "conservation unchanged" is credible.

### RECOMMENDATION: **ratify-with-changes.**

Diagnosis is correct against the executable code; the acoustic-CFL physics is sound; GW-1 is the right,
law-strengthening fix and correctly reverses A-8 without losing what A-8 protected; GW-2 is a sound, honest
knob edit; INV-ATMOS is a good falsifiable gate. Two changes before ratifying GW-3: (i) handle the
water|air swap-cohesion collapse, (ii) make the χ-formula/cutoff decision (option-a flag preferred) so it
fixes air without splitting steam. One nuance to record for GW-1: hetero-gas absolute-P is deferred, not
solved. GW-1+GW-2 are ready to ratify as a pair once the user accepts the A-8 reversal.
