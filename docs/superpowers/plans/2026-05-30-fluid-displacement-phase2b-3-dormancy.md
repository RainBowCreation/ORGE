# §10 Phase-2b (3/3) — Dormancy, event-driven wake, scratch pool & void mask — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a calm world re-simulating every cell at 4 Hz forever: make `world.snapshot(range)` step only an **active set** of sections, settle sections out of it with a per-pass countdown (flow-dormant / thermal-dormant / fully asleep), and **wake** them explicitly on an exhaustive, unit-tested trigger set (block edit/bucket, source-roster change, neighbour seam flux, new-in-range) — plus reuse worker scratch buffers and a secondary cell-level `void` mask in the kernel.

**Architecture:** All new orchestration state is **scheduler-side, server-thread-confined, pure** (mirrors `CellMaterialTracker` / `MassSnapshot`): a per-section, per-pass settle countdown (`ActiveSet` + `SettleCountdown`) that piggybacks the existing post-step writeback loop (`cleanMass`/§9 already iterate the 4096 cells, so `max|Δmass|`/`max|ΔT|` are near-free). `MinecraftThermalWorld.snapshot` intersects the player-range sphere with the active set instead of stepping the whole sphere. Wake is explicit behind a pure `WakeSink` seam, fed by common Architectury `BlockEvent`/`PlayerEvent.FILL_BUCKET` events plus an `@ExpectPlatform` `WakePlatform` for low-level neighbour/`/setblock` block-update notifications. The cell-level `void` active-mask is a SECONDARY engine optimization (one ENGINE task, bit-identical kernel↔`sim_engine`) that hard-skips genuine `void` only — never a quiet-but-conductive cell (the void-vs-dormant correctness landmine).

**Tech Stack:** Java 21, Architectury multiloader (MC 1.21.11, **Mojang mappings** — `Identifier` == `ResourceLocation`; `ServerPlayer.level()`; `level.dimension().identifier()`); Architectury common events (`dev.architectury.event.events.common.BlockEvent`, `PlayerEvent.FILL_BUCKET`) + `@ExpectPlatform`; JUnit 5; C++20 header-only engine (g++). Build env: `JAVA_HOME=/home/claude/jdk21`.

**Spec:** `docs/superpowers/specs/2026-05-30-fluid-displacement-phase2b-design.md` — this plan covers **Decisions 11 (section-level per-pass dormancy + event wake + the void-vs-dormant rule) and 13 (orchestration perf: scratch-buffer pool, and the cell-level void mask noted under 11)**.

---

## Two repositories — read this first

| Repo | Path | `git add` from |
|---|---|---|
| **MAIN** | `/home/claude/ORGE` (branch `rebuild`) | run git from `/home/claude/ORGE` |
| **ENGINE** | `/home/claude/ORGE/ORGE-ENGINE` (git **submodule**, own `.git`) | run git **inside** `ORGE-ENGINE/` |

This plan is **mostly MAIN-only** (Tasks 1–7), with **one ENGINE task** (Task 8: the cell-level void mask) and **one ENGINE→MAIN integration task** (Task 9: rebuild `liborge.so` + bump the MAIN gitlink). ENGINE tasks `cd /home/claude/ORGE/ORGE-ENGINE` before `git`. **Never `git add` across the boundary** (the submodule is its own repo).

**Push policy (standing authorization — do not ask):**
- **MAIN:** push `origin rebuild` after **every** MAIN commit (the user tests from `origin/rebuild`).
- **ENGINE:** commit per task but **do NOT push mid-plan**. The **last** ENGINE-touching task (Task 9) merges/pushes the ENGINE work branch, rebuilds `liborge.so`, and bumps the MAIN gitlink (mirror Plan-1 Task 11).

Every commit message ends with the trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

**Plans 1 and 2 must be landed first.** This plan consumes their symbols (`StepResult.material()` → `char[]`, `BatchMarshaller.flatten`, the `pendingMaterials` LUT, the §9 `massConservedPerSpecies` gate). If `Scheduler.writeBackResults` does not yet read `r.material()`, land Plans 1–2 first.

## Conventions for every task

