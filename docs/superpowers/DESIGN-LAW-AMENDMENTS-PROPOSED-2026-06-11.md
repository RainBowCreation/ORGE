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

## ✅ DECISIONS — user walkthrough 2026-06-13

The user walked every amendment item and recorded a decision. **These are the chosen options; the actual
ratification (editing `DESIGN-LAW.md` + the v4 spec) is NOT yet applied — a follow-up agent applies the decided
text, then T4 may start.** This table is the single source of truth for the decisions; each amendment section
below retains its full Current/Defect/Proposed/Impact/Caution detail.

| # | Decision |
|---|----------|
| **P-0** | ✅ **RATIFY** — universal cell law: ONE mechanic, never branch by state; states = same cell w/ different material data. Root principle; ratify first. New invariant **INV-UNIVERSAL**. |
| **A-1** | ✅ **RATIFY** — boundary ghost; match text to running code, no code delta (keystone). |
| **A-2 / ND-3** | ✅ **(i) IMPLEMENT** — cross-gas absolute P (`p_eos+P0`) on the ANCHOR at hetero-gas faces; T4. INV-AL needs air\|steam variant. |
| **A-3 / ND-2** | ✅ **ALLOW** — sanction DECODE no-penetration neighbor reads; wording tightened (clamp zeroes velocity + derived momentum, mass/E untouched). |
| **A-4 / ND-6** | ✅ **[N] convention** — τ_y stored as force threshold; gate compares N vs N; no code. |
| **A-5** | ✅ (a) **RATIFY** `ω·(1+α_eos)<2`; (b) **RATIFY** κ·divU reads before-gravity (persisted) velocity; (c) **EXEMPT** LADDER_*/GAS_CHI_MIN as T4-transient. |
| **A-6** | ✅ **ENSHRINE** — liquid\|liquid ¼Δρ into law #2; no code. |
| **A-7** | ⤵ **DOWNGRADED** — unreachable once A-10 lands (a cell can't become sub-min); drop the §5.2 branch instead of "HELD until T5". |
| **A-8** | ✅ **(iii) KEEP** — gas relaxed P stays a force-dead diagnostic; document in §2.1. |
| **A-9** | ✅ **NOT A NEW MECHANISM** — gas\|vacuum = ordinary flow into a mass-0 cell under the universal law; atmosphere emergent; **probe** to confirm the top boundary layer rests. "Shed to sky ledger" WITHDRAWN. |
| **A-10** | ✅ **(A) BLOCK + GATE** — relabel forbidden if `mass < target.min`; empty-refill needs donor-side gate. New invariant **INV-NOSUBMIN**. |
| **A-11** | 📋 **FUTURE FEATURE** — impact heat on high-force collisions (gated above a jitter floor). |
| **A-12** | ✅ **(C) ATOMIC FREEZE-EVICT** — over-max forbidden; freeze evicts excess same-pass under BOTH bounds; defer if no legal target. New invariant **INV-NOOVERMAX**. Twin of A-10: `min ≤ m ≤ max ∨ m=0`. |
| **A-13** | ✅ **SUBSUMED** — §5.1 granular = fluid with finite τ_y on the existing gate; no granular sub-system; **probe** stand/slump. |
| **A-14** | 📋 **FUTURE FEATURE** — true angle of repose (shear-aware/directional yield). |

**Recommended ratify order:** P-0 → A-1 → A-8 → A-2 → (A-6 with A-1) → A-10 + A-12 together (`min≤m≤max`) →
A-4 (+A-13) → A-3, A-5 any time → A-9 probe (after A-10/A-12) → A-11/A-14 deferred.
**New code work created (beyond labeled-debt text matches):** A-2(i) anchor P0 re-add; A-10 empty-refill
donor-side gate; A-12 atomic freeze-evict; A-9 + A-13 verification probes. Batch onto T4's injection/relabel
seam where they overlap.

**⚠ Audit-doc note:** `notes/2026-06-11-drift-audit-T1-T3.md` §A headline says "15 confirmed" but lists 14
bullets — real miscount, flagged for correction.

---

## P-0 — FOUNDATIONAL PRINCIPLE: one universal cell law *(user-raised; the root the others derive from)*

**Principle (INV-UNIVERSAL).** The law treats **every cell as a cell** — it is **never branched by phase/state**
(solid / liquid / gas / vacuum). There is **ONE mechanic**; "solid", "liquid", "gas", "vacuum" are not
separate systems but the *same* matter-cell carrying **different material data values**:

| "state" | = the universal cell with… |
|---|---|
| vacuum | `mass = 0` (void material, `min = max = 0`) |
| gas | low `min/default/max`, high `χ` (compressible), `τ_y = 0` |
| liquid | `χ = 0` (incompressible), `τ_y = 0` |
| sand / granular | `τ_y` **finite** (between 0 and ∞) |
| solid / terrain | `viscosity = ∞` / `τ_y = ∞` (never yields) |

All behavior is **emergent from the one law + gravity + the EOS + `min ≤ m ≤ max` legality**, not from
per-state code paths:
- liquids **pool & level**, gases **stratify into a barometric atmosphere** (dense low → thin high → vacuum on
  top), solids **hold**, sand **stands until force > τ_y then slumps** — all the *same* flow/yield gate with
  different numbers.
- **vacuum is not a boundary** needing special handling — it is a mass-0 cell that receives flow under the same
  `min ≤ m ≤ max` rule (gas fills it iff the result is a legal `≥min` cell; else it stays vacuum).

**Consequences (the items below are all corollaries, not independent mechanisms):**
- **A-9** (open-top gas / atmosphere) — dissolves: gas|vacuum is ordinary flow into a mass-0 cell.
- **A-10 + A-12** (`min ≤ m ≤ max ∨ m = 0`) — the single mass-legality law enforced at *every* flow, relabel,
  and eviction site, for *every* cell.
- **§5.1 granular yield** (A-13) — sand is a fluid with finite `τ_y`; the stacked-chain force is carried by the
  pressure field, not a separate granular pass.

**Mandate:** new code MUST NOT add a `switch(state)` / `if (isGas) … else if (isLiquid) …` branch in the core
mechanic. State-specific behavior comes ONLY from material data values fed to the one law. Any apparent need to
branch by state is a signal the material parameterization is incomplete — fix the data, not the law.
**IMPACT:** governs all of §2–§8. **Code delta:** none now (principle); it is the *acceptance test* for every
future task — a state-branch in the core is a regression. **CAUTION:** the EOS/χ already encode gas-vs-liquid
as data (`χ = (max−default)/(max−min)`); keep it that way. The only legitimate "branch" is reading a material's
own numbers (`τ_y`, `χ`, `viscosity`, `min/max`), never its *name*.

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
"DECODE is per-cell local except for **no-penetration clamps**, which may read the 1-hop snapshot neighbor to
zero a velocity component pointing into a wall or cross-species no-flux face. The clamp zeroes velocity;
persisted momentum is written from the clamped velocity (`p = v·m`) so the matching momentum component is
zeroed too — **mass and E untouched** (the kinetic energy removed is a sanctioned **no-deposit** per §4 /
ND-18). A boundary condition, not transport; order-independent, snapshot-read."

**IMPACT:** consumers = the two DECODE clamp loops. Affected task = none (record-only). Guards nothing
numerically (the clamp zeroes velocity + its derived momentum; mass/E preserved). **Code delta: none** (text
matched to running code).
**CAUTION:** keep the carve-out tightly worded — "no-penetration clamp, snapshot-read, mass/E untouched" — so
it cannot be read as licensing general cross-cell work in DECODE. The wall reaction / push-back is a SEPARATE
mechanism (the §4 wall-reaction pressure term `k=P_i·A·dt`, `engine_b.hpp:1054`) — the clamp only stops
penetration; pressure provides the support/push-back. NOTE the impact KE is deliberately NOT deposited as heat
(a resting pool's into-floor numerical jitter would otherwise fake-boil it every tick) — but this means a
genuine HIGH-SPEED slam under-produces heat; see A-11 (future feature).

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

## A-9 — open-top gas column rest state — NOT a new mechanism *(queue #6; reframed user-raised)*

**Defect:** under the *current* (pre-A-10/A-12) rules an open-top gas column has NO rest state (the
`Σa = +2400 Pa` argument, verified at `tests/engine_b_gas_test.cpp:10-23`). The world top is closed (sealed ⇒
fine); the standing problem case is air|VACUUM pockets from broken blocks. INV-AL was re-authored sealed-top
as a stopgap.
**Reframe (supersedes the original "shedding boundary" proposal):** there is **no special gas|vacuum
mechanism**. There is **ONE universal mechanic for every cell** regardless of contents — solid, liquid, gas,
vacuum are the *same* matter-cell with different material numbers (**vacuum = a `mass = 0` cell**, void
material `min=max=0`; **solid = `viscosity=∞`**, never yields — a *parameter*, not a different rule). So
gas→vacuum is **just flow into a mass-0 cell**, governed by the **same `min ≤ m ≤ max` legality** as A-10/A-12:
gas flows into the vacuum cell iff the receiver ends `≥ minMass` and the donor is not left stranded
(`≥ minMass` or fully drained), else **no flow — vacuum stays vacuum**. No shedding ledger, no mass leaves the
world, no new path.
**Why the "no rest" bug is expected to DISSOLVE:** the `+2400 Pa never-settles` proof assumed the *old*
un-quantized anchor rules. The universal min-floor **bounds** the expansion — a gas of total mass M can fill
at most `floor(M / minMass)` vacuum cells, then halts (no remaining vacuum cell can legally receive `≥min`),
the remainder held in the densest donor. Finite, conservative, well-defined — the same reason a liquid pool
rests. Adopting A-10/A-12 is expected to cure A-9 with **no extra mechanism**.
**Proposed:** (1) **do NOT special-case vacuum** — delete the sealed-top stopgap framing; gas|vacuum rides the
universal legality law. (2) **Probe to confirm rest** (a small air|vacuum column test) — the one open risk is
a **boundary-layer oscillation**: the top cell ends at `minMass` where p_eos is *negative* (expansion branch
→ wants to contract); must verify it lands at rest, not ping-pong `min ↔ min+ε`. If the probe shows a twitch,
the fix is a small deadband at the min boundary, still within the universal rule (not a new mechanism).
**IMPACT:** consumers = the existing flow/legality path (no new path). Affected task = verification probe +
re-arm INV-AL open-top once it passes. Guards INV-AL. **Code delta:** none beyond A-10/A-12 (possibly a tiny
min-boundary deadband if the probe shows oscillation). **CAUTION:** ratify A-10/A-12 first, THEN run the A-9
probe — A-9's resolution is downstream of the universal mass-legality law, not independent. The original
"shed mass/E to a sky ledger" idea is **withdrawn** (it destroyed mass; the universal rule conserves it).

---

## A-10 — relabel must never mint a sub-min cell (no-sub-min invariant) *(user-raised, design)*

**Current — spec §6.4:** "`0 < m < min`: never deleted — DECODE flags it; next RESOLVE drains it into the
strongest same-species neighbor." (i.e. a resting sub-min cell is *tolerated for ≥1 tick* and cleaned later.)
**Current — phase relabel:** the DECODE thermal relabel (`engine_b.hpp:1351-1376`) flips a cell's species on
a T-threshold crossing with NO check that the carried mass clears the **target** material's `minMass`; the
Java mirror (`PhasePlanner.java:36/55`) gates only on a flat `PHASE_MIN_MASS = 5 kg` (an anti-empty guard,
Bug B), not on the target's min.

**Defect (user invariant):** the law should guarantee **no cell ever rests at `0 < m < min`, from any path** —
not "appears then drains next tick." Flow already honors this (the §5.2/§3 cohesion gate cancels any drain
that would strand a donor: leave `≥ min` OR drain fully to `0`). But **relabel can mint a sub-min cell with
no flow to cancel**: a mass-preserving phase change into a target whose `minMass` exceeds the cell's mass
(e.g. a 50 kg steam wisp condensing → 50 kg water, `min_water = 125`). §6.4 currently *legalizes* that
transient instead of *preventing* it.

**Proposed — new invariant (law + spec §6.4 + §8 relabel):**
"**INV-NOSUBMIN.** No cell may hold `0 < m < min_mass(species)` at rest. A species relabel (phase change or
empty-refill adoption) is **FORBIDDEN** when the cell's carried mass `< minMass(target species)`; the cell
keeps its current species, mass, and E (the thermal relabel is keep-E, so blocking conserves energy exactly
— it only defers the label flip until the cell legally clears the target min). The §6.4 'drain next RESOLVE'
clause is **retired** for the relabel path: sub-min is *prevented at the source*, not cleaned afterward."

Two enforcement sites:
- **Thermal relabel** (`engine_b.hpp:1367`, + Java `PhasePlanner`): add the guard
  `&& mNew >= mats.byIx(tgt).minMass`. Conservation-safe (keep-E). Cost = a cell past its T-threshold but
  under target-min wears its OLD label (e.g. "cold steam" in the band `[5 kg, target_min)`) until it gains
  mass; below 5 kg already skipped.
- **Empty-refill adoption** (`engine_b.hpp:1323-1334`): an empty cell must NOT adopt a donor species when the
  delivered inflow `< minMass(donor species)`. Because the mass is *already there*, blocking the label is not
  enough — this requires extending the cohesion gate to the **receiver-empty** case: a donor may not deliver
  `< target-min` into an empty cell **except on a full-drain** (the donor's whole mass moves, leaving the
  donor at 0, and the empty cell receives a legal `≥ min` quantum or the donor fully merges).

**IMPACT:** consumers = DECODE thermal relabel + §D.5 empty-refill + Java `PhasePlanner`/`PhaseRule`; the §3
cohesion flux-gate (receiver-empty extension). Affected tasks = **T7** (latent re-examination — keep-E
blocking interacts with the latent ledger once §8.4/plateaus land) + a cohesion-gate tidy for the empty-refill
clause. Guards a NEW invariant INV-NOSUBMIN; **supersedes** §6.4's "tolerate + drain" wording. **Code delta:**
thermal guard = one-liner (×2, C++ + Java); empty-refill = real (donor-side gate). §8.4 freeze (water→ice,
1000>917, over-MAX not sub-min) is unaffected.
**CAUTION:** (1) Current linear-E engine makes the thermal block conservation-safe; **T7's latent system must
re-verify** — a cell that has *paid latent heat* but can't relabel needs a defined home for that energy, or
INV-LAT breaks. (2) The empty-refill gate is the load-bearing part — a label-only block leaves orphaned
sub-min mass in a "vacuum" cell (illegal); the donor-side gate is mandatory, not optional. (3) Directionally,
boil (water→steam, mass preserved high, steam min low) and freeze (over-max) rarely trigger; **condensation
of thin gas** is the case that actually fires.

---

## A-11 — impact heat on high-force collisions *(future feature, user-raised)*

**Defect (missing physics):** the §4 no-penetration clamp (A-3) and the CFL speed cap remove a cell's
into-wall / into-interface kinetic energy with **no heat deposit** (ND-18, sanctioned). Correct for a settling
pool (the into-floor motion is numerical jitter; depositing it would fake-boil a resting puddle every tick),
but it means a **genuine high-speed impact** (fast water/lava slamming a solid) **under-produces heat** — real
inelastic impact converts bulk KE to heat. Today no path makes impact heat.
**Proposed (future feature, NOT a current amendment):** deposit the clamped KE as heat **only when the
into-surface speed exceeds a jitter floor** `v_impact_min` (well above settling churn), so resting pools never
self-heat but a real slam warms the impact site. Threshold + deposit fraction TBD; owner task TBD (candidate:
fold into T7 thermal or a dedicated T_n). Mirrors the `vel_damp` heat-deposit machinery §4 already has.
**IMPACT:** consumers = the DECODE clamp + CFL cap sites. Affected task = new/T7. Guards a NEW INV (impact
energy closes to heat above threshold). **Code delta:** real, new — gated so it is a no-op below `v_impact_min`.
**CAUTION:** the jitter floor is load-bearing — set it too low and resting fluids self-heat (the exact bug
no-deposit avoids); too high and only extreme slams register. Needs a probe to pick `v_impact_min`. Do NOT
bundle into A-3 (which is record-only, no code) — this is separate, deferred.

---

## A-12 — over-max forbidden + atomic freeze-evict (INV-NOOVERMAX) *(user-raised, design; twin of A-10)*

**Current — spec §8.4:** the water→ice freeze relabel is sanctioned as "**the single exemption**" that lets a
cell sit at `mass > maxMass` (water ~1000 → ice, ice max ~917 ⇒ 1000 > 917), bounded, **drained next RESOLVE**.
**Defect (user invariant, twin of A-10):** the law should guarantee **`min ≤ m ≤ maxMass` at rest, from any
path** — over-max no more tolerable than sub-min. Flow already honors the upper bound (the §6 *room* gate
`room = maxMass − m`; the only leak was ND-14's 1e-3 slop, fixed → `evict=min(evict,kroom)`). But the **freeze
relabel mints over-max with no flow to cancel** — water is denser than ice's max, so a mass-preserving freeze
overflows the ice cell. §8.4 currently *legalizes the transient* instead of *preventing it*.

**Proposed — new invariant (law + spec §8.4):**
"**INV-NOOVERMAX.** No cell may hold `m > maxMass(species)` at rest. The water→ice freeze (and any relabel into
a lower-max species) must **evict its excess in the SAME pass** (atomic freeze-evict), not next RESOLVE: the
freezing cell relabels to ice at `maxMass` and the surplus `(m − maxMass_ice)` is pushed to a neighbor,
carrying mass + E. The §8.4 'drain next RESOLVE' exemption is **retired**."

**The eviction obeys BOTH bounds (couples to A-10):** the surplus is a normal flow subject to every gate —
- receiver ends **≤ maxMass** (room gate), and
- an **empty** receiver must end **≥ minMass** (A-10 empty-refill gate) — the excess may NOT spawn a sub-min cell.
Targets in priority: same-species neighbor(s) with room (split, e.g. `[917 | nbr+83]`); an empty neighbor only
if `excess ≥ minMass`. **If no legal target exists this tick** (boxed in by full ice / walls, and excess <
min for any empty cell): **DEFER the freeze** — the cell stays water (cold), retries next tick. Never forces an
illegal state; keep-E means deferral conserves energy exactly.

**IMPACT:** consumers = §8.4 freeze relabel + the §6 eviction/room machinery + the A-10 empty-refill gate.
Affected tasks = **T7** (latent: a cell that has paid latent heat but must defer the freeze needs a defined
home for that energy, or INV-LAT breaks — same caveat as A-10) + the eviction/cohesion tidy. Guards NEW
invariant INV-NOOVERMAX; **supersedes** §8.4's "single exemption". **Code delta:** real — atomic freeze-evict
with two-sided legality + defer fallback (bigger than A-10's one-liner; A-10 + A-12 share the empty-refill gate).
**CAUTION:** (1) **defer-forever** is possible (a water cell fully boxed by incompressible full neighbors never
freezes) — accepted: physically ice boxed with nowhere to expand builds crushing pressure / can't freeze, a
fair simplification. (2) A-10 (sub-min) and A-12 (over-max) are **twins** — ratify together as one two-sided
mass-legality law `min ≤ m ≤ max ∨ m = 0`, enforced at every flow + relabel + eviction site. (3) Direction
matters: freeze (water→ice) overflows; boil/condense are the A-10 sub-min side — one law covers all four.

---

## A-13 — §5.1 granular yield is subsumed by the universal law (NOT a separate mechanism) *(reframed, P-0 corollary)*

**Current — spec §5.1:** the stacked-chain yield-propagation `F_out = F_in + m·(g·n̂) − τ_y·A` is written as its
own clause, implemented **nowhere**, owned by **no task** (T4–T10). Flagged in the audit as an ownerless gap.
**Reframe (per P-0):** **sand/granular is not a separate system** — it is the universal cell with a **finite
`τ_y`** (between water's 0 and stone's ∞). The flow/swap gate **already** compares net face force against
`max(τ_y,i, τ_y,j)` (`engine_b.hpp:137`), so a finite τ_y rides the *existing* gate with no new code. The §5.1
"stacked chain" force accumulation is **carried by the pressure field** (hydrostatic pressure at a column's base
already equals the overburden weight) — the explicit `F_out=F_in+mg−τ_yA` is the per-cell *restatement* of what
pressure + the yield gate already do emergently, not a separate pass to implement.
**Proposed:** (1) **Mark §5.1 subsumed** — finite τ_y rides the universal force-vs-yield gate (+ A-4's [N]
units); no dedicated implementer/owner task. (2) **Verify by probe**: a finite-τ_y pile must *stand* below
yield and *slump* above it. (3) **Log the limitation** (see A-14): a scalar τ_y gives "hold-or-slump" but
likely **flat-ish piles**; a true **angle of repose** (sloped sand ~34°) needs shear-aware/directional yield —
deferred as a future feature, not in-arc.
**IMPACT:** consumers = the existing swap/flow yield gate. Affected task = a probe (+ A-4 units). Guards
INV-SWAP. **Code delta:** none beyond A-4 (the gate already exists; finite τ_y is just data). **CAUTION:** do
NOT build a granular sub-system — that violates P-0. The only open design question (repose angle) is A-14.

---

## A-14 — true angle of repose for granular materials *(future feature, P-0-consistent)*

**Defect (missing physics):** a scalar `τ_y` (A-13) reproduces "stand below yield / slump above" but not a real
**angle of repose** — dry sand rests at a stable slope (~34°) because it resists **shear** directionally, not
isotropically. Scalar yield likely gives flat-ish or blocky piles, not sloped dunes.
**Proposed (future, NOT current):** extend the per-face yield to be **shear-aware** (yield depends on the
face's orientation relative to gravity / the local stress direction), still as **material data** fed to the one
law (P-0-consistent — no state branch). Owner task TBD; needs a probe to tune the repose angle per material.
**IMPACT:** consumers = the swap/flow yield gate (directional variant). Affected task = new/T_n. Guards a new
INV-REPOSE. **Code delta:** real, new — gated so scalar-τ_y materials are unaffected. **CAUTION:** keep it data-
driven (a per-material repose angle), never a `if (isSand)` branch; P-0 forbids state branches.

---

## Ratification checklist (batched)

| # | Item | Kind | Code delta | Recommended order |
|---|------|------|-----------|-------------------|
| P-0 | universal cell law (no state branches) | principle | none | **0 (root — ratify first)** |
| A-1 | §4 boundary ghost + law #2 + §2.1 | law+spec | none | **1 (keystone)** |
| A-8 | gas P force-dead | decide | varies | 2 |
| A-2 | cross-gas absolute P (ND-3) | fork | varies | 3 |
| A-6 | liquid\|liquid ¼Δρ | law | none/revert | with A-1 |
| A-3 | DECODE no-penetration reads (ND-2) | law+spec | none | any |
| A-4 | τ_y units (ND-6) | spec | none | any |
| A-5 | manifest gaps (a/b/c) | spec | none | any |
| A-7 | §5.2 sub-min HELD | spec | none/T5 | any |
| A-9 | open-top gas rest state — NO new mechanism (universal rule + probe) | spec/verify | none (maybe tiny deadband) | after A-10/A-12, then probe |
| A-10 | no-sub-min from relabel (INV-NOSUBMIN) | law+spec | one-liner + empty-refill gate | with A-7 (supersedes §6.4) |
| A-11 | impact heat on high-force collisions | future feature | real/new (gated) | last (deferred, needs probe) |
| A-12 | over-max forbidden + atomic freeze-evict (INV-NOOVERMAX) | law+spec | real (shares A-10 gate) | with A-10 (twin: min≤m≤max) |
| A-13 | §5.1 granular subsumed (finite τ_y, no new system) | spec/verify | none (rides A-4 gate) | with A-4 (P-0 corollary) |
| A-14 | true angle of repose (shear-aware yield) | future feature | real/new (gated) | last (deferred, needs probe) |

**Until ratified:** every item above remains labeled in-code debt; **T4 does not start** (per the gate).
After ratification the user edits `DESIGN-LAW.md` / the v4 spec; this file and the pre-amendment text live in
git history.
