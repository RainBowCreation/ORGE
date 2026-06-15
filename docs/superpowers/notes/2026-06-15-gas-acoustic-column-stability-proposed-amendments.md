# ⚠ PROPOSED AMENDMENT to DESIGN-LAW.md + spec v4 (§2.1 / §3 / §1.2 / §1.3) — REQUIRES USER RATIFICATION

**This file does NOT change the law or the spec. Only the user edits `DESIGN-LAW.md` and the v4 spec.
This is a PROPOSAL for the user to ratify.** It follows the house format
(`DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`, `notes/2026-06-14-way2-flux-implicit-proposed-amendments.md`):
per item **Current** (verbatim) / **Defect** / **Proposed** (exact replacement text) / **IMPACT** /
**CAUTION**.

It descends directly from the in-game audit symptom **"air goes very dense at the floor (>200 kg) and ~0 mass
a couple hundred blocks up"** (memory bug #2 / #0, the deferred gas-solver), traced to root cause by a headless
repro rather than analysis.

---

## 0. Evidence (the repro — `ORGE-ENGINE/tests/atmos_probe.cpp`, design-only, kept as the acceptance test)

A walled 1-cell, 200-tall air column on a stone floor, open top (vacuum), real LUT, stepped LIVE through
`step_world_b` at `dt = 0.5`. Mass conserved to `240.000` every tick (LAW #9 holds — **no fabrication; this is
a distribution bug, not a conservation bug**).

| seed | t=0 | t≈150 | t=2000 |
|---|---|---|---|
| **uniform 1.2** (how the world seeds air) | flat 1.2 | still ~flat (looks fine) | **bottom 25–70 kg, oscillating chaotically tick-to-tick; top drained to 0.002** |
| **barometric** (seeded AT equilibrium) | holds | **holds flat ~150 ticks** | destabilises, then churns |

Three findings that **redirect the fix** away from where the conversation first pointed:

1. **The scheme IS well-balanced.** The barometric seed sits still for ~150 ticks ⇒ the discretization residual
   is negligible (`ε = −ρg(dx/H)²/12 ≈ 1.5e-8 N`). **"Repair well-balancedness" is NOT the fix.**
2. **It is a slow-growing numerical instability, not a static drift.** Both seeds eventually limit-cycle: the
   bottom *oscillates* (y1 swings 24↔69 kg between ticks), it does not *settle* into a dense layer. This is the
   memory's "air mass-churn."
3. **Way-2's per-cell `θ` is insufficient at column scale.** The probe is built from current HEAD, which already
   contains Way-2 (`θ = 1/(1+s·c·dt)` on the stiff-EOS imbalance, `engine_b.hpp:964`). Way-2 killed the
   **single sealed pocket's** resting churn (its test). It does **not** stabilise a **multi-cell column** —
   because `θ` damps each cell's OWN source imbalance locally, while the **cell-to-cell acoustic coupling stays
   explicit**.

**The quantitative crux.** Isothermal gas sound speed `c = √(R·T/M) = √82,567 ≈ 287 m/s`. Acoustic CFL at the
in-game step `CFL = c·dt/dx = 287·0.5/1 ≈ 144`. **Any explicit cell-to-cell acoustic coupling is unconditionally
unstable above CFL = 1; the engine runs at ~144.** Explicit stability would need `dt ≤ dx/c ≈ 0.0035 s` (144
substeps/tick). That ratio IS the limit cycle. The only law-true, realistic, GPU-friendly escape is to make the
**spatial** pressure coupling **implicit** — which is precisely what the law's single relaxed `P` already is.

**Why the existing test suite misses it:** `engine_b_pressure_relax_test` runs ~20–100 ticks; the instability
incubates past ~150. It hides below the test horizon, ships, and only manifests in a long-running world.

---

## Item GW-1 — §2.1: RETIRE A-8. The gas force is driven by the single relaxed `P`, not a per-cell EOS gauge anchor

> **This reverses ratified amendment A-8** ("gas relaxed P stays a force-dead diagnostic"). A-8 is the source
> of the explicit acoustic coupling. Reversing a ratified item needs the user's pen — hence this proposal.

