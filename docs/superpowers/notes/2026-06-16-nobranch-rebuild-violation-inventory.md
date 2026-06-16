# Engine-B ABSOLUTE-NO-BRANCH — Violation Inventory & Rebuild Plan

**Date:** 2026-06-16 · **Auditor task:** violation-inventory for the v4.3 no-branch rebuild.
**Worktree:** `/home/claude/ORGE-B-nobranch` (parent `nobranch`) · engine `ORGE-ENGINE` (`nobranch-engine`).
**Sources of truth (precedence):** `DESIGN-LAW.md` v4.3 (frozen) → `handoffs/00-MASTER-RULES.md` →
`specs/2026-06-10-engine-b-unified-spec-v4.md`. The 3 USER MASTER RULES outrank the law's amendments
where they differ (see §RESOLUTIONS).

**Files audited:** `core/engine_b.hpp` (PRIMARY, 3143 ln), `core/sim_engine.hpp`, `core/orge_kernel.hpp`,
`core/lut_store.hpp`, `jni/orge_jni.cpp`. **All comments discarded; real code judged only.**

> The code currently IS the debt: it implements `is_gas`/`GAS_CHI_MIN`/`PR_GAS`/`is_compressible` and an
> EOS-only-for-gas pressure model — exactly the branches v4.3 / INV-UNIVERSAL forbid. Comments throughout
> assert "this is NOT a state branch (law #0)" while the line directly below branches on `is_gas`. Those
> assertions are false and are ignored here.

---

## SUMMARY COUNTS

| category | count | where |
|---|---:|---|
| (1) state/category branches (`is_gas`/`is_compressible`/`PR_*`/χ-threshold/μ==∞ classify) | **24 distinct sites** | engine_b.hpp (22), sim_engine.hpp (1), + the 2 predicate *definitions* |
| — of which: definitions of the forbidden predicates | 4 | `GAS_CHI_MIN`, `is_gas`, `CHI_COMPR_EPS`, `is_compressible` |
| — of which: disguised `if(max==min)` inside `chi()` | 2 | `chi()` lines 165, 167 |
| — of which: `μ==∞` solid classify (`!isfinite(viscosity)`) used as a code-path fork | 16 | see (1F) |
| — of which: the `PR_FLUID/PR_GAS/PR_VAC/PR_SOLID` enum + its consumers | 1 enum, ~12 reads | relax_pressure_world |
| (2) cross-cell reads inside ENCODE | **0** | `encrypt_cell`/`encrypt_world` are clean |
| (2) cross-cell reads inside DECODE (no-penetration clamp + swap-clamp) | **2 sites (12 calls)** | decrypt_world 2799-2804, 2935-2940 |
| (3) RESOLVE reads beyond per-cell ENCODE table + LUT | **structural — entire RESOLVE** | see §(3) |
| (4) law-vs-user-rule conflicts | **4** | see §RESOLUTIONS |
| other threshold branches tracked under law #0 | 2 | radiation `|ΔT|>300` (2284); §5.2 cohesion if-ladder |

