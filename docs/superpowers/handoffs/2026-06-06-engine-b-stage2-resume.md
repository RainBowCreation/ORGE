# RESUME HANDOFF — Engine B Stage-2 (displacement) → in-game audit

**Written 2026-06-06 before a PC shutdown.** A fresh session was told the user will say only "resume". This file + the memory `engine-b-velocity-field.md` are how you continue.

## TL;DR of where things stand

**Stage-2 conservative buoyant displacement is COMPLETE, verified, and PUSHED.** Fluid now moves through the live `orge:air` medium, conserving grand AND per-species mass exactly. Nothing is half-finished in code. The ONLY remaining gate is the **in-game audit**, which only the user can run.

- Track: `rebuild` (Engine B). Parent worktree `/home/claude/ORGE-B`; engine submodule `ORGE-ENGINE` is a git worktree on `rebuild`. (Engine A lives on `main` — do not touch.)
- **HEADs (all == origin, verify on resume):** parent `3596a04`, engine `ea92a31`, gitlink → `ea92a31`. Design doc `6b6450b`.
- Working trees were clean at shutdown.

## First actions on resume (do these, in order)

1. **Verify state is intact** (a reboot shouldn't change git, but confirm):
   ```
   cd /home/claude/ORGE-B
   git rev-parse HEAD            # expect 3596a04...
   git -C ORGE-ENGINE rev-parse HEAD   # expect ea92a31...
   git status --short           # expect clean
   git rev-parse origin/rebuild # expect == parent HEAD (already pushed)
   ```
   If the `.so` is missing/stale (rebuild can wipe build artifacts but the `.so` is git-tracked so it should be present), rebuild it:
   `bash ORGE-ENGINE/native/build_liborge.sh core/src/main/resources/natives/linux-x64/liborge.so`
2. **Re-present the in-game audit checklist below** to the user and ask them to run the dev client and report per-criterion results + pasted `ORGE-INJECT` / `/orge section` traces. The in-game audit is THE gate — headless has repeatedly missed live behavior this project.
3. **When the user reports audit results:** treat each failing criterion as a focused bug. Reproduce it HEADLESS FIRST in an AIR-medium probe (never vacuum) before any code change — this discipline is what finally worked. Gate every fix on grand + per-species conservation, not on "it spreads". Push engine→parent after each commit (standing authorization on `rebuild`; see memory `always-push-rebuild`). Run an adversarial subagent review before the final push (two reviews caught a real ship-blocker this session).

## What Stage-2 actually did (so you can reason about audit findings)

Replaced the Stage-1 `crossOccluded` no-flux with an emergent cross-species **cell SWAP** (engine_b.hpp), strictly inside Encrypt→Resolve→Decrypt:
- **Swap = full-cell permutation** of two cells' `(m, species, T, v)` → grand + per-species mass exact by construction; fabrication ("1 bucket→1740 kg", the 3× revert) is structurally impossible.
- **Gate = energy-lowering** (spec design l.98 "two cells trade contents when it lowers total energy"): gravitational PE `dPE = g*(m_i-m_j)*(y_j-y_i) < 0`. Reads mass+height, NOT `molar_mass` (guard l.224). Monotone ⇒ can't oscillate.
- **§2.4 absorb/reflect**: a swap only fires into a COMPRESSIBLE lower cell (`chi(Mlow) >= 0.5`, EOS descriptor) ⇒ lava sinks through air but PINS on water (`[L,A,W]→[A,L,W]`, accept TEST4). χ values: water/lava/steam = 0, air = 0.999.
- Selection in Resolve (conflict-free greedy, each cell ≤1 swap/tick); applied in Decrypt. Swaps are vertical ⇒ always intra-chunk. Lateral spread is same-species/additive (cross-chunk).
- **Also fixed a pre-existing species leak** the old weak `safe_state` test masked: emptied cells relabeled to the heaviest STATIC neighbour (air→water/lava). Now relabel by ACTUAL inflow donor + a **vacuum-claim pre-pass** that locks each vacuum cell to its dominant inflow species and blocks foreign-species inflow at the donor.

Specs (the law): `docs/superpowers/specs/2026-06-04-engine-b-velocity-field-design.md` (§2.4, §4.1–§4.4, guard l.224), `docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md` (§C.5, §D.4/§D.5, §E, §J.5), and the Stage-2 design `docs/superpowers/specs/2026-06-06-engine-b-stage2-displacement-design.md`.

Tests: engine cheap tier 14/14 (`tests/engine_b_displacement_test.cpp` = FALL/STABLE/PIN/INVERT/MIXREFILL/SWAPWALL, all per-species exact; `engine_b_safe_state_test`, `engine_b_accept_test`). Java: `:core:test` 395/0 + `:core:integrationTest` 24/0 skipped=0 on real `.so`. Both loaders build. Run: `cd ORGE-ENGINE && bash tests/run_tests.sh`; `./gradlew :core:test :core:integrationTest --rerun-tasks`; `./gradlew :neoforge-1.21:build :fabric-1.21:build`. Golden regen pattern (after any trajectory change): temporary `__regenGolden` @Test in `core/src/test/java/net/rainbowcreation/orge/engine/ResidentLutParityIT.java` writing `core/src/test/resources/golden/resident-lut-step.bin`, run, remove.

---

## IN-GAME AUDIT CHECKLIST (give this to the user)

Engine `rebuild` ea92a31. Run NeoForge or Fabric dev client. Report PASS/FAIL + paste `ORGE-INJECT` traces and `/orge section` / `/orge get`.

**THE keystone (verify first):**
1. **1 water bucket → falls + bounded + total mass constant.** Place one bucket in open air above a floor: it must FALL to the floor and form a ~1-cell pool, NOT glow/spread across the map, total mass constant. This is the criterion that reverted 3×.

**Conservation (sacred):**
2. **No air-eating / no vanishing.** Displaced air rises and is conserved (not converted to water, not deleted). `/orge section` movable-mass total before == after.
3. **No ghosts.** No 0 kg/0 K or 1000 kg/6000 K phantom cells; no glowing full blocks where there's thin fluid.

**Displacement behaviors:**
4. **Fall through air** — water and lava both sink through air to a floor/surface.
5. **Hydrostatic stability** — a settled pool with air above does NOT churn/invert; water stays put.
6. **Lava pins on water** — drop lava onto a water pool: it sinks through the air and rests ON the water (does NOT sink through it).
7. **Leveling** — a CONNECTED water body equalises via same-species flow.

**Known NOT in this stage (don't flag as bugs):**
- `break→vacuum` (#6) Java injection-seam race — pending, separate task.
- Gas expanding to fill a void against gravity (accept TEST8) — needs EOS calibration (Stage-4 §G.2).
- Open-pool buoyant inversion / circulation THROUGH an incompressible liquid (emergent-circulation stage).
- Cross-air-gap leveling and multi-arm equalisation (separate pools stay separate).
- Falling parcels descend ~1 cell/tick (steady, not accelerating) — by design for now.

**If any of 1–3 fail, that's a conservation ship-blocker — paste the full trace.**

## Banked / next candidates (after the audit passes)
- break→vacuum #6 (Java seam race: wakeBreak→enqueueRemoval→vacuum clobbered by a neighbour-notify capture).
- accept TEST8 gas-into-void expansion (EOS §G.2 Stage-4 calibration).
- open-pool buoyant inversion/circulation through a liquid; cross-air-gap + multi-arm leveling.
- Nits: `chi=0.5` is a hard threshold (wide margin); swap clamps snapshot- vs post-step velocity.
