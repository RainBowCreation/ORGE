# Drift audit — Module A: State, Material LUT, EOS, enthalpy curves

**Date:** 2026-06-15 · **Auditor scope:** §1.1, §1.2, §1.3, §2.1, §2.2, §8.1
**Read order honored:** DESIGN-LAW.md (frozen v4.2) → spec v4.1 → module code.
**Method:** executable code only; comments discarded as evidence; spec table = truth.

Files audited:
- `/home/claude/ORGE-B/ORGE-ENGINE/core/sim_engine.hpp` (Chunk + Material structs, enthalpy curve, EOS-adjacent)
- `/home/claude/ORGE-B/ORGE-ENGINE/core/engine_b.hpp` (Globals, chi/rho_eff/EOS functions, encrypt_cell, DECODE relabel)
- `/home/claude/ORGE-B/ORGE-ENGINE/core/lut_store.hpp`
- `/home/claude/ORGE-B/ORGE-ENGINE/tests/engine_b_real_lut.hpp` (LUT values vs §1.2 table)
- `/home/claude/ORGE-B/ORGE-ENGINE/tests/engine_b_lut_schema_test.cpp` (cross-check)

Severity counts: **BLOCKER 0 · MAJOR 0 · MINOR 4** (3 CODE-BUG hygiene, 1 LABELED-DEBT).

---

## COMPLIANT (verified per checked item)

### C1 — §2.1 gas EOS formula (CODE matches spec exactly)
`engine_b.hpp:237-245` `p_eos_v4`:
```
const float p_abs = (mass / m.molarMass) * R * T / G.V;
const float P0    = (m.defaultMass / m.molarMass) * R * Tref / G.V;
return p_abs - P0;
```
with `R = 8.314f` (line 240) and `Tref = (m.T_ref_gas > 0) ? m.T_ref_gas : G.T_ref` (line 241). This is
exactly §2.1 `p_abs=(m/M)·R·T/V`, `P0=(m0/M)·R·T_ref,gas/V`, `p_eos=p_abs−P0` (gauge). R=8.314 correct.
Per-gas T_ref used (LUT column), global fallback only when row's T_ref_gas==0. COMPLIANT.

### C2 — §2.2 incompressible: no EOS branch (not divide-by-zero)
`engine_b.hpp:238` `if (!is_gas(m)) return 0.0f;` returns identically 0 before any division. `is_gas` is
`chi>0.999` (line 167). An incompressible (max==default ⇒ chi==0) never reaches the `(m/M)·R·T/V`
expression. `pressure_stiffness_v4` (line 321-326) likewise returns 0 for non-gas. §2.2 "the compression
branch is removed, not divided-by-zero" — COMPLIANT.

### C3 — §1.2 χ formula + guard + gas cutoff
`engine_b.hpp:155-161` `chi`:
```
if (m.maxMass == m.minMass) return 0.0f;   // guard
float c = (m.maxMass - m.defaultMass) / (m.maxMass - m.minMass);
return std::clamp(c, 0.0f, 1.0f);
```
Matches §1.2 `χ=(maxMass−defaultMass)/(maxMass−minMass)` with `χ≡0 when maxMass==minMass`. Gas cutoff
`GAS_CHI_MIN = 0.999f` (line 166), `is_gas = chi > 0.999` (line 167). COMPLIANT.
(There is also a `denom <= 1e-9` defensive secondary guard at line 158 — behavior-neutral on the real LUT,
not a spec deviation.)

### C4 — §2.1 absolute-P cross-gas helper (`p_abs_hetero_v4`, `p0_gas_v4`)
`engine_b.hpp:256-281`. `p0_gas_v4 = (m0/M)·R·T_ref,gas/V`, returns 0 for non-gas/empty so hetero offset
collapses to 0 on same-gas and non-gas faces (matches §2.1 "same-gas faces stay gauge-cancelled"). The
absolute-P path is `[LAW-AMEND-v42-A2 / owner T4]` — spec says implemented "at hetero-gas faces ... in T4";
the helper exists and is correct, force-side wiring is the held-for-ST7/T4 item (NOT my section's drift; it
is labeled debt owned by T4/Module C). Formula COMPLIANT.

