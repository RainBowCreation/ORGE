# Drift Audit — Module E: Swap (§7) + Thermal conduction/radiation/latent (§8.2/8.3/8.4) + Law #6

**Auditor contract:** SPEC is source of truth; CODE/TESTS presumed buggy. Comments DISCARDED (executable
code only). Read order: DESIGN-LAW.md → specs/2026-06-10-engine-b-unified-spec-v4.md.
**File audited:** `/home/claude/ORGE-B/ORGE-ENGINE/core/engine_b.hpp` (+ `core/orge_kernel.hpp` for keff/σ_SB).
**Date:** 2026-06-15.

## Severity counts
- **BLOCKER: 0**
- **MAJOR: 0**
- **MINOR: 0**
- **Spec-acknowledged DEBT in scope: 1** (radiation case-(a) sub-mK noise interaction with Way-2 gas rest
  balance — explicitly flagged in-code as T6-OPEN, owned by Way-2/user; NOT a Module-E code bug).

Module E is, on executable-code inspection, **fully spec-compliant**. Every verification item below checks
out against real code. No drift found.

---

## Verification results (executable code only)

### V1 — Swap permutes ALL of (mass, matIx, E, momentum); swapReady reset both cells — COMPLIANT
DECODE swap-apply branch, `engine_b.hpp:2806–2872`:
- `C.matIx[i] = sp->matIx[j];` (2857) — matIx permuted.
- `C.mass_kg[i] = sp->mass[j];` (2858) — mass permuted.
- `C.E[i] = sp->E[j] + dampHeat + dPE_half;` (2861) — enthalpy permuted (+ legitimate dampHeat & PE-half).
- `C.px[i]=ux*C.mass_kg[i]; C.py[i]=uy*C.mass_kg[i]; C.pz[i]=uz*C.mass_kg[i];` (2868) — momentum permuted
  (`u` seeded from partner POST-ENCODE carrier `ep->cells[j].ugx/.ugy/.ugz`, 2821, so the swapped parcel
  keeps the same gravity impulse every advected cell got — momentum-conserving).
- Both endpoints read the immutable snapshot (`sp`) ⇒ pure permutation, grand mass/E/momentum exact by
  construction. `P` intentionally NOT swapped (positional, re-formed by relaxation) — matches v4 §1.1.
- swapReady reset: `persistSwapReady(firedSwap=true)` (2871) carries the remainder
  `C.swapReady[i] = (nextR>1)?(nextR−1):0` (2790). This is the §5.3 cadence semantics the spec endorses
  ("§7.1 swapReady resets on both cells" → to the §5.3 leftover). Applied on BOTH endpoints (each cell
  hits its own DECODE swap branch). COMPLIANT.

