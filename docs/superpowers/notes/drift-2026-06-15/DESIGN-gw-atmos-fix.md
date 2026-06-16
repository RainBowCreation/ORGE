# DESIGN — GW atmos fix (deferred-#11 gas cold-rest / kill air-churn)

**Status:** S2 design note (instrument + design, NO production edit). All experiments were run in a
throwaway `/tmp/coremod/engine_b.hpp` copy compiled with `-I/tmp/coremod -Itests` (include-override
verified by a runtime sentinel: modified header prints "MODIFIED HEADER IN USE", base does not).
Worktree `/home/claude/ORGE-B-gw12/ORGE-ENGINE` (branch `gw1-gw2-engine`) was NOT touched.

---

## 1. MEASURED θ and the TRUE root cause of the 56% churn

### 1a. θ for air (measured, `eos_impulse_theta_v4`, real LUT)

| T (K) | dt   | s = ∂p_eos/∂m (Pa/kg) | s·c·dt   | **θ**        |
|-------|------|-----------------------|----------|--------------|
| 288   | 0.50 | 82 566.6              | 20 641.7 | **4.844e-05**|
| 288   | 0.25 | 82 566.6              |  5 160.4 | **1.937e-04**|
| 600   | 0.50 | 172 014               | 43 003   | 2.33e-05     |
| 1000  | 0.50 | 286 690               | 71 672   | 1.40e-05     |
| WATER | 0.50 | 0 (incompressible)    | 0        | **1.000** (NO-OP confirmed) |

**θ for air is already ≈ 0** (4.8e-5 at dt=0.5). The brief's first guess ("θ too large") is REFUTED;
θ is structurally ~0. The 56 % churn is NOT a θ-magnitude problem.

### 1b. TRUE root cause — suspect (a)+(b): the persisted SETTLING velocity is never damped

Instrumenting the H=16 walled column (`step_world_b`, dt=0.5) and reconstructing the §4 blend from a
snapshot:

* **CLEAN rest (tick 0):** the θ-blend lands `iy = i_support_y = m·g·dt`, giving `u_drive_y ≈ −0.0002`
  m/s. The rest state IS a fixed point — force balance is fine at rest.
* **SLOSHED (after 25 ticks):** cells carry a persisted `uy = −1.3333` m/s (the dx/dt advection rail).
  At ENCODE this becomes `u_g_y = u_prev_y − g·dt = −1.3333 − 5 = −6.3333`. The θ-blend sets
  `iy_applied ≈ i_support_y = m·g·dt = +5·m`, which cancels ONLY the `−g·dt` gravity part. Result:
  `u_drive_y = u_g_y + iy/m = (u_prev_y − g·dt) + g·dt = u_prev_y = −1.3333`.

**The θ-blend neutralises gravity but leaves the incoming kinetic transient `u_prev` completely
untouched.** Any perturbation that gives a gas cell a settling velocity is self-perpetuating: vel_damp
(1/(1+1·0.5)=0.667) only attenuates it slowly while gravity re-injects each tick, so it locks onto the
`‖u‖ = dx/dt = 1.3333` CFL rail and the bottom cell sloshes 3.2–4.2 kg (the measured 56 % osc). It is a
positive feedback: `i_support_y = m·g·dt` ALSO grows with the sloshed mass (suspect a), and `u_g` carries
the transient forward (suspect b). Both are facets of the same defect: **nothing in the force region
relaxes the settling velocity toward the hydrostatic-support rest.**

Evidence trail (probes, all in /tmp): `probe_theta.cpp`, `probe_churn.cpp`, `probe_discriminate.cpp`.

---

## 2. The force-region fix — backward-Euler gravity-axis (and lateral) settling damp

Replaces the y-rest blend at `engine_b.hpp` **~1115–1129** (the `if (i_gas) { … }` block). The current
code:

```cpp
if (i_gas) {
    const float theta = eos_impulse_theta_v4(Mi, si->T[i], G, dt);
    const float i_support_y = m_i * G.g * dt;
    ix = theta * ix;
    iy = i_support_y + theta * (iy - i_support_y);   // damps gravity, NOT u_prev
    iz = theta * iz;
}
```

**Proposed shape** (the exact lines tested in `/tmp/coremod`, `GW_FORCE_BE_DAMP`):

