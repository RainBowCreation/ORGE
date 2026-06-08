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

# T3 — Yield-gated force transmission · read 00-MASTER-RULES.md first

**Goal:** ensure the emergent pressure (T2) transmits **fluid → through, locked solid → blocked, vacuum →
reset**, so the **solid-shelf-shielding** RED test goes GREEN. No new pass — this is the per-face gate inside
T2's existing loop.

**Authoritative:** decomp **§8.3** (the transmission table) + **§8.4** (sideways gate). Canonical "yield_stress
universal axis; immovable = viscosity ∞."

## STRICT DO
- Per face, gate the transmitted force by the receiver: FLUID passes (`F_in + g·m` down / `F_in` unchanged
  sideways); **LOCKED solid bears + blocks** (passes 0 → the cell beyond is shielded); VACUUM/free-surface
  resets to 0. Today **all solids are terrain `viscosity/yield = ∞` → always locked → always block** — so in
  practice "solid" == the existing frozen-terrain wall path; verify the shelf-shielding + wall-between-pools
  cases behave (fluid under a stone shelf has lower driving pressure than an open column at equal depth;
  pools either side of a vertical wall don't level through).
- Keep it branch-light and data-driven (the gate reads material viscosity/yield, not a species switch).

## STRICT DON'T
- ❌ Do NOT implement the **movable-solid / granular yield** (force > yield_stress ⇒ solid flows). That is
  **DEFERRED** (decomp §4 / DEC-4, `[[engine-b-deferred-solid-yield]]`). Leave the `// DEFERRED:` seam intact.
  Terrain stays ∞-yield (always blocks); just make sure the *blocking* path is correct.
- ❌ Do NOT add horizontal gravity. Sideways = pure Pascal transmit (no weight added).
- ❌ Do NOT let a cell read past its 6 neighbours.

**Acceptance:** solid-shelf-shielding + wall-between-pools tests GREEN; hydrostatic/leveling from T2 still
GREEN; conservation exact; cheap tier green. Report which face cases you verified.