### V2 — ΔPE = (m_h − m_l)·g·dx split HALF to each cell — COMPLIANT
`engine_b.hpp:2852–2856, 2861`:
```
m_arrived = sp->mass[j];   m_left = s->mass[i];
m_h = max(m_arrived, m_left);   m_l = min(m_arrived, m_left);
dPE_half = 0.5 * (m_h − m_l) * G.g * G.dx;        // literal HALF
C.E[i] = sp->E[j] + dampHeat + dPE_half;          // half deposited here
```
Both endpoints compute the IDENTICAL (m_h − m_l) from the same immutable snapshot (one cell's
`m_arrived` is the other's `m_left` and vice-versa), each deposits exactly `0.5·|ΔPE|` ⇒ the two halves
sum to `|ΔPE|`, antisymmetric, books close (grand E rises by exactly |ΔPE|). PE-deposit is a distinct
source from the vel_damp dampHeat (no double-count). Matches §7.2 literally.

### V3 — Swap gate: cross-species cohesion+yield; same-species τ_y only — COMPLIANT (cross-checks Module D)
`swap_resistance`, `engine_b.hpp:205–210`:
```
yld = max(a.yieldStress, b.yieldStress);
if (sameSpecies) return yld;                       // §7.3 same-species: τ_y ONLY, NO cohesion
coh = G.swap_kc * min(a.minMass, b.minMass) * G.g; // cross: k_c·min(minMass)·g
return coh + yld;
```
Exactly v4 §7.3 / §0 table. The R0 gate fires the swap when drive beats resistance
(`engine_b.hpp:1393–1396`):
```
drive = (rhoUp − rhoLow) * G.g * G.V;              // Δρ_eff·g·V
sameSpecies = (mi == mj);                          // species-IDENTITY split, not a state branch (law #0)
resistance = swap_resistance(Mi,Mj,G,sameSpecies);
if (drive <= resistance) continue;                 // swaps iff drive > resistance
```
`G.swap_kc = 3.0` (= v4 k_c), `G.t_swap_min = 0.5` (= v4 t_swap_min). `ρ_eff` (218) folds in thermal
expansion β so the §7.3 same-species 126 N convection drive is positive — matches the worked numbers.
This is the SAME `swap_resistance` Module D's swap machinery consumes (single definition) ⇒ cross-module
consistent. COMPLIANT.

### V4 — Conduction: harmonic-mean k_face; flux q; conservative symmetric face-scaling limiter — COMPLIANT
- `keff`, `orge_kernel.hpp:55–57`: `(k1<=0||k2<=0)?0 : 2.0*k1*k2/(k1+k2)` — exact harmonic mean
  `2k_ik_j/(k_i+k_j)`, zero against a non-conductive partner. Matches §8.2.
- Flux, `engine_b.hpp:2270–2271, 2357`: `g_cond = kf·(A/dx)·dt; Qcond = g_cond·(Ti−Tj)`
  = `k_face·(T_i−T_j)·(A/dx)·dt`. Matches §8.2.
- Limiter, `engine_b.hpp:2350–2366`: ONE folded symmetric cap on `g_total = g_cond + g_rad`:
  ```
  gmax = (Ci*Cj)/(Ci+Cj)/6.0;
  if (g_tot > gmax && g_tot > 0) { s = gmax/g_tot; Qc *= s; Qr *= s; }   // scale BOTH channels equally
  Q = Qc + Qr;   Ai.dE += -Q;  Aj.dE += +Q;                              // ONE antisymmetric pair
  ```
  This is a face-flux SCALE (symmetric in i,j), NOT a one-sided T-clip. Antisymmetric write of the SAME
  double to both sides ⇒ grand-E exact through the limiter. The `Ci·Cj/(Ci+Cj)/6` cap bounds each face to
  move T_i ≤ 1/6 of the way to T_j ⇒ 6-face sum keeps T_next ∈ stencil [min,max]. This is precisely the
  §8.2 / law #6 "scale the offending FACE fluxes symmetrically — never a one-sided clip." COMPLIANT.
- **No forbidden one-sided T-clip anywhere in the thermal path** (grep confirmed: the only `std::clamp`
  near temperature is `derive_T`'s [0,6000] K *derivation* sanity hull in `orge_kernel.hpp` finalize, not a
  post-flux energy clip).

### V5 — Radiation: all three channels; ε_eff per channel; T⁴; σ_SB; T_sky; ΔT>300K gate; gas absorbs — COMPLIANT
`engine_b.hpp:2307–2348` (in-domain cases a + c) and `2368–2417` (case b, vacuum/sky):
- `SIGMA_SB = 5.67e-8` (`orge_kernel.hpp:61`) — exact.
- ε_eff selection (2307–2316):
  - both ε>0 (condensed↔condensed): `if (|dT| > 300.0) eps_eff = ei*ej;` — case (c) film-boiling gate at
    exactly 300 K, ε_eff = ε_i·ε_j. Below 300 K ⇒ eps_eff stays 0 (conduct only). Matches §8.3(c).
  - exactly one ε>0 (condensed↔transparent gas): `eps_eff = (ei>0)?ei:ej;` — the condensed side's ε.
    Case (a). The antisymmetric write `Ai.dE += -Q; Aj.dE += +Q` deposits the condensed cell's loss INTO
    the gas cell ⇒ gas ABSORBS antisymmetrically (gas's own ε=0 means it emits nothing, but it receives).
    Matches §8.3(a) and law #6 "gas partners absorb locally."
  - both ε=0 (gas↔gas): eps_eff = 0, no exchange. Matches §8.3.
- T⁴ flux via secant: `g_rad = ε·σ·(Ti+Tj)(Ti²+Tj²)·A·dt; Qrad = g_rad·dT = ε·σ·(Ti⁴−Tj⁴)·A·dt` (2346–2347).
  The secant `(Ti+Tj)(Ti²+Tj²) = (Ti⁴−Tj⁴)/(Ti−Tj)` is exact and (correctly) used instead of the tangent
  4σT̄³ so the folded limiter's gmax bound is the true discrete max principle — a HARDENING, not a drift
  (a tangent would under-bound and let a cell radiate below 0 K). Qrad value is exactly the §8.3 formula.
- Case (b) vacuum/sky (2368–2417): one occupied side exchanges with `T_sky = G.T_sky = 270.0` (knob, 2393),
  `Qsky = ε·σ·(Tc⁴−T_sky⁴)·A·dt`; the occupied cell's −Qsky is applied and the balancing +Qsky is
  **boundary-ledgered** to `boundaryE_sky` (2414–2415), folded into `world.boundaryE` (3147). A transparent
  gas (ε=0) facing vacuum exchanges nothing (eps_c>0 guard, 2389). Matches §8.3(b) + law #9 boundary ledger.
- T≥0 precondition (2342, 2389) guards the Stefan-Boltzmann INPUT on degenerate thinned cells — it guards
  the input, not the flux magnitude; spec-neutral (a sub-min cell with a derived negative T is a separate
  T7-owned upstream concern). COMPLIANT.

### V6 — Phase change: relabel mass-preserving; freeze water→ice EVICTS surplus same-pass or DEFERS; relabel ΔE≡0; no old "drain next RESOLVE" — COMPLIANT
- **Mass-preserving relabel:** DECODE writes `C.mass_kg[i] = mNew;` (3103) — the relabel is a pure LABEL
  flip (`sNew = tgt`, 3097/3068), never teleports mass. Boiled 1000 kg water → steam goes through the
  normal keep-E branch because steam maxMass=1000 ≥ mNew=1000 (`mats.byIx(tgt).maxMass >= mNew`, 3093) ⇒
  in-band, legal, ΔE≡0. Matches §8.4.
- **ΔE≡0 chain-anchored:** the relabel keeps `Enew` untouched and only re-derives T on the target curve
  (`Tnew = orge_enthalpy::derive_T(Enew, mNew, mats.byIx(tgt), …); sNew = tgt;`, 3096–3097); `C.E[i] = Enew`
  (3108) — no cp-jump re-encode, no fabricated E. Matches §8.1/§8.4 + law #6.
- **Freeze water→ice atomic evict-or-defer (no over-max even transiently):** owned by `freeze_evict_world`
  (`engine_b.hpp:2624–2688`), which runs BETWEEN RESOLVE and DECODE. It detects the lower-max target
  (`Mt.maxMass < mNew`, 2655), computes `surplus = mNew − Mt.maxMass`, PROBES (no writes) that the full
  surplus fits (`freeze_evict_probe`, 2660), commits the eviction all-or-nothing (`evict_surplus` loop,
  2669–2675), and only then stamps `a->cells[i].freezeTarget = tgt` (2686). If the probe fails it writes
  NOTHING (`continue`, 2667) ⇒ DEFER, cell stays cold water at full mass+E (keep-E, conserves energy
  exactly). DECODE then ONLY flips the label for a committed freeze (3065–3068) and explicitly SKIPS
  running its own trigger for any lower-max target (`mats.byIx(tgt).maxMass >= mNew` guard, 3093) ⇒ a
  deferred cell NEVER relabels to over-max. `evict_surplus` (2466–2544) carries mass + E (ṁ·h, 2531/2538)
  + momentum (2535–2537) antisymmetrically, vacuum receiver ends ≥ min (2513), occupied same-species
  receiver ends ≤ max (2518) — both bounds. This is exactly §8.4 / INV-NOOVERMAX (A-12).
- **Lava→stone at 1275 K** is data-driven via the LUT phase quadruple (`minTemp/minTarget`), handled by the
  terrain MELT relabel (2895–) and the fluid relabel; 2650/2700 under-full ⇒ DECODE keep-E branch.
- **Old "drain next RESOLVE" transient-overshoot is GONE:** grep for "drain next"/"next RESOLVE" finds no
  executable path; the over-max case is structurally owned by the same-pass freeze-evict. COMPLIANT.

---

## Law #6 (thermal rides the same pipeline) — COMPLIANT
- Heat moves in RESOLVE only: conduction + radiation accumulate into `Ai.dE/Aj.dE` inside `resolve_world`
  (`engine_b.hpp:756`, the single RESOLVE). DECODE applies the accumulated dE per-cell (3030, 3108).
- **No separate conduction pass** (grep confirmed): the only stepping entry is `step_world_b`
  (3126) → ENCODE → relaxation half-sweeps → `resolve_world` → DECODE. Conduction/radiation are folded into
  the same 6-face pair loop as advection. Satisfies the law's "No separate conduction pass" and the drift
  test (c).
- 6-face flux, harmonic mean, antisymmetric (±same double), discrete max principle enforced CONSERVATIVELY
  (symmetric face-scaling). Advection carries ṁ·h_donor (§8.1 enthalpy cargo, e.g. 2531). Enthalpy
  chain-anchored ⇒ relabel ΔE≡0. All satisfied.

---

## In-scope spec-acknowledged DEBT (not a code bug)
- **Radiation case-(a) ↔ Way-2 gas rest balance (T6-OPEN, `engine_b.hpp:2285–2306`).** Case (a) correctly
  has NO ΔT gate (spec §8.3 gives only case (c) the 300 K gate), so on a resting air column it fires on a
  genuine ~1 mK gradient produced by the §3.1 pressure solve, which the Way-2 θ-implicit gas rest balance
  is *independently* unstable to (it tips into a bounded+conserving ‖u‖=dx/dt vel_damp limit cycle even
  with radiation OFF and a +0.001 K conduction-only seed). The in-code disposition is explicit: radiation
  is the messenger not the cause; the fix is Way-2 hardening (owner: Way-2/user), NOT a §8.3 noise-floor
  gate (which would weaken spec-faithful radiation). Module E's radiation is spec-faithful and conserving.
  **This is correctly attributed elsewhere; no Module-E drift.**

## Compliant items summary
swap full-payload permutation (m,matIx,E,p) ✓ · swapReady carry-remainder reset both cells ✓ · ΔPE half-each
literal ✓ · swap gate cross=cohesion+yield / same=τ_y-only ✓ (= Module D swap_resistance) · conduction
harmonic-mean k_face ✓ · conduction flux formula ✓ · conservative symmetric folded limiter (no one-sided
clip) ✓ · grand-E exact through limiter ✓ · radiation all 3 channels (a/b/c) ✓ · ε_eff per channel ✓ ·
ΔT>300K gate for (c) ✓ · gas ε=0 absorbs (a) / transparent to sky (b) ✓ · σ_SB=5.67e-8 ✓ · T_sky=270 ✓ ·
vacuum boundary-ledgered ✓ · relabel mass-preserving ✓ · ΔE≡0 chain-anchored keep-E ✓ · freeze evict-or-defer
same-pass, no over-max transient ✓ · evict carries mass+E+momentum, both bounds ✓ · heat in RESOLVE only ✓ ·
no separate conduction pass ✓.
