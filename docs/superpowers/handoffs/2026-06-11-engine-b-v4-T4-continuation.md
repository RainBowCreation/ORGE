# HANDOFF — Engine-B v4 implementation, continue at T4 (§6 five micro-passes)

**Date:** 2026-06-11 · **Track:** Engine-B / branch `rebuild` · parent `/home/claude/ORGE-B` ↔ engine
submodule `/home/claude/ORGE-B/ORGE-ENGINE` (both `rebuild`).
**Engine HEAD `c148f49` (pushed).** Parent gitlink **intentionally pinned at pre-arc `96169ef`** — do
NOT bump it or rebuild `liborge.so` until T10 ships a coherent engine (mid-arc .so would be half-rebuilt
and trip the fragile `ResidentLutParityIT` golden for nothing). Harness fleet **PAUSED**
(`harness pause --force` 2026-06-10; PRD#5 superseded by this arc; resume only via /harness-resume).

## ⛔ Process law (non-negotiable, all of it)
1. **NO FULL TEST RUNS — EVER, BY ANYONE.** User mandate, stated twice: never invoke bare
   `bash tests/run_tests.sh`, never `full`/`quick`/`stress`, never "run the suite to check".
   Targeted SINGLE tests only: `timeout 300 ./tests/run_tests.sh <name>`. Put this verbatim in EVERY
   subagent brief (implementers AND reviewers). Keep new tests individually <~60 s where possible
   (`engine_b_gas_test` at ~2.5 min is the current ceiling — don't grow it).
2. **Subagent-driven development** (superpowers:subagent-driven-development): controller dispatches
   fresh implementer per task → spec-compliance review → code-quality review; PLUS an
   adversarial-conservation review on any R-pass/swap/relabel/ledger change (MASTER-RULES). Review
   loops until ✅. Controller (not implementers) commits-policy: implementers commit on `rebuild`,
   controller pushes engine after each task.
3. **Caveman mode** for user-facing replies (/caveman — terse, all technical substance, no fluff).
4. Read order for every agent: `docs/superpowers/DESIGN-LAW.md` (frozen law) →
   `docs/superpowers/handoffs/00-MASTER-RULES.md` → `docs/superpowers/specs/2026-06-10-engine-b-unified-spec-v4.md`.
   Spec is truth, code is the bug; do NOT reason about design from C++.
5. Background subagents: do NOT let one loop hours re-running tests; timebox (~3 honest attempts per
   defect), land green-and-coherent partials with labeled `// T?-OPEN:` skips + owner, report
   DONE_WITH_CONCERNS. Session limits / user stops: WIP survives in the working tree — salvage with a
   fresh tightly-scoped agent (worked for T3).

## Done this arc (all pushed to engine origin/rebuild; details in commit messages + reviews)
- **T1** `bf4c35e`+`19303d5` — LUT v4 §1.2 schema (ε/β/latentMin/Max/T_ref_gas, χ guard maxMass==minMass→0,
  GAS_CHI_MIN=0.999) + real test LUT exact (ICE ix=6 appended; lava 330/2650/2650 solidus 1275; stone 2700;
  steam band 0.06/0.6/1000); stone seeds 2700.
- **T2a** `d21d4c7` — §3 single-P red–black **Gauss–Seidel** relaxation (in-place per color; read-old/write-new
  is FORBIDDEN-divergent) + §2.1 ideal-gas gauge `p_eos_v4` (T_ref_gas==0 → Globals::T_ref fallback) +
  §2.2 incompressibles≡0. INV-P1: 75,006/155,006 Pa (±0.1% pinned). Knobs ω=1.5 N_relax=4 κ=20 α_eos=0.25.
- **T2b** `a6c267a`+`af55695` — **A+B DEBT DELETED** (own_weight_head, head_relax, p_ac_scale, (1−χ),
  eos_pressure K/γ, legacy Chunk::p, force_advect.hpp + its 3 tests, pdyn_probe, eos_test — grep-zero).
  §4 force = 6-face surface integral reading ONLY `Chunk::P` (solid/world faces = Neumann ghost p̄=P_i —
  adjudicated law-#2-correct); §6.3 driver = P−Φ; vel_damp=1.0 KE→heat books closed (helper
  `apply_vel_damp`); JNI pin/pout carries P (in-game persistence solved). INV-P2 levels [750.0|750.0],
  1500.0000 exact. Fixed a real +4.45 kg eviction-relabel fabrication (§6.3 claim/seal).
- **T3** `ffc6813`+`c148f49` — gas participation: DriveCtx §4 pre-pass (force hoisted, gas w_eff fold-in),
  `gas_anchor_v42` free-surface face values (killed the −mg/2 static heat pump, +1111 J/tick/cell),
  `gas_ladder_room` EOS anti-packing (LADDER_BETA=½, 1% rest deadband, per-receiver gasSpent single-spend),
  sub-min gas trickle, implicit gas relaxation source. INV-GAS pocket 1.4403 kg vs target 1.4424±0.06,
  P=20,282 vs overburden 19,903; INV-AL stratification 12.00 Pa/cell exact, airMax 1.2024.
  Conservation review: PASS — T3 *reduced* the F1 ε-deletion debt (548→0 events in A/B census).
  **BUT spec review = ❌-as-ratified: see ratification queue.** Air-medium scene variants restored
  (horizontal_flow, stage2_leveling FLAT, cohesion_settled A).

## ⚖️ USER RATIFICATION QUEUE (spec/law are user-edit-only; these ship in code as labeled debt)
1. **§4 face-value rule (v4.2 candidate):** boundary-face pressure = the boundary condition itself
   (half-cell own-ρ ghost extrapolation), NOT `½(P_i+Φ)` — the ratified averaging leaves −mg/2 on every
   free surface (proven: permanent vel_damp heat pump; no spec-conformant alternative exists — reviewer
   checked). Contradicts §2.1's ratified sentence "p_eos is never read by the force". Algebra at
   `core/engine_b.hpp:648-689`.
2. **Gas P is force-dead:** gas cells' relaxed P is no longer read by any physics (anchors do the work) —
   decide: delete, re-couple, or keep diagnostic.
3. **liquid|liquid ¼Δρ face correction** extends law #2's literal `½(P_self+P_neighbor)`.
4. **gas_anchor_v42 floor** (1%-under-rest) + dual raw/floored gauge readings per gas cell.
5. **§5.2 sub-min-donor full-merge branch HELD** (liquid want=0; gas trickles). Ratified worked examples
   130→[125|880] and 250→[125|1000] verified bit-exact on the live path — only the D≤min case deviates.
   Options: amend §5.2, or implement receiver-side drain (T5, banked stage3_cleanup) then enforce.
6. **Design gap:** open-top gas column has NO representable rest state under any current rule
   (Σa=+2400 Pa argument, independently verified — `tests/engine_b_gas_test.cpp:10-23`). World top is
   closed (sealed ⇒ fine); the standing case is air|VACUUM pockets (broken blocks). Needs a shedding/
   absorbing boundary. INV-AL re-authored sealed-top meanwhile.
7. **Spec-wording debts from T2a/T2b** (lower priority): Neumann solid-face ghost wording in §4;
   κ·divU source = PERSISTED velocity (post-ENCODE −g·dt fakes floor divergence); joint stability
   constraint ω·(1+α_eos)<2 missing from §1.3 (independent ranges permit divergent pairs).
8. **Pre-warn the in-game audit:** multi-column leveling is now FAR slower than pre-T3 (the old speed
   came from the deleted −mg/2 defect); arms-converge assert retired as a labeled T4/T8 flip target
   (`tests/engine_b_stage2_leveling_test.cpp:568-582`). Water poured into air levels via §6.3+EOS now.

## ▶▶ NEXT: T4 — §6 RESOLVE as five GPU micro-passes (spec §6.1) + accumulated fold-ins
Core (spec §6.1/§6.2/§6.3/§6.4, §9): restructure RESOLVE into **R0 swap-intent → R1 mutuality+legalized
flux+σ donor scale (whole-cell flux-XOR-swap) → R1.5 ρ′ receiver scale → R2 commit**; snapshot =
post-ENCODE; order ENCODE → 2·N_relax half-sweeps → R0→R1→R1.5→R2 → DECODE; 1-hop reads of snapshot or
prior-pass buffers, no atomics. New invariant tests: **INV-DB** (6-way diverging donor: Σout ≤ budget
every tick), **INV-RR** (6-donor converging receiver: m ≤ maxMass every tick — kills the vacuum
recvRoom=INF overfill at `engine_b.hpp:574-581`), **INV-7** (forward/reverse iteration bit-identical).

Fold-ins T4 MUST also own (each traced to a review finding; locations are @ `c148f49`):
- **F1 E-ledger:** §6.4 — `m<ε_mass` relabel ledgers mass AND E (vacuum guard at ~`engine_b.hpp:1397-1404`
  still zeroes booked conduction E, up to ~542 J/event; T3 reduced trigger frequency to ~0, close the class).
- **Unify drive maps:** liquids still use pre-force `w` (gas got w_eff) — labeled at `:1493`; also gas flux
  DIRECTION = post-force w_eff while carried momentum = pre-force u_g (rising parcel carries downward cargo).
- **gasSpent order-dependence** among competing donors (allocation, not total) — INV-7 blocker; absorb the
  gas-axis ladder/spent machinery into R1.5 properly (it pre-implements R1.5 on the gas axis, labeled).
- **gas|gas faces carry side-dependent values off-equilibrium** (bounded momentum-fabrication channel).
- **§6.3 eviction decrements recvRoom but never charges gasSpent** (one-tick ladder overshoot, conserving).
- **canDrain is gasSpent-blind** (ARG-PARITY markers at `:1078`/`:1380`) — R1/R1.5 split should make the
  two sites one.
- **Sub-min tightening:** cohesion-settled 150-tick streak gate → spec §6.4 "next RESOLVE drains";
  re-add live twins of the deleted force_advect repros (mixed-species-into-vacuum; 6-donor-overshoot =
  INV-RR) + a residue |E|≤~1 J tripwire (lost when advect_enthalpy narrowed its envelope).
- **find_chain_hop** (sim_engine.hpp:598, injection path) — MASTER-RULES-banned symbol; route injection
  escape through the standard displacement path or escalate.
- **T3-OPEN retirement (named exit criteria):** (a) gas breathing limit cycle (stiff explicit EOS spring,
  s≈82.5 kPa/kg) → expected to die in the quasi-static flux-intent rebuild; re-arm INV-AL at full spec
  strength (5000 ticks, surface u<0.01·dx/dt i.e. 0.02 m/s at dt=0.5, NO heating) and INV-GAS late-E.
  (b) **standing-rail churn 2643.7 J/tick** (relaxation dead-end residuals 0.5–3 kPa vs room-0 receivers,
  monotonic static-water heating) — if κ/N_relax recalibration can't kill it inside T4, ESCALATE to the
  user (more sweeps vs banked multigrid decision); do NOT silently hope.
- **Waterfall conservation bound** ×5 widening in enthalpy_carrier — restore 1e-3-class discrimination
  (double-precision accumulation or per-tick antisymmetry assert).
- Leveling-speed flip target (`stage2_leveling_test.cpp:568-582`) — T4/T8.

## Then (unchanged plan, queue in order)
**T5** §8.2 symmetric face-flux conduction limiter (+INV-COND; one-sided clamp dies; conduction-into-
draining-cell E-hole is the same family as F1) · **T6** §8.3 radiation (+INV-RAD a/b, INV-QUENCH;
T_sky=270 boundary-ledgered) · **T7** §8.1 enthalpy curves w/ chain-anchored latent plateaus, ΔE≡0
relabel + §8.4 (+INV-LAT/STEAM; sweep stale lava-3100 seeds in bug3_accept) · **T8** §7 full-payload swap
+ §5.3 swapReady cadence seconds-floor (+INV-SWAP2/DT; ρ_eff(β) lands here; delete dPE<0 co-gate +
swap_visc_rate·√ cadence) · **T9** §0 same-species convection, NO cohesion term (+INV-CONV 126 N worked)
· **T10** integration: JNI grows LUT columns (ε/β/latentMin/Max/T_ref_gas — defaults stubbed at
`orge_jni.cpp` TODO(T10)) + **E must CROSS the JNI boundary** (reconstruct-from-T breaks on latent
plateaus) + swapReady persistence + Java Material record/marshaller/save format + regen ResidentLutParityIT
golden vs new .so + rebuild liborge.so + bump parent gitlink + push both. In-game audit = user's gate.

## Tooling facts
- Single test: `timeout 300 ./tests/run_tests.sh <name>` (compiles + runs just that binary; logs in
  `build/<name>.run.log`). Conservation-critical neighbors worth running after RESOLVE changes:
  engine_b_gas_test, engine_b_pressure_relax_test, engine_b_displace_swap_test,
  engine_b_conservation_levels_test, engine_b_accept_test (each singly).
- Engine compile check: the test runner compiles; `.so` build deferred to T10.
- Tests: g=10, dx=V=A=1, dt 0.25–0.5; real LUT only (`tests/engine_b_real_lut.hpp`); BANKED (red, out of
  gate): resolve_accept, dambreak, stage3_cleanup.
- Controller task list lives in the session (T1–T10); reviews recorded in this conversation only — the
  durable findings are all in this file, in-code labels, and commit messages.

## Suggested skills
`superpowers:subagent-driven-development` (the executing process) · `caveman` (user-facing tone) ·
`superpowers:test-driven-development` (each invariant RED-first, inside the no-suite rule) ·
`superpowers:requesting-code-review` (reviewer dispatches) · `superpowers:verification-before-completion`
(verbatim single-test output before DONE) · `harness-resume` (only when the user wants the fleet back).
