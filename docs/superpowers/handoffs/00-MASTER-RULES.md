# Engine-B RESOLVE-force rebuild — MASTER RULES (read FIRST, every task references this)

> ## ⛔⛔ READ [`../DESIGN-LAW.md`](../DESIGN-LAW.md) BEFORE THIS FILE — it is THE design, frozen.
> Pressure is **ONE** scalar `P` per cell; force is **ONE** Vector3 built from the **6 face-neighbors**
> (`P_self − P_neighbor` per face); the **same rule in all 6 directions**; one step ENCODE→RESOLVE→DECODE.
> The law outranks this file, the working spec, AND the code. **"Spec is truth" below means the
> DESIGN-LAW is truth** — a *working spec* that contradicts the law is itself drift. Any second pressure
> number, or any force rule that differs by direction, is drift → reject (the engine's current `A+B` split
> is tracked DEBT, never design). A reviewer's first question is *"does this match the DESIGN-LAW?"*, never
> *"does the spec match the code?"*.

**You are an implementer (or reviewer) in a subagent-driven build.** This file is the law for the whole
track. Each task file (`T1…T8`) is your specific brief. **Read this file, then your task file, then ONLY
the authoritative specs named below. Do NOT read the C++ to learn the design.**

---

## ⛔ THE #1 RULE: the SPEC is truth, the CODE is the bug

The `rebuild` branch code has drifted from the design **repeatedly**. If code/comments disagree with the
spec, **the spec wins and the code is the bug to fix** — never replicate a code pattern because it's there.
**Do not reverse-engineer the physics model from C++ or from code comments.** Comments frequently describe
*old* behaviour that was already superseded.

## ✅ AUTHORITATIVE docs — these, and ONLY these, define the model
Read in this order (paths relative to `/home/claude/ORGE-B`):
1. `docs/superpowers/specs/2026-06-07-engine-b-CANONICAL-pipeline.md` — THE LAW (one sentence + facts).
2. `docs/superpowers/specs/2026-06-07-engine-b-vector-map-decomposition-design.md` — **especially §8**
   (the refined force model: one vector, emergent overburden via reflection, yield gate, swap=resistance,
   min_mass rule). **§8 supersedes any older framing in the same file or elsewhere.**
3. `docs/superpowers/plans/2026-06-07-engine-b-overburden-head-resolve.md` — the task plan (T1…T8).
4. `docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md` — per-channel MATH only (§B–§D, §C, §J.5).
   Use for flux *arithmetic*, NOT for the depth-pressure mechanism (its single `E` vector + EOS-sourced
   depth pressure are superseded by the canonical multiple-maps + reflection model).

## 🚫 STALE / DO-NOT-TRUST — never pattern-match or copy these
| Artifact | Why it's poison |
|---|---|
| `core/force_advect.hpp` (`forcePass`/`advectPass`/`stepForceAdvect`) | 3 local passes + purely-local EOS, **no resolver**. This is the drift that **broke leveling**. Do not read it for the model, do not call it. |
| `core/engine_b.hpp:633` `p_face = ½(eos p_i + eos p_j)` | The **bug** (local-EOS depth = 0 at rest under max==default). It is what T2 REPLACES — do not treat it as the intended design. |
| `swap_threshold` global constant (`≈1e3`) in `engine_b.hpp` | DRIFT. The swap barrier is the **pair's material resistance** (viscosity/cohesion), not a constant. T4 removes it. |
| any `chi` / `chi(lower) ≥ 0.5` swap gate | DELETED design. Reorder = force-difference vs pair-resistance, never a chi gate. |
| any "Σ mass above" / column-sweep / "overburden head pre-pass" | REJECTED (non-local + wrong through solids). Pressure EMERGES over ticks from gravity+reflection in the ONE pass. Decomp §8.1/§8.2. |
| single bundled energy vector `E = ρ·h·w` and its `w = E·(1+dtλ)/(ρh)` un-bundle | superseded by 3 un-mixed maps (decomp §1). |
| code comments describing the above | comments lag the design — verify every claim against the specs, don't follow comments. |
| `find_chain_hop` / `relocate_chain` (`sim_engine.hpp`) | the 1D injection-chain STOPGAP. Do NOT extend it, do NOT remove it until T8 (after T7 proves leveling in-game). |

## 🧱 The model in one paragraph (so you can sanity-check yourself)
ENCRYPT→RESOLVE→DECRYPT in one `step_world`, carrying 3 un-mixed maps (drive `w⃗`, pressure scalar `p`,
thermal `h`/`T`). **RESOLVE is ONE snapshot, ONE antisymmetric 6-face pass.** Depth-pressure is NOT a local
EOS term and NOT a column sum — it **emerges over ticks**: gravity adds downward momentum, an incompressible
cell/floor reflects the blocked momentum into pressure (§C.5), which the same vector field carries up &
sideways next tick → hydrostatic after ~H ticks. The force is a **vector** assembled from the 6 neighbour
**scalar** pressures + gravity; `Fx,Fy,Fz` come from ONE netting (no axis split, no extra pass). Transmission
is **yield-gated**: fluid passes `F_in+g·m` down / `F_in` sideways; a **locked solid bears+blocks** (shields
below); vacuum/free-surface resets. Cross-species reorder of two FULL immiscible cells = a **swap** (the
discrete branch of the same vertical force) when buoyant `(ρ_up−ρ_low)·g·V` > **pair resistance**; overburden
cancels (Archimedes). `max_mass == default_mass` for all solids/liquids — **never widen it, never add an EOS
compression band.**