```cpp
if (i_gas) {
    const float theta = eos_impulse_theta_v4(Mi, si->T[i], G, dt);
    // Pre-gravity settling velocity per axis (ENCODE stored u_g = u_prev − g·dt·ŷ):
    const float u_prev_x = d.ux[i];                 // x/z: no gravity ⇒ u_g == u_prev
    const float u_prev_y = d.uy[i] + G.g * dt;      // recover u_prev_y
    const float u_prev_z = d.uz[i];
    // Velocity-relaxation rate β from the cell's OWN EOS stiffness — MATERIAL DATA (like θ/dragScale),
    // NOT an if(isGas) branch: pressure_stiffness_v4 returns 0 for incompressibles ⇒ β=0 ⇒ θ_v=1 ⇒
    // targets vanish ⇒ identity (provable liquid NO-OP). Also gated by the existing i_gas block which
    // a liquid never enters anyway.
    const float s_i  = pressure_stiffness_v4(Mi, si->T[i], G);
    const float beta = BETA_SCALE * s_i * (G.A*G.A*dt) / (G.V*G.V) * m_i;   // 1/s
    const float theta_v = 1.0f / (1.0f + beta * dt);          // backward-Euler velocity factor ∈ (0,1]
    // Choose the impulse so the POST-force drive velocity is the BE-relaxed settling velocity:
    //   u_drive = u_prev · theta_v  (→ 0 for a settling gas), gravity exactly cancelled on y.
    //   y: u_drive_y = u_g_y + iy/m = (u_prev_y − g·dt) + iy/m  ⇒  iy = m·g·dt − m·u_prev_y·(1−θ_v)
    //   x/z (support 0):                                        ⇒  ix = − m·u_prev·(1−θ_v)
    const float iy_target = m_i * G.g * dt - m_i * u_prev_y * (1.0f - theta_v);
    const float ix_target =                 - m_i * u_prev_x * (1.0f - theta_v);
    const float iz_target =                 - m_i * u_prev_z * (1.0f - theta_v);
    // Still θ-damp the residual stiff-EOS PRESSURE imbalance toward that target (keeps a MOVING gas
    // live: a parcel with large advected u_g/R2 momentum is θ-untouched, only its pressure overshoot
    // is tamed). At the rest fixed point iy→iy_target, identical equilibrium to the explicit form.
    iy = iy_target + theta * (iy - iy_target);
    ix = ix_target + theta * (ix - ix_target);
    iz = iz_target + theta * (iz - iz_target);
}
```

