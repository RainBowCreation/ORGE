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

# T4 — Swap barrier = pair resistance (drop swap_threshold) · read 00-MASTER-RULES.md first

**Goal:** replace the global `swap_threshold` constant with the **pair's material resistance**
(viscosity/cohesion) so the **swap-by-resistance** RED test goes GREEN: lava-on-water inverts; light-on-heavy
never; a high-resistance pair stays metastable.

**Authoritative:** decomp **§2** (GPU-safe swap mechanics — gather, one-swap-per-cell, flux-XOR-swap) + **§8.6**
(the unified flow/swap/stay gate). Canonical "cross-species reorder = force-difference vs pair resistance, NOT
chi, NOT a global constant."

## STRICT DO
- Keep the swap **inside RESOLVE**, same snapshot, the GPU-safe **gather** form: both cells reach the SAME
  deterministic decision from the read-only snapshot; each thread writes only its own cell; one-swap-per-cell
  per tick; **flux-XOR-swap** per face (a face fluxes OR swaps, never both).
- Driver = the **local** buoyant force `(ρ_up − ρ_low)·g·V` (heavy-on-light). **Overburden cancels** — do
  NOT add depth/overburden into the swap decision (Archimedes; decomp §8.6).
- Barrier = a **resistance derived from the two materials' viscosity/cohesion** (define it from existing
  material fields; document the formula in a comment citing decomp §8.6). Swap iff buoyant > resistance.
- A swap is a pure permutation ⇒ per-species mass exact. Add/keep a hysteresis so a pair can't ping-pong.

## STRICT DON'T
- ❌ Do NOT keep the global `swap_threshold` constant. Remove it; re-author any test asserting its value
  (MASTER-RULES stale-tests).
- ❌ Do NOT gate on `chi` / compressibility — that's deleted design.
- ❌ Do NOT let a swap and a flux both move the same mass (flux-XOR-swap).
- ❌ Do NOT introduce scatter/atomics — gather only.

**Acceptance:** swap-by-resistance test GREEN (invert / no-invert / metastable all correct); per-species
mass exact; buoyancy order lava>water>air holds; cheap tier green. **Adversarial conservation review
required** before T7. Report the resistance formula + the three swap-case results.
