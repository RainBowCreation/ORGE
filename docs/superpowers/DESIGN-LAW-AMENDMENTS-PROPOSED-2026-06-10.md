# ⚠ PROPOSED AMENDMENTS to DESIGN-LAW.md — REQUIRES USER RATIFICATION

**This file does NOT change the law.** Only the user edits `DESIGN-LAW.md`. The 2026-06-10 physics/math
audit (`notes/2026-06-10-physics-math-audit-report.md`) confirmed, with 3-lens adversarial verification and
numeric reproduction, that several sentences of the frozen law are themselves defective — an implementer
obeying the letter builds a wrong engine. Per the user's instruction *"if DESIGN-LAW is wrong please shout
out"*: **these are the shouts.** Each item gives the exact current text, the defect, and proposed replacement
text. The v4 working spec (`specs/2026-06-10-engine-b-unified-spec-v4.md`) marks everything that depends on
an amendment with `[LAW-AMEND-n]` and is implementable only after ratification of the items it cites.

---

## LAW-AMEND-1 — law #2: the per-face force is stated 2× too strong *(audit B-4, critical)*

**Current:** "each face contributes `P_self − P_neighbor`, opposing faces combine into the vector"
**Defect:** summed literally, opposing faces give `P_below − P_above` = **2×** the correct finite-volume
surface integral. On the engine's own verified hydrostatic profile the literal law leaves 2·m·g of net
upward force on every interior cell — a literal implementation cannot rest. The spec/engine already use the
correct face-average form; the frozen text disagrees with its own verified engine.
**Proposed:** "each face carries the face pressure `½(P_self + P_neighbor)`; the six face pressures combine
as the closed surface integral `F⃗ = −Σ_faces p_face·A·n̂_out` (net per axis: `½(P_low-side − P_high-side)·A`),
which equals `−∇P·V`."

## LAW-AMEND-2 — law #2: `−∇P·dt` is not an impulse *(audit B-7)*

**Current:** "applied as `−∇P·dt`" alongside "gravity … applied as `mass·g·dt`".
**Defect:** `−∇P·dt` is an impulse **density** (off by the cell volume); the gravity clause in the same
sentence is a true impulse. Sums only under the silent convention dx=V=A=1.
**Proposed:** "applied as `−∇P·V·dt` (equivalently the face flux `−p_face·A·dt·n̂` summed over the 6 faces)".

## LAW-AMEND-3 — laws #2/#4: gravity & external impulse are assigned to two phases *(audit B-6)*

**Current:** #2 accumulates gravity + external impulse into momentum "in RESOLVE"; #4 says "ENCODE …
(apply gravity + external impulse to `momentum` …)".
**Defect:** obeying both applies gravity twice (2g free-fall; `P_base = 2ρgH`, contradicting INV-A1).
**Proposed:** gravity + external impulse are applied **once, in ENCODE** (they need no neighbor); #2's
RESOLVE sources become: (i) the 6-face pressure term, (ii) **advected momentum `ṁ·u_donor`** (see
LAW-AMEND-4). Keep "overridable per step_world" for `g`.

## LAW-AMEND-4 — laws #2/#6: moving mass must carry its momentum *(audit B-5)*

