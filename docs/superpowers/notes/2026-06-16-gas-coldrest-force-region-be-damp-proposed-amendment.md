# ⚠ PROPOSED AMENDMENT to spec v4 §4 (force region) — REQUIRES USER RATIFICATION

**This file does NOT change the law or the spec. Only the user edits `DESIGN-LAW.md` and the v4 spec.
This is a PROPOSAL for the user to ratify.** It follows the house format
(`notes/2026-06-15-gas-acoustic-column-stability-proposed-amendments.md`,
`DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`): per item **Current** (verbatim) / **Defect** /
**Proposed** (exact replacement text) / **IMPACT** / **CAUTION**; plus an Evidence section and a
law-compliance summary table.

It descends from the deferred-#11 in-game symptom **"resting air mass-churns / goes very dense at the
floor"** — specifically the gas **cold-rest** failure: a perturbed resting air cell does not settle, it
limit-cycles (56% tick-to-tick oscillation) and the resulting velocity-damp deposits spurious heat (the
"vel_damp pump"). Root cause was found by a headless repro (`atmos_ladder` / `atmos_probe`, S2 design
note in `drift-2026-06-15/DESIGN-gw-atmos-fix.md`), not by analysis.

**The engine fix is COMMITTED at `04b368a` on branch `gw1-gw2-engine`** (un-pushed; `.so` NOT rebuilt;
parent gitlink NOT bumped — the parent integrates). This note proposes ratifying the §4 text that the
committed code already implements, and **escalates three items it deliberately does NOT close**.

---

## 0. Evidence (the repro + measured before/after)

The probe (`ORGE-ENGINE/tests/atmos_ladder_test`, `atmos_probe`) walls a 1-cell air column on a stone
floor, open top (vacuum), real LUT, stepped LIVE through `step_world_b` at `dt = 0.5`. Mass is conserved
to machine precision every tick (LAW #9 holds — **this is a distribution/stability bug, not a
conservation bug**).

### Root cause (S2, measured)

`θ` for air (the existing Way-2 EOS-imbalance under-relaxation) is structurally **≈ 0** (4.8e-5 at
dt=0.5; WATER = 1.000, confirmed NO-OP). The prior gas y-rest blend
`iy = i_support_y + θ·(iy − i_support_y)` with `i_support_y = m·g·dt` therefore lands the rest impulse at
`i_support_y` — which cancels **only** the `−g·dt` gravity part ENCODE folds into `u_g`. It leaves the
incoming **settling velocity `u_prev` completely untouched**:
`u_drive_y = (u_prev_y − g·dt) + g·dt = u_prev_y`. So any perturbation that gives a gas cell a settling
velocity is self-perpetuating: it locks onto the advection CFL rail `‖u‖ = dx/dt = 1.3333` m/s and the
bottom cell sloshes ~56%, and `vel_damp` keeps depositing the railing KE as heat. **Nothing in the force
region relaxed the settling velocity toward the hydrostatic-support rest.** (Evidence trail: `/tmp`
probes `probe_theta.cpp`, `probe_churn.cpp`, `probe_discriminate.cpp`; θ table in the S2 design note §1.)

### Measured before → after (the committed fix, `GW_BE_DAMP_SCALE = 0.1`)

| Metric | BASE (RED) | FIX | Verdict |
|---|---|---|---|
| `atmos_ladder` G2 cold-rest max tick-to-tick osc | **56.0%** | **0.3%** (cap 1%) | GREEN |
| G2 bottom density vs rest | **3.5×** | **1.01×** (cap 5×) | GREEN |
| G2 top layer | — | **1.17 kg** (≥ 1.0 floor) | GREEN |
| `atmos_probe` UNIFORM-1.2, H=200, 5000 ticks: osc / bottom / top / mass | churn | **0.1% / 1.02× / at floor / exact** | ALL 4 INV-ATMOS clauses GREEN |
| `inv_al_smoke` cold-rest E-drift | **258,508 J/tick** | **2,458 J/tick** | ≈105× reduction |
| `INV-GAS` sealed pocket (m / rest) | 1.2191× | **1.2020×** | UN-regressed (feared 2.67× does NOT recur; well under ≤1.3× bound) |
| Liquids: FNV state hash (base vs fix) | — | **identical** | byte-identical NO-OP |
| `INV-P1` pinned fixed points | 75005.73 / 155004.84 | **unchanged** | OK |
| `INV-P2` walled levels | [4172.67 \| 3339.33] | **unchanged** | OK |
| Determinism (`inv7`) | exact | **exact** | OK |
| Conservation (per-species mass/tick) | exact | **exact** | OK |

