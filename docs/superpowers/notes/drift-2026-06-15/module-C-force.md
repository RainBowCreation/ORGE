# Drift audit — Module C: Force & momentum (RESOLVE), §4 + law #2/#4

**Date:** 2026-06-15 · **Auditor:** read-only drift-auditor · **Module:** C (§4, law #2/#4)
**Source of truth:** `DESIGN-LAW.md` §2/§4 + `specs/2026-06-10-engine-b-unified-spec-v4.md` §4.
**Code under audit:** `core/engine_b.hpp` (comments ignored; only executable code cited).

Severity counts: **BLOCKER 0 · MAJOR 1 · MINOR 3** (+ 1 spec-notation note). 1 spec-acknowledged DEBT.

---

## FINDING C-1 — Liquid boundary faces drop the half-cell hydrostatic ghost (MAJOR, CODE-BUG)

**Spec §4 (and law #2):** a *boundary* face (gas / vacuum / solid against fluid `i`) must carry the
cell's OWN half-cell hydrostatic ghost
`p̄_f = P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)` — "the boundary condition itself, NOT ½(P_i+Φ_f)"; an average leaves
`−m·g/2` on every free surface, "a permanent vel_damp heat pump."

**What the code ACTUALLY does** — in the DriveCtx force pre-pass, for a **LIQUID** cell `i`
(`i_gas == false`), the boundary faces use the bare cell-center pressure `P_i` (or `0`), **with NO
`ρ_i·g·(r_f−r_i)` ghost offset**:

- world floor/ceiling face — `engine_b.hpp:1026`
  `pbar = i_gas ? ownf : P_i;`  → liquid gets `P_i`.
- absent-chunk (world-edge) face — `engine_b.hpp:1034`
  `pbar = i_gas ? ownf : P_i;`  → liquid gets `P_i`.
- VACUUM neighbour — `engine_b.hpp:1040-1041`
  `if (mjm <= G.eps_mass) { pbar = 0.0f; }`  → liquid gets `0` (no `+ρ_i·g·…` term, no `P_i`).
- SOLID neighbour — `engine_b.hpp:1043`
  `pbar = i_gas ? ownf : P_i;`  → liquid gets `P_i`.

Only the GAS cell branch builds the ghost: `ownf = a_i - rho_i * ghalf * (float)DY[f]`
(`engine_b.hpp:1023`, consumed at 1026/1034/1043/1094 under `i_gas`).

So for a liquid the boundary face value is `P_i` (or `0` at vacuum) — **the spec's
`P_i + ρ_i·g·(r_f−r_i)` ghost is the gas-only path; the liquid path omits `ρ_i·g·(r_f−r_i)`
entirely.** The face value is `P_i` not an average, so this is NOT the `½(P_i+Φ)` averaging defect the
spec warns about — it is a *different* deviation: the half-cell hydrostatic extrapolation is missing.

**Should do (spec §4):** liquid boundary faces should be `P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)`
(= `P_i − ρ_i·ghalf·DY[f]` in this code's sign convention, i.e. the same `ownf` form the gas path uses
but anchored on the liquid's `P_i` instead of the gas EOS `a_i`).

**Severity = MAJOR.** Magnitude `ρ_i·ghalf = ρ·½·g·dx = 1000·0.5·10·1 = 5000 Pa` on a full water cell's
open face — exactly the free-surface half-cell head the spec's §3.1 keystone is built around. The net
per-tick force on a surface liquid cell is therefore off by `ρ_i·ghalf·A·dt` on each open vertical
face. Whether the spec's worked fixed points are met depends on the §3.1 relaxation having already
folded the hydrostatic head into `P_i` (it carries the `±ρ̄·g·dx` offset in the relaxation stencil,
`engine_b.hpp:629/635`), in which case `P_i` is the cell-center hydrostatic value and the force is
nonetheless still short the half-cell extrapolation the §4 boundary rule mandates. Note: the in-source
prose (`engine_b.hpp:443-445`, the §3.1 "5006 Pa" worked example) only describes the GAS path; the
liquid free-surface ghost has no executable counterpart. **CODE-BUG** (not filed as debt).

> Cross-module note: this interacts with Module B (§3). The relaxation gives a liquid cell its
> cell-center `P`; the §4 force is supposed to add the half-cell ghost on top at the boundary. The
> gas path does; the liquid path does not. A reviewer porting the gas `ownf` form onto the liquid
> branch (with `a_i` replaced by `P_i`) is the surgical fix — flag for the owner, do not fix here.

---

## FINDING C-2 — Interior fluid|fluid face: ¼Δρ coefficient & sign are CORRECT (COMPLIANT)

`engine_b.hpp:1106-1107`
```
pbar = 0.5f*(P_i + Cf->P[fj])
     + 0.5f*((mjm/G.V) - rho_i) * ghalf * (float)DY[f];
```
With `ghalf = 0.5·g·dx` (`:1001`), the second term is
`0.5·(ρ_j−ρ_i)·(0.5·g·dx)·DY[f] = ¼(ρ_j−ρ_i)·g·dx·DY[f]`.
Derivation of the physically-correct face value (mean of each side's half-cell extrapolation toward
the shared face): `½(P_i+P_j) + ¼(ρ_j−ρ_i)·g·dx·DY[f]` — **identical to the code.** The ¼ coefficient
and the `(ρ_j−ρ_i)` sign are correct. Compliant with spec §4 prose ("makes the two sides' half-cell
extrapolations agree at the shared face").

*Spec-notation note (MINOR, NOT a code bug):* taken **literally**, the spec/law formula
`¼(ρ_j−ρ_i)·g⃗·(r⃗_f − r⃗_i)` with `(r⃗_f−r⃗_i)` = the half-cell vector (½dx) evaluates to
`−⅛(ρ_j−ρ_i)·g·dx·DY[f]` — half the magnitude and opposite sign of what's needed. The code is right;
the spec's `(r⃗_f − r⃗_i)` notation is imprecise (the intended object is the full center-to-center
`g·dx`, halved once by the ½-average of the two extrapolations, giving ¼). Recommend the spec clarify
the notation; **no code change.**

---

## FINDING C-3 — Gravity + external applied exactly ONCE in ENCODE (COMPLIANT)

- Gravity: `engine_b.hpp:380` `e.ugy = uy - dt * G.g;` inside `encrypt_cell` (ENCODE, per-cell local,
  no neighbor read). Applied to `ugy` only, once.
- External impulse: the caller-supplied external momentum enters via the snapshot velocities
  `s->vx/vy/vz` fed into `encrypt_cell` (`:417-418`) — i.e. pre-folded into the seed velocity at the
  boundary, consistent with "external impulse applied once in ENCODE."
- RESOLVE re-application check: the force pre-pass (`:1116-1119`, `:1179-1193`) builds only the
  pressure surface integral; the advected channel (`:2206-2209`) carries `mdot·u_donor`. **Gravity is
  NOT re-added in RESOLVE** — the gas `i_support_y = m_i*G.g*dt` term (`:1181-1183`) is a
  *convex-blend under-relaxation TARGET* for the EOS pressure imbalance (`iy = i_support + θ·(iy −
  i_support)`), not an additive gravity term: at θ=1 it is the identity, so it adds no gravity. This
  is a defensible reading of law #2 (the pressure force's job at rest IS to equal `+m·g·dt`), and the
  fixed point is unchanged. See C-4.
- DECODE re-application check: `decrypt_world` seeds `u` from `e->cells[i].ugx/ugy/ugz` (`:2957`,
  `:2821`) — the ENCODE carrier — and adds only `A.dpx/dpy/dpz` (resolver deltas). **No second `-g·dt`
  in DECODE.** Compliant.

---

## FINDING C-4 — Gas EOS-imbalance θ under-relaxation: law-#2-defensible but is a tracked deviation surface (MINOR, spec-acknowledged DEBT)

`engine_b.hpp:1179-1193`: for a gas cell the pressure impulse is
`ix = θ·ix; iy = i_support_y + θ·(iy − i_support_y); iz = θ·iz` with
`θ = eos_impulse_theta_v4(...)` and `i_support_y = m_i·g·dt`.

This is NOT in spec §4's force formula (which is purely `−Σ p̄_f·A·n̂` + advected momentum). It is the
spec-§2.1/§3 gas-EOS stabilization (`[LAW-AMEND-v42-A8]`, the "force-dead diagnostic gas P" + EOS
anchor machinery). It is **material-derived** (θ from `pressure_stiffness_v4`, `:347-351`; θ≡1 for
incompressibles ⇒ provable no-op on liquids, `:1153/1229`), so it does NOT violate law #0's no-branch
rule in spirit (it is a per-cell scalar like dragScale, not an `if(isGas)` flux path — though it IS
gated by `if (i_gas)` at `:1179`, the scalar inside is data-derived). The y-target `m·g·dt` is honest
(it equals the value §4's pressure force must take at rest). **MINOR**, and it is spec-acknowledged
(the in-source `ST7-deep-2 ESCALATED` block `:1163-1178` documents that the *correct* thermal-aware
target is unsolved and KNOWN-RED `engine_b_thermal_rest_test`). DEBT, tracked, not a fresh code bug.

---

## FINDING C-5 — Cross-gas absolute-P at the force is UNIMPLEMENTED (spec-acknowledged DEBT, COMPLIANT-by-deferral)

Spec §2.1 `[LAW-AMEND-v42-A2]` requires hetero-gas faces to compare ABSOLUTE pressure (`p_eos+P0`) so
per-gas gauge offsets cancel. The force does **not** do this: `engine_b.hpp:1088`
`pbar = a_j + (mjm/G.V) * ghalf * (float)DY[f];` — the **gauge** form (`a_j`), with the absolute
compare explicitly reverted (`:1048-1086`). The spec itself tags this "to be implemented … in T4" and
"INV-AL needs an air|steam absolute-P variant once it lands" — i.e. spec marks it pending. The helper
`p_abs_hetero_v4` (`:278-281`) exists but is unwired ("HELD FOR ST7"). **DEBT, openly deferred in both
spec and code.** Not a fresh drift. (At a RESTING hetero face both gauges are 0 ⇒ pbar 0 both sides ⇒
no resting fabrication, so the deviation does not break conservation at rest.)

---

## FINDING C-6 — Advected momentum term present, antisymmetric (COMPLIANT)

`engine_b.hpp:2196-2209`: the moved mass carries `mdot·u_donor`, read from the ONE drive map
(`ugx=dd->ux[di]` etc., `:2203`), applied antisymmetrically:
```
Ai.dm  += -mdot;      Aj.dm  += +mdot;
Ai.dpx += -mdot*ugx;  Aj.dpx += +mdot*ugx;
Ai.dpy += -mdot*ugy;  Aj.dpy += +mdot*ugy;
Ai.dpz += -mdot*ugz;  Aj.dpz += +mdot*ugz;
```
Same `mdot`, opposite sign, single per-pair handling (`iFirst` gate `:1964-1966`). The displacement /
gas-eviction sub-pass likewise pairs `-evict*ug / +evict*ug` (`:1719-1721`, `:1737-1739`). The §4
pressure impulse `d.ix/iy/iz` is applied as source (i) at `:1943-1945` (`Ai.dpx += dwi->ix[i]`).
Both force sources present; advected momentum antisymmetric. **Compliant.**

---

## FINDING C-7 — vel_damp deposits removed KE as heat to the same cell; ~0 at rest (COMPLIANT)

`apply_vel_damp` (`:2750-2756`):
```
const float  vd = 1.0f / (1.0f + G.vel_damp * dt);
const double k0 = 0.5*(double)m*(ux*ux+uy*uy+uz*uz);
ux*=vd; uy*=vd; uz*=vd;
return k0 * (1.0 - vd*vd);   // = ½m(‖u‖² − ‖u'‖²)
```
Returned heat is added to the SAME cell's E on both decode paths: `C.E[i] = sp->E[j] + dampHeat +
dPE_half` (swap path `:2861`) and `Enew = s->E[i] + A.dE + dampHeat` (additive path `:3030`). At rest
`u≈0 ⇒ k0≈0 ⇒ deposit≈0`. Matches spec §4 `ΔE = ½m(‖u‖²−‖u'‖²)`, books closed. **Compliant.**

---

## FINDING C-8 — No-penetration clamp: snapshot 1-hop, zeroes v AND momentum, mass+E untouched, NO heat (COMPLIANT)

Additive path `:2962-2989`, swap path `:2827-2832`. The clamp reads the 1-hop neighbour from the
immutable `snap` via `wall_neighbor` (`:2694-2708`, reads `snap.find(...)->matIx/mass`, no transport,
no write) and `xspecies_noflux_neighbor` (`:2725-2743`, same — snapshot-only, returns bool). It zeroes
the velocity component (`ux=0` etc.); the new momentum is then recomputed `C.px[i]=ux*mNew` (`:3113`)
so the matching momentum component is zeroed too. **Mass and E are untouched** by the clamp itself
(the deposit at `:3001` is from `apply_vel_damp`, a separate dissipative sink — the wall zeroing has
NO deposit; the in-source `:2999` "no deposit" comment matches the executable behaviour).
This satisfies the ND-18 NO-DEPOSIT rule and the order-independent snapshot read. **Compliant.**
(Caveat: the apply ORDER is wall-zero → CFL-cap → vel_damp; the vel_damp deposit at `:3001` sees the
post-clamp `u`, so it does NOT charge the clamped KE as heat — correct per ND-18.)

---

## FINDING C-9 — A-11 high-speed-impact heat: NOT implemented (COMPLIANT)

No code path deposits the clamped no-penetration KE as heat. The only DECODE E source is
`apply_vel_damp` (the `g_dbg_damp_heat_sum` is the sole in-domain DECODE deposit, `:145-149`,
`:2840`/`:3002`) and the swap PE term. Spec §4 A-11 says this MUST NOT exist yet. **Compliant.**

---

## FINDING C-10 — CFL velocity clamp ‖u‖ ≤ dx/dt present on both paths (COMPLIANT)

Additive: `:2992-2994` `vmax = G.dx/std::max(1e-6f,dt); if (spd>vmax){ scale }`.
Swap: `:2833-2834` identical. Matches spec §4 `‖u⃗'‖ ≤ dx/dt`. **Compliant.**

---

## FINDING C-11 — Void floor: m<ε ⇒ u=0 with mass+E ledgered (COMPLIANT)

`:2944-2951`: when a cell drops below `eps_mass`, `droppedE = s->E[i] + a->cells[i].dE` is booked to
`world.boundaryE += droppedE` (`:2945`), then `C.px/py/pz = 0` (`:2949`), `C.E[i] = 0` (`:2951`),
`mass = 0`. Mass vanishes WITH the cell (the comment at `:2942-2943` says "Mass + momentum still
vanish"). Spec §4 requires "mass AND E ledgered to the boundary ledger." **E IS ledgered**
(`world.boundaryE`). The dropped sub-ε *mass* is not separately added to a mass ledger here — but per
law #9 / spec §4 the magnitude is `≤ ~1e-6 kg per event`, and the spec's own wording emphasises the E
hole as the correctness issue. Velocity/momentum zeroed correctly. **Compliant** (E ledgered;
sub-ε mass is the documented de-minimis at the void floor — see Module D/F for the mass-ledger check
across the JNI boundary, which is outside §4's scope).

---

## FINDING C-12 — No leftover one-sided solid wall-reaction kick (COMPLIANT)

Solid faces are CLOSED in the force loop: a solid neighbour yields `pbar = P_i` (liquid) / `ownf`
(gas) — the cell's own value, contributing the closed-surface boundary term, not a one-sided reaction
impulse (`:1043-1044`). The advective loop sets `wall = frozen_i || frozen_j` (`:1982-1984`) and the
flux is simply skipped on walled faces — no kick. DECODE applies free-slip no-penetration (zero the
into-wall component, C-8), not a reaction impulse. The v3 one-sided wall-reaction kick is **absent**.
**Compliant.**

---

## Summary table

| # | Item (spec §4 / law) | Verdict | Severity | Class |
|---|---|---|---|---|
| C-1 | Liquid boundary face = `P_i` / `0`, missing `ρ_i·g·(r_f−r_i)` ghost | **DRIFT** | MAJOR | CODE-BUG |
| C-2 | Interior ¼Δρ coeff & sign | compliant (code right; spec notation loose) | MINOR (spec) | — |
| C-3 | Gravity+external once in ENCODE; not in RESOLVE/DECODE | compliant | — | — |
| C-4 | Gas EOS θ-imbalance + `i_support_y` y-target | deviation, law-defensible, tracked | MINOR | DEBT |
| C-5 | Cross-gas absolute-P at force | unimplemented, deferred | — | DEBT |
| C-6 | Advected momentum `±mdot·u_donor`, antisymmetric | compliant | — | — |
| C-7 | vel_damp KE→heat same cell, ~0 at rest | compliant | — | — |
| C-8 | No-penetration: snapshot, zero v+p, mass/E untouched, no heat | compliant | — | — |
| C-9 | A-11 high-speed impact heat NOT implemented | compliant | — | — |
| C-10 | CFL clamp ‖u‖≤dx/dt | compliant | — | — |
| C-11 | Void floor u=0, E ledgered | compliant | — | — |
| C-12 | No one-sided wall-reaction kick | compliant | — | — |

**Headline:** one MAJOR code-bug (C-1, liquid free-surface ghost missing — the §4 boundary rule is
implemented for gas cells but NOT for liquid cells). Everything else compliant or tracked debt.
