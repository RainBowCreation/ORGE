# Engine-B physics & math audit — formulas, mass flow, heat transfer (2026-06-10)

**Scope (user-set):** audit the mathematical / physical correctness of the *documented* model only.
Sources read: `DESIGN-LAW.md` (supreme, read-only), the 2026-06-09 unified flow/force/resistance spec (v3),
`handoffs/00-MASTER-RULES.md`, and the docs those name as authoritative: the 2026-06-07 CANONICAL pipeline,
the 2026-06-07 vector-map decomposition (§8), the 2026-06-04 unified-formula, `FORK-DECISIONS-flow-force-2026-06-09.md`,
and the 2026-06-07 overburden-head-resolve plan. **No source code was read.** The audit covers (a) internal
mathematical correctness and (b) theory-vs-reality (model vs. real handbook physics), per the user's directive.

**Method:** two multi-agent verification rounds (8 dimension auditors + 4 reality auditors; every finding
adversarially verified by 3 independent lenses — math re-derivation, doc grounding, physics/game-fidelity —
majority vote), plus a numerical verification script
(`2026-06-10-physics-audit-verify-math.py`, sibling file — run it to reproduce every number below).
~366 subagents, ~23M tokens. 7 findings stranded by session limits were verified in-context by the
controller (marked ⊕ below). Self-labeled debt (A+B split, viscosity-in-swap proxy, checkerboard,
dormant granular yield) was *not* re-reported; only flaws in its justification were.

**Headline tally:** 72 confirmed finding-verdicts → **~54 distinct defects** after cross-round dedup
(several were independently found by 2–4 auditors — treated as corroboration). 8 findings refuted by
verifiers (listed at the end — the docs win those). ~150 properties positively validated as sound.

---

## A. What HOLDS (validated sound — the model's good bones)

