# Whole-arc drift audit — Engine-B T1–T3 (engine @ `c148f49`)

**Date:** 2026-06-11 · **Scope:** the cumulative T1–T3 implementation vs `DESIGN-LAW.md` (frozen, amended
2026-06-10) + `specs/2026-06-10-engine-b-unified-spec-v4.md` (ratified). **Method:** 10-dimension parallel
auditor sweep (5 dims salvaged 2026-06-11 = "Batch A" + 5 dims run this session = "Batch B"), one synthesis
judge over the UNION, then 2 adversarial lenses (refute + label-check) per candidate NEW finding. Raw machine
output banked at `notes/2026-06-11-drift-audit-workflow-result.json`; the run script is
`notes/2026-06-11-drift-audit-workflow-missing5.js` (Batch A salvage:
`notes/2026-06-11-drift-audit-partial-findings.md`).

**Headline counts:** Batch B 50 raw → synth 23 NEW-unique / 20 known-confirmed / 10 pending / 8 dropped →
verify **15 confirmed REAL new drift**, 3 already-labeled, 5 refuted.

> This doc is a CENSUS, not a change. No law/spec/code was edited. Items needing the user's pen are carried
> into `DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`. Code-debt items name an owner task (T4/T5/T7/T_n).

---

## A. NEW-confirmed drift (15) — survived adversarial refute + not already labeled

### High severity (physics / conservation / persisted state)

- **ND-3 [§2.1 cross-gas ABSOLUTE pressure]** — `engine_b.hpp:754-757` (force GAS-j) + `:484/:490/:498`
  (relaxation gas-neighbor base). The mandated "cross-gas faces compare absolute pressure `p_eos+P0` each
  side so gauge offsets cancel" (ratified DEC-v4-B) is implemented **nowhere**: both the §3.1 stencil and
  the §4 force read each side's GAUGE `p_eos` and never re-add per-gas `P0`. Air P0 ≈99.1 kPa vs steam P0
  ≈103.4 kPa ⇒ two resting gases at true equilibrium read a spurious ~4.3 kPa gauge difference → phantom
  gradient into P and the momentum force. Air|steam faces are reachable (boiling water). **Distinct** from
  queue #18 (antisymmetry) and #1 (force-reads-anchor): even a perfect anti-symmetric anchor force is wrong
  here because it compares gauges not absolutes. → **AMENDMENT FORK** (implement in T4 vs amend §2.1 to drop
  the mandate). See amendment doc A-3.
- **ND-10 [law #9 unledgered destroy]** — `sim_engine.hpp:813-825` (`apply_injections` nothingToDisplace).
  Placement over an incumbent classed `nothingToDisplace` (`!movable` e.g. 2700 kg stone, OR
  `incMass ≤ ADV_EPS_MASS`, OR void) overwrites the cell and **destroys up to 2700 kg with NO ledger entry**
  — not even `sealedLoss` — while `ledger.injected` records the new mass. Contradicts "only source/sink is
  place/break, separately ledgered" and the file's own header. (The movable no-escape sibling DOES ledger
  sealedLoss.) → **CODE FIX** T_n: `sealedLoss[incSpecies]+=incMass` (+E) before overwrite when
  `incSpecies≠void_ix`; leave the genuine 0-kg void sub-case unledgered.

### Medium severity (formula / persisted-state-mispairing / manifest)