**Current — §2.1 / `[LAW-AMEND-v42-A8]`** (verbatim):
> "A gas cell's relaxed `P` is a **force-dead diagnostic** — every gas face uses its EOS anchor, not `P`; the
> relaxed `P` is computed and persisted but never read by the force, retained as a tracked diagnostic
> `[LAW-AMEND-v42-A8]`."

**Defect:** the per-cell EOS gauge anchor is read **explicitly** into the §4 face force every tick. With
`s ≈ 82 kPa/kg` and CFL ≈ 144, the inter-cell acoustic mode is 144× over its explicit stability limit ⇒ the
multi-cell column limit-cycles (§0). Way-2's `θ` cannot reach this: `θ` is a per-cell scalar on the cell's own
source imbalance; it does not couple neighbours implicitly, so the wave between cells is still integrated
explicitly. Structurally, a gas cell currently carries **two** pressure representations doing force work — the
relaxed `P` (held dead) and the gauge anchor (live) — which is in tension with **LAW #1 (ONE number per cell)**
and arguably with **drift-test (a)**.

**Proposed — replacement §2.1 text:**
> "A gas cell's force is driven by the **single relaxed `P`** (LAW #1/#2), identically to a liquid: every face
> reads `P` (the hydrostatically-anchored relaxed field), and the live gas EOS enters **only** as the §3.1
> relaxation **source** (`α_eos·(p_eos,i − P_i)`) that drives `P` toward the gauge EOS. The standalone per-cell
> gauge **face anchor is retired** — there is exactly one pressure representation per cell. Because `P` is
> iteratively relaxed and persisted (LAW #1), the cell-to-cell pressure coupling is **implicit**: it carries the
> EOS pushback without an explicit acoustic-CFL ceiling, which is the standard low-Mach / pressure-projection
> integration. The real EOS is unchanged — a 700×-compressed pocket still relaxes `P` to its ~69 MPa and pushes
> back; only the *time integration* of the coupling changes (implicit, not explicit). At a **gas|vacuum**
> boundary the force reads the relaxed field's **hydrostatic anchor** (not raw absolute `p_abs`), so a resting
> atmosphere does not rail into the vacuum (the ST7 failure mode of driving from raw absolute pressure)."

**IMPACT:**
- **Builds on Way-2, does not undo it.** Way-2's implicit source (`θ` on the EOS imbalance) stays; GW-1 adds the
  missing implicit **spatial** half by routing the force through the spatially-coupled `P`. Together: source AND
  coupling are implicit.
- **Consumers:** the §4/§6.3 gas face force (now reads `C.P[j]` for gas neighbours, as it already does for
  `PR_FLUID`); `relax_pressure_world` (the `α_eos` source already exists — §3.1 — and the gas/vacuum hydrostatic
  anchor already exists for the *relaxation*; GW-1 makes the FORCE consume the same `P`). The `gas_anchor_v42`
  per-cell gauge path in the force is deleted.
- **Supersedes:** `[LAW-AMEND-v42-A8]` (reversed); `[T4-OPEN(cross-gas-abs-P)]` / W2-3 ST7 escalation
  (mooted — there is no per-gas gauge frame to reconcile once all faces read one `P`; cross-gas pressure
  difference is carried by `P` itself).
- **§11 invariants:** INV-P1 (column `P` profile) unaffected — `P`'s relaxed value is unchanged; only its
  consumer (the force) changes. **New acceptance: INV-ATMOS** (§4 below).

**CAUTION:**
- The ST7 rail-to-vacuum is the trap. The fix is explicit in the proposed text: drive from the **anchored
  relaxed `P`**, never raw `p_abs`. The hydrostatic anchor makes the vacuum-boundary face ≈ gauge-zero at rest
  (no spurious expansion) while staying spatially coupled (stable).
- Implicit ≠ unconditionally exact at finite sweeps — it removes the **hard** CFL ceiling but the relaxation must
  converge enough to damp the ~144-cell acoustic wavelength. That convergence is GW-2's job. GW-1 + GW-2 are a
  pair; GW-1 alone with `N_relax=4` may damp slower than desired (still vastly better than explicit).
- Liquids are untouched (they already read `P`; `s ≡ 0 ⇒` nothing changes for them).

---

## Item GW-2 — §1.3 / §3: raise the `N_relax` cap so the implicit `P` damps the acoustic wavelength

**Current — §3.1 / §1.3** (verbatim):
> "N_relax: full red–black sweeps per tick (each sweep = 2 half-sweeps), legal **[1–8]** (clamped). Default 4 …"

**Defect:** once the gas force is implicit (GW-1), stability no longer needs CFL < 1, but *accuracy/damping* of
a 144-cell-wavelength mode in one tick needs more than 4 one-hop sweeps. The current cap of 8 may under-damp the
column transient, leaving a slow residual wobble.

**Proposed:**
> "N_relax: legal **[1–32]** (clamped), default 4. For gas-dominated tall columns the relaxation may be run to
> higher sweep counts to damp long-wavelength acoustic transients; the smoother stays **1-hop red–black,
> GPU-native** (the canonical GPU stencil) — **NOT multigrid** (coarse-grid aggregation reintroduces the
> non-local column-sum the law's A+B debt exists to delete, and serialises poorly on GPU). Cost scales linearly
> in sweeps; tune for *accuracy*, not stability (stability is GW-1's implicit `P`)."

**IMPACT:** §1.3 knob range only; no schema change. INV-3 frozen-manifest range edit.

**CAUTION:** linear cost per sweep — the per-tick budget (§6.1 kernel count) grows. Acceptable because the gas
column is the worst case and the in-game cadence is one combined step per 5 ticks. If sweeps prove too costly,
the fallback is acoustic substepping of the gas pressure only (re-introducing a bounded `n = round(dt/DT_CFL)`
sub-cycle on the gas relaxation) — noted, not proposed, as the second-choice lever.

---

## Item GW-3 — §1.2 / LAW #8: decouple `χ` from `minMass`, then lower gas `minMass` to ~0 (kill the 1.0-pin churn)

**Current — §1.2** (verbatim):
> "`χ = (maxMass − defaultMass)/(maxMass − minMass)`, **guard `χ ≡ 0` when maxMass == minMass**. Gas
> classification: χ > 0.999." (air `minMass = 1.0`, `χ = 0.99980`.)

**Defect (two coupled):**
1. The probe shows gas cells **pinning at exactly `1.000`** (= air `minMass`) and shoving the imbalance onto
   neighbours — a discrete kick that **seeds** the churn. Physically a gas has **no cohesion floor** (LAW #0:
   gas ⇒ `τ_y = 0`); `minMass` is a *liquid* concept. Real air thins continuously; it should not quantise.
2. `χ` **couples to `minMass`**: lowering air `minMass` below ~0.21 drops `χ < 0.999`, silently **declassifying
   air as a gas** (no EOS — catastrophic). So the physically-correct fix (low/zero gas cohesion floor) is
   *blocked* by the classification formula. The coupling is itself a design smell: gas-ness is *compressibility*
   (`max ≫ default`), independent of the cohesion floor.

**Proposed:**
> "`χ` is defined from **compressibility alone**, independent of `minMass`:
> `χ = (maxMass − defaultMass)/maxMass` (fraction of capacity above rest density), guard `χ ≡ 0` when
> `maxMass == defaultMass`. Gas classification unchanged: `χ > 0.999`. With `χ` decoupled, **gas `minMass` is
> set to a near-zero cohesion floor** (air/steam `minMass → ε_mass`), so gases thin continuously toward vacuum
> with no quantisation pin; relabel-to-vacuum fires as `m → 0` (the existing empty-cell path). Liquids keep their
> finite cohesion `minMass` unchanged."

> *(Re-pin the new margins in §1.2 so a band edit cannot silently demote a gas, as the old line did.
> Recompute: air `χ = (1000−1.2)/1000 = 0.99880` — **below the 0.999 cutoff under the new formula**, so the
> cutoff value must be revisited together with this change, OR the formula chosen differently. THIS IS AN OPEN
> SUB-DECISION for the user — see CAUTION.)*

**IMPACT:** §1.2 χ definition (LAW #8 schema *semantics*, not the field list — no field added/removed, so not a
schema law-change); the LUT `minMass` column values for air/steam; INV-NOSUBMIN interaction (a gas at `m < ε` is
the empty/vacuum case, not a sub-min violation — INV-NOSUBMIN's "relabel forbidden if `m < target.min`" still
holds with `target.min = ε`).

**CAUTION:**
- **The cutoff/formula is an open sub-decision.** `χ = (max−default)/max` makes air `0.99880 < 0.999` — it would
  declassify air under the *current* cutoff. The user must pick: (a) keep `(max−default)/(max−min)` but hard-set
  the gas/liquid class by an explicit flag derived from `max ≫ default` (cleanest — removes the formula's
  fragility entirely), or (b) lower the cutoff to e.g. `χ > 0.99` and re-pin margins, or (c) raise gas `maxMass`.
  GW-3 should not be ratified without resolving this — flagged, not hand-waved.
- GW-3 is a **secondary** lever (removes a churn *seed*); GW-1+GW-2 are the cure (remove the *instability*).
  Ship GW-1+GW-2 first; GW-3 sharpens the result and is data-only/GPU-neutral.

---

## 4. Acceptance — INV-ATMOS (the probe IS the test; design-only, no code here)

A new §11 invariant, gated by `atmos_probe` (kept as a cheap-tier test):

- **Stability:** seed UNIFORM 1.2, run **≥ 5000 ticks at dt=0.5**. No cell oscillates >1% tick-to-tick at
  settle; the bottom does **not** exceed a bounded multiple of rest density; the top does **not** drain below
  the gas floor. (Today: bottom 25–70 kg churning, top 0.002 — FAIL.)
- **Well-balanced kept stable:** seed BAROMETRIC, run **≥ 2000 ticks**. Every cell stays within ε of its seeded
  mass (the ~150-tick hold must become a permanent hold).
- **Realistic readout:** at settle, `/orge` reads ≈ 1 ATM absolute at sea level dropping ≈ 12 Pa/block
  (≈ 1 mmHg / 11 blocks) — already implied by the EOS + a held barometric profile (no extra mechanism).
- **Conservation unchanged:** per-species air mass exact every tick (already passes — must stay).

---

## 5. Law-compliance summary (the "does it match the law" question, answered first)

| Property | Verdict |
|---|---|
| **LAW #1 (ONE P)** | ✅ **strengthened** — GW-1 collapses the gas's two pressure representations (dead `P` + live gauge anchor) into the one relaxed `P`. Moves toward the law; A-8 was the deviation. |
| **LAW #2 (force = −∇P·V, 6-face local)** | ✅ unchanged shape — gas now uses the same `½(P_i+P_j)+¼Δρ…` face term as liquid; gravity still applied once in ENCODE. |
| **LAW #3 (same rule all directions)** | ✅ — one `P`, six identical faces; vertical/horizontal already identical. |
| **LAW #4 (ENCODE→RESOLVE→DECODE)** | ✅ — the change lives entirely in RESOLVE (relax `P`, build force); no new pass. |
| **Drift-test (a) second pressure** | ✅ **resolves a latent (a)-smell** — removes the per-cell gauge-anchor-as-force alongside `P`. |
| **Realistic** | ✅ — EOS untouched; implicit integration is the standard low-Mach atmosphere method; keeps real compression pushback. |
| **GPU-friendly** | ✅ — red–black SOR is the canonical GPU stencil (already `relax_pressure_world`); explicitly rejects multigrid for GPU/locality reasons. |

**Recommended ratify order:** GW-1 (core) + GW-2 (its convergence pair) together → GW-3 after its cutoff
sub-decision is made. Verify each against `atmos_probe` / INV-ATMOS before the in-game audit gate.