**Current:** #6: "advection = enthalpy `ṁ·h` carried by the moving mass … one carried scalar per cell";
#2 lists no advective momentum source.
**Defect:** a draining cell keeps its full momentum while losing mass ⇒ derived `v = p/m` diverges — the
exact velocity-ghost law #7 forbids; #7's "thinned cells bounded" is false without `ṁ·u`.
**Proposed (#6):** "advection = the moving mass carries its **momentum `ṁ·u_donor`** and its **enthalpy
`ṁ·h_donor`** — two carried quantities, same donor, same flux."
**Scope note (review):** law #6's trailing sentence "Same shape as the force: one carried scalar per cell,
one isotropic 6-face flux" must be replaced in the same edit (it contradicts the two carried quantities):
→ "Same shape as the force: carried extensive quantities per cell, isotropic antisymmetric 6-face fluxes."

## LAW-AMEND-5 — law #6: add RADIATION as the third heat mover *(audit C-3/PC-6; user-requested)*

**Current:** "Heat moves in RESOLVE only, two ways: conduction … and advection …"
**Defect/need:** radiation is the dominant real channel for hot surfaces (exposed lava: ~190 kW/m² real vs
~55 W/m² conducted through air — 3,500×); without it lava in open air never crusts. The fix has the same
lawful shape: one antisymmetric 6-face flux.
**Proposed:** "…three ways: **conduction** = the 6-face flux `k_face·(T_i−T_j)·(A/Δx)·dt` (k_face =
harmonic mean — energy exact), **radiation** = the 6-face flux `ε_eff·σ·(T_i⁴ − T_j⁴)·A·dt` (ε_eff = the
condensed side's ε against a transparent partner; ε_i·ε_j between condensed cells, which exchange only when
|ΔT| > 300 K — the film-boiling surrogate; gas partners absorb locally; vacuum faces exchange with a world
`T_sky`, boundary-ledgered), and **advection** (LAW-AMEND-4). All explicit thermal fluxes are bounded by
the **discrete maximum principle, enforced conservatively**: the offending FACE fluxes are scaled
symmetrically (never a one-sided T-clip — that would silently create/destroy energy; review finding) until
a cell's next temperature stays inside its stencil's temperature hull (the stability clamp the audit found
required-but-unstated — audit B-2)."
*(Also fixes the dimensional shorthand: the law's `k·(T_i−T_j)` lacked A/Δx/dt. The trailing "no per-phase
branch" sentence needs one carve-out in the same edit: the radiation face-condition is the one sanctioned
material-class-conditional term — gases have ε=0.)*

## LAW-AMEND-6 — law #8: the fixed LUT schema needs four fields and one guard *(audit B-18, C-5, C-11, MAT-2)*

**Current schema:** `heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity,
defaultMass, yieldStress` + phase quadruple.
**Proposed additions (a schema change is a law change — hence this item):**
- `emissivity` (ε, 0–1) — consumer: the radiation flux (LAW-AMEND-5).
- `latentHeatMin`, `latentHeatMax` (J/kg) — energy plateaus paid at the `minTemp→minTarget` /
  `maxTemp→maxTarget` transitions (audit: the missing 2.26 MJ/kg is 5.4× water's whole sensible budget;
  keep-E relabel with cp ratios produced 185 K condensate / 753 K boil-superheat flip-flops).
- `thermalExpansion` (β, 1/K) — consumer: the swap gate's effective density `ρ_eff = (m/V)(1 − β(T−T_ref))`,
  which is what makes liquid natural convection possible at all (audit C-11: α·χ ≡ 0 made it structurally
  impossible while acceptance test 9 demands it). Mass itself never changes — only the gate reads ρ_eff.
- **χ defined in the law** (review: the symbol was used but defined nowhere authoritative):
  `χ = (maxMass − defaultMass)/(maxMass − minMass)`, with the guard `χ ≡ 0 whenever maxMass == minMass`
  (today χ = 0/0 for stone and steam — audit B-11). Gas class: χ > 0.999.
- `molarMass` finally gets its consumer: the **gas EOS** (LAW-AMEND-7), anchored at a per-gas `T_ref,gas`
  (new LUT column for gas rows); a single global `T_ref` = 288 K serves only the ρ_eff swap-gate read.
- **Bookkeeping state carve-out for law #7's enumerated list:** `swapReady` (the v4 §5.3 swap-cadence
  accumulator — persists across ticks) and `void_ix` (engine free-list index) are persisted bookkeeping
  fields with no physical meaning; law #7's list should name them as such so INV-3 can freeze them.

## LAW-AMEND-7 — law #9: "compresses via EOS" is a dead letter as written *(audit B-3/B-11, critical)*