## 🧨 STALE TESTS — triage, never blindly satisfy (READ THIS — the user flagged it)
**Some existing tests were written against the OLD design. A failing existing test may be CORRECT-failing
(the test asserts superseded behaviour), not a bug in your change.** When an existing test goes red after
your change, **TRIAGE against the spec — do not reflexively "fix" the code to pass it, and do not reflexively
edit the test to pass either:**

1. Does the **current spec** (canonical + decomp §8) require the behaviour the test asserts?
   - **Yes** → it's a **genuine regression**, fix your code.
   - **No** (it asserts old/dead behaviour) → the **TEST is stale**: re-author it to the spec or RETIRE it,
     and in the commit message **cite the spec section + why it was stale**. Never silently delete; never
     keep cementing a dead assertion just to stay green.
2. **If you cannot tell** whether it's a regression or a stale test → **ESCALATE** to the controller/user.
   Do not guess, do not "make it pass."

**Tests expected to be STALE here (promote / re-author / retire, don't satisfy as-is):**
- `engine_b_stage2_leveling_test` — was **RED-by-design / BANKED** because leveling didn't work. With T2 it
  should become **GREEN and a gate** (move it out of `BANKED_TESTS` into `CHEAP_TESTS`). A still-RED here is
  the *target to flip*, not a regression to preserve.
- any test asserting the **`swap_threshold` constant** or a specific threshold value → stale (T4 makes the
  barrier the pair resistance); re-author to assert the resistance behaviour.
- any test asserting a **`chi` swap gate**, **cross-species interfaces are no-flux/static**, or **rest
  pressure stays 0 / no leveling** → stale (those are the dead local-EOS / chi design). Re-author to spec.
- the **determinism golden** (`resident-lut-step.bin`) legitimately changes when RESOLVE changes — that is
  **expected, not a regression**: regenerate it (T2) with sanity asserts. Do not "fix" code to match an old
  golden.

**Conversely:** do NOT weaken or delete a test that asserts a *current-spec* invariant (conservation, no
fabrication, no overshoot, min_mass cohesion) just because it's inconvenient — that hides real bugs.

## 🔒 Conservation — NON-NEGOTIABLE (every task)
- **Grand mass AND per-species mass EXACT every step** (antisymmetric flux + permutation swaps).
- **No fabrication.** No `max_mass` overshoot beyond a transient that relaxes next tick.
- A swap is a pure permutation. The pressure scalar `p` is derived from the **pre-step snapshot** only.
- The **only** thing ever consumed is the lowest-`p` reachable compressible/vacuum cell (ledger it).

## 🧪 Process (subagent-driven TDD)
- **Write the failing test FIRST**, watch it go RED, then implement to GREEN. Never write code before a red test.
- Tests use the **REAL material LUT** (`tests/engine_b_real_lut.hpp`), **never synthetic Materials** —
  synthetic `chi=0.5` masked a dead engine before. **Assert it MOVED/leveled, not just "bounded".**
  Real LUT (min/default/max): water `125/1000/1000`, lava `400/3100/3100`, air `1.0/1.2/1000`,
  stone `2500/2500/2500`, steam `0.6/0.6/0.6`. g=10, V=A=Δx=1, dt typically 0.25.
- **Engine tests:** `cd /home/claude/ORGE-B/ORGE-ENGINE && bash tests/run_tests.sh`. Register any new test
  in the **CHEAP_TESTS** array in `tests/run_tests.sh`. Inspect that script for the exact compile flags.
  Cheap tier is currently green (≥21/21) — keep it green.
- **Do NOT push.** The controller pushes, rebuilds `liborge.so`, and bumps the parent gitlink (T7 only).
- Work on branch `rebuild` in **both** repos: engine `/home/claude/ORGE-B/ORGE-ENGINE`, parent
  `/home/claude/ORGE-B`. Commit your task on `rebuild` with a clear message; report the SHA.
- After your implementation: a **spec-compliance review** then a **code-quality review** (controller runs
  them). T2 (reflection) and T4 (swap) additionally get **adversarial conservation review** before T7 push.

## 📋 Report format (every implementer)
Status `DONE | DONE_WITH_CONCERNS | NEEDS_CONTEXT | BLOCKED`, the commit SHA, **verbatim** test output
(cheap-tier count + your new tests), the per-species conservation numbers, and any spec section you relied
on. Never claim success without the test output. If the design as written can't be met, **escalate — do not
invent an off-spec mechanism (no EOS band, no global Σ, no Poisson solve) without user sign-off.**

## Suggested skills
- `superpowers:test-driven-development` (every task — red first).
- `superpowers:subagent-driven-development` (the controller's loop; you are a dispatched subagent).
- `superpowers:requesting-code-review` (reviewer subagents).
- `superpowers:finishing-a-development-branch` (after T7).