- **ND-1 [law #2/#4 gravity-once]** — swap DECODE `engine_b.hpp:1681-1695` vs additive `:1755-1758`. The
  C++ snapshot is the **pre-ENCODE** image (gravity lives in a separate `enc` buffer). The additive DECODE
  path reads the gravity-applied `ugy`; the swap path reads raw snapshot `vy` with **no `−dt·g`**, so a
  swapping pair loses one tick of gravity per cadence fire. Bounded (one tick, recovered next tick,
  cadence-gated ≥0.5 s, conservation untouched) but a real hole in "gravity once on every cell".
  → **CODE FIX** T4/T8: seed swapped-in velocity from the partner's post-ENCODE `e->cells[j].ug*`.
- **ND-7 [law #6/#7 injection drops momentum]** — `sim_engine.hpp:743-844` (`relocate_chain`,
  `apply_injections`). The injection/displacement seam moves mass writing only (matIx, mass, T)+re-encoded
  E; **px/py/pz are never moved/zeroed/permuted**. A placed/vacated cell pairs the displaced incumbent's
  stale momentum with the new mass (next-step `v = p_old/m_new`) — the velocity-ghost law #7 forbids, on a
  path that runs every placement. Σp untouched (mis-pairing, not fabrication) but injects spurious velocity
  + vel_damp heat. The engine's own RESOLVE swap permutes the full payload incl. momentum. → **CODE FIX**
  T_n: zero (or caller-supplied) placed-cell momentum, carry the incumbent's momentum through relocation;
  regression test for "no velocity-ghost after displacing placement".
- **ND-11 [law #9 ledger has no energy side]** — `sim_engine.hpp:484-490` (LedgerAccum injected/sealedLoss
  = MASS only), `:779-781/:834-837`, `orge_jni.cpp:199-210` (ledgerOut = mass floats). A sealed incumbent's
  enthalpy E (and momentum) is destroyed recording only mass; injected enthalpy enters unrecorded ⇒ grand-E
  books cannot close across the sanctioned boundary. **Structurally** nowhere to ledger E (no E slot).
  Distinct from the in-step F1 vacuum-guard (queue #12, `engine_b.hpp` §D.4 site). → **CODE FIX** T4 (F1
  sibling): add E (+momentum) side to LedgerAccum + JNI ledgerOut ABI; grand-E-across-injection test.
- **ND-12 [law #9 / §1.3 second void floor]** — `orge_kernel.hpp:21` `ADV_EPS_MASS=1e-4f`, consumers
  across `sim_engine.hpp`. The injection path runs on a SECOND, off-manifest void floor **100× the spec's
  ε_mass=1e-6**; a cell holding ≤1e-4 kg is overwritten as vacuum, deleting ≤1e-4 kg/event with no ledger —
  100× the §6.4 bound, and §1.3 pins exactly one ε_mass. (Engine-B proper uses `Globals::eps_mass=1e-6`.)
  → **CODE FIX** T4/F1 cluster: unify injection onto `eps_mass=1e-6` + ledger the residue. (Overlaps the
  same nothingToDisplace/seal sites as ND-10/ND-11.)

### Low severity (wording / test-shape / cosmetic range)

- **ND-8 [law #9 no_escape seam absent]** — repo-wide grep zero; decision points `engine_b.hpp:1509`,
  `sim_engine.hpp:720`. The law-mandated `no_escape` detection seam (empty body, for future handling)
  exists nowhere. The RESOLVE push-with-no-room path correctly no-ops but fires no seam; the injection
  path's no-escape case destroys instead of no-oping (overlaps ND-9/ND-10). Dedup of Batch A law-8-9#1.
  → **CODE FIX** T4 (add the empty seam + route injection escape through the standard displacement path,
  same fold-in as the banned `find_chain_hop`).
- **ND-2 [law #4 DECODE 1-hop reads]** — `engine_b.hpp:1761-1787` + swap `:1690-1695`. DECODE does 1-hop
  neighbor reads (`wall_neighbor`, `xspecies_noflux_neighbor`) to zero velocity into wall / cross-species
  no-flux faces — a RESOLVE-domain face BC enforced in a clause-designated per-cell-local step. Mitigated:
  immutable-snapshot reads, velocity-only, order-independent, GPU-safe, no fabrication. → **SPEC AMENDMENT**
  (sanction DECODE-time velocity-only 1-hop no-penetration). Amendment doc A-2.
- **ND-6 [§1.2/§5.1 τ_y units]** — `engine_b.hpp:157-161`, `sim_engine.hpp:56`. Swap gate yield term omits
  `·A_face` and `Material::yieldStress` is declared **N** vs spec τ_y **[Pa]**; spec also doesn't specify
  the `max()` pair-combination. Numerically inert today (A=1, τ_y∈{0,∞}). → **SPEC AMENDMENT** (make the
  threshold dimensionally coherent — code's all-N convention is sounder). Amendment doc A-4.
- **ND-14 [§6.2 eviction overshoot slop]** — `engine_b.hpp:1184` `if(evict>kroom+1e-3f)continue`. §6.3
  eviction tolerates the recipient exceeding room by ≤1e-3 kg then floors room at 0 ⇒ recipient can persist
  at maxMass+≤1e-3 kg, an overshoot neither §8.4-freeze nor queue-#19-vacuum. → **CODE FIX** one-liner
  `evict=min(evict,kroom)` OR accepted note.
- **ND-16 [§1.3 α_eos range]** — `engine_b.hpp:451` `clamp(alpha_eos,0,1)` vs §1.3 `(0,1]` (0 excluded).
  Default 0.25 in range; clamp lower bound admits out-of-spec 0. → **CODE FIX** tidy (lower bound → small ε).
- **ND-19 [stale in-code law claim]** — `engine_b.hpp:1836-1840`. Comment asserts the keep-E cp-jump "is
  the documented law-§6 semantic … latent heat is a law change, user-held" — but the amendments were
  **ratified 2026-06-10**, so latent plateaus are NOW the law and the comment is stale. → **CODE FIX** T7
  (relabel comment = T7-owned DEBT against ratified §6).
- **ND-20 [test synthetic LUT mislabel]** — `engine_b_phase_relabel_test.cpp:41-58`. `make_phase_lut`
  carries a pre-v4 synthetic LUT under a comment claiming "values verbatim from materials/*.json" (lava
  cp 1450 / band 400/3100/3100 / solidus 1000 K; steam degenerate). → **CODE FIX** T7 stale-seed sweep
  (re-base on §1.2 / `make_real_lut`).
- **ND-21 [lut_schema_test under-pins]** — `engine_b_lut_schema_test.cpp:96-210`. Assertions don't pin
  what the header claims; several §1.2 cells have NO assertion and can drift silently (lava k/τ_y/β/T_ref;
  stone M/β/latentMin; ice M; air yieldStress; steam k/β). → **CODE FIX** T_n: sizeof/field-count
  static_assert + full 17-field coverage per §1.2 row.
- **ND-23 [seed helper maxMass premise]** — `sim_engine.hpp:850-870`. `fill_section_with` seeds every cell
  at maxMass; with v4 gas bands (air 1000, steam 1000) filling a gas section now seeds 1000 kg/cell ≈ 69 MPa
  gauge instead of rest density. Callers today tolerate it. → **CODE FIX** T_n: seed gases at defaultMass.

---

## B. KNOWN-confirmed (20) — re-verified, already in the ratification queue / labeled in-code

Queue #1 (§4 force reads gas/vacuum EOS anchors not ½(P_i+P_j)), #2 (gas relaxed P force-dead + implicit
source), #3 (liquid|liquid ¼Δρ face correction), #4 (gas_anchor_v42 1%-floor + dual gauge), #5 (§5.2
sub-min full-merge HELD), #9 (solid/edge faces p̄=P_i Neumann ghost wording), #10 (κ·divU from PERSISTED
velocity), #12/F1 (§D.4 vacuum-guard zeroes booked E), #13 (enthalpy_carrier bound ×5), #14 (gas flux
direction w_eff vs cargo u_g), #15 (gasSpent order-dependence INV-7), #16 (eviction recvRoom−1 no gasSpent),
#17 (canDrain gasSpent-blind), #19/INV-RR (vacuum recvRoom=+INF overfill), #20 (cohesion_settled 150-tick
streak), #21 (bug3_accept lava 3100>2650 stale), #22 (vacuum cells skipped as relaxation centers),
**find_chain_hop** (banned symbol live on injection path, T4 routes through standard displacement),
gas|gas side-dependent off-equilibrium values (T4 fold-in, distinct from ND-3), LADDER_BETA/LADDER_REST_DEADBAND
(off-manifest T3 knobs, labeled "retired by T4 flux-intent").