All four audits (spec / quality / adversarial / drift) PASS. Liquids are a provable NO-OP: their EOS
stiffness `s = 0 ⇒ β = 0 ⇒ θ_v = 1 ⇒` the damp targets collapse to the old `i_support` form, and a
liquid never enters the gas-gated block anyway.

---

## Item GW-COLDREST-1 — §4: the gas y-rest force impulse becomes a backward-Euler SETTLING damp

**Current — §4 "Force & momentum (RESOLVE)"** governs the gas boundary-face impulse. The code under §4
(`engine_b.hpp` ~1115, the `if (i_gas)` block) currently lands the rest impulse on the support target with
a θ-blend on the *deviation*:

> `iy = i_support_y + θ·(iy − i_support_y)`, `i_support_y = m·g·dt`; `ix = θ·ix`; `iz = θ·iz`.
> *(Spec §4 text the code serves: "boundary face (gas/vacuum/solid against fluid i): `p̄_f = P_i +
> ρ_i·g⃗·(r⃗_f − r⃗_i)` — the cell's own half-cell hydrostatic ghost `[LAW-AMEND-v42-A1]`"; "gravity
> `m·g⃗·dt` … ONCE, in ENCODE `[LAW-AMEND-3]`"; "vel_damp … deposits ~0 at rest (no perpetual
> gravity→damping→heat pump)".)*

**Defect:** the blend target `i_support_y = m·g·dt` cancels only the ENCODE gravity term; the persisted
**settling velocity `u_prev` is never relaxed**. With air's `θ ≈ 4.8e-5` (already ≈ 0) the blend is
effectively `iy ≈ i_support_y`, so a perturbed gas cell returns `u_drive_y = u_prev_y` and limit-cycles
on the `dx/dt` advection rail (56% osc), which `vel_damp` then converts to spurious heat — directly
contradicting §4's stated guarantee that vel_damp "deposits ~0 at rest." The spec's "no perpetual
gravity→damping→heat pump" claim is, at column scale, **not met** by the existing blend.

**Proposed — replacement §4 gas-rest text** (the exact shape committed at `04b368a`):

> "A **gas** cell's §4 boundary-face impulse is chosen so that the POST-force drive velocity is the
> **backward-Euler-relaxed settling velocity** `u_drive = u_prev · θ_v` (→ 0 for a resting gas), with
> gravity still cancelled exactly once (the ENCODE `−g·dt` is undone on the y target, never re-added).
> Per axis, recovering the pre-gravity velocity `u_prev_y = u_y + g·dt` (x/z carry no gravity):
>
> - `θ_v = 1 / (1 + β·dt)` (backward-Euler velocity factor ∈ (0,1])
> - `β = GW_BE_DAMP_SCALE · s · (A²·dt / V²) · m`  (rate, 1/s), `GW_BE_DAMP_SCALE = 0.1`
> - `s = pressure_stiffness_v4(M, T)` — the cell's OWN EOS stiffness, **material data** (the same χ/EOS
>   stiffness `θ` already uses); `s = 0` for any incompressible
> - `iy_target = m·g·dt − m·u_prev_y·(1 − θ_v)`
> - `ix_target = − m·u_prev_x·(1 − θ_v)`, `iz_target = − m·u_prev_z·(1 − θ_v)`
> - the residual stiff-EOS pressure imbalance is still θ-blended toward that target:
>   `iy = iy_target + θ·(iy − iy_target)` (and x/z likewise), so a *moving* parcel's advected momentum
>   stays live while its pressure overshoot is tamed.
>
> Because `s = 0 ⇒ β = 0 ⇒ θ_v = 1`, the targets collapse to `(m·g·dt, 0, 0)` — the prior `i_support`
> form — for every incompressible, so this is a **provable NO-OP for liquids** (which also never enter
> the gas-gated block). The impulse is computed entirely from the cell's own `m, g, dt` and its own
> persisted velocity `u`; no neighbor or EOS value is read beyond the §4 surface integral already in
> `(ix,iy,iz)`. `p̄_f` is bit-identical from both sides of every shared interior face (unchanged), so the
> re-target fabricates no net momentum."

**IMPACT:**
- **Delivers the §4 vel_damp guarantee at column scale.** "vel_damp deposits ~0 at rest" now actually
  holds for a tall gas column (E-drift down ≈105×); the limit cycle is removed (56% → 0.3%).
- **Consumers:** the §4 gas boundary-face impulse only (`engine_b.hpp` ~1115). Introduces one engine
  constant `GW_BE_DAMP_SCALE`. The §3.1 relaxation, the `p̄_f` face value, and ENCODE gravity are
  **unchanged**.
- **Builds on Way-2, does not undo it.** The existing `θ` under-relaxation stays as the blend toward the
  new target; GW-COLDREST-1 adds the missing settling-velocity relaxation.
- **§11 invariants:** satisfies INV-ATMOS clauses for the UNIFORM seed (stability/bounded/floor/mass —
  all GREEN); does NOT regress INV-GAS (1.2020× un-regressed), INV-P1/P2 (byte-identical), inv7
  (deterministic). Does NOT, on its own, close the INV-ATMOS BAROMETRIC per-cell clause or INV-AL maxAirU
  — see ESCALATION below.

**CAUTION:**
- The fix is **force-side cold-rest only**. It supplies the gravity-cancel via the boundary ghost
  independent of `∇P`; it does **not** build the §3.1 hydrostatic *ladder* (that is owned by the
  relaxation). It cures the *instability*, not the *flat-P* property — see escalation item 1.
- `β` saturates: the fixed point is independent of `GW_BE_DAMP_SCALE` for scale ≥ 0.05 (θ_v → 0 = full
  per-tick settling removal). 0.1 is a robust mid-plateau pick; it is a RATE only, not a force value, so
  it cannot move equilibrium (LAW #1 safe).
- Liquids are untouched (`s = 0` proof above); verified byte-identical via FNV state hash.

---

## ESCALATION / OPEN — REQUIRES USER DECISION

The fix delivers **cold rest** law-faithfully. It does **not** close the following related items. They are
surfaced honestly here; the user must decide each. **Do not silently weaken any committed test.**

### 1. G1 hydrostatic ladder still RED — owned by the RELAXATION, not the force. **REQUIRES USER DECISION.**

The converged open-column gas `P` is **FLAT** (~9.4 Pa) instead of the spec §3.1 ladder
`P(k) = ρg(k − ½)dx` (ideal bottom ~186 Pa). This is owned by `relax_pressure_world`, **not** the force:
the force-side cold-rest fix supplies the gravity-cancel via the boundary ghost `i_support` independently
of `∇P`, so G2 (cold rest) is GREEN while G1a/G1b (the ladder) stay RED.

The S2 probes showed **no minimal relaxation knob builds the ladder.** Measured (H=16, one relax, real LUT):

| Option | bottom P (need ≥ 93) | worst dP/dy (need ≥ 6) |
|---|---|---|
| base | 9.43 | 0.000 |
| α_eos = 0.01 | 11.18 | 0.000 |
| α_eos = 0.001 | 11.24 | 0.000 |
| conditional EOS source (band 100 Pa) | 11.25 | 0.000 |
| gas faces read relaxed `P[j]` | 16.11 | 0.009 |
| + α_eos = 0.001 | 44.01 | 0.199 |
| + α_eos = 0.001 + N_relax = 32 | **119.79** (PASS G1a) | **5.625** (marginal **FAIL** G1b) |

The structural blocker: a gas-neighbor relaxation face reads the neighbor's gauge **anchor** (`≈ 0` at
rest), not the neighbor's relaxed `P[j]`, so neighboring gas cells never propagate `P` to each other.
Lowering `α_eos` and the conditional EOS source do essentially **nothing**. Only **making gas faces read
`P[j]` + raising `N_relax` to 32** (an ~8× global relaxation-cost increase touching every test) gets
close — and even that **marginally fails G1b** (dP/dy 5.625 < 6) AND re-opens the previously-reverted
cross-gas absolute-P rule, which carries its own consistency-of-reference hazard and was **not**
regression-tested against INV-GAS / INV-P.

**RECOMMENDATION — the user decides:**
- **(a)** Accept cold-rest-without-true-ladder. G1 (a hydrostatic ladder) is a *stricter* property than
  the user's stated cold-rest need; the cold-rest deliverable is met. *(Lowest risk.)*
- **(b)** Authorize the heavier `gas-face-reads-P` + `N_relax = 32` relaxation rebuild, with full
  INV-GAS / INV-P / INV-AL re-validation and resolution of the G1b marginal fail. *(Heavy; re-opens a
  reverted rule.)*
- **(c)** Relax the G1 tolerance.

Do **NOT** silently weaken the committed G1 test in `atmos_ladder_test`.

### 2. `atmos_probe` BAROMETRIC fails one clause — same relaxation-ladder root. **REQUIRES USER DECISION.**

With a barometric seed run 2000 ticks, the per-cell deviation reaches **14.7% at the open-top cell
(y = 198)** vs the 0.1% cap. The **conservation** clause passes; **mass is exact**. The root is the same
EOS-anchor-not-building-a-hydrostatic-ladder mechanism as item 1, now manifesting at the open-top/vacuum
boundary over a long run. **Same owner (the relaxation ladder), same decision as item 1.**

### 3. `inv_al` `maxAirU = 1.333` unchanged — gas|solid LID boundary cell, out of scope. **REQUIRES USER DECISION (defer vs. fix).**

After the fix, all *interior* air cells rest cold (‖u‖ 0.0001–0.0006). The single railing cell is the
**top air layer against the SEALED STONE LID** (a gas|solid boundary, `uy = −1.3333`). This is the
documented **T4b-OPEN boundary-face channel** (an advected-momentum / boundary-face path the per-cell §4
impulse cannot fully reach; R2 advection rebuilds it each tick), **explicitly out of this fix's scope**.
Recommendation: defer (it is the bulk-cold-rest deliverable that was the gate; the lid cell is a separate
boundary-face task).

---

## Law-compliance summary (the "does it match the law" question, answered first)

| Property | Verdict |
|---|---|
| **LAW #1 (force reads only `P`)** | ✅ — the impulse is the §4 surface integral of the single relaxed `P` plus the cell's own boundary ghost; no per-cell EOS gauge enters the force value. `s` (EOS stiffness) sets only the BE *rate* `β`, never a force value. |
| **LAW #2 (force = −∇P·V, 6-face local; gravity once)** | ✅ — gravity is applied once in ENCODE; `iy_target`'s `m·g·dt` is the gravity-**cancel** target of a convex blend, not a second gravity. Face shape unchanged. |
| **LAW #0 (no species/state branch)** | ✅ — `β` is derived from `pressure_stiffness_v4` (material data), `= 0` for every incompressible ⇒ identity. No `if(name=="air")`; the enclosing `i_gas` gate is the pre-existing χ>0.999 material partition. |
| **LAW #4 (ENCODE → RESOLVE → DECODE)** | ✅ — the change lives entirely in RESOLVE (the force build); no new pass, no extra cross-cell read. |
| **Conservation / antisymmetry (LAW #9)** | ✅ — per-cell impulse only; `p̄_f` bit-identical from both sides of every shared face; mass bit-conserved (air err ≤ 7e-6 in S2; exact per-species in audits). |
| **vel_damp "≈ 0 at rest" (§4)** | ✅ **now actually delivered at column scale** — E-drift down ≈105×. |
| **Liquids** | ✅ provable NO-OP (`s = 0 ⇒ θ_v = 1`; FNV state hash byte-identical base vs fix). |
| **Realistic** | ✅ — EOS untouched; the BE settling damp is the standard implicit relaxation of a settling velocity toward hydrostatic rest. |
| **GPU-friendly** | ✅ — purely local per-cell arithmetic; no new neighbor read, no global reduction. |

**Recommended ratify order:** ratify GW-COLDREST-1 (the §4 force-region change is already implemented at
`04b368a` and passes all four audits) → then make the three escalation decisions, starting with item 1
(it subsumes item 2). Verify against `atmos_ladder_test` / `atmos_probe` / INV-ATMOS before the in-game
audit gate.

---

*Engine fix committed at `04b368a` on `gw1-gw2-engine` — UN-PUSHED, `.so` NOT rebuilt, parent gitlink NOT
bumped (the parent branch `gw1-gw2` integrates). DESIGN-LAW.md and the v4 spec are UNMODIFIED by this
proposal. S2 design note (root cause + all measured numbers):
`docs/superpowers/notes/drift-2026-06-15/DESIGN-gw-atmos-fix.md`.*