### C5 — §8.1 enthalpy curve, chain anchoring, ΔE≡0, T-inversion, latent plateaus
`sim_engine.hpp:159-219`. `curve_anchor` (161-172) walks the cold chain: a material with a `minTarget`
anchors as `h_anchor = h(minTarget @ minTemp) + latentHeatMin`; a chain ROOT (no minTarget) is `(0,0)` ⇒
single-slope `h=cp·T`. `h_of` (175-178) = `h_anchor + cp·(T−T_anchor)`. `T_of_eta` (181-208) inverts on the
slope, then pins T at the threshold across a latent band of width `latentHeatMax` (maxTemp side, 191-197)
and `latentHeatMin` (minTemp side, 201-206). This is exactly §8.1: E=m·h(T), piecewise-linear with plateaus
of width L, chain-anchored so relabel is identity on E. DECODE relabel (`engine_b.hpp:3039-3099`) keeps
`Enew` untouched and only re-derives T on the target curve (keep-E label flip) — ΔE≡0 by construction,
no cp-ratio rescale. INV-NOSUBMIN guard (`minMass(tgt) > mNew ⇒ block`, line 3091-3094) and INV-NOOVERMAX
freeze owned by `freeze_evict_world` (line 3065-3068). COMPLIANT.

### C6 — §1.2 LUT numeric values (cell-by-cell vs the table)
`tests/engine_b_real_lut.hpp:35-62`, aggregate prefix order
`{cp,k,M,minMass,maxMass,viscosity,defaultMass,yieldStress,emissivity,thermalExpansion,latentHeatMin,latentHeatMax}`
then phase quadruple + T_ref_gas set explicitly. Verified every cell:

| material | code (file:line) | §1.2 | match |
|---|---|---|---|
| water | L36-37: cp4186 k0.6 M0.018 min125 max1000 μ1e-3 def1000 τ0 ε0.96 β2.1e-4 Lmin3.34e5 Lmax2.256e6; 273→ICE / 373→STEAM | 125/1000/1000 4186 0.6 1e-3 0 0.96 2.1e-4 0.018 273→ice L3.34e5·373→steam L2.256e6 | ✓ |
| ice | L46-47,58-59: cp2108 k2.2 M0.018 917/917/917 μINF τINF ε0.97 β5e-5 Lmax3.34e5; maxTemp273→WATER | 917/917/917 2108 2.2 ∞ ∞ 0.97 5e-5 0.018 273→water L3.34e5 | ✓ |
| lava | L38-39,52-53: cp1150 k1.5 M0.065 330/2650/2650 μ5e2 τ0 ε0.95 β5e-5 Lmin4.0e5; 1275→STONE | 330/2650/2650 1150 1.5 5e2 0 0.95 5e-5 0.065 1275→stone L4.0e5 | ✓ |
| stone | L42-43,54-55: cp800 k2.5 M0.065 2700/2700/2700 μINF τINF ε0.90 β2e-5 Lmax4.0e5; maxTemp1450→LAVA | 2700/2700/2700 800 2.5 ∞ ∞ 0.90 2e-5 0.065 1450→lava L4.0e5 | ✓ |
| air | L40-41,61: cp1005 k0.026 M0.029 1.0/1.2/1000 μ1.8e-5 τ0 ε0 β0 T_ref_gas288 | 1.0/1.2/1000 1005 0.026 1.8e-5 0 0 (EOS) 0.029 T_ref288 | ✓ |
| steam | L44-45,57,62: cp2080 k0.025 M0.018 0.06/0.6/1000 μ1.3e-5 τ0 ε0 β0 Lmin2.256e6; 373→WATER T_ref_gas373 | 0.06/0.6/1000 2080 0.025 1.3e-5 0 0 (EOS) 0.018 373→water L2.256e6 T_ref373 | ✓ |

All numbers match §1.2. χ margins independently pinned in `engine_b_lut_schema_test.cpp:236-237`
(air 0.99980, steam 0.99946). No LUT mismatch found.

