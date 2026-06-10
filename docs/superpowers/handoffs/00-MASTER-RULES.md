# Engine-B — MASTER RULES (read FIRST, every task references this)

> ## ⛔⛔ READ [`../DESIGN-LAW.md`](../DESIGN-LAW.md) BEFORE THIS FILE — it is THE design, frozen.
> Pressure is **ONE** scalar `P` per cell; force is **ONE** Vector3 from the 6 face-neighbors (face-average
> surface integral, `−∇P·V·dt`); the **same rule in all 6 directions**; one step ENCODE→RESOLVE→DECODE;
> a cell moves when **force > resistance** (yield/cohesion = threshold, viscosity = rate); thermal rides
> the same pipeline (enthalpy curves + conduction + radiation + advection). The law outranks this file,
> the working spec, AND the code. **The 2026-06-10 amendments were RATIFIED by the user and are applied
> in the law's current text** — there are no pending amendments; the proposal record is in git history.

**You are an implementer (or reviewer) in a subagent-driven build.** Read this file, then your task brief,
then ONLY the authoritative docs below. **Do NOT read the C++ to learn the design.**

---

## ⛔ THE #1 RULE: the SPEC is truth, the CODE is the bug

The `rebuild` branch code has drifted from the design repeatedly. If code/comments disagree with the spec,
the spec wins and the code is the bug to fix. Do not reverse-engineer the physics from C++ or comments.

## ✅ AUTHORITATIVE docs — these, and ONLY these, define the model *(fixed precedence chain — audit B-22)*

1. [`../DESIGN-LAW.md`](../DESIGN-LAW.md) — THE LAW (frozen; user-edit only; amendments 1–9 applied 2026-06-10).
2. [`../specs/2026-06-10-engine-b-unified-spec-v4.md`](../specs/2026-06-10-engine-b-unified-spec-v4.md) —
   **THE working spec (v4).** One document: state, realistic material LUT, gas EOS, single-`P` relaxation,
   force/momentum, gates, mass flux (5-pass RESOLVE), swap, thermal (conduction + radiation + latent),
   numerics/GPU, invariants, audit-disposition map.
3. [`../notes/2026-06-10-physics-math-audit-report.md`](../notes/2026-06-10-physics-math-audit-report.md) —
   WHY v4 says what it says (54 verified findings; refuted-claims list — do not re-litigate those).

## 🚫 HISTORICAL / SUPERSEDED — DELETED 2026-06-10 (user-ordered doc cleanup; full text in git history)
All pre-v4 engine-B specs/plans/handoffs were **removed from the tree** so no future spec conflict is
possible: v3 (A+B split, `(1−χ)`, `own_weight_head`, `swap_kv·√visc`, raw-T/v manifest — all RETIRED),
the vector-map decomposition (its §8 model and its mass-deleting §3 sub-min→VACUUM rule are DEAD), the
CANONICAL pipeline (its one-sentence pipeline survives inside v4), the 2026-06-04 unified-formula (its
§B.1 EOS and §E stability "proof" are retracted; its §J worked examples were absorbed where still valid),
FORK-DECISIONS (frame kept in v4 §0; Fork-1/Fork-4 mechanisms superseded), and the T1–T8 plan/handoffs.
**Do not resurrect them from git as design sources.** Code-side bans unchanged: `force_advect.hpp`,
`swap_threshold`, `chi`-swap-gates, `p_surf`, column sweeps, `find_chain_hop`; comments lag — verify
against v4.

