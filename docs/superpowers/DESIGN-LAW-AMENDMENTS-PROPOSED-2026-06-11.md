# ⚠ PROPOSED AMENDMENTS to DESIGN-LAW.md + spec v4 — REQUIRES USER RATIFICATION (v4.2)

**This file does NOT change the law or the spec. Only the user edits `DESIGN-LAW.md` and the v4 spec.**

These are the "shouts" surfaced by the **whole-arc drift audit** of the T1–T3 implementation
(`notes/2026-06-11-drift-audit-T1-T3.md`; raw `notes/2026-06-11-drift-audit-workflow-result.json`). They
split into two kinds:

1. **Ratification queue (items 1–9 from the T4-continuation handoff)** — code shipped a deviation from the
   ratified text as *labeled debt*, pending the user's call. These are re-stated here with exact replacement
   text so they can be batched-ratified.
2. **New amendment-worthy audit findings** — drift the audit confirmed that needs a *text* decision (not
   just a code fix): **ND-3** (cross-gas absolute pressure), **ND-2** (DECODE no-penetration reads), **ND-6**
   (τ_y units).

Everything else the audit found is **code-debt with a named owner task** (T4/T5/T7/T_n) and does NOT need
the user's pen — it is listed in the inventory doc §E, not here.

Each item gives: **Current** (verbatim), **Defect**, **Proposed** (exact replacement text), **IMPACT**
(consumers / affected tasks / guarded §11 invariants / code delta), **CAUTION** (chain analysis). After
ratification, mark dependents `[LAW-AMEND-v42-n]` in the spec and ride otherwise as labeled debt.

---

## A-1 — §4 boundary-face value + law #2 + §2.1 — the free-surface −mg/2 heat pump *(queue #1, critical)*

**Current — law #2:** "each face carries the face pressure `½(P_self + P_neighbor)`".
**Current — spec §4:** `p̄_f = ½(P_i + P_j); against gas/vacuum: ½(P_i + anchor Φ_f)`.
**Current — spec §2.1:** "`p_eos` is **never read by the force** (§4)".

**Defect:** at a free surface (liquid touching gas/vacuum) the averaged face value `½(P_i + Φ_f)` is one
half-cell-weight *below* the hydrostatic boundary value the surface cell actually presses against. The
residual `−m·g/2` per surface cell never cancels; DECODE's `vel_damp` converts it to heat every tick — a
permanent gravity→damping→heat pump (proven at `engine_b.hpp:648-689`; the reviewer confirmed no
spec-conformant `½(P_i+Φ)` alternative removes it). The implementation already uses the boundary-condition
value, not the average — the ratified text disagrees with the running engine.

**Proposed — spec §4** (replace the face-value line):
```
F⃗_i·dt = Σ_faces −p̄_f·A·dt·n̂_out
  interior fluid|fluid face:    p̄_f = ½(P_i + P_j)
  boundary face (gas/vacuum/solid against fluid i): p̄_f = P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)
        # the cell's own half-cell hydrostatic ghost — the boundary condition itself, NOT ½(P_i + Φ_f)
```
**Proposed — law #2** (replace "each face carries the face pressure `½(P_self + P_neighbor)`"):
"each **interior** face carries `½(P_self + P_neighbor)`; a **boundary** face (against gas, vacuum, or a
solid wall) carries the cell's own half-cell hydrostatic ghost `P_self + ρ_self·g⃗·(r⃗_face − r⃗_self)` — the
boundary condition, not an average with the far side."
**Proposed — spec §2.1** (replace "`p_eos` is never read by the force"):
"`p_eos` is **never SUMMED with `P` as a second pressure for the same cell** — exactly one pressure number
acts per cell. It enters the force only through the §3.1 relaxation that sets `P`, and a *gas* cell's own
boundary-face ghost uses its EOS anchor in place of `P` (see A-8)."

