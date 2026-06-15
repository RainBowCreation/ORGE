# Drift Audit — Module B: Pressure (the ONE field P, §3 + law #1/#3)

**Date:** 2026-06-15 · **Auditor:** drift-auditor (READ-ONLY) · **Branch:** rebuild
**Source of truth:** `DESIGN-LAW.md` (frozen v4.2) → `specs/2026-06-10-engine-b-unified-spec-v4.md`.
**File audited:** `ORGE-ENGINE/core/engine_b.hpp` — `relax_pressure_world` (470–676), `Globals` knobs
(33–124), `gas_anchor_v42` (298–303), step orchestration `step_world_b` (3126–3137), `Chunk::P`
(`sim_engine.hpp:262`). Comments discarded; only executable code cited.

## Verdict: COMPLIANT (1 MINOR drift, 1 spec-acknowledged DEBT-by-design, 0 BLOCKER/MAJOR)

The single-P red–black Gauss–Seidel relaxation faithfully implements §3.1/§3.2. The forbidden
weighted-Jacobi double-buffer is NOT present. No second pressure field. No retired A+B symbols.

---

## Severity counts
- BLOCKER: 0
- MAJOR: 0
- MINOR: 1 (gas-NEIGHBOR face anchor reads the own-floored value, not the raw `p_eos,j`)
- Spec-acknowledged note (not drift): cross-gas absolute-P relaxation anchor unimplemented (spec marks
  it `[T4-OPEN]` / owner T4b-Way2-ST7 — labeled debt, not a code bug)

---

## Per-requirement findings

