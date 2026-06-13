# Engine-B T4+ RECONCILED task map — post-v4.2-ratification (2026-06-13)

**Supersedes the task/queue sections of `2026-06-11-engine-b-v4-T4-continuation.md`.** That handoff's
**⚖️ USER RATIFICATION QUEUE is DEAD** — every item was ratified into the law/spec on 2026-06-13 (parent
`2332ea5`) and is now either applied text or a code item folded below. Read the older handoff only for its
**Process law** and the *historical* T1–T3 detail; for *what to build next*, this file is authoritative.

**State:** engine `c148f49` (T1–T3, pushed, clean). Parent `2332ea5` (docs only; **gitlink still pinned
pre-arc — do NOT bump / no `.so` rebuild until T10**). Law+spec are at **v4.2** (ratified). Harness fleet
PAUSED. Sources folded here: (O) original T4-continuation fold-ins; (V) v4.2 ratified code work
`[LAW-AMEND-v42-*]`; (D) T1–T3 drift code-debt from `notes/2026-06-11-drift-audit-T1-T3.md`.

## ⛔ Process law (unchanged — copy verbatim into EVERY subagent brief)
1. **NO FULL TEST RUNS, EVER.** Targeted single only: `timeout 300 ./tests/run_tests.sh <name>`. New tests
   <~60 s where possible (gas_test ~2.5 min is the ceiling — don't grow it).
2. **Subagent-driven development:** fresh implementer → spec-compliance review → code-quality review; PLUS
   adversarial-conservation review on any R-pass/swap/relabel/ledger change. Loop til ✅. Implementers commit
   on `rebuild`; controller pushes engine after each task.
3. **Caveman** for user-facing replies. **Read order every agent:** DESIGN-LAW.md (v4.2) → 00-MASTER-RULES.md
   → specs/2026-06-10-engine-b-unified-spec-v4.md. Spec is truth, code is the bug.
4. **P-0 is now law #0:** the core mechanic MUST NOT branch by state (`switch(state)`/`if(isGas)…`).
   State behavior comes only from material data. **INV-UNIVERSAL is an acceptance test for every task** — a
   state-branch in the core is a regression, flag it.
5. Background subagents: timebox (~3 attempts/defect), land green-and-coherent partials with labeled
   `// T?-OPEN:` skips + owner, report DONE_WITH_CONCERNS. WIP survives in the tree; salvage with a fresh
   scoped agent.

---

## T4 — §6 RESOLVE as five GPU micro-passes + the big fold-in cluster
**Core (spec §6.1–§6.4, §9):** restructure RESOLVE into **R0 swap-intent → R1 mutuality + legalized flux +
σ donor-scale (whole-cell flux-XOR-swap) → R1.5 ρ′ receiver-scale → R2 commit**. Snapshot = post-ENCODE;
order ENCODE → 2·N_relax half-sweeps → R0→R1→R1.5→R2 → DECODE; 1-hop reads of snapshot or prior-pass
buffers, no atomics.
**New invariant tests:** INV-DB (6-way diverging donor: Σout ≤ budget every tick) · INV-RR (6-donor
converging receiver: m ≤ maxMass every tick — kills the vacuum recvRoom=INF overfill, `engine_b.hpp:574-581`,
= drift **ND-19/Queue-19**) · INV-7 (forward/reverse iteration bit-identical).

**Fold-ins T4 OWNS** (tag: O=original, V=v4.2, D=drift-audit):
- **(O/D) F1 E-ledger** §6.4 — `m<ε_mass` relabel must ledger mass AND E; the §D.4 vacuum guard
  (`engine_b.hpp:~1397`) still zeroes booked conduction E (~542 J/event). **(D ND-11)** the place/break
  ledger (`LedgerAccum`, `sim_engine.hpp:484`) has **no E side at all** — add E (+momentum) slot + JNI
  ledgerOut ABI; **(D ND-10)** `nothingToDisplace` branch (`sim_engine.hpp:813`) destroys ≤2700 kg
  **unledgered** → ledger `sealedLoss[incSpecies]+=incMass` (+E) when `incSpecies≠void`; **(D ND-12)** unify
  the injection void floor `ADV_EPS_MASS=1e-4` (`orge_kernel.hpp:21`) onto `eps_mass=1e-6` + ledger residue.
  All four are one F1 ledger cluster on the `sim_engine.hpp` injection seam + the `engine_b.hpp` §D.4 site.
- **(D ND-7) injection drops momentum** — `relocate_chain`/`apply_injections` move (matIx,mass,T,E) but never
  px/py/pz → velocity-ghost (`v=p_old/m_new`). Zero placed-cell momentum, carry incumbent momentum through
  relocation (mirror E + the RESOLVE swap payload); regression test "no velocity-ghost after displacing place".
- **(D ND-8) no_escape seam absent** — add the law-#9 empty seam; route the injection escape through the
  standard displacement path. Same fold-in as **(O) find_chain_hop** (`sim_engine.hpp:598`, MASTER-RULES-banned
  symbol) — kill both together.
- **(D ND-1) swap skips one tick of gravity** — swap DECODE (`engine_b.hpp:1681-1695`) seeds swapped-in
  velocity from the **pre-ENCODE** snapshot (no −dt·g); seed from the partner's post-ENCODE `e->cells[j].ug*`.
- **(O) Unify drive maps** — liquids use pre-force `w`, gas got `w_eff` (`:1493`); gas flux DIRECTION =
  post-force `w_eff` while carried momentum = pre-force `u_g` (rising parcel carries downward cargo,
  **D ND/Queue-14**). One drive map.
- **(O/D Queue-15) gasSpent order-dependence** among competing donors (INV-7 blocker) — absorb the gas-axis
  ladder/spent machinery into R1.5 properly. **(O/Queue-16)** §6.3 eviction decrements recvRoom but never
  charges gasSpent. **(O/Queue-17)** canDrain is gasSpent-blind (`:1078`/`:1380` ARG-PARITY) — R1/R1.5 split
  makes the two sites one. **(O/Queue-18)** gas|gas faces carry side-dependent values off-equilibrium.
- **(V A-2(i)) cross-gas absolute-P** `[LAW-AMEND-v42-A2]` — at hetero-gas (different-species) faces compare
  `p_eos+P0` on the ANCHOR (same-gas faces stay gauge-cancelled). **INV-AL needs an air|steam variant**
  (resting air|steam reads NO spurious ~4.3 kPa gradient). Lives on the anchor (A-8 kept gas-P diagnostic).
- **(V A-10 partial) empty-refill donor-side gate** `[LAW-AMEND-v42-A10]` — a donor may not deliver
  `< min(target)` into an empty cell except on a full-drain; extends the cohesion gate to the receiver-empty
  case. Enforces **INV-NOSUBMIN** on the flow path (the relabel half is T7). NOTE: with A-10 landed the §5.2
  `0<D−f<min` branch is **unreachable** (already marked superseded in spec) — verify it cannot fire.
- **(V A-12 partial) atomic freeze-evict room machinery** — the eviction/room path that A-12's same-pass
  surplus eviction rides (the relabel trigger is T7/§8.4). Build the two-sided-legal eviction here so T7 can
  call it.
- **(O) sub-min tightening** — cohesion-settled 150-tick streak gate → §6.4 "next RESOLVE drains" is now
  **retired by INV-NOSUBMIN**; re-add live twins of the deleted force_advect repros (mixed-species-into-vacuum;
  6-donor-overshoot = INV-RR) + a residue |E|≤~1 J tripwire.
- **(O) T3-OPEN retirement (named exit criteria):** (a) gas breathing limit cycle (stiff explicit EOS spring,
  s≈82.5 kPa/kg) → expected to die in the quasi-static flux-intent rebuild; **re-arm INV-AL at full strength**
  (5000 ticks, surface u<0.02 m/s, NO heating) + INV-GAS late-E. (b) **standing-rail churn 2643.7 J/tick** —
  if κ/N_relax recalibration can't kill it inside T4, **ESCALATE to the user** (more sweeps vs banked
  multigrid); do NOT silently hope.
