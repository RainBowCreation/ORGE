# 🔍 HANDOFF — Independent DRIFT AUDITOR for T2 + T2.5 (Engine-B RESOLVE-force rebuild)

You are a **fresh, independent drift auditor.** Your job is NOT to implement. It is to determine whether the
T2 (emergent pressure) and T2.5 (horizontal flow) implementation **matches the spec**, or has **drifted** —
and especially to catch the failure mode that already beat this project twice: **a test that is GREEN for the
wrong reason.** You report drift; you do not fix it.

> ⚠ You are auditing work that has drifted REPEATEDLY. Trust the **spec**, never the C++ or code comments.
> "All tests green" is NOT evidence of correctness here — green-for-the-wrong-reason is the recurring bug.

## Repos / state
- Engine: `/home/claude/ORGE-B/ORGE-ENGINE` (branch `rebuild`). Parent: `/home/claude/ORGE-B`.
- Engine origin/rebuild was `814869b` (T2.5 v1, the drifted attempt). The **T2.5-REV** rework is in flight:
  `83bc5a2` = "revert p_surf" (step 1 done); §8.5 lateral + `p_dyn` density fix land after. **Audit whatever
  is at engine HEAD when you start** — record the SHA. If HEAD is still mid-rework, audit what exists and flag
  what's unfinished rather than waiting.

## READ FIRST (do not skip — these ARE the law and the history)
All in `/home/claude/ORGE-B/docs/superpowers/handoffs/` (durable) — do not re-derive, read them:
- `00-MASTER-RULES.md` — spec>code, DO-NOT-TRUST list, 🧨 stale-test triage, conservation non-negotiables.
- `T2.5-horizontal-flow.md` → `T2.5-REV-revert-and-unify.md` → `T2.5-REV-NOTE-B-pdyn-density.md` — the brief
  and its two corrections (read in that order; later overrides earlier).
Authoritative spec (in repo `docs/superpowers/specs/`): `…-CANONICAL-pipeline.md`,
`…-vector-map-decomposition-design.md` (**§8**, esp **§8.4/§8.5/§8.6**), `…-unified-formula.md`
(**§B.1** EOS/free-surface, **§C.5** gravity-reflects-into-pressure, **§J.5** hydrostatic head).
Memory context: `[[engine-b-resolve-force-handoff]]`, `[[engine-b-canonical-pipeline]]`.

## The specific drifts to confirm or clear (the audit checklist)
For each, state: **MATCHES SPEC / DRIFTED / GREEN-FOR-WRONG-REASON / UNVERIFIABLE**, with the spec citation.

1. **`p_surf` fully gone.** `git diff 8701e36 -- core/engine_b.hpp` must show **no** `p_surf` / `c_head` /
   `c_head_mob` residue. The free-surface head was an invented 4th driver (forbidden). Confirm it's out.