- **The A+B algebra itself is exact:** `P(center, k cells above) = ρg·k·dx + ρg·dx/2 = ρg(k+½)dx`. Units check (B = 5000 Pa for full water).
- **Face-average pressure flux `−½(p_i+p_j)·A·dt·n̂` is the correct conservative FV operator** — §J.5's hydrostatic worked example balances *exactly* (+2500 vs −2500), and the §12 H=16 stair profile satisfies discrete force balance cell-by-cell with zero residual.
- **Checkerboard harmlessness argument is algebraically right** for the probed geometries: the odd-even mode lies in the face-average gradient's null space; guardrail-a (nothing ranks absolute mid-column `p`) closes the selection-side risk.
- **Antisymmetric flux + permutation swap ⇒ grand & per-species mass conservation** is structurally sound; §12's records (8000.0000 exact, ~8e-9 relative drift) are consistent with float ULP accumulation.
- **The swap keystone is real physics:** `(ρ_up−ρ_low)·g·V` with overburden cancelling is exact Archimedes; `ΔPE = −(m_up−m_low)·g·dx` confirms the driver; depth-independence is correct, and the gate cannot false-fire light-on-heavy.
- **Rayleigh–Taylor claim correct:** heavy-on-light always overturns; viscosity is rate, never threshold; only cohesion/yield is a legitimate threshold. (The mantle example is technically Rayleigh–Bénard, but the RT conclusion stands.)
- **Conduction form is textbook:** `q = k_face·(T_i−T_j)·(A/Δx)·dt` with **harmonic-mean k_face** (specified in §C.3 — the law's `k·(T_i−T_j)` is shorthand); intensive-T potential gives the right second-law equilibrium; antisymmetry ⇒ energy-exact.
- **Store-extensive / derive-intensive (law #7) is the right design** — it is exactly what makes ghost bugs impossible *when followed* (see B5).
- **B's leveling fixed point is correct** ([1000|500]→[750|750]; every candidate head is monotone in m and vanishes at equal mass) and the §3.1 air-launch guard-rail arithmetic is right (≈2083 m/s on a 1.2 kg cell — keeping B out of the vertical kick is genuinely load-bearing).
- **§8.7 cohesion arithmetic** (130-onto-875 worked example) is internally consistent *as a proposed-f mover* (but see B-13).
- **LUT densities are handbook-realistic:** water 1000 (real 998), air 1.2 (1.204), steam 0.6 (0.598 — exact), stone 2500 (sedimentary range), g=10 (2% rounding). Hydrostatic magnitude at depth is real: 80 kPa @ 8 m vs 78.5 real.
- **λ = μ/(ρΔx²)** is the correct viscous rate (dt·λ dimensionless; `1/(1+dt·λ)` unconditionally stable; μ→∞ ⇒ frozen).
- **§G.1b acoustic CFL** (`c_s ≤ Δx/dt = 2 m/s`) is correctly derived and honestly labeled as the deliberate pseudo-compressibility tradeoff.
- §J.3/J.4/J.6 worked examples all re-derive exactly (conduction 100 J/±1e-4 K; advection 125 kg/0.111 m/s; air-pushes-water 1.5e-4 m/s).

---

## B. CONFIRMED DEFECTS — internal math (3-lens verified)

### Critical

**B-1. Multi-face donor-budget composition is undefined → composable mass fabrication.** *(mass-flow; script §9)*
The law names a "donor-budget" clamp but no doc defines it; the only specified clamp (§C.5) is receiver-side.
Per-face clamps don't compose: a donor upwind on several faces can legally emit more than it holds
(worked: D=300 kg, 4 faces × f=150 each individually legal → donor at −300 kg; §D.4's `m'<floor ⇒ VOID`
guard then *fabricates* 300 kg). The multi-face budget rule must be stated (proportional scaling or
sequential debit) and tested.

**B-2. Explicit conduction's stability bound is stated nowhere, and §E's stability argument is false.** *(heat; script §5)*
Required: `dt ≤ m·cp·Δx/(6·k_face·A)` per cell (max-principle). §E claims "energy cannot be created ⇒ no
blow-ups" — fallacious: antisymmetric conduction conserves ΣE *while* the two-cell mode diverges
(amplification `1 − κ(1/m_icp_i + 1/m_jcp_j)`, ≈ **−429/tick** for a 1e-6 kg advection-thinned water cell).
This is exactly the historical temp-ghost bug class; full cells have 4–5 orders of margin, which is why it
hides. The bound (or a max-principle clamp) must be in the spec, not just in engine lore.

**B-3. A compressed gas exerts no pressure — the complete mechanism of the open air-over-accumulation bug.** *(EOS ⊕→3/3; script §12; corroborated by MAT-3, PC-2)*
Sum the documented pressure sources for air: A is suppressed ×(1−χ)≈2.0e-4 (~5000× slower than water on an
already-O(H²) clock); B≡0 for gas (χ>0.999); p_eos declared "inert (max==default)" — a justification that is
**false for air** (max 1000 ≠ default 1.2; air is the one material with a real EOS band, and the CANONICAL
doc explicitly promises "gases have max_mass > default_mass (compressible band)"). Net: an air pocket
compressed 696× (the observed in-game 835 kg/cell) pushes back with ~0 where reality gives ~70 MPa.
χ as a material *constant* is blind to compression state (real gas: p ∝ ρ). §4 eligibility makes air always
consumable at any mass (own_weight_head ranks 0 for gas regardless). Law #9's no-escape relief
("compresses via EOS") presupposes the live gas EOS the v3 spec disabled — unreconciled.

**B-4. DESIGN-LAW #2's force statement is 2× the correct operator (and disagrees with the spec/engine).** *(law text; script §3)*
Literal law: per-face `P_self − P_neighbor`, opposing faces combined ⇒ net `P_{i−1} − P_{i+1}`. Correct FV
surface integral (and spec §1/§5, and the §12-verified engine): `½(P_{i−1} − P_{i+1})`. On the engine's own
converged stair profile, the law-literal operator gives 2·m·g net upward on every interior cell — a literal
implementation of the frozen law cannot rest hydrostatically. One sentence fix (user-only edit): make the
face value the average, or halve the combination. (Verifiers note the *engine* is right; the defect is the
frozen text — which is what future implementers are required to obey.)

### Major — the frozen law's own text

**B-5. Momentum is never advected in the law.** Law #6 says moving mass carries enthalpy only ("one carried
scalar"); law #2's momentum sources exclude `ṁ·u`. A draining cell keeps its momentum while losing mass ⇒
derived `v = p/m` inflates ×8 draining 1000→125 kg — precisely the velocity-ghost law #7 forbids, and #7's
"thinned cells bounded" claim fails without the `ṁ·u_donor` term. (The subordinate 06-04 doc §C.2 *does*
advect momentum — the law dropped it.)

**B-6. Gravity & external impulse are assigned to two phases.** Law #2: accumulated into momentum "in
RESOLVE from three sources… (ii) gravity, (iii) external impulse". Law #4: "ENCODE… apply gravity + external
impulse to momentum". Followed literally ⇒ 2g free-fall and `P_base = 2ρgH`, contradicting INV-A1. All
subordinate docs apply gravity once (ENCRYPT `u_g`).

**B-7. `−∇P·dt` is not an impulse.** Off by the volume factor: clause (i) is an impulse *density*
(kg·m⁻²·s⁻¹) while clause (ii) `mass·g·dt` in the same sentence is an impulse (kg·m/s). Only the 06-04/07
docs carry the `·A·dt` flux form; the law, spec v3, FORK doc and MASTER-RULES all silently assume
dx=V=A=1. Same family: yield gates compare F (N) to yield_stress (Pa) with no area factor anywhere; §6
stacked-yield also silently drops decomp §8.3's `+g·m_own` self-weight term; `swap_kv` carries undeclared
units (kg^½·m^{3/2}·s^{−3/2}); the law's `k·(T_i−T_j)` omits A/Δx/dt (subordinate docs have them). ⊕

### Major — spec v3 vs its own §12 probe data (internal contradictions)

**B-8. Convergence is O(H²), not the claimed "~H ticks / O(H)".** *(4 independent confirmations; script §1)*
t(8)=1500, t(16)=6000 ⇒ exponent exactly 2.0; both points fit t = 23.4·H². O(H) predicts 3000 at H=16.
Extrapolation: H=32 ⇒ ~24,000 ticks ≈ 3.3 sim-hours before a deep pool's base pressure is right — and
FORK-4 bans every remedy (sub-cycling, jump-start, projection) on the strength of the wrong class. DEC-A's
cost-acceptance and the §11 perf gate were ratified under a complexity class the spec's own appendix refutes.
(Physics agrees: the damped relaxation is diffusive, not ballistic; "propagates ≤1 cell/tick" is signal
speed, conflated with relaxation time.) Secondary unexplained datum: H=8 locks 47 Pa low, H=16 0.27 Pa low —
a 175× residual disparity in the wrong direction, never discussed.

**B-9. A's definition and its verification target differ by one cell-weight.** §3 defines A = ρg·k·dx
("the k cells of weight above"; base of H=8 ⇒ 70,000) but §12's probes lock A at ρg·(k+1)·dx (79,953 ≈
80,000 = bottom-face/floor pressure), so A+B overshoots the cell-center value by ρg·dx (10 kPa, 14% at H=8).
INV-A1 enshrines the measured value, so formula and invariant now disagree; either §3's "exact
discretization" wording or the lock target is wrong. *(3 independent confirmations; script §2)*

**B-10. The relaxation law is stated a factor dt apart in two places, with no stability bound anywhere.**
§1: `dp/dt = −head_relax·p_ac_scale·(1−χ)·divU` (coefficient in Pa) vs §3: `pNew = p − …` (per-tick, Pa·s).
Since dt floats in [0.25,0.5], the effective stiffness ρc² silently doubles when the scheduler halves dt.
No pseudo-acoustic stability region (head_relax × p_ac_scale × vel_damp × dt) is stated; `head_relax`'s
value appears in no doc. FORK-7's "c² derives from EOS stiffness K" is **non-evaluable**: the EOS is
singular for every incompressible material (B-11), and an EOS-derived c² would also violate §G.1b's own
c_s ≤ 2 m/s. The only finite coefficient in use (flat p_ac_scale=2000) "derives" from nothing.

**B-11. The EOS is singular at the shipped LUT.** *(3 independent confirmations; script §6)*
Compression branch divides by (m_max − m_rest) = 0 for water/lava/stone/steam (max==default); χ = 0/0 for
stone and steam. Law #9's "compresses via EOS" is mathematically vacuous for exactly the incompressible
materials it must cover; §C.5's "transient overshoot relaxes next tick via p" evaluates an undefined
expression for every incompressible receiver. The guards in actual use are undocumented.

**B-12. The CI-frozen state manifest contradicts law #7.** §9.1 persists raw double-buffered `T_curr/T_next`
and raw `vx,vy,vz` — no momentum, no enthalpy field — the *named, forbidden* temp-ghost/velocity-ghost
pattern of the law's own drift test (c), and INV-3 *weaponizes CI to protect the forbidden representation*.
Unlike A+B this deviation carries no debt label. (Tracked separately as the F1/momentum+enthalpy refactor.)

**B-13. §7's worked example contradicts its own rule.** The gate as written admits any f ≤ 5 for the
130-onto-875 case (R+f≤max ✓, D−f≥min ✓), so "blocked" is wrong unless an unstated proposed-f semantics
(movers only offer f = room or f = D) is added; T5 enshrines the example as a test. The rule also permits
permanently-suspended min-mass blobs (cells at exactly min on a wall, no driver to merge them).

### Major — conservation holes (beyond B-1)

**B-14. Cross-species inflow into a non-empty cell has no labeling rule.** §D.5 covers same-species merge
and refill-of-~0-cell only. Generic case (130 kg water into a cell still holding 1.2 kg air): any resolution
either destroys 1.2 kg of air and fabricates 1.2 kg of water, or violates one-species-per-cell. Per-species
conservation is decided exactly here and is unspecified ("~0" has no threshold either).

**B-15. Flux-XOR-swap cannot be both conservative and 6-neighbour-local as documented.** If cell i swaps
vertically, rule 5 says i does no advective flux on its lateral faces that tick; lateral neighbour k cannot
know this without re-evaluating i's swap decision — which requires reading i's *vertical* neighbours (2-hop
reads no doc grants). Otherwise k books −F on the shared face while i books nothing ⇒ ΣΔm ≠ 0.

**B-16. The swap permutation omits momentum and potential energy.** The copy list is "mass/matIx/T" —
velocity is unaddressed: either reading (u stays with location / u travels) fabricates or destroys pair
momentum `(m_up−m_low)(v_b−v_a)`; and the released PE (~21 kJ per lava/water swap, gated dPE<−1e-3) has no
energy destination (no KE/heat credit) — an energy leak in the model's own bookkeeping terms. The dPE gate
itself is never defined and (if pair-PE) is mathematically redundant to the force gate. ⊕

**B-17. DECRYPT's sub-min VACUUM relabel deletes mass and enthalpy as written.** Decomp §3: `m' < max(ε,
min_mass) → VACUUM` — up to 125 kg of labeled water (and its E) vanishes with no ledger; contradicts v3 §7
("no settled sub-min" via healing, not deletion) and law #9 ("mass moves, never vanishes"). Which rule is
current is undecidable from the docs. ⊕

### Major — thermal channel (beyond B-2)

**B-18. Latent heat is absent from the law's fixed schema.** Phase change at constant E is energy-free —
the missing term (2.26 MJ/kg vaporization) is **5.4×** water's entire 0–100 °C sensible budget. Phase fronts
have no energy brake. *(script §11)*

**B-19. Relabel "keeping E" jumps T by cp_old/cp_new and can cascade/oscillate.** Worked with real cp:
condensing steam at 372 K lands at **185 K** (insta-freeze cascade to ice); boiling water at 374 K reads
**753 K** (PC-4: keep-E boiling *raises* T — anti-compensating exactly where latent heat should cool).
No hysteresis/per-tick-relabel-limit/cp-ratio constraint exists; a permanent per-tick oscillator is
constructible. Corollary (MAT-2 + ES-7 ⊕): every shipped phase pair relabels into mass outside the target's
[min,max] band — most extreme: a boiled 1000 kg water cell becomes "steam" at **1667× steam's maxMass**
(real steam at that T,P occupies 1673 m³/kg) — a state the docs' own non-negotiables forbid and whose
prescribed relief (EOS) is singular for steam (B-11). *(script §10)*

**B-20. Cross-cp enthalpy advection is undefined-by-label.** T = E/(m·cp(matIx)) when E arrives from a
different-cp donor: outcome swings ×4 with the receiver's label (lava→water: +940 K spikes constructible);
no doc states the mixing rule. ⊕

**B-21. Two normative formulas for advected h.** Law #6: `h = cp·T`; 06-04 §B.2 (authoritative MATH doc):
`h = cp·T + p/ρ + ½‖u‖²`. §D.3's dissipation-to-heat closure (KE loss → heat — the only place friction heat
exists) is also incompatible with v3's no-momentum manifest. Which h is current is undecidable; E is
internal energy, not enthalpy (no pV work anywhere; 833× adiabatic compression would be +Δ×14.7 in T,
model adds 0 — PC-3). ⊕

### Major — cross-doc governance

**B-22. The supersession chain is broken.** MASTER-RULES (the file every implementer must read first)
grants decomp §8 supremacy ("§8 supersedes any older framing") and *omits spec v3 from its authoritative
list* — while v3 replaces two of decomp §8's rules (yield stacking `F_in−τ_y` vs §8.3's undiminished pass;
§8.5 lowest-`p` ranking vs §4's own_weight_head ranking). MASTER-RULES also teaches "fluid passes F_in+g·m
down / F_in sideways" — superseded by v3 §6, and its own law-banner mechanically rejects parts of the body
text. Three FORK-DECISIONS ratified answers diverge from what v3 wrote down; the ratified record was never
amended. The swap barrier has three different compositions across the docs (yield_stress is absent from the
live R_pair — solid immobility currently rides on √(visc=∞) alone, which Fork-4's own "Do now" wording
corrects but the live formula does not reflect). ⊕

### Selected minors (full list in the machine record)

- Checkerboard ceiling mislabeled: recorded 20,000 Pa = **2ρg·dx**, not "ρg·dx" *(script §4)*; odd-H parity never probed; §3.1's "A did it" attribution ignores p_dyn≠0 during transients; wall-reaction reads a one-sided absolute (p_dyn+p) transient as a physical impulse scaled by the arbitrary p_ac_scale ⊕.
- own_weight_head is half the true floor-pressure difference for partial cells (rate-only; equilibrium unaffected) *(script §8)*.
- v3 has **no velocity invariant**: the only CFL cap + void floor live in the superseded-era docs; the frozen knob manifest doesn't contain them ⊕.
- Several cadences are tick-quantized with no dt factor (swap 1 cell/tick, signal cap): halving dt doubles sink speed — outcomes are scheduler-dependent ⊕ *(corroborates TS-3)*.
- Consumed-cell bookkeeping: MASTER-RULES says "consumed … (ledger it)" vs law/v3 "relocate, never delete".
- Gas–gas swaps are unsatisfiable at rest densities (max buoyancy 6 N < cohesion floor 18 N) — benign only via an undocumented relabel path.

---

## C. CONFIRMED — theory vs reality (3-lens verified, game-fidelity weighted)

Real handbook values; severities already discounted for "this is a 1 m-voxel game, not CFD".

| # | Finding | Real vs model | Fidelity verdict |
|---|---|---|---|
| C-1 (MAT-3/PC-2/B-3) | Trapped air reaches 833× compression (denser than liquid air) under ~1 m of water; pushback ~0 vs real ~10⁸ Pa | **critical** — wrong equilibrium, known in-game bug |
| C-2 (PC-4) | Boiling **anti-compensates**: keep-E + cp ratio fabricates ~+378 K superheat where reality absorbs 2.26 MJ/kg | major |
| C-3 (PC-6) | No thermal radiation: exposed lava loses ~55 W/m² vs real ~190 kW/m² (3,500×) ⇒ lava in air never crusts on gameplay timescales (≈72 sim-days per 100 K) | major — guts the lava→stone mechanic in open air |
| C-4 (TS-5/ES-6) | Conduction across 1 m faces is gameplay-invisible: water next to lava reaches boiling in ~3.5 sim-days (real: seconds, film-boiling ~0.1–1 MW/m²); lava-water quench ~14 days | major |
| C-5 (MAT-1) | Lava 3100 kg/m³ > stone 2500: melt denser than solid rock inverts reality (basalt melt 2600–2800 < solid 2700–3000) ⇒ lava would sink *through* its own bedrock if solids could swap | major |
| C-6 (MAT-4) | No single lava viscosity satisfies both documented consumers: rate term needs μ≳5.6e4 Pa·s for "lava barely moves", swap barrier needs μ_sum < 13,225 (code units) or buoyant overturn freezes *(script §7: real rhyolite 1e5 Pa·s flips the gate to a permanent wrong freeze)* | major |
| C-7 (TS-1/MAT-8) | Pressure establishment ~10⁵× slow (8 m: 750 sim-s vs 5.4 ms); signals travel ~1.4 m/s vs 1480 | major (downgraded from critical: statics end-state is correct; kinetics wrong) |
| C-8 (TS-2) | Leveling ~75–100× slower than real water and monotone-overdamped; oscillation excluded *by spec* (T1 "monotone, no overshoot"; T6 "no oscillation") while §G.3/test-7 demands sloshing — an internal acceptance conflict | major |
| C-9 (TS-4/PC-5 + ES-1 ⊕) | Universal 1-cell/tick cap = 2–4 m/s everything: dam-break fronts 10× slow (§12's own 1.80 m/s sits exactly at the cap vs Ritter 17.9 m/s), waterfalls ~Mach-slow-motion; **law #3's promised depth-driven gushing is depth-blind above h\*≈0.8 m** (Torricelli √(2gh) saturates at the cap) | major (genre-tolerable per fidelity lens, but contradicts doc-promised behaviors) |
| C-10 (TS-3) | Swap transport tick-quantized: identical speed for all pairs, doubled by halving dt | major |
| C-11 (PC-1 + α·χ ⊕) | α·χ ≡ 0 for liquids ⇒ thermal expansion exactly zero ⇒ **natural convection structurally impossible** — yet "thermal convection" is acceptance test 9 and α is advertised as its calibration knob; molarMass is a dead field (MAT-6) | major |
| C-12 (PC-3) | No compression work: 833× adiabatic compression should reach ~4,270 K; model adds 0 K | minor (gas thermals out of scope until EOS lives) |
| C-13 (MAT-5) | Air buoyancy clamps at m_min=1.0 ⇒ hot-air lift saturates at ~63 °C, ~4.5× too weak at lava temps | minor |
| C-14 (PC-7/MAT-7/ES-5) | min_mass=125 kg ⇒ 12.5 cm minimum films (real puddles ~5 mm; σ_eff ≈ 5×10⁵ × water) ⇒ 1 m³ wets ≤8 cells vs ~200 m² | note — *deliberate, defensible voxel-scale stylization* (Bond number ≫1 argument is correct) |
| C-15 (ES-4 ⊕, downgraded) | B's "supported" predicate text is ambiguous ("cell below is not a mass-bearing wall/incompressible solid" — does liquid count?) and no §12 probe covers liquid-supported surface cells; the deep-pool freeze reading was REFUTED (subsurface A-gradient levels it, slowly), but the predicate text + probe gap stand | minor |
| C-16 (ES-3 ⊕, mostly refuted) | Solids never sink/float through liquid (stone rests on water forever; submerged ice pinned) — this is **documented deferred design** (DEC-4, τ_y=∞), not a defect; recorded here because the deferral note never states the flotation/sinking consequence | note |

**Validated as realistic** (sample of 44): density set ≈ handbook; incompressible-liquid idealization correct
(ΔV < 0.005% at game pressures); steam-lighter-than-air matches molar masses; swap cadence within 2–4× of
real RT growth and terminal velocities; conduction *stability* fine for full cells; viscous-drag form exact;
hydrostatic end-states (pressure at depth, level surfaces, 750/750) match reality to ~2%.

---

## D. REFUTED (the docs win these — recorded so they are not re-litigated)

1. **B-support freeze of deep-pool leveling** — refuted: the cells *below* a surface bump carry the
   overburden difference in A; INV-B2's floor-resting geometry doesn't transfer; pools level via the
   subsurface A-gradient (slowly). (Residual minor doc-ambiguity kept as C-15.)
2. **§6 atomic chain vs ≤1-cell/tick** — refuted: the stacking rule telescopes (`F > Σ τ_y` is a *local*
   test at the chain end), so all-or-nothing needs no N-hop read.
3. **Swap-rate step function** — refuted as restating the self-labeled §8 proxy debt without a new flaw.
4. **R_pair missing yield_stress = exit hole** — refuted: Fork-4's "Do now" wording already adds
   `cohesion + yield_stress` to the canonical threshold; the bank is correctly scoped. (The *live formula*
   discrepancy is kept inside B-22.)
5. **ES-2** (oscillation excluded / 100× slow) — duplicate of C-8. **ES-5** — duplicate of C-14.
   **ES-6** — duplicate of C-4/C-3. **ES-7** — folded into B-19.

---

## E. Numerical verification

`2026-06-10-physics-audit-verify-math.py` (this directory) reproduces: the O(H²) fit (exponent exactly
2.000, C=23.44), the A off-by-one-cell-weight (79,953 ≈ ρgH not ρg(H−1)), the factor-2 face-force check on
the §12 stair profile (face-average balances at 10,000 N; law-literal overshoots at 20,000 N), the
checkerboard ceiling 2× mislabel, conduction stability margins (full cells safe by 10³–10⁶; 1e-6 kg cell
diverges at −429×/tick), EOS/χ singularities for 4 of 5 materials, swap-barrier margins vs real viscosities
(swaps at μ≤1e3, wrongly freezes at 1e5), B's partial-fill factor-2, the multi-face donor fabrication
example, keep-E relabel jumps with real cp (steam→water lands at 185 K; water→steam at 753 K), latent-heat
magnitudes (5.4× sensible), the pressureless-gas computation (70 MPa missing), and the reality timescale
table. All inputs traced to the allowed docs; real-world constants labeled REAL.

## F. Caveats & coverage

- **Code was not read** (per the goal); everywhere the docs and the engine may differ, this audit binds the *docs*.
- Two session-limit interruptions: round-1's dedup agent and ~34 verifier calls, round-2's 21 ES verifier
  calls + both completeness critics. All stranded findings were subsequently verified (round-2 fleet for 14;
  controller in-context for the 7 ES, marked ⊕ with the round-1 refutation evidence applied). **No
  completeness critic ever ran**; controller self-check found the planned dimensions covered (hydrostatics,
  force/momentum, mass flow, buoyancy/resistance, heat, EOS, numerics, cross-doc, materials/timescales/
  phenomena/scenarios vs reality), with external-impulse-pathway details and multi-arm U-tube (already
  user-banked) as the thinnest spots.
- Machine record: full finding bodies + 3-lens vote transcripts live in the session workflow journals
  (runs `wf_d41deaf7-a7e`, `wf_533f0c7b-5a5`); condensed JSON at `/tmp/confirmed_findings.json`,
  `/tmp/round2_result.json`, `/tmp/es_findings.json` (session-lifetime only).

## G. Suggested priority (no code/law edited by this audit)

1. **B-3/C-1** gas pressure (open in-game bug #2's mechanism) and **B-1** donor-budget rule — conservation/equilibrium.
2. **B-2** state the conduction bound + clamp; **B-19/B-18** phase-change semantics (hysteresis or latent field — law change, user's call).
3. **B-4/B-6/B-7/B-5** one-sentence law-text fixes (user-only edits) — cheap to fix, expensive if a fresh implementer obeys the letter.
4. **B-8/B-9/B-10** re-state the accepted cost as O(H²) and reconcile A's definition with INV-A1 before more invariants are built on them.
5. **B-22** repair the read-order/supersession chain (MASTER-RULES is the entry point and is stale).