### 1. The §3.1 stencil — COMPLIANT (one MINOR deviation on the gas-neighbor anchor)
Spec §3.1:
```
fluid neighbor:      Φ_f = P_j     + ρ̄_f·g⃗·(r⃗_i − r⃗_j)
gas/vacuum neighbor: Φ_f = p_eos,j + ρ̄_f·g⃗·(r⃗_i − r⃗_j)   (vacuum: p_eos,j = 0)
target_i = (Σ_f Φ_f)/n_open  [+ α_eos·(p_eos,i − P_i) if gas]
ρ̄_f = ½(ρ_i + ρ_j)
```
Code (engine_b.hpp):
- ±y faces (626–637): `base = (cls[j]==PR_FLUID) ? C.P[j] : cx.anchor[j];`
  `sum += base + 0.5f*(rho_i + cx.rho[j]) * gdx;` (above ⇒ `+`, below ⇒ `−`). `gdx = G.g*G.dx` (582).
  → `ρ̄_f = ½(ρ_i+ρ_j)` exact; vertical offset `±½(ρ_i+ρ_j)·g·dx` = `ρ̄_f·g⃗·(r⃗_i−r⃗_j)` exact (law #3).
- ±x/±z faces (640–652): `sum += (cls[j]==PR_FLUID) ? P[j] : anchor[j];` no offset (same height ⇒
  `g⃗·(r⃗_i−r⃗_j)=0`). Correct — same isotropic rule, sideways dot-product is 0 (law #3 satisfied).
- `n_open` (`nopen`): incremented ONCE per OPEN face; solid faces and absent-chunk world-boundary
  faces are skipped (627, 633, 641) → excluded from both the sum AND n_open. `nopen==0 ⇒ continue`
  (654): fully solid-enclosed cell keeps P. Matches "solids excluded from n_open; closed faces".
- `target = sum/(float)nopen` (656). Exact.
- vacuum neighbor: `cx.anchor` for a PR_VAC cell is set to `0.0f` (501) → `p_eos,j = 0`. Correct.
- gas-i source: `Pn = ((1−ω)Pi + ω(target + aeos*anchor[i]))/(1+ω*aeos)` for PR_GAS (663–665). This
  is the IMPLICIT form of `target + α_eos·(p_eos,i − P_i)` (algebra reproduced in code: fixed point
  `(target+α·a)/(1+α)`, amplification `|1−ω|/(1+ωα)<1`). The implicit evaluation is mathematically
  equivalent at the fixed point and strictly more stable — NOT drift. `anchor[i]` for a gas cell is
  the cell's own `gas_anchor_v42(...)` = its `p_eos,i`. Correct.

**MINOR drift (gas/vacuum NEIGHBOR anchor):** For a GAS neighbor `j`, the spec's `Φ_f` term is
`p_eos,j` (the neighbor's raw gauge EOS). The code reads `cx.anchor[j]` (628/634/642), which for a
gas cell is `gas_anchor_v42(M, T_j, p_eos4_j, G)` (506–507) = `max(p_eos,j, floorA)` where
`floorA = −0.01·(m0/M)·R·T/V` (≈ −991 Pa for air). I.e. the neighbor's contribution is the
**own-side-floored** anchor, not the raw `p_eos,j`. The spec's §4 force-side rule (gas_anchor_v42
header, 295–297) explicitly distinguishes "own-side floored" vs "neighbor-side raw with sub-min
override `(m<minMass)?0:p_eos4`", but the RELAXATION stencil applies the floored variant to ALL gas
cells regardless of whether they are the center or a neighbor — so a gas neighbor below ~1% under
rest contributes its floor (−991 Pa) instead of its true (more-negative) raw gauge.
- **Impact:** small. The floor only bites on deeply-rarefied (>1% under rest) gas, a regime the
  model intentionally cannot resolve (gas_anchor_v42 header rationale, 283–297). In the common
  resting-atmosphere and compressed-pocket regimes the raw gauge is at/above the floor, so
  `anchor==raw` and there is no difference. The relaxation is a smoother whose fixed point the §4
  force does not read for gas (gas P is force-dead, A-8), so the deviation perturbs only the
  diagnostic gas P, never momentum.
- **Classification:** CODE-BUG (technically the stencil should read `p_eos,j` raw for gas neighbors,
  per §3.1 verbatim), severity MINOR. It is partially spec-anticipated — the spec acknowledges the
  own/neighbor floor asymmetry exists in the FORCE faces (295–297) but does NOT sanction applying
  the own-floor to a relaxation neighbor. Recommend confirming with the spec owner whether the
  relaxation neighbor should use the raw/sub-min-override form.

### 2. Dataflow = Gauss–Seidel in-place per color — COMPLIANT (NO forbidden Jacobi)
Spec §3.2: black half-sweep MUST read red's freshly-written values; read-old/write-new double-buffer
across both halves = forbidden weighted Jacobi (BLOCKER if found).
Code: sweep loop (584–675). `for (color=0; color<2; ++color)` (586), inner stride
`for (x=(y+z+color)&1; x<CHUNK_W; x+=2)` (591) → exactly the `(x+y+z)&1==color` parity. Fluid
neighbor reads are **`C.P[j]` / `nc->C->P[j]`** (628, 634, 642) — the LIVE persisted field — and the
write is **`C.P[i] = Pn`** in place (671). There is NO second `P` buffer in the relaxation; the red
pass writes `C.P` and the black pass reads those fresh red values from the same `C.P`. A cell's 6
face-neighbors are all the opposite parity, so per-color in-place is race-free. **This is genuine
Gauss–Seidel; the forbidden double-buffer is absent.** COMPLIANT. (BLOCKER cleared.)

Cross-chunk parity: chunk-local `(x+y+z)&1` is the global parity because CHUNK_W/CHUNK_D=16 are even
(neighbor chunk indices resolved via `cx.nb[]`, links built 513–520) — crossing a chunk border flips
parity correctly. No parity break at seams.

### 3. κ·divU once per tick, persisted velocity, −g·dt excluded, κ bound — COMPLIANT
Spec §3.1/§3.2: κ·divU applied ONCE per tick (first sweep only), reads PERSISTED (pre-ENCODE)
velocity (exclude the −g·dt ENCODE contribution); κ ≤ ρ̄·dx²/dt.
- Once per tick: `const bool kpair = useKappa && (sweep == 0);` (585) and `if (kpair) Pn -= keff*divU`
  (667–670). Applied only on `sweep==0`, both colors of the first sweep. The spec wording is "first
  half-sweep PAIR" — code applies it on both colors of sweep 0, i.e. the full first sweep. This
  matches "first sweep of the tick only" (§3.1). COMPLIANT.
- Persisted (pre-ENCODE) velocity: divU is built from `cx.s->vx/vy/vz` (537), and `cx.s` is the
  WorldSnapshot taken in `step_world_b` at line 3131 `snap = snapshot_world(world)` — taken BEFORE
  `enc = encrypt_world(...)` (3132). `snapshot_world` sets `cs.vx[i] = C.px[i]*inv` (sim_engine.hpp
  594) from the LIVE pre-ENCODE momentum. `encrypt_world` takes `const World&` (408) and returns a
  separate `WorldEncrypt`; it does NOT mutate world momentum — the −g·dt gravity impulse lives in the
  encrypt's `ugy` (361, separate object), never in `snap.vy`. → divU reads the pre-ENCODE persisted
  velocity, EXCLUDING the −g·dt contribution, exactly as §3.1 demands. COMPLIANT.
- κ bound: `keff = std::min(G.kappa, rho_i * G.dx*G.dx / std::max(dt,1e-6f))` (668) = `min(κ, ρ̄·dx²/dt)`
  with local ρ̄=ρ_i. Per-cell clamp to the §3.2 pseudo-acoustic CFL bound. COMPLIANT.
- divU is a 6-face finite difference with closed (solid/world-boundary) faces contributing 0
  (545–565), `cx.divU[i] = s*(A/V)` (565) → units 1/s. Matches §3.1.

### 4. SOR ω form + defaults + joint stability — COMPLIANT
Spec: `P_i ← (1−ω)P_i + ω·target_i`; ω∈[1.0,1.9]; joint `ω·(1+α_eos)<2`.
- Form: non-gas branch `Pn = (1−ω)*Pi + ω*target` (666) — exact.
- ω clamp: `omega = std::clamp(G.omega, 1.0f, 1.9f)` (574) — exactly the §1.3 legal range.
- Default literals: `omega = 1.5f` (82), `alpha_eos = 0.25f` (116). Check `ω·(1+α_eos) = 1.5·1.25 =
  1.875 < 2` ✓. COMPLIANT with the spec's worked default.
- α_eos clamp `[AEOS_MIN=1e-6, 1.0]` (580, 30). The joint `ω·(1+α)<2` clamp itself is RETIRED in
  code (29, 570–579) because the gas source is applied IMPLICITLY (`/(1+ω·α)`), whose amplification
  `|1−ω|/(1+ωα)<1` is unconditionally stable for any ω∈(0,2), α≥0. This is a STRICTLY-SAFER
  superset of the spec's stability constraint — at the DEFAULTS the spec's `<2` still holds, and no
  legal (ω,α) pair can diverge. The spec §1.3 lists the joint constraint as `[LAW-AMEND-v42-A5]`; the
  implicit form satisfies it for all inputs. NOT drift. COMPLIANT.
- N_relax: `nswp = std::clamp(G.N_relax, 1, 8)` (581), default `N_relax = 4` (86) — within §1.3 [1–8].

### 5. Solids carry no P / no second pressure field / no A+B split — COMPLIANT
- Solid (μ=∞ with mass, `!std::isfinite(M.viscosity)`, 502) classified PR_SOLID: `if(ci==PR_SOLID)
  continue;` as a CENTER (594) → never relaxed, carries no P; as a NEIGHBOR/face → skipped from sum
  and n_open (627/633/641) → shields. COMPLIANT (law #1/#3 shelf behavior).
- Vacuum center pinned `C.P[i]=0.0f` (596) and skipped — matches "vacuum P unreadable, anchors read 0".
- **No second pressure field.** `p_eos` enters ONLY through the gas source `α_eos·(p_eos,i−P_i)` and
  the gas/vacuum face anchor — it is never SUMMED with P as a parallel pressure for the same cell.
  The §4 force reads ONLY `Chunk::P` and its boundary anchor (verified: force site reads `C.P[i]`,
  `Cf->P[fj]`, gas anchor — engine_b.hpp 994, 1099, 1106, 1681; no parallel field). Drift-test (a)
  CLEARED.
- **A+B split / retired symbols ABSENT.** `grep` over all `core/*.hpp` for
  `own_weight_head | p_surf | swap_threshold | head_relax | p_ac_scale | swap_kv` → **NONE FOUND.**
  The law's tracked DEBT (A=overburden + B=own-weight head, B used only sideways — a
  direction-dependent rule, drift-test (b)) is fully DISCHARGED: there is one isotropic 6-face rule,
  the same in all directions. Drift-test (b) CLEARED.

### 6. P persisted across ticks, only a tick-boundary copy — COMPLIANT
- `Chunk::P` is a persisted SoA member (`std::vector<float> P;` sim_engine.hpp 262). The relaxation
  mutates it in place (671); DECODE explicitly does NOT touch P (3114–3115 comment confirms, and no
  `C.P` write in DECODE) → it carries across ticks; the next tick re-relaxes from the new mass/T.
- **No within-engine double buffer.** The "tick-boundary copy" referenced by spec §1.1/§3.2 is the
  JNI pin/pout round-trip (Java holds the prior-tick P and feeds it back), NOT a per-sweep device
  buffer — consistent with §1.1 ("a second device buffer for P exists only as a tick-boundary copy").
  Within the engine the relaxation is purely in-place. COMPLIANT.

---

## Cross-gas absolute-P (spec-acknowledged labeled debt, NOT drift)
Spec §3.1 (`[LAW-AMEND-v42-A2]`) wants a hetero-gas face (air|steam) to compare ABSOLUTE pressure
(`p_eos+P0` each side) so per-gas gauge offsets cancel. The relaxation stencil reads the per-gas
GAUGE anchor at hetero-gas faces too (601–615), NOT absolute. **This is explicitly marked OPEN in
both the spec (§2.1 "to be implemented … in T4", INV-AL "once it lands") and the code
(`[T4-OPEN(cross-gas-abs-P)]`, 609–615, owner T4b-Way2/ST7).** The `p_abs_hetero_v4` helper exists
(278–281) but is intentionally not wired. Per the contract this is **spec-acknowledged LABELED-DEBT,
not a CODE-BUG.** Reported for completeness.

---

## Summary line
Module B implements the law's single-P exit correctly: in-place Gauss–Seidel red–black SOR, one
persisted scalar P, isotropic 6-face stencil with the ½(ρ_i+ρ_j)·g·dx hydrostatic offset, gas EOS
source applied implicitly (safer superset of the joint-stability constraint), κ·divU once/tick off
the pre-ENCODE persisted velocity under the per-cell CFL bound, solids/vacuum handled, and the
forbidden weighted-Jacobi double-buffer absent. The only true drift is MINOR: gas NEIGHBOR faces in
the relaxation read the own-side-floored anchor instead of the raw `p_eos,j` (§3.1 verbatim) —
impact confined to deeply-rarefied gas and the force-dead gas diagnostic. The cross-gas absolute-P
anchor is spec-acknowledged open debt, not a bug.
