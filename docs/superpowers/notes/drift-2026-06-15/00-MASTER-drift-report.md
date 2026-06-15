# Engine-B — Spec-vs-Code DRIFT AUDIT (master report)

**Date:** 2026-06-15 · **Method:** subagent-task-tree drift-audit layer, 6 modules in parallel, read-only.
**Rule applied (verbatim):** the SPEC is truth; CODE and TESTS are presumed buggy; **all code comments were
discarded** — only executable code was checked against spec.
**Authoritative truth:** `DESIGN-LAW.md` (frozen) ▸ `specs/2026-06-10-engine-b-unified-spec-v4.md`.
**HEADs audited:** parent `de7f3b0` · engine `a2a51cd`.

Per-module detail files live beside this one (`module-A…` … `module-F…`).

---

## Verdict

**0 BLOCKERS. 5 MAJORS (2 fresh code-bugs, 3 spec-acknowledged debt). ~13 MINORS.**
The native C++ engine is largely spec-faithful. **The two material fresh code-bugs are: (1) the Java
persistence layer (Module F), and (2) the liquid boundary-face force ghost (Module C).** Everything else is
either spec-labeled T4/Way-2 debt or cosmetic (dead fields / comment drift / test-helper / naming).

| Module | scope | BLK | MAJ | MIN | headline |
|---|---|:--:|:--:|:--:|---|
| A | State / LUT / EOS / enthalpy §1,§2,§8.1 | 0 | 0 | 4 | LUT values + EOS + enthalpy chain all match spec; only dead-field/test-helper minors |
| B | Pressure relaxation §3 | 0 | 0 | 1 | genuine in-place Gauss–Seidel SOR (forbidden Jacobi ABSENT); one gas-anchor-floor minor |
| C | Force & momentum §4 | 0 | **1** | 3 | **C-1: liquid boundary faces drop the `ρ_i·g·(r_f−r_i)` half-cell ghost (gas-only)** |
| D | Gates & 5-pass flux §5,§6 | 0 | 2* | 2 | lateral cross-species swap/displacement is vertical/gas-only (*both spec-tagged T4 debt) |
| E | Swap & thermal §7,§8.2/3/4 | 0 | 0 | 0 | **fully compliant** — swap payload, PE→heat, conduction limiter, 3 radiation channels, freeze-evict |
| F | JNI ABI / Java persistence / numerics §1.1,§9 | 0 | **2** | 3 | **F1: enthalpy E not persisted in Java (rebuilt as m·cp·T); F2: momentum persisted as velocity** |

\* Module D's two MAJORs are explicitly `owner T4` in both spec and code — labeled debt, not silent drift.

---

## The findings that matter (fresh code-bugs vs an UNCONDITIONAL law/spec clause)

### ★ F1 — Enthalpy `E` is never persisted in Java; reconstructed each tick as `mass·cp·T`
- **Spec/law:** §1.1 + law #7 — `E` is a persisted extensive field; `T` is derived, never stored.
- **Code:** no `E` channel in `SectionData` (`SectionData.java:36-46`), `ColumnTask/Result`, `RegionMarshaller.Flat`,
  or `SectionCells`. `NativeEngine.stepWorld` synthesizes `eIn = mass·cp·T` (`NativeEngine.java:171-179`)
  and **discards `eOut`** (`NativeEngine.java:186-189`).
- **Why it bites:** any latent-plateau cell (boiling/freezing water, lava→stone) is collapsed onto a single
  cp-slope each tick — the latent fraction is lost/fabricated across the tick boundary. **Defeats INV-LAT**
  at the Java seam even though the engine's own §8.1 enthalpy curve is correct (Module A confirmed).
- **Class:** CODE-BUG vs unconditional §1.1. (Tracked as "Subtask 9" debt in `NativeEngine` comments — but
  §1.1 is not conditional, so this is live drift.)