**Already-labeled (3)** — flagged NEW by an auditor but verify found an existing owner-label:
ND-13 (molarMass single-consumer comment), ND-17 (live budget decrement = queue #15 INV-7 family),
ND-22 (χ guard totality = labeled).

---

## C. PENDING-by-design (10) — pre-v4 mechanism, owner task not yet run (NOT drift)

- **T4** — `resolve_world` is the monolithic single pass (DriveCtx + swap-select + vacClaim + canDrain +
  displacement + additive flux), not the 5 micro-passes R0/R1/R1.5/R2.
- **T5** — RESOLVE conduction is already law-shape (on E, antisymmetric, harmonic-mean k_face); the §8.2
  symmetric-face limiter is the only gap (current one-sided clamp).
- **T6** — radiation entirely absent (no σ_SB/ε-consumer/T_sky/INV-RAD/QUENCH); ε column stored
  storage-only; T_sky=270 manifest knob held.
- **T7** — enthalpy is pre-v4 linear `E=m·cp·T`; DECODE keep-E relabel jumps T by cp_old/cp_new (water→steam
  ~750 K superheat); latent plateaus + §8.4 absent.
- **T8** — swap cadence is the retired √μ form `period=swap_visc_rate·√(μ_i+μ_j)·(R/F)` gated by
  `fmod(simClock,period)`; dPE co-gate live; no t_swap_min floor; swapReady field absent.
- **T9** — swap buoyancy uses raw mass diff `(mUp−mLow)·g`, not `ρ_eff=(m/V)(1−β(T−T_ref))`; β stored unread.
- **T10** — 12-array ABI unchanged; 5 v4 LUT columns stubbed `0` under TODO(T10); E does not cross JNI.
- **§1.3 retired symbols** — `T_curr/T_next` still exist as persisted-shaped `std::vector` on Chunk
  (re-derived from E every tick via `derive_world_T`, so E is truth) — classification: persisted-shape
  survival, behavior-neutral.
- **§5.1 stacked-chain yield-propagation** (`F_out=F_in+m·(g·n̂)−τ_y·A`) implemented NOWHERE and **NO task
  in T4–T10 owns §5.1**. Behaviorally compliant today (τ_y∈{0,∞}). ⚠ flagged so it is not silently dropped.
- **swapReady persist (§1.1) + void_ix per-cell** — void_ix is a per-chunk sentinel (sanctioned
  interpretation); swapReady persistence is T8/T10.

---

## D. REFUTED (5) — auditor flagged, adversarial lens found compliant

- **ND-4** is_compressible 0.999 dual-cutoff — spec-sanctioned binary GAS classification, not a §1.2 violation.
- **ND-5** §5.2 full-drain budget — misread the parenthetical as exhaustive; code honors "single full-drain
  face is the only outflow".
- **ND-9** injection no-escape seal — lives in `apply_injections` (JNI place/break boundary), which IS the
  sanctioned separately-ledgered sink, and it DOES ledger sealedLoss. (Contrast ND-10's *unledgered* branch.)
- **ND-15** stone→lava relabel at 2700>2650 — relabel is mass-preserving; the >maxMass is a §8.4-class
  transient the next RESOLVE drains, not an unsanctioned overshoot.
- **ND-18** wall/CFL KE destruction — §4 defines a heat-deposit only for vel_damp; clamps/reactions are
  sanctioned no-deposit. (Recorded as a known spec ambiguity, queue-adjacent, not new drift.)

---

## E. Forward-risk per upcoming task (what the drift means for the queue)

- **T4 (RESOLVE 5-pass)** must additionally own: ND-1 (swap gravity), ND-11+ND-12 (injection E-ledger +
  eps unify, F1 cluster), and the ND-3 fork outcome if the user chooses "implement". The known F1/INV-RR/
  gasSpent fold-ins are unchanged. ND-7 (injection momentum) and ND-10 (unledgered destroy) sit on the
  `sim_engine.hpp` injection seam — same files T4's find_chain_hop reroute touches, so batch them there.
- **T7** owns ND-19 (stale comment) + ND-20 (test LUT) + the latent-plateau pending items.
- **T_n tidies** (no current owner): ND-6 (τ_y units, pending amendment), ND-14 (eviction slop), ND-16
  (α_eos clamp), ND-21 (schema-test coverage), ND-23 (gas seed density). Suggest a single "manifest/test
  hygiene" task after T4.
- **§5.1 ownerless** — decide whether the granular-yield stage is in-arc or explicitly post-arc; today it
  is compliant-by-vacuity (τ_y∈{0,∞}) but the law clause has no implementer.

---

## F. What goes to the user's pen (amendment draft)

Items requiring law/spec edits are in `DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`: the §4 boundary-face
value rule + §2.1 wording + law #2 ½(P_self+P_neighbor) (the pre-agreed core), the ND-3 cross-gas fork,
ND-2 DECODE-read sanction, ND-6 τ_y units, plus the §1.3 manifest gaps (queue #11 ω·(1+α_eos)<2; queue #10
κ·divU source wording; LADDER_BETA/DEADBAND/GAS_CHI_MIN knobs; ε_mass uniqueness vs ND-12). Everything else
is code-debt with a named owner task above.