## 🧱 The model in one paragraph (v4)
ENCODE (per-cell: gravity+external once into momentum; cache T, gas EOS) → RESOLVE (the ONE cross-cell
step, as **three 1-hop micro-passes**: swap-intent → flux-intent + donor-scale → commit; plus `2·N_relax`
red–black sweeps relaxing the **single persisted `P`** whose fixed point is discrete hydrostatics including
fill differences; force = face-average pressure flux + advected momentum; heat = conduction + **radiation**
+ advected enthalpy, all antisymmetric, max-principle-clamped) → DECODE (per-cell: derive `u = p⃗/m`,
`T = h⁻¹(E/m)` on enthalpy curves with **latent plateaus**; relabel T-continuously at plateau edges; CFL cap;
sub-min flagged-not-deleted). Gases carry a **live gauge ideal-gas EOS** (rest air ≈ 1 atm ⇒ 0 gauge;
compressed pockets push back with real MPa). Cross-species mass moves ONLY by swap/displacement (full
payload: m, matIx, E, momentum; ΔPE → heat). `max==default` = incompressible (no EOS branch, no band).
Solids: τ_y=∞ bears+blocks (shelf shields); granular yield deferred.

## 🧨 STALE TESTS — triage, never blindly satisfy (unchanged, verbatim policy)
A failing existing test may be CORRECT-failing (it asserts superseded behavior). Triage against the v4 spec:
spec requires it → genuine regression, fix code; asserts dead behavior → re-author/retire citing the spec
section; cannot tell → ESCALATE. Never silently delete; never cement a dead assertion. Expected stale after
v4 lands: anything asserting `own_weight_head`, `swap_kv` thresholds, `(1−χ)` suppression, keep-E relabel
jumps, the 130/875 "blocked" example (pending the user's §5.2 call), sub-min→VACUUM deletion, T_curr/T_next
or raw-v persistence, and the determinism golden (regenerate with sanity asserts when RESOLVE changes).
Do NOT weaken conservation / no-fabrication / no-overshoot / settled-no-sub-min tests — those carry over.

## 🔒 Conservation — NON-NEGOTIABLE (every task)
- Grand mass AND per-species mass exact every step (antisymmetric flux + full-payload permutation swaps).
- Grand ENERGY exact every step: E-fluxes antisymmetric; vel_damp/dissipation/swap-ΔPE deposit to cell E;
  vacuum-relabel/radiation-to-sky/place/break go to the **boundary ledger** — nothing is silently dropped.
- Momentum: advected with mass; swaps permute it; the only sink is vel_damp (→ heat, same cell). Solid
  faces are closed (no flux/force exchange; shielding via the P-field — v4 §3/§4).
- No fabrication; no `maxMass` overshoot except the SINGLE §8.4 freeze-relabel transient (water→ice
  1000>917; bounded, relieved, tested). Water→steam is in-band by the v4 steam band.
- Donor budget AND receiver room: Σ outflows ≤ budget (R1 σ) and Σ inflows ≤ room (R1.5 ρ′) — INV-DB +
  INV-RR guard both. The frozen field/knob manifest is v4 §1.3 (INV-3 diffs against it).

## 🧪 Process (subagent-driven TDD — unchanged)
- Failing test FIRST (RED), then implement (GREEN). Real material LUT only (`tests/engine_b_real_lut.hpp`
  must be regenerated to the v4 §1.2 table — lava 2650, stone 2700, steam band, ice row, new fields).
  Assert it MOVED/leveled/boiled, not "bounded". g=10, dx=V=A=1, dt 0.25–0.5.
- Engine tests: `cd ORGE-ENGINE && bash tests/run_tests.sh`; register new tests in CHEAP_TESTS; keep the
  cheap tier green. Do NOT push; the controller pushes + rebuilds `liborge.so` + bumps the parent gitlink.
- Branch `rebuild` in both repos. Report: status, SHA, verbatim test output, conservation numbers, spec
  sections relied on. Off-spec mechanism (global Σ, Poisson solve, EOS band on incompressibles) ⇒ ESCALATE.
- Reviews: spec-compliance + code-quality on every task; adversarial-conservation review on R-pass /
  swap / relabel / ledger changes before push.

## Suggested skills
`superpowers:test-driven-development` · `superpowers:subagent-driven-development` ·
`superpowers:requesting-code-review` · `superpowers:verification-before-completion`.