- **(O) Waterfall conservation bound** ×5 widening in enthalpy_carrier (Queue-13) — restore 1e-3-class
  discrimination (double-precision accumulation or per-tick antisymmetry assert).
- **(O) Leveling-speed flip target** (`stage2_leveling_test.cpp:568-582`) — T4/T8. Pre-warn the in-game audit:
  multi-column leveling is now FAR slower than pre-T3 (old speed came from the deleted −mg/2 defect).

**Conservation-neighbors to run singly after RESOLVE changes:** engine_b_gas_test, _pressure_relax_test,
_displace_swap_test, _conservation_levels_test, _accept_test.

---

## T5 — §8.2 symmetric face-flux conduction limiter
+INV-COND. One-sided clamp dies (conservative symmetric face-scale). The conduction-into-draining-cell
E-hole is the **same family as F1** (D) — make sure the T4 F1 ledger + this limiter close it jointly.

## T6 — §8.3 radiation
+INV-RAD a/b, INV-QUENCH. `ε_eff·σ·(T⁴−T⁴)·A·dt`; `T_sky=270` boundary-ledgered. (ε column already stored
storage-only.) **(V A-9 PROBE):** after A-10/A-12 land, run the air|vacuum column probe to confirm the
open-top atmosphere rests (the universal min-floor bounds expansion); a tiny min-boundary deadband only if
the probe shows ping-pong. A-9 is NOT a new mechanism — gas|vacuum is ordinary flow into a mass-0 cell.