### ★ F2 — Momentum is round-tripped through Java as *velocity* (a §1.3 retired symbol)
- **Spec/law:** §1.1 persists `momentum (px,py,pz)` (extensive); §1.3 retires `persisted vx/vy/vz`; velocity
  is derived. Extensive storage is what makes advection conservative (law #7).
- **Code:** `SectionData/SectionCells/ColumnTask/ColumnResult/RegionMarshaller` persist `velX/velY/velZ`; the
  JNI rebuilds `px=v·m` in (`orge_jni.cpp:207-209`) and re-derives `v=p/m` out (`orge_jni.cpp:314-320`).
- **Why it bites:** consistent *within* a call, but the cross-tick source-of-truth is velocity — so when a
  cell's mass changes (flux / relabel / swap-recipient) between ticks, momentum is silently rescaled
  (`v·m_new ≠ p_old`). The velocity-ghost law #7 forbids.
- **Class:** CODE-BUG vs unconditional §1.1/§1.3.

> Note the engine/Java split: **inside the engine**, momentum (`px,py,pz`) and `E` ARE the source of truth and
> `T`/`v` are re-derived every tick (Module A + F confirmed — no in-engine law-#7 violation). The drift is
> purely at the **Java persistence boundary**, which down-converts to `v` and `T` before storing.

### ★ C-1 — Liquid boundary faces drop the half-cell hydrostatic ghost (force side)
- **Spec/law:** §4 / law #2 — a boundary face (gas/vacuum/solid vs fluid `i`) carries
  `p̄_f = P_i + ρ_i·g⃗·(r⃗_f − r⃗_i)` (the cell's own half-cell ghost).
- **Code (`engine_b.hpp:1020-1115`, executable only):** the `ownf = a_i − ρ_i·ghalf·DY` ghost is built **only
  when `i_gas`** and uses the **gas EOS anchor `a_i`**. For a **liquid** cell every boundary face uses bare
  `P_i` (world floor/ceiling :1026, absent chunk :1034, solid :1043) or `0` (vacuum :1041) — no
  `ρ_i·g·(r_f−r_i)` term. *Verified by the controller directly.*
- **Nuance (honest):** this is NOT the `½(P_i+Φ)` averaging defect the spec explicitly warns against (value
  is `P_i`, not an average). The §3.1 relaxation folds head into `P_i`, so a top free surface is ~coincidentally
  fine; but side/solid/floor liquid boundary faces diverge from the spec's mandated extrapolation. Physical
  magnitude up to `ρ_i·½·g·dx ≈ 5000 Pa` on a full water cell.
- **Class:** CODE-BUG vs §4 literal. (Owner should confirm whether the relaxation fixed point makes it
  benign, or it needs the liquid `ownf` form anchored on `P_i`.)

---

## Spec-acknowledged DEBT surfaced (already labeled — not silent drift)

- **D-MAJOR-1 / D-MAJOR-2 (`owner T4`):** general lateral cross-species swap + displacement is unimplemented —
  §7 buoyant swap is vertical-only (`engine_b.hpp:1358`), §6.3 displacement runs per-GAS-cell only
  (`:1638`). Conserving; coverage narrow; flagged T4 in code+spec.
- **B-debt / C-5 (`owner T4b-Way2/ST7`):** cross-gas absolute-P (`[LAW-AMEND-v42-A2]`) unwired at both the
  §3.1 relaxation and the §4 force; hetero-gas faces stay per-gas gauge. Helper `p_abs_hetero_v4` exists,
  intentionally unused. INV-AL air|steam variant pending.
- **C-4 (KNOWN-RED / Way-2):** gas EOS-imbalance θ under-relaxation y-target is the §2.1/§3 gas stabilizer,
  not §4's literal force; thermal-aware target openly unsolved.
- **A-M4 (`owner T10/Module F`):** `Globals::T_ref=288` doubles as ρ_eff ref + gas-EOS fallback when a row's
  `T_ref_gas==0`; behavior-neutral on the real LUT.

---

## MINORS (cosmetic / dead / comment-drift / test-helper — no behavioral drift)

- **A-M1:** `Chunk` persists dead `T_curr`/`T_next` vectors (`sim_engine.hpp:231-232`); re-derived from `E`
  at step open so never trusted across a tick, but INV-3 struct-diff would flag. Make transient/delete.
- **A-M2:** `swapPartnerKey` (`sim_engine.hpp:274`) persisted but not enumerated in §1.1's seven (arguably the
  sanctioned swap "partner key"); enumerate or fold into swapReady.
- **A-M3:** `grand_energy` test helper computes `m·cp·T_curr` not `E[i]` (`engine_b_real_lut.hpp:92`) — false
  conservation reading on latent plateaus. Test-helper only.
- **B-MIN:** §3.1 gas/vacuum neighbor relaxation reads the own-floored `gas_anchor_v42` (≈−991 Pa floor)
  rather than raw `p_eos,j`; bites only >1% rarefied gas; perturbs only the force-dead diagnostic gas P.
- **C interior-notation:** §4's literal `g⃗·(r⃗_f−r⃗_i)` reads as −⅛ if taken verbatim; code's `¼Δρ` at
  `:1106-1107` is the physically-correct mean-of-extrapolations — spec-notation imprecision, code right.
- **D-MIN-1:** R0 sorts by raw `drive` not `drive − R_pair` (`:1424/1446`); threshold gate at :1396 removes
  non-passing pairs, so low impact; one-line fix.
- **D-MIN-2:** missing the "(3) lower coordinate-parity" tie-break (`std::sort` on `prio` alone, not stable);
  measure-zero float ties; INV-7 guards determinism.
- **F3:** `swapReady` in-memory only, resets on reload (documented acceptable).
- **F4:** `void_ix` doesn't cross the ABI, resets to 0 each call (harmless under stable slot-0 vacuum).
- **F5:** stale comments claim sub-cycling (`Scheduler.java:216`, `orge_jni.cpp:282`) and an unimplemented
  swapReady TODO (`sim_engine.hpp:271`); executable code does ONE step per call (FORK-4 honored) and
  round-trips swapReady.

---

## What was confirmed COMPLIANT (high-value negatives)

- **Drift-test (a) — second pressure number: ABSENT.** One persisted `P`; no live `A+B`/`own_weight_head`/
  `p_surf`/`swap_threshold`/`swap_kv`/`head_relax`/`p_ac_scale`/`(1−χ)` in core (grep-clean, Modules A/B/D).
- **Drift-test (b) — direction-dependent force rule: ABSENT.** One isotropic 6-face rule (Module B).
- **Pressure §3:** genuine in-place Gauss–Seidel red–black SOR (forbidden weighted-Jacobi double-buffer is
  NOT present); κ·divU once/tick on pre-ENCODE velocity, excludes −g·dt; κ CFL-clamped (Module B).
- **LUT §1.2:** all six materials match the table cell-by-cell; χ formula+guard+0.999 cutoff; full law-#8
  17-column schema, none added/removed (Module A + F).
- **EOS §2:** `p_abs=(m/M)RT/V`, per-gas `P0`, gauge `p_eos`, R=8.314; incompressible has no branch (Module A).
- **Enthalpy §8.1:** chain-anchored curve, latent plateaus, keep-E ΔE≡0 relabel (Module A).
- **Thermal §8.2/8.3/8.4 — fully compliant (Module E):** conduction harmonic-mean k_face + conservative
  symmetric face-flux limiter (no one-sided T-clip); all 3 radiation channels with correct ε_eff, σ_SB,
  T_sky=270, ΔT>300K gate, vacuum boundary-ledger; mass-preserving relabel + atomic freeze-evict-or-defer
  (no over-max transient; old "drain next RESOLVE" gone).
- **Swap §7 — fully compliant (Module E):** permutes (mass,matIx,E,momentum); ΔPE/2 each cell; gate matches
  Module-D `swap_resistance` (cross-module consistent).
- **Flux §6 (Module D):** 5 passes R0→R1→R1.5→R2; σ=min(1,budget/Σf) budget=m−minMass; ρ′=min(1,room/Σinbound);
  commit f·σ·ρ′; whole-cell flux-XOR-swap; INV-NOOVERMAX + INV-NOSUBMIN gates present; ε-VACUUM relabel
  ledgers mass+E.
- **ABI / numerics (Module F):** pass order ENCODE→relax(2·N_relax)→R0..R2→DECODE; no atomics in hot passes;
  momentum/E/P/swapReady all cross the ABI both directions; dt seconds-clamped [0.25,0.5]; no full-step
  sub-cycling.

---

## Recommended remediation order (when the user authorizes fixes — NOT done here)

1. **F1 + F2** — add an `E[]` channel and persist `px/py/pz` (extensive) end-to-end through Java
   (SectionData → ColumnTask/Result → RegionMarshaller → JNI). Highest physics payoff (restores INV-LAT and
   law-#7 momentum across the tick boundary). These are the two material live bugs.
2. **C-1** — give liquid boundary faces the `P_i + ρ_i·g·(r_f−r_i)` ghost (or document why the relaxation
   makes bare `P_i` correct and pin it as an invariant).
3. **D MAJORs / cross-gas absolute-P** — already T4/Way-2 labeled; schedule per the existing plan.
4. **MINORS** — A-M1/M2 (struct hygiene for INV-3), A-M3 test helper, D-MIN-1, comment cleanup (F5).
