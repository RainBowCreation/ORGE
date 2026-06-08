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

# T8 — Spec-pure placement displacement (rip out the chain) · read 00-MASTER-RULES.md first

**⚠ GATE: do NOT start T8 until T7's leveling/relief is PROVEN in-game by the user.** The 1D injection-chain
is the working stopgap until then.

**Goal:** make placement inject the block + deposit the displaced incumbent as a transient over-max in the
best neighbour, and let RESOLVE relieve it over ticks (handles vertical / multi-column / partial / complex
uniformly). Then remove the bespoke 1D chain.

**Authoritative:** decomp §8 (displacement = the same emergent resolve); plan T8; the user decision
"Spec-pure: resolver does it." Existing stopgap: `core/sim_engine.hpp` `find_chain_hop` / `relocate_chain` /
`apply_injections`.

## STRICT DO
- Placement of a block into a fluid cell: write the block; deposit the displaced incumbent (species, mass, T)
  as a transient **over-max** in the best single neighbour (lowest-`p` reachable), allowed to exceed `max`
  for the tick — the EOS `^γ` wall + RESOLVE relieve it next ticks. Conserve exactly (nothing deleted except
  the lowest-`p` reachable compressible/vacuum, ledgered).
- Verify in headless tests that `[A,W,W]+L`, deep chains, vertical, multi-column, and the mixed basin all
  relieve correctly **over ticks** via RESOLVE (not via a path walk).
- Remove `find_chain_hop` / `relocate_chain` and simplify `apply_injections` to inject+deposit.

## STRICT DON'T
- ❌ Do NOT remove the chain before T7 is proven in-game (regression risk).
- ❌ Do NOT re-implement a multi-hop path DFS in the new path — displacement is the resolver's field solve.
- ❌ Do NOT let placement delete incumbent mass to "make room" — deposit as over-max, conserve.
- ❌ Do NOT special-case "is it a gas" for what gets consumed — it's the **lowest-`p` reachable** cell.

**Acceptance:** placement displacement emerges via RESOLVE for linear/vertical/multi-column/complex; chain
code removed; conservation exact; cheap+heavy green; in-game re-audit. Report the removed LOC + the cases
verified. Separate follow-on plan/PR.