### C7 — §1.2 LUT schema fields (law #8 list, no add/remove)
`Material` struct `sim_engine.hpp:48-75` carries exactly the law-#8 schema:
`heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass, yieldStress,
emissivity, thermalExpansion, latentHeatMin, latentHeatMax`, phase quadruple `minTemp/maxTemp/minTarget/
maxTarget`, and `T_ref_gas`. No extra physical field, none missing. COMPLIANT.

### C8 — §1.3 retired-symbol ban list (live-logic grep)
`grep -rE 'own_weight_head|p_surf|swap_kv|swap_threshold|head_relax|p_ac_scale' core/` → **no hits** in
either core header. The `(1−χ)` relaxation factor is absent. The T4-transient exempt symbols
`GAS_CHI_MIN` (line 166) and `CHI_COMPR_EPS` (line 173) are present (spec §1.3 marks them exempt;
`LADDER_BETA`/`LADDER_REST_DEADBAND` are not present at all). Swap threshold uses `swap_kc` (the §1.3 `k_c`
knob), NOT the retired `swap_kv`/`swap_threshold`. COMPLIANT.

### C9 — §1.1 the live persisted set is behaviorally correct (E source of truth, T derived)
`step_world_b` (`engine_b.hpp:3126-3153`) opens with `derive_world_T(world, mats)` (line 3130) which
overwrites `T_curr = h⁻¹(E/m)` from the persisted E **before** `snapshot_world` reads it (line 3131). The
JNI/save layer round-trips `matIx, mass, momentum, E, P, swapReady` and re-derives T. No raw T or raw v is
trusted across a tick. Law #7 (store extensive, derive intensive) is satisfied in *behavior*.

---

## MINOR findings

### M1 (MINOR · CODE-BUG) — dead `T_curr`/`T_next` member vectors in the persisted `Chunk` struct
**Spec:** §1.1 — persisted per-cell state is EXACTLY `{matIx, mass_kg, momentum(px,py,pz), E, P,
swapReady, void_ix}`; law #7 forbids a stored raw T. §1.3 retired list includes `T_curr/T_next`.
**Code (`sim_engine.hpp:231-232`):**
```
std::vector<float> T_curr; // K (front buffer, derived per tick)
std::vector<float> T_next; // K (back buffer, conduction scratch)
```
These are persisted member vectors of the long-lived `Chunk` object — i.e. they physically survive across
ticks in memory, and `T_curr` is even part of the swap/terrain write-back (`engine_b.hpp:2864, 2927, 3104`).
**Why this is only MINOR, not a BLOCKER:** the stored value is never *trusted* across ticks — `step_world_b`
re-derives `T_curr` from E (`derive_world_T`, line 3130) at the top of every step before any read, so a
scribbled raw T cannot survive (verified at C9). `T_next` is referenced ONLY in comments inside engine_b.hpp
(`grep` shows lines 2252, 2405 are comment text) — it is genuinely dead in the Engine-B pipeline (a leftover
from the deleted `sim_engine` forward-Euler conduction). **Classification: CODE-BUG (struct hygiene / INV-3
manifest drift).** The frozen manifest §1.3 lists `T_curr/T_next` as RETIRED, yet they remain as live struct
members. INV-3 ("struct/knob diff against §1.3 must be empty") would flag this if enforced. It is not a
physics drift because the derive-first discipline neutralizes it, but it is exactly the kind of latent
temp-ghost surface the law wants gone. **Should:** make `T_curr` a transient (snapshot-local / per-pass)
buffer and delete `T_next` entirely.

