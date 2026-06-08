> # 🛑 LOAD FIRST — do not skip (the controller may forget to say it)
> **Before reading anything else, read these two files IN FULL and obey them as the law:**
> 1. `/tmp/orge-engine-b-resolve-handoff/00-MASTER-RULES.md` — spec>code, the **DO-NOT-TRUST** list,
>    🧨 **STALE-TESTS triage**, conservation non-negotiables, TDD/subagent process, report format.
> 2. **This file** (your task brief).
>
> Then read **ONLY** the authoritative specs the master rules name (canonical pipeline + decomposition §8
> + unified-formula §C/§J.5). **Do NOT learn the model from the C++ or code comments — the code is drifted;
> the spec is truth.** Skipping the master rules ⇒ you WILL rebuild the stale design. If `/tmp` is empty,
> ask the controller for the handoff set.

---

# T6 — Stability soak · read 00-MASTER-RULES.md first

**Goal:** prove the emergent-pressure resolver doesn't overshoot/oscillate on hard cases, and tune the
relaxation if it rings.

**Authoritative:** plan T6; decomp §8; unified-formula §G.1b (acoustic/CFL bound, "gradual equalisation
tradeoff"), §K (red-team edge cases) + §D.4 (vacuum/CFL guards).

## STRICT DO
- Soak cases (heavy tier OK): deep single column, U-tube, multi-arm / manometer, the mixed **A/W/L/S basin**
  (decomp §8 worked example). Assert: converges to level (within tolerance), **no overshoot**, energy
  bounded, no oscillation, conservation exact throughout.
- If it rings: tune the single global `G.head_relax` (∈(0,1]) and/or confirm the existing capacity clamp
  (§C.5) + CFL cap (§D.4) fire. Gradual leveling over many ticks is **accepted** (§G.1b), instant is not
  required.
- Note the resolver **cell-rate** (perf) in your report — it is the ~100× hot path; just record it.

## STRICT DON'T
- ❌ Do NOT "fix" oscillation by adding an EOS band, a sub-cycle count baked into the kernel, or scaling
  gravity — escalate first (off-spec, master rule).
- ❌ Do NOT optimise perf in this task (calibration is a later banked stage). Record the number; don't chase it.
- ❌ Do NOT loosen conservation tolerances to make a soak "pass" — a conservation drift in a soak is a real
  bug, not a tolerance issue.

**Acceptance:** all soak cases converge bounded + conserved; `head_relax` value documented; perf cell-rate
recorded. Report any case that needed damping and the final knob value.