**IMPACT:** consumers = the §4 DriveCtx face loop (`engine_b.hpp:640-766`), every free surface and wall
face. Affected tasks = **T4** (the force pre-pass is being restructured anyway — land the ratified wording
there). Guards §11 INV-P2 (the wall ghost `p̄=P_i` is the special case `r_f−r_i` ⟂ g, load-bearing for
levels [750|750]=1500.0) and kills the INV-AL/INV-GAS static-heating debt (queue #7). **Code delta: none**
— text is being matched to running code.
**CAUTION:** this is the keystone amendment; A-8 (gas P force-dead) and A-6 (¼Δρ) are sub-cases of the same
face-value machinery and should be read together. The boundary ghost makes the force *not* read the
neighbor's `p_eos` at liquid|gas faces (good for §2.1), but a gas cell still reads its *own* `p_eos` anchor
(A-8) — the §2.1 rewrite must permit that, hence the "for the same cell" clause.

---

## A-2 — spec §2.1 — cross-gas faces must compare ABSOLUTE pressure *(ND-3, high — FORK)*

**Current — spec §2.1:** "**Cross-gas faces compare ABSOLUTE pressure** (`p_eos + P0` each side) so per-gas
gauge offsets cancel." (ratified decision DEC-v4-B.)
**Defect:** implemented **nowhere**. At an air|steam face both the §3.1 stencil and the §4 force read each
side's *gauge* `p_eos` and never re-add per-gas `P0`. Air P0 ≈99.1 kPa vs steam P0 ≈103.4 kPa ⇒ two resting
gases at true mechanical equilibrium read a spurious ~4.3 kPa gauge difference → phantom gradient into `P`
and the momentum force. Air|steam faces are reachable (boiling water). Distinct from A-1/A-8: even a correct
boundary-ghost force is wrong here because it compares gauges, not absolutes.

**FORK — the user picks one:**
- **(i) Keep the mandate, implement it (T4):** at a face whose two endpoints are *different* gas species,
  compare `p_eos + P0` each side (re-add per-gas P0); same-gas faces stay gauge-cancelled. No text change.
- **(ii) Drop the mandate (amend §2.1):** replace the sentence with "Cross-gas faces compare **gauge**
  `p_eos`; per-gas P0 offsets are neglected (the resting air|steam interface carries a documented ~4.3 kPa
  false equilibrium — acceptable in-game)." Also retire DEC-v4-B.

**IMPACT:** consumers = §3.1 gas-neighbor anchor (`engine_b.hpp:484/490/498`) + §4 GAS-j force branch
(`:754-757`). Affected task = **T4** (option i) or none (option ii). Guards §11 INV-AL (air|steam
stratification) — option (i) changes the resting interface, so INV-AL would need an air|steam variant.
**Code delta:** option (i) = real (per-gas P0 re-add at hetero-gas faces); option (ii) = none.
**CAUTION:** option (i) interacts with A-8 — if gas P is deleted, the absolute-P comparison must live on the
anchor, not P. Recommend deciding A-8 first.

---

## A-3 — law #4 / spec §4 — sanction DECODE-time no-penetration neighbor reads *(ND-2, low)*

**Current — law #4:** "RESOLVE is the **only** cross-cell step … DECODE is per-cell **local** (derive
`T`/`v`, relabel, write back)."
**Defect:** DECODE (`decrypt_world`, `engine_b.hpp:1761-1787` + swap `:1690-1695`) does 1-hop neighbor reads
(`wall_neighbor`, `xspecies_noflux_neighbor`) to zero velocity components pointing into wall / cross-species
no-flux faces — a face boundary condition enforced in a clause-designated per-cell-local step. The reads are
of the immutable pre-step snapshot, velocity-only, order-independent, GPU-safe, no fabrication.
**Proposed — spec §4 DECODE bullet** (add one sentence) / **law #4** (amend the DECODE clause):
"DECODE is per-cell local except for **velocity-only no-penetration clamps**, which may read the 1-hop
snapshot neighbor to zero a velocity component pointing into a wall or cross-species no-flux face (a
boundary condition, not mass/heat transport — fabricates nothing, order-independent)."

**IMPACT:** consumers = the two DECODE clamp loops. Affected task = none (record-only). Guards nothing
numerically (velocity-only). **Code delta: none** (text matched to running code).
**CAUTION:** keep the carve-out tightly worded — "velocity-only, snapshot-read" — so it cannot be read as
licensing general cross-cell work in DECODE.

---

## A-4 — spec §1.2 / §5.1 — τ_y dimensional coherence *(ND-6, low)*

