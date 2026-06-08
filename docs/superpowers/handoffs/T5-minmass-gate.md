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

# T5 — min_mass flow gate audit · read 00-MASTER-RULES.md first

**Goal:** verify (and fix if it diverges) that the same-species cohesion flux-gate exactly matches the
ratified rule, so the **min_mass** RED test goes GREEN and leveling never creates sub-min fragments.

**Authoritative:** decomp **§8.7** (the rule + the 130/875/250 worked example). Existing code seam:
`core/engine_b.hpp:556–575` (the flux-gate) + the `canDrain` pre-pass (~`358–394`).

## The rule (verbatim)
Same-species flow `f` from donor `D` to receiver `R` allowed **iff** `R+f ≤ max` **AND**
(`D−f == 0` **or** `D−f ≥ min`). Cross-species requires `f ≥ min`. Flows are **NOT** quantized to `min`; the
**only** ban is leaving or creating a cell at `0 < mass < min`. Worked: water `min125/max1000`, 130 onto
875 → blocked (125 leaves donor at 5; 130 overfills to 1005); 250 → `[1000, 125]`.

## STRICT DO
- Read the existing gate + canDrain pre-pass and check it against the rule above; write the 130/875/250 case
  as a test. Fix divergences minimally.
- Keep the `canDrain` exemption that lets an actively-emptying cell drain fully to 0 (→VACUUM) without a
  min_mass trail — that exists to stop the air-medium pinning / leveling stall; don't regress it.

## STRICT DON'T
- ❌ Do NOT make flows quantized to min_mass chunks — the rule is only "never land in (0,min)".
- ❌ Do NOT reintroduce a DECRYPT-side "cleanup that deletes sub-min" as the primary mechanism — it's a
  RESOLVE flux-gate (prevents creation), per the revised Task-10 decision. The DECRYPT VACUUM relabel of
  *fully*-drained cells stays (decomp §3) but is not the cohesion mechanism.

**Acceptance:** 130/875/250 test GREEN; leveling (T2) produces no `0<mass<min` cells; conservation exact;
cheap tier green. Report whether the existing gate matched or needed a fix (quote the diff).