**Current:** a no-escape cell "compresses via EOS".
**Defect:** for every max==default material the EOS compression branch divides by zero — the prescribed
relief is non-evaluable for exactly the incompressible materials it must cover. Meanwhile the *gas* EOS
(the one place compression is real) is disabled in the working spec, which is the complete mechanism of the
open in-game air-over-accumulation bug.
**Proposed:** "A pushed cell with no escape is a no-op (mass stays). A **gas** genuinely compresses and
pushes back via its live EOS `p_eos = (m/M)·R·T/V − P₀` (gauge; P₀ = its rest-state pressure at its
`T_ref,gas`) — a 700× compressed pocket resists with real megapascals. An **incompressible** cell
(max==default) does not compress; its relief is its pressure `P` rising through the relaxation (P is the
constraint force). Mass is never deleted in either case." *(The existing `no_escape` detection-seam
sentence is kept verbatim. Symbol harmonized to `p_eos` per v4 — review.)*

## LAW-AMEND-8 — laws #6/#7: enthalpy is a curve, not `m·cp·T`, once latent heat exists *(audit B-19/B-20)*

**Current:** "`E = mass·cp·T`"; "`T = E/(mass·cp)` … derived every tick".
**Defect:** with latent plateaus (LAW-AMEND-6) the linear map is wrong at transitions, and the current
relabel-keep-E rule jumps T by `cp_old/cp_new` at every threshold (audit-verified flip-flop/cascade
constructions). Cross-cp advection is undefined-by-label (audit B-20).
**Proposed:** "each material defines an invertible **enthalpy curve** `E = m·h(T)` — piecewise linear in T
whose inverse has plateaus of width `latentHeat` at its phase thresholds; `T = h⁻¹(E/m)` is derived, never
stored. **Phase-paired curves are chain-anchored: `h_target(T*) ≡ h_donor(T*) + L` at each plateau's far
edge** (ice→water→steam one chain), so the relabel re-base is the identity on `E` — **ΔE ≡ 0 by
construction** (review: without this anchoring constraint, T-continuity and E-conservation cannot both
hold and a boil relabel would fabricate MJ per cell). T-continuous ⇒ no flip-flop, no fabricated superheat;
advected enthalpy converts through the donor's curve."

## LAW-AMEND-9 — the law's working-spec pointer is stale *(review finding)*

**Current ("How to use this file"):** "The working spec
(`specs/2026-06-09-engine-b-unified-flow-force-resistance-design.md`) describes the *current implementation
and its deviations*."
**Defect:** the supreme document directs every fresh reader into the superseded v3.
**Proposed:** "The working spec is **the dated spec named in `handoffs/00-MASTER-RULES.md`** (currently
`specs/2026-06-10-engine-b-unified-spec-v4.md`). It describes the current implementation and its
deviations; it is subordinate." *(Generic wording so the law never goes stale on this point again.)*

---

## Explicitly NOT proposed (the law is right; the audit confirmed it)

- One scalar `P`, 6-face isotropic force, same rule all directions, ENCODE→RESOLVE→DECODE, force>resistance,
  viscosity=rate / yield+cohesion=threshold, store-extensive/derive-intensive, antisymmetric conservation,
  the A+B split being labeled DEBT with a single-`P` exit. The v4 spec *implements* that exit (its single-`P`
  relaxation absorbs B), so ratifying v4 *discharges* the debt the law already tracks — no law change needed.

## One worked-example escalation (user ratified the old answer — needs your call)

§8.7's "130 onto 875 ⇒ **blocked**" contradicts the rule as written (f ≤ 5 is legal: receiver 880 ≤ max,
donor 125 = min). The audit confirmed the engine's own probes do sub-min-quantum transfers when leveling
(750.0006|749.9994). v4 keeps the rule and changes the worked example to "transfers up to 5 → [125, 880]".
**If you want "blocked" to stand, the rule needs an explicit minimum-transfer quantum — which would also
forbid the fine leveling the probes show. Please pick: (a) rule wins (v4 default), (b) add the quantum.**