**Current — spec §1.2:** `τ_y [Pa]`. **§5.1:** "Net force vs `τ_y·A_face` [N vs N]".
**Defect:** the swap gate (`engine_b.hpp:157-161`) omits the `·A_face` factor and `Material::yieldStress`
is declared **N** (`sim_engine.hpp:56`) — the struct stores Newtons while the spec stores Pascals and
converts via A_face. Spec also doesn't specify the pair-combination (code uses `max(τ_i,τ_j)`). Numerically
inert today (A=1, τ_y∈{0,∞}) but any A≠1 or finite-τ_y material silently inherits the wrong scaling.
**Proposed — spec §1.2 + §5.1** (pick the code's convention, which is sounder):
"§1.2: `yieldStress` is stored as a **force threshold [N]** (already includes the face area for A=1 cells).
§5.1: the swap/yield gate compares net face force against `max(τ_y,i, τ_y,j)` [N vs N]; for A≠1 cells the
LUT value is the per-unit-area yield times the reference face area." (Alternatively keep [Pa] and add the
explicit `·A_face` conversion to the code — a code fix instead of an amendment.)

**IMPACT:** consumers = `swap_resistance`, `Material::yieldStress`. Affected task = T8 (ρ_eff/swap lands
there) or a tidy task. Guards §11 INV-SWAP (none today; τ_y∈{0,∞}). **Code delta:** none if the [N]
convention is ratified; a comment/declaration fix only.
**CAUTION:** §5.1's stacked-chain rule `F_out=F_in+m·(g·n̂)−τ_y·A` (currently implemented nowhere, no owner
task) uses the same τ_y — fix both conventions together or the chain rule inherits the mismatch.

---

## A-5 — spec §1.3 frozen manifest — three gaps *(queue #10/#11 + new knobs)*

**(a) Joint stability constraint missing *(queue #11)*.** §1.3 bounds `ω∈[1.0–1.9]` and `α_eos∈(0,1]`
independently, but the gas relaxation's combined SOR+EOS update is divergent unless `ω·(1+α_eos) < 2`.
**Proposed:** add to §1.3 — "joint constraint `ω·(1+α_eos) < 2` (independent ranges admit divergent
pairs)."
**(b) κ·divU source un-pinned *(queue #10)*.** The `κ·divU` term is sourced from the **persisted**
(pre-ENCODE snapshot) velocity, deliberately, to avoid a phantom `−g·dt` floor divergence; §3.1 doesn't say
which velocity. **Proposed:** add to §3.1 — "`divU` in the κ term reads the **persisted** velocity (the
post-ENCODE −g·dt is excluded — it is not a real divergence)."
**(c) Off-manifest knobs.** T3 added `LADDER_BETA=0.5`, `LADDER_REST_DEADBAND=0.99`, `GAS_CHI_MIN=0.999`
not in §1.3 (labeled in-code "retired by T4 flux-intent"). **Proposed:** either enroll them in §1.3 or
ratify that they are T4-transient and exempt from INV-3 until T4 deletes them.

**IMPACT:** consumers = `relax_world` (a,b), gas ladder (c). Affected tasks = T4 (c deletes the knobs).
Guards §11 INV-3 (manifest CI-diff must be empty — currently fails on these). **Code delta: none** (a/b are
wording; c is a label-vs-enroll decision).
**CAUTION:** (c) — if T4 genuinely deletes LADDER_*, enrolling them now then deleting them churns §1.3;
the "T4-transient exempt" ruling is cleaner.

---

## A-6 — law #2 — liquid|liquid ¼Δρ face correction *(queue #3, medium)*

