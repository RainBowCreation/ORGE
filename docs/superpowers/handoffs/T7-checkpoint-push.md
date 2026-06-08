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

# T7 — Checkpoint + push (controller-run) · read 00-MASTER-RULES.md first

**Goal:** full green across engine + Java + both loaders, rebuild the native lib, push engine + bump parent
gitlink + push parent. Then the **in-game re-audit is the gate** (user-run).

**This task is run by the controller, not a fresh blind implementer** (it touches both repos + the .so +
push, per memory `[[always-push-rebuild]]`).

## STRICT DO (in order)
1. Engine: `cd /home/claude/ORGE-B/ORGE-ENGINE && bash tests/run_tests.sh` — cheap **and** heavy tiers green
   on the **real LUT**; the promoted `engine_b_stage2_leveling_test` is now GREEN in CHEAP_TESTS (not banked).
2. Rebuild `liborge.so` → `core/src/main/resources/natives/linux-x64/liborge.so` (the tracked-but-gitignored
   path; `git add -f` it). Use the engine's existing build/CMake path used for prior `.so` rebuilds.
3. Java: `cd /home/claude/ORGE-B && ./gradlew :core:test :core:integrationTest` on the real `.so` (skipped=0),
   then both loaders build (`:neoforge-1.21` + `:fabric-1.21` assemble/build).
4. Commit engine on `rebuild`, push engine; bump the parent gitlink to the new engine SHA, commit parent on
   `rebuild`, push parent. Update the build banner SHAs come for free (generateBuildInfo restamps).
5. Update memory `[[engine-b-canonical-pipeline]]` with the shipped SHAs + that leveling is now emergent.

## STRICT DON'T
- ❌ Do NOT push if ANY tier is red or skipped>0, or either loader fails to build.
- ❌ Do NOT push without the **adversarial conservation reviews of T2 (reflection) + T4 (swap)** done.
- ❌ Do NOT claim in-game success — that's the **user's** gate. Hand off with: "rebuild + runClient; banner
   should read parent <sha>/engine <sha>; test leveling, U-tube, lava-sink, place-displace."

**Acceptance:** all suites green, `.so` rebuilt+bundled, engine+parent pushed on `rebuild`, banner SHAs
fresh, memory updated. Report the two SHAs and the full green tallies.