### M2 (MINOR · CODE-BUG) — `swapPartnerKey` is a persisted field not enumerated in §1.1
**Spec:** §1.1 persisted set is the seven named fields; the bookkeeping additions sanctioned by
`[LAW-AMEND-6]` are `swapReady` and `void_ix` ONLY. §1.1 describes `swapReady` as "one float + a partner
key" but the frozen field list (and §1.3 manifest "§1.1's seven (+ the P copy)") does not enumerate a
separate partner-key field.
**Code (`sim_engine.hpp:274`):** `std::vector<int> swapPartnerKey;` — a distinct persisted per-cell vector
alongside `swapReady` (line 273), default `-1` (line 297).
**Classification: CODE-BUG (manifest under-specification / INV-3 surface).** Defensible reading: the partner
key is the second half of the "swapReady cadence accumulator + partner key" the spec text describes at §1.1,
so it is arguably *part of* the sanctioned swapReady bookkeeping. But it is a separate field in the struct
and is not in the enumerated seven, so a strict INV-3 struct-diff would flag it. Severity MINOR — it is
bookkeeping with no physical meaning, conserves nothing, and the spec text contemplates a partner key. Flag
for the user/INV-3 to either enumerate it explicitly in §1.1/§1.3 or fold it into `swapReady`.

### M3 (MINOR · CODE-BUG) — test energy oracle still reads `T_curr` and recomputes m·c·T (not E)
**Spec:** law #7 / §8.1 — energy is the persisted extensive `E`; T is derived. A grand-energy oracle should
sum `E` (+ KE), not reconstruct `m·cp·T_curr`.
**Code (`tests/engine_b_real_lut.hpp:80-96`, `grand_energy`):**
```
float c = mats.byIx(C.matIx[i]).heatCapacity;
...
e += (double)m * (c * C.T_curr[i] + kin);
```
This computes internal energy as `m·cp·T_curr` rather than reading `C.E[i]`. On the latent plateaus (water
mid-boil pinned at 373 K with η anywhere in `[h(373), h(373)+L]`) `m·cp·T` is NOT equal to the stored E —
the oracle would mis-measure conservation across a phase transition. **Classification: CODE-BUG in TEST
helper.** Per contract, tests are presumed buggy and do not define correct behavior; this is reported as a
test-helper drift (it would give a false conservation reading once latent heat is exercised), not a
production drift. **Should:** sum `C.E[i] + ½m‖u‖²`.

### M4 (MINOR · LABELED-DEBT) — `Globals::T_ref` doc/fallback overlap with per-gas `T_ref_gas`
**Spec:** §1.3 frozen knobs — `T_ref,global = 288 K (ρ_eff only)` and a separate per-gas `T_ref,gas` LUT
column. The two must not be conflated: `T_ref,global` is for `ρ_eff` buoyancy only; the gas EOS uses the
per-gas column.
**Code:** `Globals::T_ref = 288.0f` (`engine_b.hpp:34`) is consumed by `rho_eff` (line 219, correct, ρ_eff
only) AND used as the EOS fallback when a row's `T_ref_gas == 0` (`p_eos_v4` line 241, `p0_gas_v4` line 259,
`pressure_stiffness_v4` line 325). The fallback is only reachable for a gas row whose T_ref_gas was left 0
(the real LUT sets air=288, steam=373, so it never fires in production — air's fallback would coincidentally
also be 288). **Classification: LABELED-DEBT** — the code comment at line 34-35 explicitly calls this the
"JNI not-set convention until the T10 ABI bump," and Module F owns the JNI T_ref_gas round-trip. On the real
LUT it is behavior-neutral. Reported as a watch item: a future gas with a non-288 rest temp that loses its
T_ref_gas over JNI would silently EOS-anchor at 288 K. Not a §1.2/§2.1 formula drift.

---

## Summary
The §1.2 LUT values, the §2.1/§2.2 gas EOS formulas (p_abs/P0/p_eos gauge, R=8.314, per-gas T_ref, the
absolute-P helper, incompressible no-branch), the §1.2 χ formula + guard + 0.999 cutoff, and the §8.1
chain-anchored enthalpy curve (ΔE≡0 relabel, latent plateaus, T(E/m) inversion, INV-NOSUBMIN/NOOVERMAX
guards) are all **code-accurate to the spec**. No BLOCKER or MAJOR drift. The only issues are struct/manifest
hygiene: dead `T_curr`/`T_next` persisted members (neutralized by derive-first), an un-enumerated
`swapPartnerKey` field, a test-helper energy oracle that still uses m·cp·T, and a labeled-debt EOS fallback
on `Globals::T_ref`.