**Current — law #2:** interior face `½(P_self + P_neighbor)`.
**Defect:** liquid|liquid faces add a `¼(ρ_j − ρ_i)·g·dx·DY` density-difference correction beyond the
literal mean (`engine_b.hpp:760-766`); probe-justified (a partial-over-full +1250 N limit cycle) but extends
the ratified formula.
**Proposed (option to enshrine):** law #2 interior face → "`½(P_self + P_neighbor) + ¼(ρ_neighbor −
ρ_self)·g⃗·(r⃗_face − r⃗_self)` — the density-difference correction that makes the two sides' half-cell
hydrostatic extrapolations agree at the shared face." **Or (option to revert):** delete the correction in
T4 and accept the limit cycle / find another fix.
**IMPACT:** consumers = §4 liquid|liquid branch. Affected task = T4. Guards INV-P2 levels. **Code delta:**
none if enshrined; real if reverted. **CAUTION:** this is the same half-cell-ghost family as A-1 — ratify
them with one mental model (faces carry each side's own hydrostatic extrapolation).

---

## A-7 — spec §5.2 — sub-min D≤min full-merge branch HELD *(queue #5, medium)*

**Current — §5.2:** "…else `f ← D` if `R + D ≤ maxMass`, else `f ← 0`" (the D≤min full-merge branch).
**Defect:** a sub-min **liquid** donor is HELD (`want=0`, pre-T3 behavior; full-merge was implemented in T3
then reverted after probes); a sub-min **gas** donor trickles under the EOS ladder. The two ratified worked
examples (130→[125|880], 250→[125|1000]) are bit-exact; only the D≤min case deviates.
**Proposed:** either (i) amend §5.2's D≤min branch to "HELD until a receiver-side drain exists (T5)", or
(ii) implement the receiver-side drain in T5 (banked stage3_cleanup) and enforce the ratified text then.
**IMPACT:** consumers = §5.2 sub-min branch. Affected task = T5. Guards INV-COH. **Code delta:** none now
(option i); real in T5 (option ii). **CAUTION:** liquids and gases already diverge here — any amendment must
state both species' behavior explicitly.

---

## A-8 — gas cells' relaxed P is force-dead *(queue #2, medium — decide)*

**Current — spec §2.1/§3.1:** gas cells participate fully in the relaxation (P is relaxed for gas).
**Defect:** a gas cell's force never reads its own relaxed `P`; every face uses its EOS anchor
(`ownf = a_i − ρ_i·g·…`). The relaxed gas `P` is computed and persisted but force-dead — diagnostic only.
**DECIDE (user):** (i) **delete** gas `P` from the relaxation (gases carry only `p_eos`), (ii) **re-couple**
(make the gas force read `P`), or (iii) **keep** as a tracked diagnostic and document it in §2.1.
**IMPACT:** consumers = §3.1 gas branch + §2.1. Affected task = T4. Guards INV-AL/INV-GAS. **Code delta:**
(i) real, (ii) real + risks re-introducing the static-heating the anchor fixed, (iii) doc-only.
**CAUTION:** strongly coupled to A-1 and A-2(i) — the gas boundary ghost and the cross-gas absolute-P both
live on the anchor, so deleting/re-coupling P here changes where those must be implemented. Recommend
ratifying A-1 → A-8 → A-2 in that order.

---

## A-9 — open-top gas column has no representable rest state *(queue #6, design gap)*

**Defect:** under every current rule an open-top gas column has NO rest state (the `Σa = +2400 Pa` argument,
independently verified at `tests/engine_b_gas_test.cpp:10-23`). The world top is closed (sealed ⇒ fine); the
standing case is air|VACUUM pockets from broken blocks. INV-AL was re-authored sealed-top as a stopgap.
**Proposed:** ratify a **shedding/absorbing boundary** for gas-against-vacuum (a gas face against vacuum
sheds mass/momentum/E to the boundary ledger until the surface cell reaches rest density) — design TBD, owner
task TBD (candidate: fold into T6 radiation-to-sky boundary or a dedicated T_n). This is a *new mechanism*,
not a wording fix — flagged so it is not silently dropped.
**IMPACT:** consumers = a new gas|vacuum boundary path. Affected task = new. Guards INV-AL (would re-arm
open-top). **Code delta:** real, new. **CAUTION:** needs its own brainstorm + spec section before any code;
do not bundle into T4.

---

## Ratification checklist (batched)

| # | Item | Kind | Code delta | Recommended order |
|---|------|------|-----------|-------------------|
| A-1 | §4 boundary ghost + law #2 + §2.1 | law+spec | none | **1 (keystone)** |
| A-8 | gas P force-dead | decide | varies | 2 |
| A-2 | cross-gas absolute P (ND-3) | fork | varies | 3 |
| A-6 | liquid\|liquid ¼Δρ | law | none/revert | with A-1 |
| A-3 | DECODE no-penetration reads (ND-2) | law+spec | none | any |
| A-4 | τ_y units (ND-6) | spec | none | any |
| A-5 | manifest gaps (a/b/c) | spec | none | any |
| A-7 | §5.2 sub-min HELD | spec | none/T5 | any |
| A-9 | open-top gas rest state | new mechanism | real/new | last (needs brainstorm) |

**Until ratified:** every item above remains labeled in-code debt; **T4 does not start** (per the gate).
After ratification the user edits `DESIGN-LAW.md` / the v4 spec; this file and the pre-amendment text live in
git history.