## T7 — §8.1 enthalpy curves + latent plateaus + §8.4
+INV-LAT/STEAM. Chain-anchored latent plateaus, ΔE≡0 relabel; §8.4 phase bands.
- **(V A-10 relabel half + A-12) `[LAW-AMEND-v42-A10/A12]`:** the thermal relabel guard (forbid relabel when
  `m<min(target)`, keep-E, ×2 C++ + Java `PhasePlanner`) and the **atomic freeze-evict** (water→ice relabels
  to ice@max, evicts surplus same-pass via the T4 eviction machinery under BOTH bounds, or DEFERS). **⚠ latent
  caveat:** a cell that has *paid latent heat* but must defer the relabel needs a defined home for that energy
  or INV-LAT breaks — re-verify when plateaus land.
- **(D ND-19)** rewrite the stale `engine_b.hpp:1836` comment (keep-E cp-jump is now T7-DEBT against the
  ratified §6, not "the law"). **(D ND-20)** re-base `engine_b_phase_relabel_test.cpp:41` `make_phase_lut` on
  v4 §1.2 (lose the mislabeled synthetic LUT). **(O/Queue-21)** sweep stale lava-3100 seeds in bug3_accept
  (>v4 max 2650).

## T8 — §7 full-payload swap + §5.3 swapReady cadence (seconds-floor)
+INV-SWAP2/DT. `ρ_eff(β)` lands here; `t_swap=max(t_swap_min, (μ_i+μ_j)/(Δρ_eff·g·dx))` + per-pair swapReady
accumulator; delete dPE<0 co-gate + the √μ `swap_visc_rate` cadence. Leveling-flip target may resolve here.

## T9 — §0 same-species ρ_eff convection, NO cohesion term
+INV-CONV (126 N worked). `ρ_eff=(m/V)(1−β(T−T_ref))`; β stored, currently unread.

## T10 — JNI / Java integration + .so rebuild + gitlink bump
JNI grows LUT columns (ε/β/latentMin/Max/T_ref_gas — defaults stubbed `TODO(T10)`); **E must CROSS the JNI
boundary** (reconstruct-from-T breaks on latent plateaus); **ledgerOut ABI grows the E (+momentum) side**
(D ND-11); swapReady persistence; Java Material record/marshaller/save format; regen ResidentLutParityIT
golden vs new `.so`; rebuild `liborge.so`; **bump parent gitlink**; push both. In-game audit = user's gate.

## T_n — manifest / test / tidy (no current owner; suggest one task after T4)
- **(D ND-6 residual)** τ_y is now ratified [N]; `Material::yieldStress` already [N] — just drop any `·A_face`
  in `swap_resistance` if present and confirm the gate reads `max(τ_i,τ_j)` directly.
- **(D ND-14)** eviction `+1e-3` overshoot slop (`engine_b.hpp:1184`) → `evict=min(evict,kroom)`
  (now law-required by INV-NOOVERMAX).
- **(D ND-16)** α_eos clamp `[0,1]` → `(0,1]` (lower bound → small ε) per §1.3.
- **(D ND-21)** lut_schema_test: add sizeof/field-count static_assert + full 17-field per-row coverage.
- **(D ND-23)** `fill_section_with` seeds gases at maxMass → seed at defaultMass (rest density).
- **(V A-13 PROBE)** finite-τ_y pile stands-below / slumps-above (granular rides the existing gate, no new
  system). **(V A-11/A-14)** impact-heat + angle-of-repose = explicit FUTURE features, not in this arc.

---

## Subsumed / dead (do not re-open as work)
- The T4-continuation **ratification queue items 1–9** → all ratified; their text is in v4.2 law/spec.
  Queue 10–22 (the code items) are folded above under their owner tasks.
- **A-9 "shed to sky ledger"** WITHDRAWN (destroyed mass); A-9 is a verification probe under T6.
- §5.2 `0<D−f<min` HELD branch → unreachable under INV-NOSUBMIN (verify-only in T4).
- Refuted audit findings (ND-4/5/9/15/18) → not work.

## Open escalation flags (decide WHEN hit, don't silently absorb)
- T4 standing-rail churn (2643.7 J/tick) if κ/N_relax can't kill it → user: more sweeps vs multigrid.
- T7 latent-vs-deferred-relabel energy home (A-10/A-12 keep-E caveat) → may need an INV-LAT amendment.