2. **`p_dyn` is now density/fill-dependent (the real SCENE-1 fix, NOTE-B).** Spec §C.5/§J.5: a resting cell's
   **weight m·g** reflects off the floor into pressure ⇒ **mass-dependent** ⇒ 1000-cell builds higher `p` than
   500-cell at equal height. The T2 bug was mass-INDEPENDENT `p` (gravity as bare acceleration `e.ugy=uy-dt*g`
   at ~`engine_b.hpp:193` + constant `p_ac_scale` where `c²ρ` needs cell ρ). Verify the fix restores
   ρ-weighting AND did not just re-bolt a parallel field. **⚠ My (controller's) NOTE-B prescription is a
   HYPOTHESIS reasoned from C++ — audit whether it's actually what the spec requires, not whether the agent
   copied my note.**
3. **No new runaway / per-species leak.** The original T2 free-surface fix (`95a4841`) cured a `maxP→∞` +
   per-species AIR leak. A ρ-aware `p_dyn` risks reviving it. Check: walled hydrostatic column settles with
   `maxP = ρg·depth` EXACT; grand + per-species mass exact over a long soak.
4. **SCENE 1 & SCENE 2 green for the RIGHT reason.** Re-run the two repro scenes (recipe below). SCENE 1
   (same-height 1000|500 → 750|750) must level **via the one pressure field**, not a special term. SCENE 2
   (dam-break / pool spreads into air) must slump **via §8.5 lateral lowest-`p` displacement** (§8.4 gradient
   drives, §8.5 transports cross-species mass into the low-`p` air). Confirm conservation exact, no
   `0<mass<min` residue, no max overshoot.
5. **§8.5 lateral is spec-faithful, not a hack.** No new Globals constant, no stored-momentum kick, no
   separate mass channel; flux-XOR-swap with T4's vertical swap (a face fluxes OR swaps, never both); yield
   gate applies sideways. Driver = the corrected `p_dyn`, not a bespoke term.
6. **`YG_B` re-validated for real (T3's vacuous-pass debt).** Original `YG_B` passed vacuously (lateral flow
   was dead, so pools "didn't level through the wall" regardless of the wall). With lateral flow now alive:
   remove-wall must LEVEL, restore-wall must BLOCK. If the test still can't distinguish those, it's still
   vacuous — flag it.
7. **Regression breadth, not new-scene green.** The recurring failure is "made my scene green, silently broke a
   neighbor." Run the FULL cheap tier; confirm U-tube, connected-leveling, hydrostatic rest, yield_gate, eos,
   swap_resistance, conduction, conservation_levels ALL still pass — and pass for their original reasons (spot
   check that they assert MOVEMENT/values, not just "bounded").

## Repro recipe (your independent oracle — author yourself, do NOT trust the in-repo tests blindly)
Build any scratch test against the real LUT: `g++ -std=c++20 -O2 -I. -Icore -Itests yourtest.cpp -pthread`.
Use `#include "engine_b_real_lut.hpp"`; `rlut::make_real_lut(w)`; `step_world_b(w,w.materials,M.G,0.5f)`.
- SCENE 1: stone floor y=20 x=2..11; water(1000)@(5,21), water(500)@(6,21); fill rest with air(1.2);
  ~400 steps → expect 750|750, water mass exact.
- SCENE 2: stone floor y=20 x=2..10; water(1000)@(2, y=21..24) [4-tall column]; air elsewhere; ~400 steps →
  expect the column to slump and spread to several floor columns; mass exact.
(Controller confirmed on `814869b`: SCENE 1 = 750|750 but via the invented `p_surf`; SCENE 2 = still 1/9 =
broken. Your job: confirm the REV does both for the RIGHT reason.)

## STRICT — auditor conduct
- ❌ Do NOT edit production code or "fix" anything. Author only throwaway scratch tests (delete them after).
- ❌ Do NOT learn the model from C++/comments; cite the spec section for every verdict.
- ❌ Do NOT accept "tests pass" as the conclusion — independently re-derive the observable and check WHY it
  passes. A test that asserts only "bounded" or reuses a vertical-only geometry is a wrong-reason green.
- ✅ DO escalate genuine spec gaps you find (don't invent the answer). ✅ DO name exact files/lines/SHAs.
- ✅ If you cannot determine something headless (e.g. perf, real-LUT live path), say UNVERIFIABLE and defer to
  the in-game audit — do not guess.

## Report format
A table: each checklist item (1–7) → verdict (MATCHES / DRIFTED / WRONG-REASON-GREEN / UNVERIFIABLE) + spec
cite + evidence (SHA/file:line/scene output). Then: the single biggest residual drift risk, and whether the
work is safe to gate via **in-game re-audit** (the real oracle) before T5. Keep it factual; you are the
skeptic of record.

## Suggested skills
- `superpowers:verification-before-completion` — the core mindset (verify the observable, distrust green).
- `superpowers:debugging` IF you find a wrong-reason-green and want to localize the mechanism (do NOT fix).
- `superpowers:requesting-code-review` — framing for the adversarial conservation pass on `p_dyn` + §8.5.
Do NOT use implementation skills — you audit, you don't build.