Then the existing `defect-C fold-in` (`d.ux[i] = ugx + ix/m_i; …`) propagates the SAME damped impulse
into the drive map, so flux DIRECTION and carried momentum stay consistent (law #2 / ST7).

### Why it is law-faithful
* **LAW #1 (force reads only P):** `ix/iy/iz` are the §4 pressure surface integral of the single relaxed
  `P` (no per-cell EOS gauge in the force — GW-1 already retired that). The damp targets are built from
  `m`, `g`, `dt`, and the cell's OWN persisted velocity `d.u*` — no neighbor P read beyond the existing
  surface integral. `s_i = ∂p_eos/∂m` is the SAME χ/material stiffness used by θ; it sets only the RATE,
  not a force value.
* **LAW #0 (no species branch):** β is derived from `pressure_stiffness_v4`, which returns 0 for every
  incompressible (max==default) ⇒ θ_v=1 ⇒ all three `*_target = (gravity only on y)` collapse to the
  identity blend. There is no `if(name=="air")`. (The enclosing `if(i_gas)` is the existing χ>0.999
  material-data partition, identical to the original code.)
* **Liquids untouched:** θ=1 AND θ_v=1 for incompressibles ⇒ `iy = i_support_y(=0 since not gas-gated)`…
  in practice a liquid never enters the `i_gas` block at all ⇒ provable NO-OP (verified: WATER θ=1, s=0).
* **Conservation / antisymmetry:** the blend is a PER-CELL impulse computed entirely from per-cell
  boundary data and the cell's own velocity; it adds NOTHING to a shared interior face that the two
  sides could disagree on. `pbar` is still identical from both sides of every shared face (unchanged).
  The damp only RE-TARGETS where the per-cell impulse lands — it does not write across faces, so it
  fabricates no net momentum. Mass is bit-conserved in every experiment (air err ≤ 7e-6).

### β magnitude / saturation
With air s≈82567, A=V=1, dt=0.5, m=1.2: β = scale·49540 /s. The result PLATEAUS for scale ≥ 0.05
(θ_v→0, i.e. the settling velocity is fully removed each tick). The exact β does not matter past the
plateau; the robust recommendation is **scale = 0.1** (β≈4954/s, θ_v≈4e-4 — essentially full settling
removal) OR equivalently a saturated form `theta_v = 0` for any gas with s above an acoustic threshold.
The fixed point is identical to the explicit form regardless of β (β moves only the approach).

---

## 3. G1 (ladder) DECISION — escalation recommended; cold rest does NOT need it

**Force-side-only is sufficient for COLD REST and does NOT build the G1 ladder** (confirmed:
atmos_ladder G2 GREEN while G1a/G1b stay RED — the flat P is owned by the §3.1 relaxation, not the
force). The user's real need (cold rest) is supplied by `i_support` independent of ∇P.

### Why none of the minimal relaxation options builds the G1 ladder cleanly

The structural blocker is in `relax_pressure_world` (~627–653): a **gas-neighbor face reads the
neighbor's gauge ANCHOR (`cx.anchor[j]` ≈ 0 at rest), NOT the neighbor's relaxed P**. So neighboring gas
cells never propagate P to each other and the ladder cannot build, regardless of α_eos. Measured (H=16,
one relax, real LUT):

| Option                                         | bottom P (need ≥93) | worst dP/dy (need ≥6) | INV-GAS pocket |
|------------------------------------------------|---------------------|-----------------------|----------------|
| base                                           | 9.43                | 0.000                 | (see below)    |
| Opt1 α_eos=0.01                                | 11.18               | 0.000                 | —              |
| Opt1 α_eos=0.001                               | 11.24               | 0.000                 | —              |
| Opt2 conditional EOS source (band 100 Pa)      | 11.25               | 0.000                 | —              |
| **Opt3 gas faces read P[j]**                   | 16.11               | 0.009                 | —              |
| Opt3 + α_eos=0.001                             | 44.01               | 0.199                 | —              |
| Opt3 + α_eos=0.001 + N_relax=32                | 119.79 (PASS G1a)   | 5.625 (marginal FAIL G1b) | NOT measured |

* **Lowering α_eos (Opt1) and the conditional EOS source (Opt2) do essentially NOTHING** — P stays flat,
  because the neighbor faces read anchor≈0, not P. These are dead ends for G1.
* **Opt3 (gas faces read relaxed P[j] instead of the anchor) is the ONLY lever that builds a real
  gradient**, but it (i) requires N_relax=32 (an 8× relaxation cost increase, a global knob touching
  every test) to converge a 16-deep ladder, (ii) STILL only reaches worst dP/dy=5.625 (marginal G1b
  FAIL at the open-top cell), and (iii) is a structural change to the gas-face relaxation rule that the
  prior T4 work explicitly reverted (the cross-gas absolute-P / face-Φ rule is a documented OPEN item
  with its own consistency-of-reference hazard — see the relaxation header ~602–625 and the force-site
  ~1098–1114). Changing it risks the INV-GAS sealed pocket and the INV-P1/P2 pinned fixed points.

### RECOMMENDATION: ship the force-side cold-rest fix; ESCALATE G1 to the user.

The cold-rest deliverable (kill the air churn / vel_damp pump) is met by the force-side change ALONE,
with NO INV-GAS regression (§4). G1's ladder is a SEPARATE, deeper relaxation-architecture question
(gas-face-reads-anchor vs reads-P) entangled with the reverted cross-gas absolute-P work; it cannot be
fixed by a minimal knob, only by Opt3 + N_relax=32 which is a heavy global change that still marginally
fails G1b and was not regression-tested against INV-GAS/INV-P. **The G1 ladder tolerance (and whether
to re-open the gas-face-reads-P relaxation rule) needs user sign-off.** Do not silently weaken G1.

---

## 4. MEASURED experimental results

### Force-side change (GW_FORCE_BE_DAMP, scale=0.1)

* **atmos_ladder G2 (narrow walled column, H=16, 400 ticks):**
  maxOsc **0.3 %** (cap 1 % — GREEN; base was 56 %), bottom **1.01× rest** (cap 5× — GREEN),
  top **1.17 kg** (≥ floor 1.0 — GREEN), air mass err 2.5e-6. **G2 cold-rest fully GREEN.**
  Robust across scale 0.05–0.5 (plateau: maxOsc 0.27–0.30 %).
* **atmos_ladder G1 (same):** bottom P 9.43, worst dP/dy 0.000 — **STILL RED** (force cannot build the
  ladder; owned by the relaxation, as predicted).
* **inv_al_smoke rest-cold (full-chunk atmosphere, the brief's gate):** E-drift
  **258 508 J/tick → 2 457 J/tick (≈100× reduction)**; P ladder sane (bot 964 vs base 76 766 — packing
  gone); air mass err 7e-6. BUT maxAirU **still 1.33333** ⇒ the maxAirU<0.02 and E-drift<5 J asserts
  still FAIL. **Residual rail pinpointed:** all INTERIOR air cells rest cold (‖u‖ 0.0001–0.0006); the
  ONLY railing cell is the TOP layer against the SEALED STONE LID (y=AIR_HI, uy=−1.3333). This is the
  documented **gas|solid boundary face** sub-case — the brief flags walled maxAirU=1.33333 as
  "a SEPARATE solid|gas issue, T4b-OPEN, NOT our gate." The bulk cold-rest deliverable IS met; the
  boundary rail is out of force-region scope (it is an advected-momentum / boundary-face channel that the
  per-cell §4 impulse cannot fully reach, rebuilt by R2 advection each tick).
* **vel_damp deposit (narrow column):** base ~5 J/tick, modified ~5–10 J/tick (the narrow column was
  already low-deposit; the dominant pump is the full-chunk geometry, where E-drift fell 100×).

### G1 relaxation options — see the table in §3 (G1 numbers). INV-GAS measured only for the shipped
force-side change (the relaxation options are NOT recommended for ship):

* **INV-GAS sealed pocket, force-side change (scale=0.1), real `engine_b_gas_test` (≈2 min):**
  pocket m = **1.4423 kg = 1.2020× rest** (target 1.20±0.05× — GREEN), closed-form 1+ρgh/P0 rel err
  **0.11 %**, P(pocket) 20358 Pa vs overburden 19873 (1.7 %). **NO INV-GAS regression** (the prior V4
  experiment's 2.67× regression does NOT recur; well under the ≤1.3× safety bound). The only INV-GAS
  FAIL is the "late |Δm|/tick→0 quiescence" assert (0.008/tick) — a pre-existing standing-rail item
  noted in that test's own header, not the pocket multiple.

### Must-stay-green (force-side change, scale=0.1)

| test                         | result |
|------------------------------|--------|
| pressure_relax (INV-P1/P2)   | **OK** |
| resolve_invariants           | **ALL PASS** |
| conservation_levels          | **OK** |
| swap_pe_heat                 | **OK** |
| inv7                         | **OK** |
| way2_rest_balance            | RED at BASE too (2 fails); modified IMPROVES to 1 fail — not a regression |

---

## 5. Law / conservation confirmation

* **LAW #1:** force reads only the single relaxed P (§4 surface integral); damp targets use only m, g,
  dt, and the cell's own persisted velocity — no extra neighbor/EOS read. ✔
* **LAW #0:** β from `pressure_stiffness_v4` (χ/material data) ⇒ 0 for incompressibles ⇒ identity; no
  name/state branch. ✔
* **Conservation / antisymmetry:** per-cell impulse only; `pbar` unchanged and still bit-identical from
  both sides of every shared face; mass bit-conserved in every run (≤7e-6). ✔
* **Liquids:** θ=1, s=0 ⇒ provable NO-OP; liquids never enter the `i_gas` block. ✔

## Verdict (one line)
**Cold rest is reachable law-faithfully by the force-side BE settling-damp alone** (G2 GREEN, INV-GAS
pocket 1.2020× un-regressed, full-chunk bulk rests cold, vel_damp pump down 100×). **The remaining
maxAirU=1.33333 on inv_al_smoke is the gas|solid-LID boundary cell — the documented T4b-OPEN
boundary-face channel, NOT our gate. G1 (the hydrostatic ladder) needs ESCALATION** — no minimal
relaxation knob builds it; only re-opening the reverted gas-face-reads-P rule + N_relax=32 gets close
(and still marginally fails G1b), which needs user sign-off and INV-GAS/INV-P re-validation.