Total **forbidden state/category branch sites to eliminate: 24** (plus the radiation 300 K ramp and the
cohesion if-ladder, which are threshold-not-category but are named under law #0/§13).

---

## (1) STATE / CATEGORY BRANCHES — file:line, condition, branchless replacement

### (1A) The forbidden predicate DEFINITIONS — delete the symbols entirely

| # | file:line | current | replacement |
|---|---|---|---|
| 1A-1 | engine_b.hpp:175 | `constexpr float GAS_CHI_MIN = 0.999f;` | **DELETE.** No χ-cutoff may exist (§1.2/§1.3 forbidden token). |
| 1A-2 | engine_b.hpp:176 | `bool is_gas(const Material& m){ return chi(m) > GAS_CHI_MIN; }` | **DELETE the function.** Every caller is rewritten to use `χ` as a continuous weight (below). No call may survive. |
| 1A-3 | engine_b.hpp:182 | `constexpr float CHI_COMPR_EPS = 1e-3f;` | **DELETE.** |
| 1A-4 | engine_b.hpp:183 | `bool is_compressible(const Material& m){ return chi(m) >= CHI_COMPR_EPS; }` | **DELETE the function.** Callers rewritten to χ-weight (below). |

### (1B) `chi()` itself contains the disguised `if(max==min)` — make it the regularized formula

| # | file:line | current | replacement |
|---|---|---|---|
| 1B-1 | engine_b.hpp:165 | `if (m.maxMass == m.minMass) return 0.0f;` | **DELETE the guard.** Law #8 / §1.2: use the regularized denominator. |
| 1B-2 | engine_b.hpp:167 | `if (denom <= 1e-9f) return 0.0f;` | **DELETE the guard.** |
| 1B-3 | engine_b.hpp:164-170 | the whole branchy body | Replace with one line: `return clamp((m.maxMass - m.defaultMass) / (m.maxMass - m.minMass + EPS_CHI), 0.f, 1.f);` with `constexpr float EPS_CHI = 1e-6f;`. `max==min` ⇒ numerator 0 / (0+ε) = 0 by arithmetic; no `if`. (`clamp` is sanctioned saturating math.) |

### (1C) EOS computed-only-for-gas — make it universal, weighted by χ at the consumer

| # | file:line | current | replacement |
|---|---|---|---|
| 1C-1 | engine_b.hpp:247 | `if (!is_gas(m)) return 0.0f;` inside `p_eos_v4` | **DELETE.** Compute `p_eos = (m/M)·R·T/V − P0` for **every** cell unconditionally (keep only the `mass<=eps \|\| M<=0 ⇒ 0` numeric guard at 248, which is a genuine divide-by-zero floor, not a category test — but prefer regularizing: `R·T/(M+ε_M·…)`). A `max==default` cell already gets `p_eos≈0` near rest, and its **contribution** is zeroed by the χ-weight at the source (1C-5), so no gate is needed here. |
| 1C-2 | engine_b.hpp:266 | `if (!is_gas(m) \|\| m.molarMass<=0) return 0.0f;` in `p0_gas_v4` | **DELETE the `is_gas` clause.** Compute P0 for all; keep only the `M<=0` numeric guard (or regularize). |
| 1C-3 | engine_b.hpp:331 | `if (!is_gas(m)) return 0.0f;` in `pressure_stiffness_v4` | **DELETE.** `s = R·T/(M·V)` is the real ∂p/∂m for every cell. For `max==default` the EOS *source* is χ-weighted to 0 (1C-5), so a finite `s` does no harm; θ-coupling (1C-4) then equals 1 only when the χ-weighted impulse is 0. Reframe θ so the incompressible no-op falls out of `χ=0`, not `s=0`. |
| 1C-4 | engine_b.hpp:356-359 | `eos_impulse_theta_v4` keys off `s` (which keys off `is_gas`) | Re-derive θ from the **χ-weighted** stiffness `χ·s`: `θ = 1/(1 + χ·s·c·dt)`. At χ=0 ⇒ θ=1 (identity) by the multiplier; at χ≈1 ⇒ full backward-Euler damping. No `is_gas`. |
| 1C-5 | engine_b.hpp:672-675 | `float Pn = (ci == PR_GAS) ? (…implicit EOS source…) : (…plain SOR…);` | **This is the mechanism of the gas-sink bug (see §GAS-SINK).** Replace the `?:` with ONE formula for all cells: `Pn = [(1−ω)P + ω(target + χ_i·α·a_i)] / (1 + ω·χ_i·α)`. At χ=0 the source term and the denominator correction vanish by the χ multiplier → pure hydrostatic SOR (the old "fluid" branch) with **no branch**. `a_i` = the cell's own `p_eos` (now computed for all). |

### (1D) The `PR_FLUID/PR_GAS/PR_VAC/PR_SOLID` classification in `relax_pressure_world`

This is the single largest violation: an explicit per-cell `enum cls[]` computed once then forked on ~12×.

| # | file:line | current | replacement |
|---|---|---|---|
| 1D-1 | engine_b.hpp:487 | `enum : uint8_t { PR_FLUID, PR_GAS, PR_VAC, PR_SOLID };` | **DELETE the enum.** No cell is classified. |
| 1D-2 | engine_b.hpp:510-517 | the `if(vac) … else if(!isfinite μ) PR_SOLID … else if(is_gas) PR_GAS … else PR_FLUID` classifier | **DELETE.** Per cell cache only continuous numbers: `rho_i = m/V` (0 at empty by the value), `anchor_i = χ_i·p_eos_i` (the EOS source, χ-weighted — 0 for liquids by the weight), and a per-cell mobility/coupling weight (see 1D-3). No class label. |
| 1D-3 | engine_b.hpp:603,636-646,649-661 | `if (ci==PR_SOLID) continue;` / `if (cx.cls[j]!=PR_SOLID)` face gates / `(cls[j]==PR_FLUID)? P[j] : anchor[j]` base select | Replace the solid open/closed test with the **continuous face mobility weight** `w_f = 1/(1 + dt·λ_f)`, `λ_f = (μ_i+μ_j)/(ρ̄_f·dx²)` (spec §3.1). `w_f→0` as μ→∞ ⇒ a terrain face contributes ~0 with no `if`. The face base is ALWAYS `Φ_f = w_f·(P_j + ρ̄_f·g·(r_i−r_j))`; accumulate `Σ w_f·Φ_f / Σ w_f`. The `(cls==PR_FLUID)?P[j]:anchor[j]` select disappears: every neighbor contributes its relaxed `P_j` (vacuum: `P_j=0,ρ_j=0` by value; gas: its relaxed `P_j` per GW-1 — there is no separate `anchor` read). |
| 1D-4 | engine_b.hpp:604-607 | `if (ci==PR_VAC){ C.P[i]=0; continue; }` | Keep as a numeric floor but de-categorize: a `mass<=eps` cell has `Σ w_f`-source pulling it to 0 already; if a hard pin is wanted, gate on the **mass value** `m_i<=eps` (a genuine void floor, sanctioned — it is `m=0` of law #9, not a category), NOT on a `PR_VAC` label. Acceptable as a value-floor; flagged for the continuity guard. |
| 1D-5 | engine_b.hpp:554,558-573 | the κ·divU builder skips `PR_SOLID` and gates each face on `!=PR_SOLID` | Same μ-weight treatment: weight each face's velocity contribution by `w_f` (→0 at terrain), drop the `PR_SOLID` skips. |
| 1D-6 | engine_b.hpp:515-516 | `cx.anchor[i] = gas_anchor_v42(...)` only on the `PR_GAS` branch | The anchor becomes `χ_i·p_eos_i` for ALL cells (0 for χ=0 by weight). The `gas_anchor_v42` floor (1G) is a separate conflict. |

### (1E) The §4 force pre-pass (DriveCtx) gas/liquid face fork + per-gas θ block

| # | file:line | current | replacement |
|---|---|---|---|
| 1E-1 | engine_b.hpp:1002 | `const bool i_gas = is_gas(Mi);` | **DELETE.** Replace with `const float chi_i = chi(Mi);` used as a weight below. |
| 1E-2 | engine_b.hpp:1042-1060 | `else if (is_gas(Mf) \|\| i_gas) { …gas face… } else { …liquid|liquid… }` | The two branches compute the **identical** `pbar = ½(P_i+P_j) + ½(ρ_j−ρ_i)·ghalf·DY` already (verified: 1052-1053 == 1058-1059). **DELETE the `is_gas` discriminator** and keep ONE interior-face formula. (Note the spec wants `¼Δρ`, code has `½Δρ` — a separate correctness item, but the *branch* removal is pure: collapse both arms to one.) |
| 1E-3 | engine_b.hpp:1123-1165 | `if (i_gas) { …θ + GW-ATMOS y-rest impulse… }` whole block guarded by `is_gas` | Re-key on the continuous weight. Compute the θ/β damping with `χ_i` folded in: `s_eff = χ_i·s`, `β = scale·s_eff·…`, `θ = 1/(1+s_eff·c·dt)`. At χ=0 ⇒ s_eff=0 ⇒ θ=1, θ_v=1 ⇒ targets reduce to the identity (the current liquid no-op) **by the weight, not the `if`**. Apply the (now χ-scaled) blend `iy = iy_target + θ·(iy−iy_target)` to ALL cells unconditionally — a liquid's is a provable no-op. **NB:** the GW-ATMOS y-rest impulse `iy_target = m·g·dt − m·u_prev_y·(1−θ_v)` is itself the gas-sink workaround; see §GAS-SINK / RESOLUTION-D — the real fix is the unified P-source making this impulse unnecessary. |
| 1E-4 | engine_b.hpp:1183-1198 | `if (i_gas) { d.ux=ug+ix/m; … post-force drive }` | Universalize: every cell's drive `u_donor = u_g + i_applied/m`. For χ=0 `i_applied` is the unscaled −∇P·V·dt (current liquid path) so the result equals the current liquid `d.ux=u_g` only if the liquid drive is also moved post-force. This is the "liquid-drive-dir T4-OPEN"; unifying it here removes the branch. Drive = `u_donor·dragScale` for all. |

### (1F) `μ==∞` solid classification used as a code-path fork (`!std::isfinite(viscosity)`)

Each of these computes-then-classifies "this cell is a solid" and forks. Replace EVERY one with the
continuous mobility/coupling weight (the §3.1 `w_f`) or the yield-gate limit `max(0,|F|−τ_y)→0 as τ_y→∞`,
so a rigid cell shields **by arithmetic**. Sites:

| # | file:line | current (the fork) |
|---|---|---|
| 1F-1 | engine_b.hpp:511 | relax classifier `PR_SOLID` (covered by 1D) |
| 1F-2 | engine_b.hpp:1000-1001 | `self_solid = !isfinite(μ) && !self_vac; if(self_vac\|\|self_solid) continue;` — force skips solids |
| 1F-3 | engine_b.hpp:1040-1041 | force face: `else if(!isfinite(Mf.viscosity)) pbar=liqf;` (closed-solid face) |
| 1F-4 | engine_b.hpp:1179 | drive dragScale guarded `&& isfinite(Mi.viscosity)` |
| 1F-5 | engine_b.hpp:1316-1317 | swap: `frozen_i/frozen_j = !isfinite(μ) && m>eps` |
| 1F-6 | engine_b.hpp:1502 | drain: `if(!isfinite(Mi.viscosity)) continue;` (frozen never drains) |
| 1F-7 | engine_b.hpp:1524 | flux: `frozen_j` |
| 1F-8 | engine_b.hpp:1665 | displacement: `\|\| !isfinite(ML.viscosity) continue;` |
| 1F-9 | engine_b.hpp:1804 | `frozen_i` |
| 1F-10 | engine_b.hpp:1835 | `frozen_j` |
| 1F-11 | engine_b.hpp:1953-1955 | `frozen_i/frozen_j` flux gate |
| 1F-12 | engine_b.hpp:2476 | `nbrFrozenTerrain` |
| 1F-13 | engine_b.hpp:2554 | `nbrFrozenTerrain` |
| 1F-14 | engine_b.hpp:2609 | freeze-relabel `if(!isfinite(μ)) continue;` |
| 1F-15 | engine_b.hpp:2679 | `wall_neighbor`: `return !isfinite(μ) && nm>1e-6` — the solid-wall predicate |
| 1F-16 | engine_b.hpp:2711 | `xspecies_noflux_neighbor`: `!isfinite(μ) ⇒ wall` |

**Unified replacement strategy for (1F):** introduce ONE continuous face weight
`w_f = 1/(1 + dt·(μ_i+μ_j)/(ρ̄_f·dx²))` reused by relax, force, flux, and drain. `w_f` multiplies every
flux/force/coupling term. `μ=∞ ⇒ w_f=0 ⇒` term vanishes; no `isfinite` test anywhere. The yield gate
`max(0,|F|−max(τ_y))` handles the move threshold (→0 as τ_y→∞). `wall_neighbor`/`xspecies_noflux_neighbor`
collapse into "the flux is `w_f`-weighted and the yield gate clamps it" — the **predicates themselves are
deleted**, not re-implemented.

### (1G) `gas_anchor_v42` — the 1%-of-rest floor `if`

| # | file:line | current | replacement |
|---|---|---|---|
| 1G-1 | engine_b.hpp:307-312 | `gas_anchor_v42`: `floorA = −0.01·(m0/M)·R·T/V; return max(a_raw, floorA);` | The `max(...)` itself is sanctioned saturating math — keep the `max`. BUT this helper exists only because gas is special-cased. Under the unified P-source (1C-5) the anchor is `χ_i·p_eos_i` and the relaxation handles rarefaction continuously; the bespoke floor should be **deleted** with the gas path. If a floor is still wanted it must apply to all cells via `χ·max(p_eos, floor)` (continuous). |

### (1H) `is_compressible` / `is_gas` callers in flux & displacement & sub-min gate

| # | file:line | current | replacement |
|---|---|---|---|
| 1H-1 | engine_b.hpp:1610 | `if(!is_compressible(mats.byIx(gsp))) continue;` (gas-eviction "must be a gas") | The §6.3 displacement-swap should be selected by the **continuous driver** `P_donor − Φ_anchor` and the χ-weighted room, not by "is it a gas". Rewrite the eviction candidacy as: any cell whose neighbor-above can accept its mass (room `maxMass−m ≥ 0`, which is the universal room) — selection ranked by driver magnitude. Remove the `is_compressible` gate; let χ=0 cells fall out because their EOS room/driver is ~0. |
| 1H-2 | engine_b.hpp:1665 | `if(is_compressible(ML) \|\| !isfinite(ML.viscosity)) continue;` (displacing liquid "incompressible, not terrain") | Replace with continuous: rank candidates by `χ`-low-ness implicitly via the driver; a high-χ neighbor naturally has low driver. Remove the explicit `is_compressible` and `isfinite` gates (the latter → w_f weight). |
| 1H-3 | engine_b.hpp:2142 | `if (recvVac && \|mdot\|>0 && !is_gas(MdonorG)) {…sub-min gate…}` | The donor-side sub-min gate must apply by **cohesion weight**, not gas-class. Replace `!is_gas(MdonorG)` with a continuous cohesion factor `(1−χ_donor)`: scale the min-quantum requirement by `(1−χ)` so a high-χ (gas-like) donor's effective min-floor → 0 (it may expand into vacuum freely) and a χ=0 donor keeps the full min-floor — by the weight, not the `if`. e.g. require `\|mdot\| ≥ (1−χ_donor)·minTgt` for a non-full-drain refill. |
| 1H-4 | engine_b.hpp:2713 | `if (is_compressible(self) && is_compressible(nix)) return false;` (no-flux unless both compressible) | Part of `xspecies_noflux_neighbor` (deleted per 1F-16). The "both-compressible ⇒ flux allowed" becomes: cross-species flux is gated by the χ-weighted displacement driver continuously; no boolean compressibility test. |

### (1I) sim_engine.hpp seeding gas-vs-non-gas

| # | file:line | current | replacement |
|---|---|---|---|
| 1I-1 | sim_engine.hpp:1113-1115 | `bool isGas = (max!=min)&&((max−def)/(max−min)>0.999f); float mdef = isGas? defaultMass : maxMass;` | Seed at `defaultMass` for **every** cell: a non-gas has `default==max` so `defaultMass` already equals the full-cell value — the `isGas? : ` choice is redundant. Replace with `const float mdef = (mat==void)? 0 : fm.defaultMass;`. No χ test, no `0.999`. (This is the cleanest fix: defaultMass is the EOS rest density and equals maxMass for all incompressibles by §1.2.) |

---

## (2) CROSS-CELL READS IN ENCODE / DECODE (USER RULE 1 violations)

### ENCODE — CLEAN
`encrypt_cell` (engine_b.hpp:374-405) and `encrypt_world` (417-432) read **only** the cell's own
`s->mass[i], s->T[i], s->vx/vy/vz[i]` and material. **Zero neighbor reads. No violation.** (Gravity +
external + dragScale + `p_eos` cache are all per-cell — compliant with law #4 and user rule 1.)

### DECODE — the no-penetration clamp reads the 1-hop snapshot neighbor (2 sites, 12 calls)

| # | file:line | current | resolution |
|---|---|---|---|
| 2-1 | engine_b.hpp:2799-2804 | swap-path clamp: 6× `wall_neighbor(world,snap,mats,C,x,y,z,±1,…)` zero u into wall | **Move into RESOLVE.** USER RULE 1 (stricter than law #4 [A3]) forbids ANY cross-cell read in DECODE. The clamp reads the snapshot neighbor's `matIx`/`mass`/`viscosity`. Relocate the no-penetration zeroing into a RESOLVE micro-step (it is a boundary condition on the resolved velocity, order-independent, snapshot-read — fits RESOLVE's contract). DECODE then only derives `u=p/m`, `T=h⁻¹(E/m)` from the cell's own resolved state. |
| 2-2 | engine_b.hpp:2935-2940 | advective-path clamp: 6× `wall_neighbor(...)` | Same: move into RESOLVE. |

Both call `wall_neighbor` (2666-2680) which reads `sj->matIx[j]`, `sj->mass[j]`, and the neighbor
material's viscosity — definitively cross-cell. Under the unified mobility weight (1F) the clamp becomes
unnecessary in its current form: a terrain face already passes `w_f=0` force/flux, so velocity never
points *into* a wall after RESOLVE. **Preferred resolution: eliminate the clamp** by letting the RESOLVE
mobility weight + yield gate produce zero into-wall momentum, leaving DECODE purely local. If a residual
clamp is still needed, it lives in RESOLVE, never DECODE.

Also note 2-1's neighbor read at 2780-2794 (swap partner snapshot) is part of swap *transport* which
belongs in RESOLVE; the swap commit currently straddles DECODE. The rebuild should commit swaps in RESOLVE
(spec §6.1 R2) so DECODE only writes back per-cell.

---

## (3) WHAT RESOLVE READS BEYOND THE PER-CELL ENCODE TABLE + LUT (USER RULE 1)

USER RULE 1: RESOLVE may read **only** the per-cell ENCODE output table + the LUT. The current RESOLVE
reads far more — this is structural and the largest gap from the user's model:

1. **`relax_pressure_world` reads the live `WorldSnapshot`** (`s->mass`, `s->matIx`, `s->T`, `s->vx/vy/vz`)
   directly (510-517, 544-546) and the **persisted `Chunk::P`** in-place (637, 651, 680). Per rule 1 the
   snapshot fields it needs (`rho`, `p_eos`, `T`) must come from the ENCODE table (CellEncrypt already
   caches `rho` and `p_eos4`; `T` is snapshot-only). **Action:** ENCODE must publish everything RESOLVE
   reads per-cell (`rho`, `χ·p_eos`, `T`, the mobility inputs `μ`-derived). RESOLVE then reads ENCODE +
   LUT + the persisted `P` field (which is itself a resolved/persisted quantity, allowed).
2. **The DriveCtx force pre-pass** reads `si->mass`, `si->matIx`, `si->T`, `ei->cells[].*`, and the
   neighbor chunks' `Chunk::P` (1050-1058). Neighbor `P` is the legitimate cross-cell RESOLVE read (the
   ONE field). Neighbor `mass`/`matIx`/`T` reads (1028-1029) should be served from the ENCODE table of
   the neighbor cell, not the raw snapshot — a bookkeeping change, but rule 1 wants RESOLVE's inputs to be
   exactly {ENCODE table, LUT, the relaxed P}.
3. **Flux/swap/displacement passes** read snapshot `mass`/`matIx`/`T`/`E`/`swapReady` of neighbors
   throughout (R0/R1/R1.5/R2). These ARE the legitimate cross-cell RESOLVE reads (the only cross-cell
   step) — but they should read the **post-ENCODE snapshot table**, which is what `CellEncrypt` + the
   immutable snapshot jointly are. **Assessment:** acceptable under rule 1 *if* the snapshot is understood
   as the post-ENCODE per-cell table. The drift to fix is (a) the gas-class branches inside these passes
   (§1) and (b) `relax_pressure_world` recomputing `chi`/`is_gas`/`anchor` from raw material instead of
   reading the ENCODE-published χ-weighted anchor.

**Net:** RESOLVE's *cross-cell* access (neighbor `P` + neighbor post-ENCODE payload for flux) is consistent
with rule 1's "RESOLVE is the only cross-cell step." The violations are (i) it re-derives per-cell
classifications instead of reading continuous ENCODE outputs, and (ii) the no-penetration clamp lives in
DECODE not RESOLVE. No global/column-sum reads were found (good — the A+B column-sum debt is already gone).

---

## (4) LAW-vs-USER-RULE CONFLICTS — resolved autonomously toward the STRICTER user rule

### CONFLICT-A — DECODE 1-hop no-penetration clamp
- **Law #4 / [LAW-AMEND-v42-A3]:** sanctions a DECODE 1-hop snapshot read to zero into-wall velocity.
- **User rule 1:** ENCODE and DECODE are STRICTLY per-cell local — ZERO cross-cell reads at all.
- **RESOLUTION (user rule wins):** the clamp MUST move out of DECODE. Implement it as a RESOLVE
  micro-step (or eliminate it via the §3.1 mobility weight so into-wall momentum is never produced).
  DECODE becomes purely local. (Inventory items 2-1, 2-2.)

### CONFLICT-B — law #0/§6 "even radiation's ε=0 is just data" vs the `|ΔT|>300 K` film-boiling gate
- **Law #6 / spec §8.3(c):** condensed↔condensed radiation fires only when `|ΔT| > 300 K`
  (engine_b.hpp:2284 `if (std::fabs(dT) > 300.0) eps_eff = ei*ej;`). Law #6 itself flags this as "a
  threshold branch [that] must become a branchless smooth ramp — tracked under #0."
- **User rules 2 & 3:** no threshold-fork; real continuous physics.
- **RESOLUTION (user rules win):** replace the hard `if(|ΔT|>300)` with a continuous ramp on ε_eff, e.g.
  `eps_eff = ei·ej · smoothstep((|ΔT|−ΔT0)/ΔT_w)` or `ei·ej·|ΔT|/(|ΔT|+ΔT_ref)`, so the film-boiling
  onset is smooth and `eps_eff→0` as `ΔT→0` by arithmetic. No branch. Tracked as a rebuild task.

### CONFLICT-C — §5.2 cohesion legalization `if`-ladder
- **Spec §5.2:** `if (0 < D−f < min) { f ← D−min if … else f ← D if … else 0 }` (a 3-way `if`-ladder on
  mass-vs-min comparisons). It is not a *category* branch but it IS an `if` that compares a
  material-derived quantity (mass vs minMass) to select a code path — under USER RULE 2's literal wording
  ("ANY if/?: that compares … mass … against a constant to select a code path").
- **RESOLUTION (user rule wins):** re-express the legalization as branchless clamps. The room/capacity
  clamp `f ← min(f, maxMass−R, D)` is already branchless. §5.2/[A7] states the `0<D−f<min` full-merge
  branch is **unreachable** once INV-NOSUBMIN's donor-side gate lands. Implement the donor budget as
  `budget = max(0, m − minMass)` (continuous; full-drain when the single outflow takes all of `m`), so
  the post-scale donor never lands in (0,min) — the ladder dissolves into `min`/`max`. Track as a task.

### CONFLICT-D — law #2 "gravity applied ONCE in ENCODE" vs the GW-ATMOS y-rest impulse in RESOLVE
- **Code reality (engine_b.hpp:1123-1165):** the `if(i_gas)` block injects a per-cell
  `iy_target = m·g·dt − m·u_prev_y·(1−θ_v)` — a gravity-cancelling support impulse + a velocity-relaxation
  damp — into RESOLVE, gated on `is_gas`. This is both a state branch (§1E-3) AND it re-introduces a
  gravity-axis force term in RESOLVE that exists only because the gas pressure source is broken
  (see §GAS-SINK). Law #2 says gravity is applied once in ENCODE and the pressure force balances it
  emergently.
- **RESOLUTION (law + user rules agree against the code):** this whole workaround is DEBT. The correct
  fix is the unified χ-weighted P-source (§1C-5) which makes the resting gas hydrostatically supported by
  the relaxed `P` (gravity balanced by ∇P), so no bespoke `iy_target` support impulse is needed. Remove
  the `if(i_gas)` block; if a velocity-settling damp is genuinely required it must be χ-weighted and
  applied to all cells (a no-op at χ=0). Tracked as the core gas-sink rebuild task.

---

## GAS-SINK BUG — mechanism & cure

**Symptoms:** in-game a floor air cell ratchets 2→10 kg; headless `atmos_probe` UNIFORM seed does not
converge; `inv_al` wide-geometry rails at `maxAirU ≈ 1.33`.

**Mechanism (which inventoried branch):** the bug is the **EOS-only-for-gas / non-continuous pressure
source split at engine_b.hpp:672-675** (item **1C-5**), compounded by the gas/liquid face fork (1E-2) and
the bespoke gas y-rest impulse (1E-3 / CONFLICT-D). Concretely:

1. The relaxation runs a *different update* for `PR_GAS` cells (implicit EOS source
   `+α·anchor`, anchor = `gas_anchor_v42(p_eos)`) than for `PR_FLUID` cells (plain SOR). The gas branch
   relaxes `P` toward the **per-gas gauge EOS anchor with the 1%-floor** (1G), which is NOT the hydrostatic
   stratification a resting column needs. The two regimes meet at the χ=0.999 line with a **discontinuity**
   (exactly what INV-UNIVERSAL's continuity guard is designed to catch).
2. Because the gas `P` is anchored to its own gauge EOS rather than to a hydrostatically-consistent
   stratified field, the §4 force never produces a clean `∇P = ρg` balance. The leftover imbalance is
   patched in RESOLVE by the `if(i_gas)` `iy_target = m·g·dt − …` support impulse (1E-3). That impulse is
   a *flat* per-cell support that does not encode the cell-to-cell pressure coupling, so a stack of air
   cells has no restoring gradient distinguishing "bottom" from "top." Net `−∇P·V + m·g` is slightly
   downward-biased at the floor → the floor cell accretes mass each tick (2→10 kg ratchet) and the UNIFORM
   column never reaches the barometric fixed point (atmos_probe non-convergence; maxAirU rail).
3. The face split (1E-2) and the anchor floor (1G) mean a gas|gas vertical face and a gas|liquid face are
   driven inconsistently, leaving a residual y-drive that the relaxation cannot null (the INV-ATMOS
   "EOS-anchor→non-hydrostatic-P→residual-y-drive ratchet" the spec §11 explicitly logs as OPEN).

**How the branchless unification cures it:** apply **1C-5** — ONE relaxation update for every cell,
`Pn = [(1−ω)P + ω(target + χ_i·α·p_eos_i)] / (1 + ω·χ_i·α)`. Now:
- A χ=0 liquid: source term and denominator correction vanish by the multiplier → pure hydrostatic SOR.
- A χ≈1 air cell: same formula, χ·α≈α coupling → `P` relaxes to the χ-weighted EOS *as a source on top of
  the hydrostatic face average*, so the field carries BOTH the EOS pushback AND the hydrostatic
  stratification in one continuous `P`. The face average `Σw_f Φ_f/Σw_f` (1D-3) gives every cell — gas or
  liquid — the same `P_j + ρ̄g·(r_i−r_j)` neighbor coupling, so a resting air column relaxes to its
  ~12 Pa/cell barometric gradient with a real restoring ∇P.
- With that gradient present, the §4 force (1E-2 unified to ONE interior formula) yields `−∇P·V + m·g ≈ 0`
  at every cell **emergently** — the bespoke `iy_target` support impulse (1E-3) is no longer needed and is
  deleted (CONFLICT-D). The floor cell no longer sees a downward bias; the ratchet stops; UNIFORM
  converges to barometric; maxAirU → ~1.0.

The cure is therefore exactly the no-branch unification: removing the `(ci==PR_GAS)? : ` fork removes the
discontinuity that the whole gas-sink ratchet grows out of. INV-UNIVERSAL's continuity guard (sweep χ
across 0.999 and assert `P`/force/`p_eos`-contribution are continuous) is the regression test that pins it.

---

## PROPOSED ORDERED REBUILD TASK LIST

`engine_b.hpp` is one shared header → mostly sequential. Each task: RED test first, grand+per-species
mass AND energy exact, then GREEN. **INV-UNIVERSAL (static grep + continuity sweep) gates EVERY task.**

**T0 — Static no-branch guard + continuity harness (gate for all later tasks).**
- Add the INV-UNIVERSAL build-failing grep (forbidden tokens: `is_gas`, `is_liquid`, `is_solid`,
  `is_compressible`, `GAS_CHI_MIN`, `PR_GAS/PR_LIQUID/PR_SOLID`, `switch(state)`, χ/μ/τ_y-vs-const compares).
- Add the continuity sweep test: ramp one material's χ across 0.999 (and mass across min/max) and assert
  `P`, resolve-force, `p_eos`-contribution have no jump.
- Gates: this test compiles+fails-on-token now (proving it works), passes only after the purge.

**T1 — `chi()` regularized; delete `is_gas`/`is_compressible`/`GAS_CHI_MIN`/`CHI_COMPR_EPS` definitions.**
- Items 1A-1..4, 1B-1..3. Rewrite `chi` to the regularized one-liner.
- This will break every caller's compile → those are T2–T6. Do the LUT/seeding fix 1I-1 here (it only
  needs `chi`). Gate: `engine_b_lut_schema_test` (χ margins still pinned), compiles.

**T2 — Unified pressure relaxation (the gas-sink core fix).** *Depends T1.*
- Items 1C-1 (p_eos universal), 1C-5 (one relaxation update), 1D-1..6 (delete PR_* enum + classifier;
  μ-weight faces; χ-weighted anchor). Publish χ·p_eos + rho + mobility inputs from ENCODE.
- Gate: **INV-P1, INV-P2** (liquids byte-identical / cell-center hydrostatics held), **INV-GAS** (sealed
  pocket ≤1.20× rest), **INV-ATMOS UNIFORM converges + maxAirU→~1.0** (the gas-sink regression),
  **INV-AL**, continuity sweep. This is the load-bearing task.

**T3 — Unified §4 force + drive; delete the `if(i_gas)` θ/y-rest block.** *Depends T2.*
- Items 1C-2,1C-3,1C-4 (P0/stiffness/θ universal, χ-weighted), 1E-1..4, 1G-1, CONFLICT-D removal.
- Gate: INV-ATMOS (no residual-y ratchet), INV-P2 leveling, rest-balance (no vel_damp heat pump),
  conservation exact, continuity sweep.

**T4 — Unified μ-weight across flux/swap/drain/displacement; delete `wall_neighbor` &
`xspecies_noflux_neighbor` predicates.** *Depends T3.*
- Items 1F-1..16, 1H-1..4. Introduce the single `w_f` mobility weight + yield gate; remove all
  `!isfinite(viscosity)` forks and `is_compressible` displacement gates.
- Gate: INV-DB, INV-RR, terrain-shielding test (frozen cell takes no flux/force by weight), INV-SWAP2,
  conservation exact, continuity sweep.

**T5 — Move the no-penetration clamp out of DECODE into RESOLVE (or eliminate it).** *Depends T4.*
- Items 2-1, 2-2, CONFLICT-A. Verify DECODE has ZERO cross-cell reads afterward (grep `decrypt_world` for
  `snap.find`/neighbor index). Commit swaps in RESOLVE so DECODE is purely local.
- Gate: INV-7 (order-independence), resting-pool-no-self-heat (ND-18), DECODE-locality assertion.

**T6 — Branchless radiation ramp + branchless cohesion legalization.** *Depends T4.*
- CONFLICT-B (engine_b.hpp:2284 → smooth ε_eff ramp), CONFLICT-C (§5.2 if-ladder → clamps + donor budget
  `max(0,m−minMass)`).
- Gate: INV-RAD, INV-QUENCH, INV-NOSUBMIN, INV-COND, continuity sweep.

**T7 — sim_engine.hpp + final sweep.** *Depends T1 (done) — verification pass.*
- Confirm 1I-1 landed; full forbidden-token grep across `core/*.hpp` + `jni/*.cpp` returns ZERO; run the
  whole cheap tier; in-game audit gate (UNIFORM air converges, no 2→10 ratchet).

**Dependency graph:** T0 → T1 → T2 → T3 → {T4 → T5, T4 → T6} → T7. T2 is the critical path (gas-sink).

---

## APPENDIX — clean spots (no action)
- `encrypt_cell`/`encrypt_world` — per-cell local, compliant (no neighbor reads).
- `jni/orge_jni.cpp` — no classification branches (LUT marshalling only).
- `orge_kernel.hpp`, `lut_store.hpp` — no forbidden tokens.
- No global/column-sum reads in RESOLVE (the historic A+B debt is already deleted).