- **MAIN one test class:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "<FQCN>" --rerun-tasks`
- **MAIN full core suite:** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
- **MAIN all 3 loaders compile (the gate):** `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
- **ENGINE single test:** `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/<file>.cpp -o build/<file> -pthread && ./build/<file>`
- **ENGINE full suite:** `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh`
- **ENGINE native build:** `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
- TDD per task: write the failing test → run it to confirm it fails (with the expected message) → implement the minimal code → run it to pass → run the loader gate (MAIN, when production code changed) → commit **(+ push for MAIN)**.
- C++ harness: each test is a `void test_x()` using `TH_CHECK`, `TH_CHECK_MSG`, `TH_CHECK_CLOSE(got,want,rel)`, registered in `main()` via `run("name", test_x)`; the binary exits non-zero if any check fails.
- **Bit-identicality is sacred (ENGINE):** the void-mask change in `orge_kernel.hpp` MUST be mirrored in `sim_engine.hpp` in the SAME task/commit; the parity test stays green.

## Type consistency with Plans 1 & 2 (CRITICAL — reviewed against them)

These symbols come from Plans 1/2 and are used verbatim here. Do not rename them.

| Symbol | Shape | Where |
|---|---|---|
| `StepResult` | `record StepResult(float[] temperature, float[] mass, char[] material)` (+ 2-arg back-compat ctor → `material == null`) | `core/.../engine/StepResult.java` |
| `StepResult.material()` | returns `char[]` (per-cell species, the engine's `matOut`) | accessor |
| `ThermalWorld.BatchEntry` | `record BatchEntry(Identifier dimension, SubchunkKey key, StepTask task)` | `core/.../scheduler/ThermalWorld.java` |
| `ThermalWorld.Batch` | `record Batch(List<BatchEntry> entries, List<Material> lut)` | same |
| `SubchunkKey` | `record SubchunkKey(int cx, int sectionY, int cz)` | `core/.../section/SubchunkKey.java` |
| `OrgeEngine.PASS_CONDUCTION` / `PASS_ADVECTION` | `int` bits `1` / `2` | `core/.../engine/OrgeEngine.java` |
| `CellMaterialTracker.forgetColumn(Identifier,int,int)` | per-column unload prune | mirror its lifecycle exactly |
| `SectionStoreManager.setColumnUnloadListener(ColumnUnloadListener)` | `(Identifier dim,int cx,int cz)->void` | `core/.../section/SectionStoreManager.java` |
| native LUT names | `minFlow`, `maxMass`, `gas`, `fullMass`, `fluid`, `cond` | `BatchMarshaller.Flat` / `MatLUT` |
| ENGINE kernel | `step_section_with_halo(matIx, mass, Tin, haloT, haloMat, haloMass, lut, dt, passes, Tout, massOut, matOut)`; constants `SEC=16`, `SEC_N=4096`, `sidx(x,y,z)`, `ADV_EPS_MASS` | `orge_kernel.hpp` |

**Settle signal (Plan-1 Task 6 guarantee):** a settled field reports `max|massOut[i]-mass[i]| < ε` and `max|Tout[i]-Tin[i]| < ε`. This plan computes those reductions in the writeback loop; it does **not** add any new engine output.

---

## Dormancy model (single source — Spec Decision 11)

Per section, two independent **per-pass** countdowns (the user's proven decaying-cell method, lifted cell → section):

| pass | dormant when | countdown field |
|---|---|---|
| **flow** (advection, 4 Hz) | `max|Δmass| < EPS_MASS` for `K_SETTLE` consecutive advection steps | `flowCountdown` |
| **thermal** (conduction, 1 Hz) | `max|ΔT| < EPS_TEMP` for `K_SETTLE` consecutive conduction steps | `thermalCountdown` |

- The **countdown (not instant sleep) is the hysteresis** — a section near equilibrium that twitches once doesn't flicker active↔dormant.
- **Both** passes dormant ⇒ the section is **fully asleep** and is dropped from the snapshot active set entirely.
- **Either** pass live ⇒ only that pass runs for the section (per-pass independence: a flow-dormant but thermal-active section still conducts).
- **Wake** resets the relevant countdown(s) back to `K_SETTLE` and re-adds the section to the active set.
- Constants (audit-tunable; live in `SettleCountdown`):

```java
public static final int   K_SETTLE = 3;        // consecutive quiet steps before a pass sleeps
public static final float EPS_MASS = 1e-3f;    // kg, "no mass moved" threshold (matches Plan-1 Task-6 settle test)
public static final float EPS_TEMP = 1e-3f;    // K,  "no heat moved" threshold
```

**Active-set semantics:** a section is in the active set if **either** pass is live. `snapshot(range)` returns sections in `(player-range sphere) ∩ (active set)`, plus any section a wake trigger injected since last cycle. A never-seen section (new-in-range) starts active (both countdowns = `K_SETTLE`).

---

## File structure

| File | Repo | Action | Responsibility |
|---|---|---|---|
| `core/.../scheduler/SettleCountdown.java` | MAIN | create | pure per-pass countdown record + step/reset/asleep logic; constants `K_SETTLE`/`EPS_MASS`/`EPS_TEMP`. |
| `core/.../scheduler/ActiveSet.java` | MAIN | create | pure per-dimension/section active-set map of `SettleCountdown`; `markActive`, `noteFlowDelta`, `noteThermalDelta`, `isFlowDormant`, `isThermalDormant`, `forgetColumn`, `intersect(range-keys)`. Mirrors `CellMaterialTracker`. |
| `core/.../scheduler/WakeSink.java` | MAIN | create | pure seam the loaders push wakes into: `wake(Identifier dim, int blockX, int blockY, int blockZ)` + `wakeSection(Identifier,SubchunkKey)`. |
| `core/.../scheduler/SchedulerActiveSetTest.java` etc. | MAIN | create | unit tests for the above (exhaustive wake triggers). |
| `core/.../scheduler/MinecraftThermalWorld.java` | MAIN | modify | hold the `ActiveSet`; `snapshot` intersects the sphere with the active set; writeback piggybacks `max|Δ|` into the countdowns. |
| `core/.../scheduler/Scheduler.java` | MAIN | modify | feed per-pass deltas to the active set in `writeBackResults`; skip a section's pass when that pass is dormant (the snapshot already drops fully-asleep ones, but a coincident tick must respect per-pass dormancy). |
| `core/.../scheduler/Worker.java` *(or a new `ScratchPool`)* | MAIN | create `ScratchPool` | reuse `temp[]`/`mass[]`/`material[]` on the worker thread (Decision 13c). |
| `core/.../engine/NativeEngine.java` / `BatchMarshaller.java` | MAIN | modify | source the per-step output arrays from the `ScratchPool`. |
| `core/.../platform/WakePlatform.java` | MAIN | create | `@ExpectPlatform` seam for low-level block-update notifications (`/setblock`, neighbour updates, piston). |
| `fabric-1.21/.../platform/fabric/WakePlatformImpl.java` | MAIN | create | Fabric block-update hook. |
| `neoforge-1.21/.../platform/neoforge/WakePlatformImpl.java` | MAIN | create | NeoForge block-update hook. |
| `core/.../Orge.java` | MAIN | modify | construct the `ActiveSet`/`WakeSink`, wire `BlockEvent.PLACE/BREAK` + `PlayerEvent.FILL_BUCKET` + source-roster + `WakePlatform` + new-in-range to the sink. |
| `ORGE-ENGINE/orge_kernel.hpp` | ENGINE | modify | cell-level `void` active-mask: skip genuine void in conduction + advection (bit-identical). |
| `ORGE-ENGINE/sim_engine.hpp` | ENGINE | modify | mirror the void mask. |
| `ORGE-ENGINE/tests/test_phase2b_voidmask.cpp` | ENGINE | create | void-skip + the void-vs-dormant correctness test (a settled-but-conductive cell still receives heat). |
| `core/src/main/resources/natives/linux-x64/liborge.so` | MAIN | replace (Task 9) | rebuilt artifact. |

**Task ordering rationale:** pure logic first (Tasks 1–2: `SettleCountdown`, `ActiveSet` + `WakeSink`), then wire snapshot to the active set (Task 3), then the per-loader wake impls + Orge wiring (Tasks 4–5: block-update/bucket/range/source-roster + seam-flux), then the scratch pool (Task 6), then a MAIN verification gate (Task 7), then the ENGINE void mask (Task 8), then ENGINE merge/rebuild/gitlink-bump (Task 9). Pure logic is committed and reviewable before any Minecraft binding.

---

## Task 1 [MAIN]: pure `SettleCountdown` — per-pass settle/wake state

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/SettleCountdown.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SettleCountdownTest.java`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/SettleCountdownTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettleCountdownTest {

    @Test
    void freshSectionIsFullyActive() {
        SettleCountdown c = SettleCountdown.active();
        assertFalse(c.flowDormant());
        assertFalse(c.thermalDormant());
        assertFalse(c.asleep());
    }

    @Test
    void flowGoesDormantAfterKQuietAdvectionSteps() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            assertFalse(c.flowDormant(), "still live before K quiet steps");
            c = c.noteFlow(0f); // delta below EPS_MASS
        }
        assertTrue(c.flowDormant(), "dormant after K consecutive quiet advection steps");
        assertFalse(c.thermalDormant(), "thermal pass independent — still live");
        assertFalse(c.asleep(), "not fully asleep while thermal is live");
    }

    @Test
    void aboveThresholdDeltaResetsTheFlowCountdown() {
        SettleCountdown c = SettleCountdown.active();
        c = c.noteFlow(0f).noteFlow(0f);            // 2 quiet steps
        c = c.noteFlow(SettleCountdown.EPS_MASS * 10f); // a real move resets
        for (int i = 0; i < SettleCountdown.K_SETTLE - 1; i++) c = c.noteFlow(0f);
        assertFalse(c.flowDormant(), "reset means it takes K more quiet steps");
        c = c.noteFlow(0f);
        assertTrue(c.flowDormant());
    }

    @Test
    void bothPassesDormantMeansAsleep() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) c = c.noteFlow(0f);
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) c = c.noteThermal(0f);
        assertTrue(c.flowDormant());
        assertTrue(c.thermalDormant());
        assertTrue(c.asleep(), "both dormant -> fully asleep -> drop from schedule");
    }

    @Test
    void wakeFlowRevivesOnlyTheFlowPass() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { c = c.noteFlow(0f); c = c.noteThermal(0f); }
        assertTrue(c.asleep());
        c = c.wakeFlow();
        assertFalse(c.flowDormant(), "flow revived");
        assertTrue(c.thermalDormant(), "thermal still dormant (per-pass wake)");
        assertFalse(c.asleep());
    }

    @Test
    void wakeAllRevivesBothPasses() {
        SettleCountdown c = SettleCountdown.active();
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { c = c.noteFlow(0f); c = c.noteThermal(0f); }
        assertTrue(c.asleep());
        c = c.wakeAll();
        assertFalse(c.flowDormant());
        assertFalse(c.thermalDormant());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SettleCountdownTest" --rerun-tasks`
Expected: compile FAIL — `SettleCountdown` does not exist.

- [ ] **Step 3: Implement `SettleCountdown`**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/SettleCountdown.java`:

```java
package net.rainbowcreation.orge.scheduler;

/**
 * Per-section, per-pass settle countdown (DESIGN §10 Decision 11) — the user's proven decaying-cell
 * method lifted from cell to section grain. Immutable value: each step returns a new instance, so the
 * active-set map can swap the reference without aliasing surprises (server-thread confined, no locking).
 *
 * <p>A pass (flow / thermal) is <b>dormant</b> once its countdown has reached 0 after {@link #K_SETTLE}
 * consecutive "quiet" steps (a per-step max-|Δ| below the pass threshold). A real move
 * ({@code delta >= threshold}) resets that pass's countdown to {@link #K_SETTLE}. The countdown — not
 * instant sleep — is the hysteresis that stops a near-equilibrium section flickering. Both passes
 * dormant ⇒ {@link #asleep()} ⇒ the scheduler drops the section from the schedule until a wake.</p>
 */
public record SettleCountdown(int flowCountdown, int thermalCountdown) {

    /** Consecutive quiet steps a pass must see before it sleeps (audit-tunable). */
    public static final int K_SETTLE = 3;
    /** kg; a per-step {@code max|Δmass|} at/below this is "no mass moved" (matches Plan-1 Task-6). */
    public static final float EPS_MASS = 1e-3f;
    /** K; a per-step {@code max|ΔT|} at/below this is "no heat moved". */
    public static final float EPS_TEMP = 1e-3f;

    /** A fully-active section: both passes have the full countdown remaining. */
    public static SettleCountdown active() {
        return new SettleCountdown(K_SETTLE, K_SETTLE);
    }

    /** Advection step bookkeeping: a quiet step decrements (floored at 0); a real move resets. */
    public SettleCountdown noteFlow(float maxMassDelta) {
        int fc = maxMassDelta >= EPS_MASS ? K_SETTLE : Math.max(0, flowCountdown - 1);
        return new SettleCountdown(fc, thermalCountdown);
    }

    /** Conduction step bookkeeping: a quiet step decrements (floored at 0); a real move resets. */
    public SettleCountdown noteThermal(float maxTempDelta) {
        int tc = maxTempDelta >= EPS_TEMP ? K_SETTLE : Math.max(0, thermalCountdown - 1);
        return new SettleCountdown(flowCountdown, tc);
    }

    /** Explicit wake of the flow pass (block edit, seam flux): restore the flow countdown. */
    public SettleCountdown wakeFlow() {
        return new SettleCountdown(K_SETTLE, thermalCountdown);
    }

    /** Explicit wake of the thermal pass (source-roster change): restore the thermal countdown. */
    public SettleCountdown wakeThermal() {
        return new SettleCountdown(flowCountdown, K_SETTLE);
    }

    /** Explicit wake of both passes (a generic edit that can affect mass AND heat). */
    public SettleCountdown wakeAll() {
        return active();
    }

    public boolean flowDormant() {
        return flowCountdown <= 0;
    }

    public boolean thermalDormant() {
        return thermalCountdown <= 0;
    }

    /** Both passes dormant — drop the section from the schedule entirely until a wake. */
    public boolean asleep() {
        return flowDormant() && thermalDormant();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SettleCountdownTest" --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/SettleCountdown.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SettleCountdownTest.java
git commit -m "feat(dormancy): pure per-pass SettleCountdown (flow/thermal sleep + wake, K hysteresis)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 2 [MAIN]: pure `ActiveSet` + `WakeSink` seam — the section roster the snapshot steps

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/ActiveSet.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/WakeSink.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/ActiveSetTest.java`

- [ ] **Step 1: Write the failing test** — covers the EXHAUSTIVE wake-trigger semantics and column-unload pruning.

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/ActiveSetTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ActiveSetTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final SubchunkKey K = new SubchunkKey(1, 4, 2);

    @Test
    void unseenSectionInRangeStartsActive() {
        ActiveSet a = new ActiveSet();
        // new-in-range: a key the set has never seen is treated active so it gets one settle step.
        List<SubchunkKey> stepped = a.activeWithin(DIM, List.of(K));
        assertEquals(List.of(K), stepped, "a never-seen in-range section is stepped (starts active)");
    }

    @Test
    void quietSectionFallsAsleepAndLeavesTheActiveSet() {
        ActiveSet a = new ActiveSet();
        // bring it into tracking (new-in-range), then feed K quiet flow + K quiet thermal steps.
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            a.noteFlowDelta(DIM, K, 0f);
            a.noteThermalDelta(DIM, K, 0f);
        }
        assertTrue(a.isAsleep(DIM, K), "both passes dormant");
        // an asleep section is NOT stepped even though it is in range.
        assertEquals(List.of(), a.activeWithin(DIM, List.of(K)));
    }

    @Test
    void flowDormantButThermalLiveStillStepsForThermal() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) a.noteFlowDelta(DIM, K, 0f);
        assertTrue(a.isFlowDormant(DIM, K));
        assertFalse(a.isThermalDormant(DIM, K));
        // still in the active set because thermal is live.
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }

    // ---- WAKE TRIGGERS (must be exhaustive) ----

    @Test
    void wakeFlowRevivesAnAsleepSection() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, K, 0f); a.noteThermalDelta(DIM, K, 0f); }
        assertTrue(a.isAsleep(DIM, K));
        a.wakeFlow(DIM, K);                      // trigger (a)/(c): edit / seam flux
        assertFalse(a.isFlowDormant(DIM, K));
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }

    @Test
    void wakeThermalRevivesTheThermalPassOnly() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, K, 0f); a.noteThermalDelta(DIM, K, 0f); }
        a.wakeThermal(DIM, K);                   // trigger (b): source-roster change
        assertFalse(a.isThermalDormant(DIM, K));
        assertTrue(a.isFlowDormant(DIM, K), "flow stays dormant on a thermal-only wake");
    }

    @Test
    void wakeByBlockPosResolvesToTheOwningSectionAndWakesBoth() {
        ActiveSet a = new ActiveSet();
        // block (20, 70, 35) -> section (cx=1, sectionY=4, cz=2)  (>>4 each)
        SubchunkKey owner = new SubchunkKey(20 >> 4, 70 >> 4, 35 >> 4);
        a.activeWithin(DIM, List.of(owner));
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) { a.noteFlowDelta(DIM, owner, 0f); a.noteThermalDelta(DIM, owner, 0f); }
        assertTrue(a.isAsleep(DIM, owner));
        a.wakeBlock(DIM, 20, 70, 35);            // trigger (a)/(d): block placed/broken/bucket
        assertFalse(a.isAsleep(DIM, owner));
    }

    @Test
    void forgetColumnDropsTrackingForThatColumn() {
        ActiveSet a = new ActiveSet();
        a.activeWithin(DIM, List.of(K));
        a.forgetColumn(DIM, K.cx(), K.cz());
        // after forget, the key is "unseen" again -> starts active when next in range.
        assertEquals(List.of(K), a.activeWithin(DIM, List.of(K)));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.ActiveSetTest" --rerun-tasks`
Expected: compile FAIL — `ActiveSet` does not exist.

- [ ] **Step 3: Implement the `WakeSink` seam**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/WakeSink.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * The pure seam the loaders push wake events into (DESIGN §10 Decision 11). Once a section is dormant
 * it is no longer snapshotted, so the snapshot-diff ({@link CellMaterialTracker}) cannot notice a
 * change — wake MUST be explicit. The exhaustive trigger set (a missed trigger = stale frozen fluid):
 * <ul>
 *   <li>(a) block placed/broken/changed incl. bucket → {@link #wakeBlock} (resolves to the owning section);</li>
 *   <li>(b) temperature source added/removed (B+C pins) → {@link #wakeThermalSection};</li>
 *   <li>(c) neighbour pushes flux across the shared seam → {@link #wakeFlowSection} on the adjacent section;</li>
 *   <li>(d) section newly enters player range → handled by the active-set's "unseen = active" rule.</li>
 * </ul>
 * Implemented by {@link ActiveSet}. Server-thread confined.
 */
public interface WakeSink {

    /** (a) A block at world coords changed (place/break/bucket/setblock/piston): wake the owning section. */
    void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ);

    /** (c) A neighbour pushed mass across the shared seam: wake the flow pass of one adjacent section. */
    void wakeFlowSection(Identifier dim, SubchunkKey key);

    /** (b) A temperature source (B+C pin) was added/removed in this section: wake its thermal pass. */
    void wakeThermalSection(Identifier dim, SubchunkKey key);
}
```

- [ ] **Step 4: Implement `ActiveSet`** (the `WakeSink` impl + the snapshot roster)

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/ActiveSet.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-section dormancy roster (DESIGN §10 Decision 11). Holds one {@link SettleCountdown} per
 * tracked section; the snapshot steps only sections that are NOT fully asleep. Mirrors
 * {@link CellMaterialTracker}'s shape and lifecycle (server-thread confined, pruned per column on
 * chunk unload), so a plain {@link HashMap} needs no synchronization.
 *
 * <p>Implements {@link WakeSink}: the loader event hooks push wakes here. A section the set has never
 * seen is treated as active (the new-in-range rule, trigger (d)).</p>
 */
public final class ActiveSet implements WakeSink {

    private final Map<Identifier, Map<Long, Map<Integer, SettleCountdown>>> byDim = new HashMap<>();

    private static long col(int cx, int cz) {
        return (cx & 0xffffffffL) | (((long) cz) << 32);
    }

    private SettleCountdown get(Identifier dim, SubchunkKey key) {
        Map<Long, Map<Integer, SettleCountdown>> d = byDim.get(dim);
        if (d == null) return null;
        Map<Integer, SettleCountdown> c = d.get(col(key.cx(), key.cz()));
        return c == null ? null : c.get(key.sectionY());
    }

    private void put(Identifier dim, SubchunkKey key, SettleCountdown c) {
        byDim.computeIfAbsent(dim, k -> new HashMap<>())
                .computeIfAbsent(col(key.cx(), key.cz()), k -> new HashMap<>())
                .put(key.sectionY(), c);
    }

    /**
     * Filter {@code inRange} (the player-range sphere keys) down to the sections to actually step this
     * cycle: a never-seen section is admitted active (new-in-range, trigger (d)) and recorded; a tracked
     * section is admitted iff it is not fully {@link SettleCountdown#asleep() asleep}. Server thread only.
     */
    public List<SubchunkKey> activeWithin(Identifier dim, List<SubchunkKey> inRange) {
        List<SubchunkKey> out = new ArrayList<>(inRange.size());
        for (SubchunkKey key : inRange) {
            SettleCountdown c = get(dim, key);
            if (c == null) {
                put(dim, key, SettleCountdown.active()); // new-in-range starts active
                out.add(key);
            } else if (!c.asleep()) {
                out.add(key);
            }
        }
        return out;
    }

    /** Advection writeback bookkeeping: decrement/reset the flow countdown from this step's max|Δmass|. */
    public void noteFlowDelta(Identifier dim, SubchunkKey key, float maxMassDelta) {
        SettleCountdown c = get(dim, key);
        if (c == null) c = SettleCountdown.active();
        put(dim, key, c.noteFlow(maxMassDelta));
    }

    /** Conduction writeback bookkeeping: decrement/reset the thermal countdown from this step's max|ΔT|. */
    public void noteThermalDelta(Identifier dim, SubchunkKey key, float maxTempDelta) {
        SettleCountdown c = get(dim, key);
        if (c == null) c = SettleCountdown.active();
        put(dim, key, c.noteThermal(maxTempDelta));
    }

    public boolean isFlowDormant(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.flowDormant();
    }

    public boolean isThermalDormant(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.thermalDormant();
    }

    public boolean isAsleep(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        return c != null && c.asleep();
    }

    /** Explicit flow wake (trigger (a)/(c)). Creates the entry active if the section was untracked. */
    public void wakeFlow(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeFlow());
    }

    /** Explicit thermal wake (trigger (b)). */
    public void wakeThermal(Identifier dim, SubchunkKey key) {
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeThermal());
    }

    // ---- WakeSink ----

    @Override
    public void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ) {
        SubchunkKey key = new SubchunkKey(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockY),
                SectionPos.blockToSectionCoord(blockZ));
        SettleCountdown c = get(dim, key);
        put(dim, key, c == null ? SettleCountdown.active() : c.wakeAll());
    }

    @Override
    public void wakeFlowSection(Identifier dim, SubchunkKey key) {
        wakeFlow(dim, key);
    }

    @Override
    public void wakeThermalSection(Identifier dim, SubchunkKey key) {
        wakeThermal(dim, key);
    }

    /** Drop every tracked section of one chunk column (chunk unload), exactly like {@link CellMaterialTracker}. */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Map<Integer, SettleCountdown>> d = byDim.get(dim);
        if (d != null) d.remove(col(cx, cz));
    }

    /** Drop all tracked countdowns (server stop). */
    public void clear() {
        byDim.clear();
    }
}
```

> `SectionPos.blockToSectionCoord(int)` is the same vanilla helper `MinecraftThermalWorld.snapshot` already uses to map a player block-pos to a section coord (verified there), so `wakeBlock` resolves a world coord to the owning `SubchunkKey` identically.

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.ActiveSetTest" --rerun-tasks`
Expected: PASS.

- [ ] **Step 6: Run full suite + loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green (no production code changed besides the two new files).
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 7: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/ActiveSet.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/WakeSink.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/ActiveSetTest.java
git commit -m "feat(dormancy): ActiveSet roster + WakeSink seam (exhaustive triggers, column-prune)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 3 [MAIN]: wire `snapshot` to step the ACTIVE SET; piggyback `max|Δ|` into the countdowns

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerDormancyTest.java` (create)

> This is the change that makes large worlds cheap: `snapshot(range)` no longer assembles the whole sphere — it assembles `sphere ∩ activeSet`. The bookkeeping (`max|Δmass|`, `max|ΔT|`) piggybacks the **existing** writeback loop (`Scheduler.writeBackResults` already iterates the result arrays per entry), so it adds one max-reduction per array, not a new pass.

- [ ] **Step 1: Write the failing test** — a `Scheduler`-level test over a fake `ThermalWorld` that records which keys were stepped, proving a settled section drops out and a wake re-admits it.

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerDormancyTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepResult;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the scheduler against a fake world whose {@code snapshot} consults a real {@link ActiveSet},
 * and a fake engine that returns the input unchanged (a perfectly settled field). After K advection +
 * K conduction quiet steps the section must drop out of the snapshot; a wake re-admits it.
 */
class SchedulerDormancyTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    private static final SubchunkKey K = new SubchunkKey(0, 0, 0);

    /** Engine that returns its input mass/temp verbatim — a settled field (no Δ). */
    private static final class SettledEngine implements OrgeEngine {
        public List<StepResult> step(List<StepTask> tasks, List<Material> lut, double dt, int passes) {
            List<StepResult> out = new ArrayList<>();
            for (StepTask t : tasks) {
                out.add(new StepResult(t.temperature().clone(), t.mass().clone(), t.matIx().clone()));
            }
            return out;
        }
        public double lastStepMillis() { return 0; }
    }

    @Test
    void settledSectionStopsBeingSnapshotted_thenWakeReadmitsIt() {
        ActiveSet active = new ActiveSet();
        ActiveSetWorld world = new ActiveSetWorld(active, DIM, K); // helper below
        // Run many advection cycles; the SettledEngine never moves anything.
        // After K_SETTLE flow + K_SETTLE thermal quiet steps the section is asleep.
        for (int i = 0; i < SettleCountdown.K_SETTLE; i++) {
            active.noteFlowDelta(DIM, K, 0f);
            active.noteThermalDelta(DIM, K, 0f);
        }
        assertTrue(active.isAsleep(DIM, K));
        assertTrue(world.snapshot(2).entries().isEmpty(), "asleep section is not snapshotted");

        active.wakeBlock(DIM, 0, 0, 0); // a bucket placement in that section
        assertFalse(world.snapshot(2).entries().isEmpty(), "wake re-admits the section");
    }
}
```

> `ActiveSetWorld` is a 20-line test double in the same file: it holds one `SubchunkKey`, builds a trivial `StepTask` for it, and returns it from `snapshot` **only when** `active.activeWithin(DIM, List.of(K))` admits it. Author it to the `ThermalWorld` interface (`Batch snapshot(int)`, `void writeBack(BatchEntry, StepResult)` — `writeBack` is a no-op here). This isolates the active-set gating from Minecraft.

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SchedulerDormancyTest" --rerun-tasks`
Expected: compile FAIL (or assertion FAIL) — `ActiveSetWorld` / the active-set gating does not exist yet.

- [ ] **Step 3: Add the `ActiveSet` to `MinecraftThermalWorld` and gate `snapshot`**

In `MinecraftThermalWorld.java`, add the field + constructor wiring (mirror the existing `cellMaterials` field):

```java
    private final SectionStoreManager stores;
    private final CellMaterialTracker cellMaterials;
    private final ActiveSet activeSet;
    private volatile MinecraftServer server;

    public MinecraftThermalWorld(SectionStoreManager stores, CellMaterialTracker cellMaterials,
                                 ActiveSet activeSet) {
        this.stores = stores;
        this.cellMaterials = cellMaterials;
        this.activeSet = activeSet;
    }

    /** Convenience for headless write-back tests that never call {@link #snapshot}. */
    public MinecraftThermalWorld(SectionStoreManager stores) {
        this(stores, new CellMaterialTracker(), new ActiveSet());
    }

    /** The wake sink the loader event hooks push into. */
    public WakeSink wakeSink() {
        return activeSet;
    }
```

In `snapshot`, after building the per-level `union` (the player-sphere + forced keys), **filter it through the active set** before assembling geometry. Replace the `for (SubchunkKey key : union) {` loop header so it iterates only the admitted keys:

```java
            Set<SubchunkKey> union = SphereUnion.expand(anchors, range);
            addForcedSections(level, union);

            // §10 Decision 11: step only the ACTIVE SET within range. A never-seen section is admitted
            // active (new-in-range); a fully-asleep section is dropped here so a calm ocean stops
            // re-simulating. Ordering of the union is irrelevant to correctness.
            List<SubchunkKey> active = activeSet.activeWithin(dim, new ArrayList<>(union));

            for (SubchunkKey key : active) {
```

> `activeWithin` both filters and records new-in-range keys; the rest of the loop body (geometry, temps, mass, halo, `entries.add`) is unchanged. Add `import java.util.List;` if not already present (it is).

- [ ] **Step 4: Piggyback the per-pass `max|Δ|` into the active set in `Scheduler.writeBackResults`**

The scheduler must feed each section's per-step deltas to the active set. It does **not** import `MinecraftThermalWorld` (it talks to `ThermalWorld`), so expose a tiny hook on `ThermalWorld` and route through it. Add to `ThermalWorld`:

```java
    /**
     * Record a section's per-step settle deltas (DESIGN §10 Decision 11). Called from the writeback
     * loop with the max |Δmass| (advection) / max |ΔT| (conduction) over the section's 4096 cells, so a
     * quiet section counts down toward dormancy. {@code maxMassDelta < 0} means "no advection this
     * cycle" (skip the flow countdown); likewise {@code maxTempDelta < 0} skips thermal. Default no-op
     * for headless test worlds.
     */
    default void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) { }
```

Implement it in `MinecraftThermalWorld`:

```java
    @Override
    public void noteSettle(BatchEntry entry, float maxMassDelta, float maxTempDelta) {
        if (maxMassDelta >= 0f) {
            activeSet.noteFlowDelta(entry.dimension(), entry.key(), maxMassDelta);
        }
        if (maxTempDelta >= 0f) {
            activeSet.noteThermalDelta(entry.dimension(), entry.key(), maxTempDelta);
        }
    }
```

In `Scheduler.writeBackResults`, compute the reductions in the **existing** per-entry loop and call `noteSettle`. In the **advection** branch (right after `cleanM` is computed / validated, before/after `writeBack` — anywhere in scope), and the **conduction** branch, add:

```java
            if (advection) {
                float[] cleanM = StepValidator.cleanMass(r.mass(), fullMassBound);
                // ... existing per-species §9 gate (Plan 2) ...
                world.writeBack(entry, new StepResult(cleanT, cleanM, r.material()));
                // §10 Decision 11: piggyback the settle reduction on this loop (near-free). Conduction's
                // ΔT is already folded into cleanT on a coincident tick, so report both deltas here.
                float maxMassDelta = maxAbsDelta(cleanM, entry.task().mass());
                float maxTempDelta = conduction ? maxAbsDelta(cleanT, entry.task().temperature()) : -1f;
                world.noteSettle(entry, maxMassDelta, maxTempDelta);
                if (conduction) {
                    phaseChanger.applyPhaseChanges(entry);
                }
                fluidReconciler.reconcile(entry, r.material(), pendingMaterials);
            } else {
                world.writeBack(entry, new StepResult(cleanT, entry.task().mass()));
                world.noteSettle(entry, -1f, maxAbsDelta(cleanT, entry.task().temperature()));
                phaseChanger.applyPhaseChanges(entry);
            }
```

Add the helper to `Scheduler` (a private static):

```java
    /** Max absolute per-cell difference of two equal-length arrays (the settle reduction). */
    private static float maxAbsDelta(float[] a, float[] b) {
        float m = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            float d = Math.abs(a[i] - b[i]);
            if (d > m) m = d;
        }
        return m;
    }
```

> The `fluidReconciler.reconcile(entry, r.material(), pendingMaterials)` line is Plan-2's 3-arg form; if Plan 2 already wrote it, leave it. The only additions in this step are the two `noteSettle` calls + the `maxAbsDelta` helper + the `noteSettle` default on `ThermalWorld`.

- [ ] **Step 5: Update `Orge.init()` to construct the shared `ActiveSet`**

In `Orge.java`, create the `ActiveSet` next to `cellMaterials`, pass it into `MinecraftThermalWorld`, and prune it on column unload (the existing single listener already prunes `cellMaterials`; chain the new prune onto it):

```java
        CellMaterialTracker cellMaterials = new CellMaterialTracker();
        ActiveSet activeSet = new ActiveSet();
        SECTION_STORES.setColumnUnloadListener((dim, cx, cz) -> {
            cellMaterials.forgetColumn(dim, cx, cz);
            activeSet.forgetColumn(dim, cx, cz);
        });
        thermalWorld = new MinecraftThermalWorld(SECTION_STORES, cellMaterials, activeSet);
```

> The current code sets `SECTION_STORES.setColumnUnloadListener(cellMaterials::forgetColumn)` (verified). Replace that single line with the chained lambda above. Keep `activeSet` referenced for Task 5 (the wake wiring) — store it in a `static` field beside `thermalWorld`, or fetch it via `thermalWorld.wakeSink()` (preferred — avoids a second field). Add `import net.rainbowcreation.orge.scheduler.ActiveSet;` (only if you keep the field; otherwise no import needed since `wakeSink()` returns the `WakeSink` interface). Also add `activeSet.clear();` next to `cellMaterials.clear();` in `SERVER_STOPPING` (or call `thermalWorld.wakeSink()`-backed clear; simplest is to keep the local `activeSet` reference for `clear()`).

- [ ] **Step 6: Run the new test, full suite, loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SchedulerDormancyTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — existing scheduler tests (`SchedulerMassTest`, etc.) use the 2-arg `MinecraftThermalWorld(stores)` convenience ctor (new `ActiveSet`), and `noteSettle` is a default no-op on any test `ThermalWorld`.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 7: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java \
        core/src/main/java/net/rainbowcreation/orge/Orge.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerDormancyTest.java
git commit -m "feat(dormancy): snapshot steps the active set; writeback piggybacks max|Δ| countdowns

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 4 [MAIN]: `WakePlatform` `@ExpectPlatform` seam + per-loader block-update wake impls

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/platform/WakePlatform.java`
- Create: `fabric-1.21/src/main/java/net/rainbowcreation/orge/platform/fabric/WakePlatformImpl.java`
- Create: `neoforge-1.21/src/main/java/net/rainbowcreation/orge/platform/neoforge/WakePlatformImpl.java`
- Test: (none — these are loader-binding shells; correctness is the compile gate + the pure `ActiveSet` wake tests in Task 2)

> **API facts (verified against this repo's deps):** Architectury ships **common** `dev.architectury.event.events.common.BlockEvent.PLACE` / `.BREAK` and `PlayerEvent.FILL_BUCKET` (confirmed present in `architectury-fabric-19.0.1.jar` / `-neoforge-19.0.1.jar`). Those three are wired in **common** code in Task 5 (no per-loader split needed for them). The `@ExpectPlatform` `WakePlatform` here covers the **low-level block changes the common events miss**: `/setblock`, command/datapack edits, piston moves, and the reconciler's own `air→fluid` placements — i.e. the general `Level.setBlock`/neighbour-update path, which has **no Architectury common event** (mirrors why `SectionStorePlatform` exists for chunk load/unload).
>
> **Loader hook (most likely API; flag as uncertain):** there is no first-class "any block changed" common event. The robust per-loader signal is a **mixin or low-level listener on block setting**:
> - **NeoForge:** subscribe `net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent` on `NeoForge.EVENT_BUS` (fires for the neighbour-update fan-out of `Level.setBlock`/`updateNeighbourForOutputSignal`; server-guard `event.getLevel() instanceof ServerLevel`). This covers `/setblock`, pistons, and most programmatic edits. If `NeighborNotifyEvent` proves too narrow in testing, fall back to a `ServerLevel#setBlock` mixin (same `FlowingFluidMixin`-style approach already in the repo).
> - **Fabric:** there is no comparable common Fabric block-change callback; use a **mixin** on `net.minecraft.world.level.Level#setBlock(BlockPos,BlockState,int,int)` (or `ServerLevel`), `@Inject(at = @At("TAIL"))`, server-side only — mirroring the existing `FlowingFluidMixin` discipline. The mixin calls `WakePlatform`'s static bridge.
> **Both impls ultimately call one common bridge** (`WakeBridge.wake(dim, x, y, z)` set by `Orge.init()`), exactly like `OrgeFluidPolicy.setManagedSectionPredicate`. Because this is the belt-and-suspenders path (the common `BlockEvent`s already cover player place/break/bucket), if a loader's low-level hook is hard to land, ship `WakePlatform` as a documented no-op on that loader for v1 and rely on the common events — note it loudly in the audit.

- [ ] **Step 1: Implement the common `WakePlatform` seam**

Create `core/src/main/java/net/rainbowcreation/orge/platform/WakePlatform.java`:

```java
package net.rainbowcreation.orge.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * Loader-specific low-level block-change wake hook (DESIGN §10 Decision 11, trigger (a)). The common
 * {@code BlockEvent.PLACE/BREAK} + {@code PlayerEvent.FILL_BUCKET} (wired in {@code Orge.init()}) cover
 * player-driven edits; this seam covers the rest of the {@code Level.setBlock} fan-out that has NO
 * Architectury common event — {@code /setblock}, datapack/command edits, pistons, dispenser/bucket
 * placements, and the reconciler's own {@code air→fluid} writes — so a dormant section near a
 * programmatic change still wakes (a missed trigger = stale frozen fluid).
 *
 * <p>Each impl registers its loader-native block-change signal (NeoForge {@code NeighborNotifyEvent} /
 * a Fabric {@code Level#setBlock} mixin), guards server-side, derives the dimension
 * {@link Identifier} from {@code serverLevel.dimension().identifier()}, and forwards the world block
 * coords to {@code sink.wakeBlock(dim, x, y, z)}. Server-side only; ORGE state is server-authoritative.</p>
 */
public final class WakePlatform {

    private WakePlatform() {
    }

    /**
     * Registers the per-loader block-change listener that pushes wakes into {@code sink}. Called once
     * from {@code Orge.init()} (after the sink is constructed).
     */
    @ExpectPlatform
    public static void registerBlockChangeWake(WakeSink sink) {
        throw new AssertionError("ExpectPlatform implementation not found");
    }
}
```

- [ ] **Step 2: Implement the Fabric impl** (mixin-bridged)

Create `fabric-1.21/src/main/java/net/rainbowcreation/orge/platform/fabric/WakePlatformImpl.java`:

```java
package net.rainbowcreation.orge.platform.fabric;

import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * Fabric implementation of {@code WakePlatform} (matched by {@code <name>Impl}). Fabric has no common
 * "block changed" callback, so the signal comes from a {@code Level#setBlock} mixin
 * ({@code WakeSetBlockMixin}) which calls {@link #wake}. This impl just stashes the sink the mixin
 * forwards into (the {@code FlowingFluidMixin}/{@code OrgeFluidPolicy} static-bridge pattern already in
 * the repo).
 */
public final class WakePlatformImpl {

    private static volatile WakeSink SINK;

    private WakePlatformImpl() {
    }

    public static void registerBlockChangeWake(WakeSink sink) {
        SINK = sink;
    }

    /** Called from {@code WakeSetBlockMixin} at the TAIL of a server-side {@code Level#setBlock}. */
    public static void wake(net.minecraft.resources.Identifier dim, int x, int y, int z) {
        WakeSink s = SINK;
        if (s != null) {
            s.wakeBlock(dim, x, y, z);
        }
    }
}
```

Create the Fabric mixin `fabric-1.21/src/main/java/net/rainbowcreation/orge/fabric/mixin/WakeSetBlockMixin.java`:

```java
package net.rainbowcreation.orge.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.platform.fabric.WakePlatformImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric wake hook (DESIGN §10 Decision 11, trigger (a)): at the TAIL of a successful server-side
 * {@code Level#setBlock}, wake the owning section so a dormant region near a programmatic block change
 * ({@code /setblock}, piston, dispenser, reconciler write) re-enters the active set. Server-side only.
 */
@Mixin(Level.class)
public abstract class WakeSetBlockMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("TAIL"))
    private void orge$wakeOnSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (((Object) this) instanceof ServerLevel level && Boolean.TRUE.equals(cir.getReturnValue())) {
            WakePlatformImpl.wake(level.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
```

> Verify the `setBlock` descriptor against the 1.21.11 Mojang-mapped `Level` (the repo's mixins are already Mojang-mapped). The 4-arg `setBlock(BlockPos,BlockState,int,int)` (pos, state, flags, recursionLeft) is the canonical internal entry; if the mapping differs, target the actual `Level#setBlock` that returns `boolean`. Register the mixin in `fabric-1.21/src/main/resources/orge.mixins.json` under `"mixins"` (the server-common list, beside `FlowingFluidMixin`).

- [ ] **Step 3: Implement the NeoForge impl** (event-bus)

Create `neoforge-1.21/src/main/java/net/rainbowcreation/orge/platform/neoforge/WakePlatformImpl.java`:

```java
package net.rainbowcreation.orge.platform.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.rainbowcreation.orge.scheduler.WakeSink;

/**
 * NeoForge implementation of {@code WakePlatform} (matched by {@code <name>Impl}). Subscribes
 * {@code BlockEvent.NeighborNotifyEvent} on {@code NeoForge.EVENT_BUS}: it fires for the neighbour-update
 * fan-out of {@code Level.setBlock}, covering {@code /setblock}, pistons, dispensers, and programmatic
 * edits. Server-guarded ({@code getLevel() instanceof ServerLevel}); ORGE is server-authoritative.
 */
public final class WakePlatformImpl {

    private WakePlatformImpl() {
    }

    public static void registerBlockChangeWake(WakeSink sink) {
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                var pos = event.getPos();
                sink.wakeBlock(level.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
        });
    }
}
```

> `BlockEvent.NeighborNotifyEvent` is in `net.neoforged.neoforge.event.level` and exposes `getLevel()` (a `LevelAccessor`) + `getPos()`. If the precise event class differs in this NeoForge build, the closest equivalent that fires on a server block change is acceptable — the goal is "a programmatic block change wakes the owning section"; over-waking (a spurious wake) is harmless (one extra settle step), under-waking is the failure mode.

- [ ] **Step 4: Compile gate (the primary verification for loader shells)**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile. If the Fabric `setBlock` descriptor or the NeoForge event class is wrong, the compile/mixin-apply fails here — fix the signature against the mapped sources and re-run.

- [ ] **Step 5: Full suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green (no core logic changed; `WakePlatform` is only invoked from `Orge.init()` in Task 5).

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/platform/WakePlatform.java \
        fabric-1.21/src/main/java/net/rainbowcreation/orge/platform/fabric/WakePlatformImpl.java \
        fabric-1.21/src/main/java/net/rainbowcreation/orge/fabric/mixin/WakeSetBlockMixin.java \
        fabric-1.21/src/main/resources/orge.mixins.json \
        neoforge-1.21/src/main/java/net/rainbowcreation/orge/platform/neoforge/WakePlatformImpl.java
git commit -m "feat(wake): WakePlatform ExpectPlatform seam + per-loader block-change wake (setblock/piston/neighbour)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 5 [MAIN]: wire ALL wake triggers in `Orge.init()` — block events, bucket, source-roster, seam-flux, new-in-range

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java` (seam-flux wake)
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java` (a `wakeNeighbourFlow` hook + `wakeThermalSource` hook)
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java` (implement those hooks)
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SeamFluxWakeTest.java` (create)

> Triggers (d) new-in-range is already covered (Task 2/3: unseen = active). This task wires (a) block place/break/bucket via common events + the `WakePlatform` seam, (b) source-roster change, and (c) the **seam-flux wake**: when an active section pushes mass across a section face, the ADJACENT section must wake or flow stops dead at a dormant border.

### 5a — common block/bucket events (trigger (a))

- [ ] **Step 1: Wire the three common Architectury events to the wake sink in `Orge.init()`**

After `thermalWorld = new MinecraftThermalWorld(...)` and the scheduler is built, add (in `Orge.init()`):

```java
        // §10 Decision 11 trigger (a): wake the owning section on any player block edit or bucket use.
        // BlockEvent.PLACE/BREAK + PlayerEvent.FILL_BUCKET are COMMON Architectury events (one
        // registration, both loaders). WakePlatform (ExpectPlatform) adds the low-level setBlock path
        // (/setblock, pistons, programmatic edits) the common events don't cover.
        WakeSink wake = thermalWorld.wakeSink();
        BlockEvent.PLACE.register((level, pos, state, placer) -> {
            if (level instanceof ServerLevel sl) {
                wake.wakeBlock(sl.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
            return EventResult.pass();
        });
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            if (level instanceof ServerLevel sl) {
                wake.wakeBlock(sl.dimension().identifier(), pos.getX(), pos.getY(), pos.getZ());
            }
            return EventResult.pass();
        });
        PlayerEvent.FILL_BUCKET.register((player, level, stack, target) -> {
            if (level instanceof ServerLevel sl && target instanceof BlockHitResult hit) {
                BlockPos p = hit.getBlockPos();
                wake.wakeBlock(sl.dimension().identifier(), p.getX(), p.getY(), p.getZ());
            }
            return InteractionResult.PASS;
        });
        WakePlatform.registerBlockChangeWake(wake);
```

Add imports to `Orge.java`:

```java
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.rainbowcreation.orge.platform.WakePlatform;
import net.rainbowcreation.orge.scheduler.WakeSink;
```

> `BlockEvent.PLACE`/`BREAK` return `EventResult` (`EventResult.pass()` = don't interfere); `FILL_BUCKET` returns `InteractionResult` (`PASS`). The `FillBucket.fill(player, level, stack, target)` `target` is a `HitResult`; the bucket fill/empty acts on the block it targets, so waking `target`'s block pos covers both filling from and emptying into a cell. `ServerPlayer.level()` / `level.dimension().identifier()` are the verified Mojang-mapped accessors. The fill event being `PASS` does not cancel the bucket; over-waking is harmless.

### 5b — source-roster wake (trigger (b))

- [ ] **Step 2: Add a thermal-source wake hook + fire it where pins change**

A B+C temperature source (a `pinned` material like lava, or a hot-source binding) added/removed changes the section's thermal forcing — its conduction must re-run even if the field looked settled. The source roster is derived **fresh each snapshot** from the section's blocks (`SourcePinPlanner`), so a source appearing/disappearing is exactly a **block change** — which trigger (a) already wakes (the new block's `setBlock`/`BlockEvent`). The explicit thermal wake is therefore a **belt-and-suspenders** path for the case where a pin's *temperature* changes without a block change (e.g. a `/orge set` write into a source cell, or a reload re-binding sources).

Wire `/orge set`/`fill` writes (the `ServerStoreWriteSink` path) to a thermal wake. In `Orge.init()`, the `ServerStoreWriteSink` is constructed for the command logic; pass the wake sink so a write wakes the section. The sink's write methods are `writeTemp(Identifier dim, SubchunkKey key, int cell, float kelvin)` and `writeMass(Identifier dim, SubchunkKey key, int cell, float kg)` (verified) — they carry the dimension + the **section key** (not raw block coords), so the natural wake here is `wakeThermalSection`/`wakeFlowSection`, not `wakeBlock`. Add an optional `WakeSink` field (constructor-injected, nullable for tests):

```java
        OrgeCommandLogic commandLogic = new OrgeCommandLogic(
                List.of(new ServerStoreReadSource(SECTION_STORES)),
                new ServerStoreWriteSink(SECTION_STORES, thermalWorld.wakeSink()),
                (ReadRangeProvider) () -> Scheduler.MAX_RANGE);
```

In `ServerStoreWriteSink`, keep the existing single-arg ctor (`this(stores, null)`) for tests, add the field, and at the TAIL of `writeTemp` wake the thermal pass, at the TAIL of `writeMass` wake the flow pass:

```java
    private final SectionStoreManager stores;
    private final WakeSink wake; // nullable

    public ServerStoreWriteSink(SectionStoreManager stores) {
        this(stores, null);
    }

    public ServerStoreWriteSink(SectionStoreManager stores, WakeSink wake) {
        this.stores = stores;
        this.wake = wake;
    }

    @Override
    public void writeTemp(Identifier dimension, SubchunkKey key, int cell, float kelvin) {
        // ... existing body ...
        if (wake != null) wake.wakeThermalSection(dimension, key); // a temp edit re-runs conduction
    }

    @Override
    public void writeMass(Identifier dimension, SubchunkKey key, int cell, float kg) {
        // ... existing body ...
        if (wake != null) wake.wakeFlowSection(dimension, key);    // a mass edit re-runs advection
    }
```

Add `import net.rainbowcreation.orge.scheduler.WakeSink;` to `ServerStoreWriteSink`. This satisfies trigger (b) for temperature edits that bypass block changes (and a flow wake for direct mass edits); ordinary source placement/removal is already covered by (a). **Document in the audit** that B+C source *block* changes wake via (a) and source *temperature*/*mass* edits via this hook — together exhaustive.

### 5c — seam-flux wake (trigger (c))

- [ ] **Step 3: Write the failing test** — an active section with nonzero boundary-face mass flux wakes the adjacent (possibly dormant) section.

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/SeamFluxWakeTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision 11 trigger (c): an active section that moved mass at its boundary face must wake the
 * adjacent section, or flow stops dead at a dormant border. We test the pure rule
 * {@link SeamFluxWake#neighboursToWake}: given a section key + the six per-face "did mass cross"
 * flags, it returns the adjacent keys to wake.
 */
class SeamFluxWakeTest {

    private static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void noFaceFluxWakesNoNeighbour() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        var n = SeamFluxWake.neighboursToWake(k, false, false, false, false, false, false);
        assertTrue(n.isEmpty());
    }

    @Test
    void negXFaceFluxWakesTheNegXNeighbour() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        // faces order: negX, posX, negY, posY, negZ, posZ
        var n = SeamFluxWake.neighboursToWake(k, true, false, false, false, false, false);
        assertEquals(java.util.List.of(new SubchunkKey(1, 3, 4)), n);
    }

    @Test
    void posYFaceFluxWakesTheCellAbove() {
        SubchunkKey k = new SubchunkKey(2, 3, 4);
        var n = SeamFluxWake.neighboursToWake(k, false, false, false, true, false, false);
        assertEquals(java.util.List.of(new SubchunkKey(2, 4, 4)), n);
    }

    @Test
    void multipleFacesWakeMultipleNeighbours() {
        SubchunkKey k = new SubchunkKey(0, 0, 0);
        var n = SeamFluxWake.neighboursToWake(k, true, true, false, false, false, false);
        assertTrue(n.contains(new SubchunkKey(-1, 0, 0)));
        assertTrue(n.contains(new SubchunkKey(1, 0, 0)));
        assertEquals(2, n.size());
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SeamFluxWakeTest" --rerun-tasks`
Expected: compile FAIL — `SeamFluxWake` does not exist.

- [ ] **Step 5: Implement the pure `SeamFluxWake` rule**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/SeamFluxWake.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure rule for Decision 11 trigger (c): map a section's six boundary-face mass-flux flags to the
 * adjacent section keys that must wake. Without this, an active section that pushes fluid toward a
 * dormant neighbour would see flow stop dead at the border (the dormant neighbour is never snapshotted,
 * so it never accepts the incoming mass). Face order matches the kernel/halo convention used elsewhere:
 * negX, posX, negY, posY, negZ, posZ.
 */
public final class SeamFluxWake {

    private SeamFluxWake() {
    }

    public static List<SubchunkKey> neighboursToWake(SubchunkKey k,
                                                     boolean negX, boolean posX,
                                                     boolean negY, boolean posY,
                                                     boolean negZ, boolean posZ) {
        List<SubchunkKey> out = new ArrayList<>(6);
        if (negX) out.add(new SubchunkKey(k.cx() - 1, k.sectionY(), k.cz()));
        if (posX) out.add(new SubchunkKey(k.cx() + 1, k.sectionY(), k.cz()));
        if (negY) out.add(new SubchunkKey(k.cx(), k.sectionY() - 1, k.cz()));
        if (posY) out.add(new SubchunkKey(k.cx(), k.sectionY() + 1, k.cz()));
        if (negZ) out.add(new SubchunkKey(k.cx(), k.sectionY(), k.cz() - 1));
        if (posZ) out.add(new SubchunkKey(k.cx(), k.sectionY(), k.cz() + 1));
        return out;
    }
}
```

- [ ] **Step 6: Compute boundary-face flux in the writeback loop and wake neighbours**

The advection writeback already has both the snapshot input mass (`entry.task().mass()`) and the post-step mass (`cleanM`); a face has flux if any cell on that 16×16 boundary plane changed mass. Add a per-face detector to `Scheduler` and, when a face moved mass, wake the adjacent section's flow pass through a new `ThermalWorld` hook.

Add to `ThermalWorld`:

```java
    /** Wake the flow pass of a section adjacent to one that pushed mass across the shared seam
     *  (Decision 11 trigger (c)). Default no-op for headless test worlds. */
    default void wakeNeighbourFlow(Identifier dim, SubchunkKey neighbour) { }
```

Implement in `MinecraftThermalWorld`:

```java
    @Override
    public void wakeNeighbourFlow(Identifier dim, SubchunkKey neighbour) {
        activeSet.wakeFlowSection(dim, neighbour);
    }
```

In `Scheduler.writeBackResults`, in the advection branch after `noteSettle`, detect boundary flux and wake:

```java
                // Decision 11 trigger (c): if mass crossed any boundary face, wake the adjacent section
                // so flow propagates into a dormant border instead of stopping dead.
                float[] inMass = entry.task().mass();
                boolean negX = faceMoved(inMass, cleanM, Face.NEG_X);
                boolean posX = faceMoved(inMass, cleanM, Face.POS_X);
                boolean negY = faceMoved(inMass, cleanM, Face.NEG_Y);
                boolean posY = faceMoved(inMass, cleanM, Face.POS_Y);
                boolean negZ = faceMoved(inMass, cleanM, Face.NEG_Z);
                boolean posZ = faceMoved(inMass, cleanM, Face.POS_Z);
                if (negX || posX || negY || posY || negZ || posZ) {
                    for (SubchunkKey nb : SeamFluxWake.neighboursToWake(entry.key(),
                            negX, posX, negY, posY, negZ, posZ)) {
                        world.wakeNeighbourFlow(entry.dimension(), nb);
                    }
                }
```

Add the per-face detector to `Scheduler` (16×16×16 cell layout `i = x + y*16 + z*256`, `SEC = 16`):

```java
    private enum Face { NEG_X, POS_X, NEG_Y, POS_Y, NEG_Z, POS_Z }

    private static final int SEC = 16;

    /** True if any cell on the given boundary plane changed mass beyond the settle epsilon. */
    private static boolean faceMoved(float[] before, float[] after, Face face) {
        for (int a = 0; a < SEC; a++) {
            for (int b = 0; b < SEC; b++) {
                int i = faceIndex(face, a, b);
                if (Math.abs(after[i] - before[i]) >= SettleCountdown.EPS_MASS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int faceIndex(Face face, int a, int b) {
        return switch (face) {
            case NEG_X -> idx(0, a, b);
            case POS_X -> idx(SEC - 1, a, b);
            case NEG_Y -> idx(a, 0, b);
            case POS_Y -> idx(a, SEC - 1, b);
            case NEG_Z -> idx(a, b, 0);
            case POS_Z -> idx(a, b, SEC - 1);
        };
    }

    private static int idx(int x, int y, int z) {
        return x + y * SEC + z * SEC * SEC;
    }
```

> This reuses the data already in hand (snapshot mass vs post-step mass) — no engine change, no new pass. It is a *conservative over-wake*: a boundary cell that merely settled (mass changed for a non-seam reason) also wakes the neighbour, costing one extra settle step there; that is the safe direction (under-waking strands flow). A precise engine-reported seam flux is the deferred refinement (spec risk 9); see Self-review.

- [ ] **Step 7: Run the seam-flux test, full suite, loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SeamFluxWakeTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green (`ServerStoreWriteSink` keeps its single-arg ctor for existing command tests).
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 8: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/Orge.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/SeamFluxWake.java \
        core/src/main/java/net/rainbowcreation/orge/command/ServerStoreWriteSink.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SeamFluxWakeTest.java
git commit -m "feat(wake): wire all triggers — block/bucket events, source edits, seam-flux neighbour wake

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 6 [MAIN]: scratch-buffer pool on the worker thread (Decision 13c)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/ScratchPool.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java` (source `tOut`/`massOut`/`matOut` from the pool)
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/ScratchPoolTest.java` (create)

> The native step allocates `tOut`/`massOut`/`matOut` (`n*4096` floats/chars) every call; at 4 Hz that churns the GC. The pool reuses per-thread arrays sized to the current batch, growing only when a batch is larger than ever seen. The engine runs single-threaded on the runner's background thread, so a single per-pool buffer set (no `ThreadLocal` needed) is correct as long as the slices are copied out before the next step — which `BatchMarshaller.slice*` already does (it allocates per-section result arrays). The pooled arrays are the *flat scratch*, not the returned per-section arrays.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/engine/ScratchPoolTest.java`:

```java
package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScratchPoolTest {

    @Test
    void reusesArraysAcrossCallsOfTheSameSize() {
        ScratchPool p = new ScratchPool();
        float[] t1 = p.temp(4096);
        float[] m1 = p.mass(4096);
        char[]  s1 = p.material(4096);
        float[] t2 = p.temp(4096);
        assertSame(t1, t2, "same-size temp request returns the SAME array (no realloc)");
        assertSame(m1, p.mass(4096));
        assertSame(s1, p.material(4096));
    }

    @Test
    void growsWhenABiggerBatchArrives_andKeepsTheBiggerArray() {
        ScratchPool p = new ScratchPool();
        float[] small = p.temp(4096);
        float[] big = p.temp(8192);
        assertNotSame(small, big);
        assertTrue(big.length >= 8192);
        // a subsequent smaller request reuses the bigger array (length >= requested), still no realloc.
        float[] again = p.temp(4096);
        assertSame(big, again, "pool keeps the high-water array and serves smaller requests from it");
    }

    @Test
    void lengthAtLeastRequested() {
        ScratchPool p = new ScratchPool();
        assertTrue(p.temp(100).length >= 100);
        assertTrue(p.mass(100).length >= 100);
        assertTrue(p.material(100).length >= 100);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.ScratchPoolTest" --rerun-tasks`
Expected: compile FAIL — `ScratchPool` does not exist.

- [ ] **Step 3: Implement `ScratchPool`**

Create `core/src/main/java/net/rainbowcreation/orge/engine/ScratchPool.java`:

```java
package net.rainbowcreation.orge.engine;

/**
 * Per-worker reusable scratch arrays for the native step's flat outputs (DESIGN §10 Decision 13c).
 * The engine runs single-threaded on the runner's background thread, so one buffer set (no
 * {@link ThreadLocal}) suffices. Arrays grow to the batch high-water mark and are kept, so a steady 4 Hz
 * cadence allocates nothing after warm-up — the result slices ({@code BatchMarshaller.slice*}) copy the
 * per-section data out before the next step reuses the scratch.
 *
 * <p>Not thread-safe by design: confined to the single engine worker thread.</p>
 */
public final class ScratchPool {

    private float[] temp = new float[0];
    private float[] mass = new float[0];
    private char[] material = new char[0];

    /** A {@code float[]} of at least {@code n}, reused when the held array is already big enough. */
    public float[] temp(int n) {
        if (temp.length < n) temp = new float[n];
        return temp;
    }

    public float[] mass(int n) {
        if (mass.length < n) mass = new float[n];
        return mass;
    }

    public char[] material(int n) {
        if (material.length < n) material = new char[n];
        return material;
    }
}
```

- [ ] **Step 4: Source the native step's flat outputs from the pool**

In `NativeEngine.java`, replace the per-call `new float[...]`/`new char[...]` flat output allocations with pool requests. Add a `private final ScratchPool scratch = new ScratchPool();` field, and in `step(...)`:

```java
        BatchMarshaller.Flat f = BatchMarshaller.flatten(tasks, lut);
        int total = f.n() * BatchMarshaller.SEC_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);
        lastStepMillis = orgeStep(
                f.n(), f.matIx(), f.mass(), f.tIn(),
                f.haloT(), f.haloMat(), f.haloMass(),
                f.lutCond(), f.lutHeatCap(), f.lutVisc(), f.lutFullMass(), f.lutFluid(),
                f.lutMinFlow(), f.lutMaxMass(), f.lutGas(),
                passes, dtSeconds, tOut, massOut, matOut);
        List<float[]> t = BatchMarshaller.slice(tOut, f.n());
        List<float[]> mm = BatchMarshaller.sliceMass(massOut, f.n());
        List<char[]> matm = BatchMarshaller.sliceMat(matOut, f.n());
        List<StepResult> out = new ArrayList<>(f.n());
        for (int s = 0; s < f.n(); s++) out.add(new StepResult(t.get(s), mm.get(s), matm.get(s)));
        return out;
```

> **Critical correctness note:** `slice`/`sliceMass`/`sliceMat` must each **copy** out of the flat scratch into fresh per-section arrays (they already do — they `System.arraycopy` per section, verified in Plan-2 Task-3's `sliceMat`). The pooled flat arrays are overwritten on the next step, but the returned `StepResult` arrays are independent copies, so reuse is safe. If any `slice*` ever returns a *view* into the flat array, it must be changed to copy — but the existing implementations copy. The pool's `temp(n)` may return an array **longer** than `total`; `orgeStep` and `slice*` index only `[0, total)`, so the tail is ignored (harmless).

- [ ] **Step 5: Run the pool test, full suite, loader gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests "net.rainbowcreation.orge.engine.ScratchPoolTest" --rerun-tasks`
Expected: PASS.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green — `NativeEngine` results are still independent copies, so every engine/scheduler test is unaffected.
Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: all 3 loaders compile.

- [ ] **Step 6: Commit + push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/ScratchPool.java \
        core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java \
        core/src/test/java/net/rainbowcreation/orge/engine/ScratchPoolTest.java
git commit -m "perf(engine): pool the native step's flat temp/mass/material scratch (4 Hz GC churn)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 7 [MAIN]: verification gate — both loaders + full suite green (pre-engine)

**Files:** none (verification only).

- [ ] **Step 1: Full loader gate + suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava :core:test`
Expected: all green. End state so far: the snapshot steps only the active set; settled sections sleep and wake on edits/bucket/source-edits/seam-flux/new-in-range; the native step reuses scratch buffers. The cell-level void mask (engine) is the remaining work.

- [ ] **Step 2: Confirm clean tree**

Run: `cd /home/claude/ORGE && git status --short`
Expected: clean (everything from Tasks 1–6 committed + pushed). If anything remains, it belongs to a task above; commit under that task's message and `git push origin rebuild`.

---

## Task 8 [ENGINE]: cell-level `void` active-mask in the kernel (bit-identical) — SECONDARY optimization

**Files:**
- Modify: `ORGE-ENGINE/orge_kernel.hpp` (conduction loop + advection loops)
- Modify: `ORGE-ENGINE/sim_engine.hpp` (`advect_chunk` + the conduction path — same skip, mirrored)
- Test: `ORGE-ENGINE/tests/test_phase2b_voidmask.cpp` (create)
- Modify: `ORGE-ENGINE/tests/run_tests.sh` (build + run the new test)

> **Scope modestly (spec Decision 11):** the section-level active set already captures ~95% of the win. This task only hard-skips genuinely-`void` cells (`matIx==0` AND `cond<=0` AND `fluid==0`) inside an active section's inner loops, so a sparse-fluid-in-mostly-air active section costs less. **The void-vs-dormant correctness landmine (Decision 11) governs this entire task:**
> - `void` (`matIx==0`, `cond<=0`, `fluid==0`) is ALREADY a per-face no-flux boundary (`keff` returns 0 if either side is non-conductive). Hard-skipping it changes nothing physically — it is inert.
> - A **settled-but-CONDUCTIVE** cell (cold stone, a calm pool — `cond>0` or holds mass) must **NEVER** be voided/skipped: a voided face is a perfect insulator, so an active neighbour heating up later would have its heat flow into a never-updated cell and **vanish** (energy not conserved). Those use the section-level dormancy countdown + wake shell, NOT this mask.
> - The mask predicate is therefore the **strict genuine-void test only**, never "settled" or "quiet".

- [ ] **Step 1: Write the failing test**

Create `ORGE-ENGINE/tests/test_phase2b_voidmask.cpp`:

```cpp
// Build: g++ -std=c++20 -O2 -g -I. tests/test_phase2b_voidmask.cpp -o build/t_vm -pthread
//
// The void active-mask must (a) leave a genuine void cell untouched and not visit it as a neighbour,
// and (b) NOT skip a settled-but-conductive cell — heat from an active neighbour must still reach it
// (the void-vs-dormant correctness rule, Spec Decision 11).
#include "test_harness.hpp"
#include "orge_kernel.hpp"
#include <vector>
#include <cmath>
using namespace orge;

// LUT: 0 void (cond 0, fluid 0), 1 stone (cond>0, NOT fluid)
static MatLUT stoneLut(float* C,float* H,float* V,float* F,uint8_t* FL,float* MN,float* MX,uint8_t* G){
    return MatLUT{C,H,V,F,FL,MN,MX,G,2};
}

void test_void_cell_stays_void_and_is_skipped() {
    float C[2]={0.0f,2.0f},H[2]={1.0f,840.0f},V[2]={0,0},F[2]={0,2500.0f};
    uint8_t FL[2]={0,0}; float MN[2]={0,0},MX[2]={0,2500.0f}; uint8_t G[2]={0,0};
    MatLUT lut=stoneLut(C,H,V,F,FL,MN,MX,G);
    std::vector<uint16_t> mat(SEC_N,0);                 // all void
    std::vector<float> mass(SEC_N,0.0f), T(SEC_N,300.0f);
    std::vector<float> hT(FACE*FACES,300.0f),hM(FACE*FACES,0.0f); std::vector<uint16_t> hMt(FACE*FACES,0);
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    step_section_with_halo(mat.data(),mass.data(),T.data(),hT.data(),hMt.data(),hM.data(),
        lut,1.0f,PASS_CONDUCTION,Tout.data(),mo.data(),matOut.data());
    for (int i=0;i<SEC_N;++i) TH_CHECK_MSG(std::fabs(Tout[i]-300.0f)<1e-6f, "void cells untouched");
}

void test_settled_conductive_cell_still_receives_active_neighbour_heat() {
    // One hot stone cell next to a cold stone cell: heat MUST flow (the cold cell is conductive,
    // NOT void — it must never be masked out). This is the energy-conservation guard.
    float C[2]={0.0f,2.0f},H[2]={1.0f,840.0f},V[2]={0,0},F[2]={0,2500.0f};
    uint8_t FL[2]={0,0}; float MN[2]={0,0},MX[2]={0,2500.0f}; uint8_t G[2]={0,0};
    MatLUT lut=stoneLut(C,H,V,F,FL,MN,MX,G);
    std::vector<uint16_t> mat(SEC_N,1);                 // all stone (conductive, settled)
    std::vector<float> mass(SEC_N,2500.0f), T(SEC_N,300.0f);
    int hot=sidx(8,8,8); T[hot]=600.0f;                 // one active (hot) cell
    std::vector<float> hT(FACE*FACES,300.0f),hM(FACE*FACES,2500.0f); std::vector<uint16_t> hMt(FACE*FACES,1);
    std::vector<float> Tout(SEC_N),mo(SEC_N); std::vector<uint16_t> matOut(SEC_N);
    step_section_with_halo(mat.data(),mass.data(),T.data(),hT.data(),hMt.data(),hM.data(),
        lut,1.0f,PASS_CONDUCTION,Tout.data(),mo.data(),matOut.data());
    int cold=sidx(7,8,8);                               // a settled neighbour of the hot cell
    TH_CHECK_MSG(Tout[cold] > 300.0f, "settled-but-conductive neighbour received heat (not voided)");
}

int main(){
    run("void_skipped", test_void_cell_stays_void_and_is_skipped);
    run("conductive_not_voided", test_settled_conductive_cell_still_receives_active_neighbour_heat);
    return th_summary();
}
```

- [ ] **Step 2: Run to verify it fails / passes-as-baseline**

Run: `cd /home/claude/ORGE/ORGE-ENGINE && g++ -std=c++20 -O2 -g -I. tests/test_phase2b_voidmask.cpp -o build/t_vm -pthread && ./build/t_vm`
Expected: both PASS already (the kernel's existing `keff`-based no-flux on void plus normal conduction satisfy both). This test is the **regression guard** that the mask we add in Step 3 skips ONLY genuine void and never strands heat. If `conductive_not_voided` ever fails after Step 3, the mask predicate is too broad — narrow it back to the strict genuine-void test.

- [ ] **Step 3: Add the strict genuine-void skip to the kernel inner loops**

In `orge_kernel.hpp`, add a single predicate near the top of `step_section_with_halo` (after the identity init), and use it to skip void cells:

```cpp
    // Genuine-void test (Spec Decision 11 void-vs-dormant rule): a cell that is the void sentinel AND
    // non-conductive AND non-fluid is inert (keff already returns 0 across its faces). It is SAFE to
    // hard-skip — never a settled-but-conductive cell, which must keep receiving an active neighbour's
    // heat (a voided face would be a perfect insulator and destroy energy). This is a strict guard, NOT
    // a "settled"/"quiet" test.
    auto isVoidCell = [&](int cell) {
        uint16_t m = matIx[cell];
        return m == 0 && lut.cond[m] <= 0.0f && lut.fluid[m] == 0;
    };
```

**Conduction loop:** skip the *self* cell only (do NOT skip visiting it as a neighbour beyond what `keff` already nullifies — `keff(0, k2)=0` already makes a void neighbour contribute no flux, so the existing flux lambda is already correct; the only saving is not *computing* a void cell's own update):

```cpp
    for (int z = 0; z < SEC; ++z) for (int y = 0; y < SEC; ++y) for (int x = 0; x < SEC; ++x) {
        const int i = sidx(x, y, z);
        if (isVoidCell(i)) continue;                 // void self: nothing to update (Tout[i]=Tin[i] from init)
        const uint16_t mix = matIx[i];
        // ... unchanged conduction body ...
    }
```

**Advection loops:** the fall, horizontal-spread, and seam loops already `continue` on `!isFluid(matIx[i])`, and a void cell is not fluid — so the *self* skip is already in place. Add a neighbour guard so an active fluid cell does not attempt to flux into a genuine-void neighbour (it never could donate mass into void anyway, but the explicit skip documents intent and saves the comparison):

```cpp
            // in the horizontal-spread neighbour scan, before the same-species/air test:
            if (interior && isVoidCell(sidx(nx,ny,nz))) continue;   // never flux into genuine void
```

> **Do NOT** add any "skip settled cell" logic here — that is the section-level dormancy's job (Java side). The kernel mask is exclusively the genuine-void hard-skip.

- [ ] **Step 4: Mirror in `sim_engine.hpp` (SAME commit — bit-identical)**

Add the identical `isVoidCell` predicate to `sim_engine.hpp`'s conduction path and `advect_chunk`, using `mats.byIx(ix)`'s `cond`/`fluidFlag` (the sim_engine analogues of `lut.cond`/`lut.fluid`). Skip the self conduction update for a genuine-void cell and the flux-into-void neighbour in the spread loop, exactly as in the kernel. The predicate must be character-for-character equivalent in behaviour so the parity test stays green.

- [ ] **Step 5: Add the new test to `run_tests.sh` and run the full suite**

In `ORGE-ENGINE/tests/run_tests.sh`, add a build+run block for `test_phase2b_voidmask.cpp` (mirror the existing `advection_test` block):

```bash
echo "==> Building test_phase2b_voidmask"
$CXX $STD -O2 -g $INC tests/test_phase2b_voidmask.cpp -o build/test_phase2b_voidmask -pthread
# ... and in the run section:
echo "==> Running test_phase2b_voidmask"
./build/test_phase2b_voidmask || RC=$?
```

Run: `cd /home/claude/ORGE/ORGE-ENGINE && ./tests/run_tests.sh`
Expected: all green — including the **parity test** (kernel `Tout`/`massOut`/`matOut` == sim_engine `T_curr`/`mass_kg`/`matIx`), confirming the void skip is bit-identical across both surfaces; and `conductive_not_voided` confirming no settled-but-conductive heat is stranded.

- [ ] **Step 6: Commit (ENGINE — do NOT push)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git add orge_kernel.hpp sim_engine.hpp tests/test_phase2b_voidmask.cpp tests/run_tests.sh
git commit -m "perf(engine): cell-level genuine-void active-mask (bit-identical kernel==sim_engine)

Hard-skips only genuine void (matIx==0 && cond<=0 && fluid==0); never a settled-but-conductive
cell (void-vs-dormant rule, Spec Decision 11). Parity test stays green.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9 [ENGINE→MAIN]: rebuild `liborge.so`, integrate, bump the MAIN gitlink

**Files:**
- Build: `ORGE-ENGINE/build/liborge.so`
- Replace: `core/src/main/resources/natives/linux-x64/liborge.so`
- Submodule: merge the ENGINE work branch → ENGINE `main`, push; bump the MAIN gitlink.

> The void mask is a kernel change, so the bundled `.so` must be rebuilt even though the ABI is unchanged (no new args this plan). Mirror Plan-1 Task-11.

- [ ] **Step 1: Rebuild the native lib**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/ORGE/ORGE-ENGINE/native/build_liborge.sh /home/claude/ORGE/ORGE-ENGINE/build/liborge.so`
Expected: fresh `liborge.so` (no compile error).

- [ ] **Step 2: Copy into MAIN resources + verify identity**

```bash
cp /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
md5sum /home/claude/ORGE/ORGE-ENGINE/build/liborge.so /home/claude/ORGE/core/src/main/resources/natives/linux-x64/liborge.so
```
Expected: identical md5s.

- [ ] **Step 3: Smoke-test the native path end-to-end (MAIN)**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: all green, including any test that loads the native engine. The ABI is unchanged from Plans 1–2, so no declaration change is needed; only behaviour (void skip) changed, and it is physics-inert for genuine void.

- [ ] **Step 4: Integrate the ENGINE submodule + bump the MAIN gitlink (mirror Plan-1 Task-11)**

```bash
cd /home/claude/ORGE/ORGE-ENGINE
git checkout main && git merge --ff-only <work-branch> && git push origin main
cd /home/claude/ORGE
git add ORGE-ENGINE core/src/main/resources/natives/linux-x64/liborge.so
git commit -m "build(engine): bundle Phase-2b dormancy liborge.so (void active-mask); bump gitlink

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

> `<work-branch>` is the ENGINE branch Tasks 8 committed on. If ENGINE work landed directly on `main` locally, the `merge --ff-only` is a no-op `git push origin main`. Never `git add` ENGINE sources from MAIN — only the gitlink (`ORGE-ENGINE`) and the bundled `.so`.

- [ ] **Step 5: Final gate — both loaders compile + full suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava :core:test`
Expected: all green. **End state of Plan 3 (and Phase-2b):** a calm world stops re-simulating at 4 Hz — only the active set is snapshotted; settled sections sleep per-pass and wake on the exhaustive trigger set; the worker reuses scratch buffers; and the kernel hard-skips genuine void without ever stranding heat from a settled-but-conductive cell.

---

## Self-review notes (coverage vs spec)

| Spec item | Task(s) | How |
|---|---|---|
| **Decision 11 — per-pass settle countdown** (flow-dormant `max|Δmass|<ε` for K; thermal-dormant `max|ΔT|<ε` for K; both → asleep; either → only the live pass) | 1, 3 | `SettleCountdown` (pure, per-pass, K hysteresis, `asleep()`); `Scheduler.writeBackResults` piggybacks `maxAbsDelta` into `noteFlowDelta`/`noteThermalDelta` on the existing writeback loop (near-free). |
| **Decision 11 — snapshot steps the ACTIVE SET within range, not the whole sphere** | 2, 3 | `ActiveSet.activeWithin` filters the sphere union; `MinecraftThermalWorld.snapshot` iterates only admitted keys; unseen = active (new-in-range). |
| **Decision 11 — countdown (not instant) = hysteresis; bookkeeping piggybacks writeback** | 1, 3 | `K_SETTLE` countdown in `SettleCountdown`; `maxAbsDelta` computed in the existing per-entry loop, no new grid pass. |
| **Decision 11 — event-driven WAKE behind a pure seam, ExpectPlatform, BOTH loaders** | 2, 4, 5 | `WakeSink` (pure) impl by `ActiveSet`; `WakePlatform` `@ExpectPlatform` + Fabric mixin / NeoForge `NeighborNotifyEvent` impls; common `BlockEvent`/`FILL_BUCKET` wired in `Orge.init()`. |
| **Decision 11 — exhaustive triggers (a) block/bucket (b) source (c) seam-flux (d) new-in-range, unit-tested** | 2, 5 | (a) `BlockEvent.PLACE/BREAK` + `FILL_BUCKET` + `WakePlatform` setBlock path; (b) `ServerStoreWriteSink`→`wakeBlock` + source *block* via (a); (c) `SeamFluxWake` + boundary-face flux detect → `wakeNeighbourFlow`; (d) unseen-active rule. Unit tests: `ActiveSetTest` (all wakes), `SeamFluxWakeTest`. |
| **Decision 11 — void-vs-dormant correctness landmine** | 8 | Kernel `isVoidCell` is the STRICT genuine-void test (`matIx==0 && cond<=0 && fluid==0`); `conductive_not_voided` test proves a settled-but-conductive cell still receives active-neighbour heat (energy conserved). Dormancy (Java) never voids a cell holding mass/heat. |
| **Decision 11 — cell-level void active-mask is SECONDARY, scope modestly; bit-identical** | 8 | One ENGINE task; mirrored kernel↔`sim_engine` in the same commit; parity test green; only hard-skips genuine void. |
| **Decision 13c — scratch-buffer pool (reuse `temp[]`/`mass[]`/`matOut[]`)** | 6 | `ScratchPool` reused on the single worker thread; `NativeEngine.step` sources flat outputs from it; `slice*` still copies per-section, so reuse is safe. |
| **Lifecycle parity with `CellMaterialTracker`** | 2, 3 | `ActiveSet.forgetColumn` chained onto the existing column-unload listener; `clear()` on `SERVER_STOPPING`; server-thread confined, no locking. |

**Type consistency with Plans 1 & 2 (verified):** `StepResult.material()` → `char[]` (Task 3 fake engine, Task 6); `ThermalWorld.BatchEntry`/`Batch`/`SubchunkKey` shapes (Tasks 2,3,5); the Plan-2 3-arg `fluidReconciler.reconcile(entry, r.material(), pendingMaterials)` left intact (Task 3); `pendingMaterials` LUT untouched; native LUT array names (`lutMinFlow`/`lutMaxMass`/`lutGas`) unchanged (Task 6 just re-homes the *output* scratch). No ABI change in this plan — Task 9 rebuilds the `.so` only because the kernel's void skip changed, not its signature.

**API uncertainty flagged (per minecraft-modding skill):**
- The `WakePlatform` low-level block-change hook has **no first-class Architectury common event**. Fabric uses a `Level#setBlock` **mixin** (confirm the 1.21.11 Mojang-mapped descriptor against the sources, mirroring the existing `FlowingFluidMixin`); NeoForge uses `BlockEvent.NeighborNotifyEvent` on `NeoForge.EVENT_BUS`. If a loader's hook is hard to land, ship it as a documented no-op there for v1 — the **common** `BlockEvent.PLACE/BREAK` + `FILL_BUCKET` already cover player edits, so the gap is only programmatic edits (`/setblock`, pistons). Over-waking is harmless; under-waking is the failure mode, so the seam errs toward waking.
- `PlayerEvent.FILL_BUCKET.fill(player, level, stack, target)` — `target` is a `HitResult`; the `BlockHitResult` cast yields the targeted block pos. Confirmed the event is a **common** Architectury event in `architectury-{fabric,neoforge}-19.0.1.jar`.

**Carried cross-seam §9 transient risk — WATCH, do not change without sign-off (spec risk 9):** wetting makes the round-3 cross-seam §9 transient (per-section closed-wall gate vs. real kernel seam flux) more frequent, and the new **seam-flux wake (Task 5c)** increases legitimate cross-seam flow at dormant borders — which can surface that transient more often. The deferred fix (batch-level Σ, or engine-reported seam flux) is the proper remedy and would also let Task 5c's *conservative over-wake* (snapshot-vs-post-step face diff) become a *precise* engine-reported seam flux. **Do NOT change §9 granularity or the seam model in this slice without user sign-off** — flagged for the audit.
