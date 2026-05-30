# §7 Phase Change (v1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build DESIGN §7 — after the §8 scheduler writes new temperatures into `SectionData` each second, replace any block whose cell temperature crossed its material's boiling/freezing threshold with the target **block**, carrying temperature across. Ships water↔ice, water↔steam, lava→stone.

**Architecture:** Pure decision units (`PhaseRule`, `PhasePlanner`) behind a thin MC adapter (`MinecraftPhaseChanger`) reached through a `PhaseChanger` seam the `Scheduler` calls after write-back — mirroring §8's pure-units-behind-an-adapter split. Targets are **vanilla block ids**; the only new block is the inert gas marker `orge:steam` (no collision, no ticking, no BlockItem — all sim state stays in the `SectionData` float arrays). Mass is not carried in v1 (Phase 2); temperature carries for free because it lives in `SectionData`, untied to block identity.

**Tech Stack:** Java 21, `:core` (loader-agnostic; vanilla `net.minecraft.*` allowed, NO `net.fabricmc.*`/`net.neoforged.*`), JUnit 5, Architectury common `DeferredRegister`, the existing §2/§5/§6/§8 subsystems.

**Build env (no `java` on PATH — use exactly this):**
```
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
```
Fast unit loop: `:core:test`. Single class: `:core:test --tests 'net.rainbowcreation.orge.phase.PhaseRuleTest'`. Full both-loader gate: `build`. Headless/no-GL sandbox — `runClient`/`runData` do NOT run; verify assets by `build`, reason about JSON.

**Commit convention:** one commit per task; trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Push `origin rebuild` after each task. Work directly on `rebuild` (no worktree).

**Conventions reused:** cell index `i = x + 16*y + 256*z` (x-fastest), `x=i&15, y=(i>>4)&15, z=(i>>8)&15`; `SectionData.CELLS = 4096`. `Material` record params in order: `(id, thermalConductivity, heatCapacity, viscosity, defaultMass, molarMass, boilingPoint, freezingPoint, boilingTarget, freezingTarget, representativeBlock)`; defaults boiling `+∞`, freezing `−∞`, targets/representative `null`.

**javap-confirmed APIs (1.21.11, official mappings):** `BlockBehaviour.Properties.of()` + `.mapColor(MapColor)`, `.noCollision()`, `.noOcclusion()`, `.replaceable()`, `.instabreak()`, `.noLootTable()`, `.sound(SoundType)`, `.pushReaction(PushReaction)`, `.setId(ResourceKey<Block>)` (**required** in 1.21.11); `new Block(Properties)`; `Block.UPDATE_CLIENTS == 2`; `Level.setBlock(BlockPos, BlockState, int)`; `BuiltInRegistries.BLOCK.containsKey(Identifier)` / `.getValue(Identifier)` (DefaultedRegistry); `Registries.BLOCK : ResourceKey<Registry<Block>>`; `new BlockPos(int,int,int)`; architectury `DeferredRegister.create(String, ResourceKey<Registry<T>>)` + `.register(String, Supplier)` → `RegistrySupplier<T>` + `.register()`.

---

## File Structure

**New (core main):**
- `phase/PhaseRule.java` — pure: `(temp, Material) → Optional<Identifier>` target block id.
- `phase/PhasePlanner.java` — pure: section temps → `List<Transition>` (cellIndex, blockId).
- `phase/PhaseChanger.java` — seam interface + `NOOP`.
- `phase/MinecraftPhaseChanger.java` — the only new MC-coupled class; does `setBlock`.
- `scheduler/LiveMaterials.java` — extracted shared live material/section helpers.
- `block/ModBlocks.java` — `orge:steam` registration (common `DeferredRegister`).

**New (core resources):**
- `data/orge/orge/materials/steam.json`, `data/orge/orge/materials/ice.json`.
- `assets/orge/blockstates/steam.json`, `assets/orge/models/block/steam.json`, `assets/orge/lang/en_us.json`.

**Modified (core):**
- `scheduler/Scheduler.java` — `PhaseChanger` ctor param + post-write-back call.
- `scheduler/MinecraftThermalWorld.java` — delegate to `LiveMaterials` (behaviour-preserving).
- `Orge.java` — register blocks; construct/bind `MinecraftPhaseChanger`; pass it to `Scheduler`.
- `data/orge/orge/bindings/default.json` — add `orge:steam`, `minecraft:ice` overrides.
- `material/MaterialJsonLoader.java` — remove the now-stale "phase-change deferred" TODO comment.

**New (core test):**
- `phase/PhaseRuleTest.java`, `phase/PhasePlannerTest.java`, `phase/DefaultPhaseDataTest.java`.
- `scheduler/SchedulerTest.java` — add a recording `PhaseChanger` + 3 assertions.

DESIGN.md §7 is already updated (committed with the spec). No task needed.

---

## Task 1: PhaseRule (pure decision)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/PhaseRule.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/PhaseRuleTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PhaseRuleTest {

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    /** A material with the given phase thresholds/targets; other constants are irrelevant here. */
    private static Material mat(float boilingPoint, Identifier boilingTarget,
                               float freezingPoint, Identifier freezingTarget) {
        return new Material(Identifier.fromNamespaceAndPath("orge", "x"),
                0.6f, 1000f, 0f, 1000f, 0.018f,
                boilingPoint, freezingPoint, boilingTarget, freezingTarget, null);
    }

    /** The record defaults: never transitions. */
    private static Material inert() {
        return mat(Float.POSITIVE_INFINITY, null, Float.NEGATIVE_INFINITY, null);
    }

    @Test
    void boilsAboveBoilingPoint() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.of(id("air")), PhaseRule.targetBlock(400f, water));
    }

    @Test
    void freezesBelowFreezingPoint() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.of(id("ice")), PhaseRule.targetBlock(250f, water));
    }

    @Test
    void noTransitionInsideTheBand() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(300f, water));
    }

    @Test
    void exactThresholdsAreNoOps() {
        Material water = mat(373.15f, id("air"), 273.15f, id("ice"));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(373.15f, water), "boiling uses strict >");
        assertEquals(Optional.empty(), PhaseRule.targetBlock(273.15f, water), "freezing uses strict <");
    }

    @Test
    void nullTargetsNeverTransitionEvenPastThreshold() {
        Material noBoil = mat(373.15f, null, 273.15f, null);
        assertEquals(Optional.empty(), PhaseRule.targetBlock(9999f, noBoil));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(0f, noBoil));
    }

    @Test
    void infiniteDefaultsNeverTransition() {
        assertEquals(Optional.empty(), PhaseRule.targetBlock(5000f, inert()));
        assertEquals(Optional.empty(), PhaseRule.targetBlock(1f, inert()));
    }

    @Test
    void boilingTakesPrecedenceWhenBothCouldFire() {
        // Contrived overlap: boil at 300 -> A, freeze at 400 -> B; temp 350 satisfies both.
        Material m = mat(300f, id("a"), 400f, id("b"));
        assertEquals(Optional.of(id("a")), PhaseRule.targetBlock(350f, m), "boiling checked first");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.PhaseRuleTest'`
Expected: FAIL — `PhaseRule` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.Optional;

/**
 * The pure §7 phase-change decision: given a cell's new temperature and its current
 * {@link Material}, return the id of the block it should become, or empty. Boiling is
 * checked first; both tests use strict inequalities, so a cell exactly at a threshold is a
 * no-op. Null targets / ±∞ default thresholds (the record defaults) never transition.
 *
 * <p>{@code boilingTarget}/{@code freezingTarget} are the <b>block id to place</b> (the
 * resulting block's own thermal behaviour and reverse transition come from its binding/
 * material). No Minecraft world access — fully unit-testable.</p>
 */
public final class PhaseRule {

    private PhaseRule() {}

    public static Optional<Identifier> targetBlock(float temperatureK, Material current) {
        if (current.boilingTarget() != null && temperatureK > current.boilingPoint()) {
            return Optional.of(current.boilingTarget());
        }
        if (current.freezingTarget() != null && temperatureK < current.freezingPoint()) {
            return Optional.of(current.freezingTarget());
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.PhaseRuleTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/PhaseRule.java core/src/test/java/net/rainbowcreation/orge/phase/PhaseRuleTest.java
git commit -m "feat(phase): PhaseRule — pure temp+material -> target block (DESIGN §7)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 2: PhasePlanner (pure per-section plan)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/PhasePlanner.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/PhasePlannerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class PhasePlannerTest {

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    private static Material water() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "water"),
                0.6f, 1000f, 0f, 1000f, 0.018f,
                373.15f, 273.15f, id("air"), id("ice"), null);
    }

    /** Inert: never transitions. */
    private static Material air() {
        return new Material(Identifier.fromNamespaceAndPath("orge", "air"),
                0.026f, 1005f, 0f, 1.2f, 0.029f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    private static float[] fill(float v) {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, v);
        return t;
    }

    private static final Predicate<Identifier> ALL_EXIST = x -> true;

    @Test
    void uniformBoilingSectionTransitionsEveryCell() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), allWater, ALL_EXIST);
        assertEquals(SectionData.CELLS, plan.size());
        assertEquals(0, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void heterogeneousSectionTransitionsOnlyReactiveCells() {
        // Even cells water, odd cells air; all at 400 K. Only water boils.
        IntFunction<Material> mix = i -> (i % 2 == 0) ? water() : air();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), mix, ALL_EXIST);
        assertEquals(SectionData.CELLS / 2, plan.size());
        for (PhasePlanner.Transition t : plan) {
            assertEquals(0, t.cellIndex() % 2, "only even (water) cells transition");
            assertEquals(id("air"), t.blockId());
        }
    }

    @Test
    void targetBlocksThatDoNotExistAreSkipped() {
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(fill(400f), allWater, x -> false);
        assertTrue(plan.isEmpty(), "no transition when the target block is not registered");
    }

    @Test
    void recordsTheCorrectCellIndex() {
        // One hot cell (index 1234) in an otherwise in-band section.
        float[] temps = fill(300f);
        temps[1234] = 400f;
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, allWater, ALL_EXIST);
        assertEquals(1, plan.size());
        assertEquals(1234, plan.get(0).cellIndex());
        assertEquals(id("air"), plan.get(0).blockId());
    }

    @Test
    void freezingProducesTheFreezeTarget() {
        float[] temps = fill(250f);
        IntFunction<Material> allWater = i -> water();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(temps, allWater, ALL_EXIST);
        assertEquals(Set.of(id("ice")), Set.copyOf(plan.stream().map(PhasePlanner.Transition::blockId).toList()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.PhasePlannerTest'`
Expected: FAIL — `PhasePlanner` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Builds one section's phase-change plan: for each of the {@value SectionData#CELLS} cells,
 * ask {@link PhaseRule} whether its temperature crosses a threshold, and if the resulting
 * target block exists, record a {@link Transition}. Pure (no Minecraft world access) — the
 * per-cell {@link Material} and the block-exists check are injected, so it tests with fakes.
 * The live caller supplies {@code BuiltInRegistries.BLOCK::containsKey} and a block→material
 * lookup; see {@code MinecraftPhaseChanger}.
 */
public final class PhasePlanner {

    /** One cell's transition: section-local cell index (x+16y+256z) → block id to place. */
    public record Transition(int cellIndex, Identifier blockId) {}

    private PhasePlanner() {}

    public static List<Transition> plan(float[] temperatures,
                                        IntFunction<Material> cellMaterial,
                                        Predicate<Identifier> blockExists) {
        List<Transition> out = new ArrayList<>();
        for (int i = 0; i < SectionData.CELLS; i++) {
            Optional<Identifier> target = PhaseRule.targetBlock(temperatures[i], cellMaterial.apply(i));
            if (target.isPresent() && blockExists.test(target.get())) {
                out.add(new Transition(i, target.get()));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.PhasePlannerTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/PhasePlanner.java core/src/test/java/net/rainbowcreation/orge/phase/PhasePlannerTest.java
git commit -m "feat(phase): PhasePlanner — per-cell section plan (skips unregistered targets)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 3: PhaseChanger seam (+ NOOP)

A tiny interface so the `Scheduler` stays Minecraft-free. No dedicated test — it is exercised by `SchedulerTest` (Task 4) and implemented for real in Task 8.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/PhaseChanger.java`

- [ ] **Step 1: Write the interface**

```java
package net.rainbowcreation.orge.phase;

import net.rainbowcreation.orge.scheduler.ThermalWorld;

/**
 * The §7 seam the {@link net.rainbowcreation.orge.scheduler.Scheduler} calls once per
 * written section, after a successful write-back, to apply phase transitions. Keeps the
 * scheduler loader-free; the live implementation ({@code MinecraftPhaseChanger}) reads the
 * just-written temperatures + current blocks and places target blocks on the server thread.
 */
public interface PhaseChanger {

    /** Apply phase changes for one written section (server thread). */
    void applyPhaseChanges(ThermalWorld.BatchEntry entry);

    /** A no-op used in tests and any configuration without phase change. */
    PhaseChanger NOOP = entry -> {};
}
```

- [ ] **Step 2: Verify it compiles**

Run: `... ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/PhaseChanger.java
git commit -m "feat(phase): PhaseChanger seam + NOOP (keeps Scheduler MC-free)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 4: Wire PhaseChanger into the Scheduler

Add a 5-arg constructor taking a `PhaseChanger`; keep the existing 4-arg one delegating to `NOOP` (so the existing `SchedulerTest` + `SchedulerEngineIntegrationTest` compile unchanged). In `complete(...)`, call `phaseChanger.applyPhaseChanges(entry)` right after each successful `writeBack`.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java`

- [ ] **Step 1: Write the failing test — add to `SchedulerTest`**

Add this recording fake as a nested class inside `SchedulerTest` (beside `FakeRunner`/`FakeWorld`):

```java
    private static final class RecordingPhaseChanger
            implements net.rainbowcreation.orge.phase.PhaseChanger {
        final List<SubchunkKey> applied = new ArrayList<>();
        @Override public void applyPhaseChanges(ThermalWorld.BatchEntry entry) {
            applied.add(entry.key());
        }
    }
```

Add these three tests:

```java
    @Test
    void phaseChangeRunsForEachWrittenEntryOnASuccessfulStep() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        for (int i = 0; i < 20; i++) s.onServerTick();
        runner.done = true;
        s.onServerTick();

        assertEquals(List.of(new SubchunkKey(0, 0, 0)), phase.applied,
                "phase change applied once for the single written section");
    }

    @Test
    void phaseChangeDoesNotRunWhenTheStepFails() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        for (int i = 0; i < 20; i++) s.onServerTick();
        runner.failure = new RuntimeException("boom");
        runner.done = true;
        s.onServerTick();

        assertTrue(phase.applied.isEmpty(), "no phase change on a failed step (previous temps held)");
    }

    @Test
    void phaseChangeDoesNotRunWhenTheDeadlineIsMissedAndCancelled() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        RecordingPhaseChanger phase = new RecordingPhaseChanger();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker(), phase);

        for (int i = 0; i < 20; i++) s.onServerTick();
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();

        assertTrue(phase.applied.isEmpty(), "no phase change when the step is cancelled");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SchedulerTest'`
Expected: FAIL — the 5-arg `Scheduler` constructor does not exist (compilation error).

- [ ] **Step 3: Modify `Scheduler.java`**

Add the import near the other imports:

```java
import net.rainbowcreation.orge.phase.PhaseChanger;
```

Add the field beside `private final Worker worker;`:

```java
    private final PhaseChanger phaseChanger;
```

Replace the existing constructor with these two:

```java
    /** Backwards-compatible constructor: no phase change (used by unit tests). */
    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker) {
        this(engine, world, runner, worker, PhaseChanger.NOOP);
    }

    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker,
                     PhaseChanger phaseChanger) {
        this.engine = engine;
        this.world = world;
        this.runner = runner;
        this.worker = worker;
        this.phaseChanger = phaseChanger;
    }
```

In `complete(...)`, replace the write-back loop:

```java
        int n = Math.min(results.size(), pendingEntries.size());
        for (int i = 0; i < n; i++) {
            ThermalWorld.BatchEntry entry = pendingEntries.get(i);
            float[] cleaned = StepValidator.clean(results.get(i), entry.task().temperature());
            world.writeBack(entry, cleaned);
        }
        toIdle();
```

with (phase change applied per entry, right after its temps are persisted — DESIGN §7 "after reading back new temps"):

```java
        int n = Math.min(results.size(), pendingEntries.size());
        for (int i = 0; i < n; i++) {
            ThermalWorld.BatchEntry entry = pendingEntries.get(i);
            float[] cleaned = StepValidator.clean(results.get(i), entry.task().temperature());
            world.writeBack(entry, cleaned);
            phaseChanger.applyPhaseChanges(entry);
        }
        toIdle();
```

- [ ] **Step 4: Run the full scheduler suite to verify pass + no regressions**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.*'`
Expected: PASS — the 3 new `SchedulerTest` cases plus all existing scheduler tests (incl. `SchedulerEngineIntegrationTest`, which uses the 4-arg constructor) stay green.

- [ ] **Step 5: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java
git commit -m "feat(phase): Scheduler applies PhaseChanger after each write-back

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 5: Extract LiveMaterials (shared live world helper)

`MinecraftPhaseChanger` (Task 8) needs the same block→material lookup and chunk/section access that `MinecraftThermalWorld` already has as private members. Extract them into a public helper so both reuse it (DRY). Behaviour-preserving — guarded by the existing §8 scheduler integration test.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/LiveMaterials.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`

- [ ] **Step 1: Create `LiveMaterials.java`** (lifts the existing logic verbatim)

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialBindings;

/**
 * Live (server-thread) helpers shared by the Minecraft-coupled scheduler/phase adapters:
 * the block→{@link Material} lookup (via the live block-tag bridge) and chunk/section/cell
 * access that never forces generation. Extracted from {@link MinecraftThermalWorld} so
 * {@code MinecraftPhaseChanger} reuses the exact same logic.
 */
public final class LiveMaterials {

    private LiveMaterials() {}

    /** Live tag-membership bridge — §6's {@link MaterialBindings.TagMembership} consumer. */
    public static final MaterialBindings.TagMembership LIVE_TAGS = (tagId, blockId) -> {
        // getValue returns the default (air) for an unregistered id rather than null; safe
        // because callers only pass ids from BuiltInRegistries.BLOCK.getKey(block).
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        return BuiltInRegistries.BLOCK.wrapAsHolder(BuiltInRegistries.BLOCK.getValue(blockId)).is(tag);
    };

    /** The {@link Material} bound to {@code block} in the given active materials state. */
    public static Material materialFor(Block block, ActiveMaterials.State mats) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        Identifier matId = mats.bindings().materialFor(blockId, LIVE_TAGS);
        return mats.registry().getOrFallback(matId);
    }

    /** A loaded chunk, or null if not currently loaded (never forces generation). */
    public static LevelChunk loadedChunk(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    /** The chunk's section at vanilla sectionY, or null if out of the chunk's Y range. */
    public static LevelChunkSection sectionOrNull(LevelChunk chunk, int sectionY) {
        int idx = chunk.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return null;
        }
        return chunk.getSection(idx);
    }

    /** The block at section-local cell index i (x-fastest, x+16y+256z); 0..15 per axis. */
    public static Block blockAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z).getBlock();
    }
}
```

- [ ] **Step 2: Update `MinecraftThermalWorld.java` to delegate**

Delete the private `LIVE_TAGS` field, the private `materialFor(Block, ActiveMaterials.State)` method, and the private static `blockAt`, `loadedChunk`, `sectionOrNull` methods. Replace every call site:
- `materialFor(blockAt(section, i), mats)` → `LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats)` (appears in `snapshot` and `neighbor`).
- `loadedChunk(level, …)` → `LiveMaterials.loadedChunk(level, …)` (in `snapshot`, `addForcedSections`, `neighbor`).
- `sectionOrNull(chunk, …)` → `LiveMaterials.sectionOrNull(chunk, …)` (in `snapshot`, `neighbor`).

Remove now-unused imports (`Registries`, `TagKey`, `MaterialBindings` if no longer referenced; keep `BuiltInRegistries`/`Block`/`LevelChunk`/`LevelChunkSection` if still used elsewhere). Let the compiler guide which imports to drop.

- [ ] **Step 3: Run the scheduler suite to verify no behaviour change**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.*'`
Expected: PASS — including `SchedulerEngineIntegrationTest` (the live-engine hot-cell diffusion test), proving the extraction preserved behaviour.

- [ ] **Step 4: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/LiveMaterials.java core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java
git commit -m "refactor(scheduler): extract LiveMaterials (shared block->material + section access)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 6: Register the orge:steam block (+ assets)

The one new block — an inert gas marker. Common Architectury `DeferredRegister`; **explicitly `setId(...)`** on the Properties (required in 1.21.11). No `BlockItem`, no ticking, no collision. Assets are hand-authored (no `runData` in this sandbox); the model is empty (invisible in v1 — visual steam is Phase-2 polish, consistent with the "dumb marker" perf principle).

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/block/ModBlocks.java`
- Create: `core/src/main/resources/assets/orge/blockstates/steam.json`
- Create: `core/src/main/resources/assets/orge/models/block/steam.json`
- Create: `core/src/main/resources/assets/orge/lang/en_us.json`
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java` (call `ModBlocks.register()`)

- [ ] **Step 1: Create `ModBlocks.java`**

```java
package net.rainbowcreation.orge.block;

import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.rainbowcreation.orge.Orge;

/**
 * ORGE's block registrations (common Architectury {@link DeferredRegister}, both loaders).
 *
 * <p>Core registers a new block ONLY for a concept vanilla lacks (DESIGN §7): {@code orge:steam},
 * the inert gas marker produced when water boils. It carries NO simulation data — temperature
 * lives in the per-cell {@link net.rainbowcreation.orge.section.SectionData} arrays — and has no
 * collision, no ticking, no {@code BlockItem}, so it stays a cheap 1 Hz-driven marker. Vanilla
 * phase targets (ice, stone) are overrides, never re-created here.</p>
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Orge.MOD_ID, Registries.BLOCK);

    private static final ResourceKey<Block> STEAM_KEY =
            ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Orge.MOD_ID, "steam"));

    /** Inert gas: air-like (no collision/occlusion, replaceable, instabreak, no loot, no item). */
    public static final RegistrySupplier<Block> STEAM = BLOCKS.register("steam", () -> new Block(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE)
                    .noCollision()
                    .noOcclusion()
                    .replaceable()
                    .instabreak()
                    .noLootTable()
                    .sound(SoundType.EMPTY)
                    .pushReaction(PushReaction.DESTROY)
                    .setId(STEAM_KEY)));   // 1.21.11 requires the block id be set on Properties

    private ModBlocks() {}

    /** Flush the deferred registrations into the live registries (call once from Orge.init). */
    public static void register() {
        BLOCKS.register();
    }
}
```

- [ ] **Step 2: Create the assets**

`core/src/main/resources/assets/orge/blockstates/steam.json`:

```json
{
  "variants": {
    "": { "model": "orge:block/steam" }
  }
}
```

`core/src/main/resources/assets/orge/models/block/steam.json` (no elements → renders nothing; steam is invisible in v1):

```json
{
  "elements": []
}
```

`core/src/main/resources/assets/orge/lang/en_us.json`:

```json
{
  "block.orge.steam": "Steam"
}
```

- [ ] **Step 3: Call `ModBlocks.register()` from `Orge.init`**

In `core/src/main/java/net/rainbowcreation/orge/Orge.java`, add the import:

```java
import net.rainbowcreation.orge.block.ModBlocks;
```

Inside `init()`, immediately after `initialized = true;` and the opening `LOGGER.info(...)` line, add (registrations must flush before the world loads):

```java
        // DESIGN §7 — register ORGE's blocks (the inert orge:steam gas marker). Must run
        // during mod init, before any world loads.
        ModBlocks.register();
```

- [ ] **Step 4: Build both loaders (registration is only exercised by a real load — the build is the gate)**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build`
Expected: BUILD SUCCESSFUL — both `fabric-1.21` and `neoforge-1.21` remapped jars build with `orge:steam` registered and the assets packaged; all `:core` tests stay green.

- [ ] **Step 5: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/block/ModBlocks.java core/src/main/resources/assets/orge/blockstates/steam.json core/src/main/resources/assets/orge/models/block/steam.json core/src/main/resources/assets/orge/lang/en_us.json core/src/main/java/net/rainbowcreation/orge/Orge.java
git commit -m "feat(phase): register inert orge:steam gas block + assets (DESIGN §7)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 7: Phase-change datapack (steam + ice materials, bindings)

Add the steam and ice materials and bind the new/overridden blocks so the cycles resolve: water boils → steam (condenses back < 373.15), water freezes → ice (melts back > 273.15), lava freezes → stone (already bound via `#c:stones`). A classpath test guards the shipped JSON against typos.

**Files:**
- Create: `core/src/main/resources/data/orge/orge/materials/steam.json`
- Create: `core/src/main/resources/data/orge/orge/materials/ice.json`
- Modify: `core/src/main/resources/data/orge/orge/bindings/default.json`
- Test: `core/src/test/java/net/rainbowcreation/orge/phase/DefaultPhaseDataTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.phase;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shipped default ORGE datapack: the steam + ice materials parse and carry the
 * §7 phase fields that close the water↔steam and water↔ice cycles. Reads the real resource
 * files from the classpath, so a typo in the JSON fails here.
 */
class DefaultPhaseDataTest {

    private static Identifier orge(String path) { return Identifier.fromNamespaceAndPath("orge", path); }
    private static Identifier mc(String path) { return Identifier.fromNamespaceAndPath("minecraft", path); }

    private static JsonElement resource(String path) {
        try (InputStream in = DefaultPhaseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing classpath resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ActiveMaterials.State loadDefaultPack() {
        Map<Identifier, JsonElement> materials = new LinkedHashMap<>();
        for (String id : List.of("air", "water", "lava", "generic_solid", "steam", "ice")) {
            materials.put(orge(id), resource("/data/orge/orge/materials/" + id + ".json"));
        }
        List<JsonElement> bindings = List.of(resource("/data/orge/orge/bindings/default.json"));
        return ActiveMaterials.buildState(materials, bindings);
    }

    @Test
    void steamMaterialCondensesBackToWater() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material steam = reg.get(orge("steam")).orElseThrow();
        assertEquals(373.15f, steam.freezingPoint(), 0.01f);
        assertEquals(mc("water"), steam.freezingTarget(), "steam condenses back to water below 373.15 K");
    }

    @Test
    void iceMaterialMeltsBackToWater() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material ice = reg.get(orge("ice")).orElseThrow();
        assertEquals(273.15f, ice.boilingPoint(), 0.01f);
        assertEquals(mc("water"), ice.boilingTarget(), "ice melts to water above 273.15 K");
    }

    @Test
    void waterBoilsToSteamAndFreezesToIce() {
        MaterialRegistry reg = loadDefaultPack().registry();
        Material water = reg.get(orge("water")).orElseThrow();
        assertEquals(orge("steam"), water.boilingTarget(), "water boils to the orge:steam block");
        assertEquals(mc("ice"), water.freezingTarget(), "water freezes to minecraft:ice");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.DefaultPhaseDataTest'`
Expected: FAIL — `steam.json` / `ice.json` resources are missing (assertNotNull fails).

- [ ] **Step 3: Create the material JSON**

`core/src/main/resources/data/orge/orge/materials/steam.json`:

```json
{
  "thermal_conductivity": 0.025,
  "heat_capacity": 2080,
  "default_mass": 0.6,
  "molar_mass": 0.018,
  "freezing_point": 373.15,
  "freezing_target": "minecraft:water",
  "representative_block": "orge:steam"
}
```

`core/src/main/resources/data/orge/orge/materials/ice.json`:

```json
{
  "thermal_conductivity": 2.2,
  "heat_capacity": 2050,
  "default_mass": 917,
  "molar_mass": 0.018,
  "boiling_point": 273.15,
  "boiling_target": "minecraft:water",
  "representative_block": "minecraft:ice"
}
```

- [ ] **Step 4: Update `bindings/default.json`**

Replace the file with (adds the `orge:steam` and `minecraft:ice` overrides; keeps the existing ones):

```json
{
  "tags": [
    { "tag": "c:stones", "material": "orge:generic_solid" }
  ],
  "overrides": {
    "minecraft:air":   "orge:air",
    "minecraft:water": "orge:water",
    "minecraft:lava":  "orge:lava",
    "minecraft:ice":   "orge:ice",
    "orge:steam":      "orge:steam"
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.phase.DefaultPhaseDataTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/resources/data/orge/orge/materials/steam.json core/src/main/resources/data/orge/orge/materials/ice.json core/src/main/resources/data/orge/orge/bindings/default.json core/src/test/java/net/rainbowcreation/orge/phase/DefaultPhaseDataTest.java
git commit -m "feat(phase): steam + ice materials and bindings (close water cycles)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 8: MinecraftPhaseChanger (live adapter) + Orge.init wiring

The only new Minecraft-coupled class. It reads the just-written temps + current blocks for one section, runs `PhasePlanner`, and `setBlock`s each transition (server thread, `UPDATE_CLIENTS` only). Then wire it into `Orge.init`: construct it, bind/unbind on the existing server lifecycle listeners, and pass it to the 5-arg `Scheduler` constructor.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/phase/MinecraftPhaseChanger.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java`

- [ ] **Step 1: Create `MinecraftPhaseChanger.java`**

```java
package net.rainbowcreation.orge.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.scheduler.ThermalWorld;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;

/**
 * Live {@link PhaseChanger} over a running {@link MinecraftServer} (DESIGN §7). After the
 * scheduler writes a section's new temperatures, this evaluates each cell against its
 * material's boiling/freezing thresholds ({@link PhasePlanner}) and places the target block
 * with {@link Block#UPDATE_CLIENTS} (no neighbour/physics cascade). Server-thread only;
 * mirrors {@code MinecraftThermalWorld}'s server-binding pattern and reuses {@link LiveMaterials}.
 *
 * <p>Temperature is left untouched in {@link SectionData}, so it carries across the swap (v1
 * carries temperature only; mass is Phase 2). §8 rescans geometry every snapshot, so the new
 * block's material is picked up next tick with no invalidation plumbing.</p>
 */
public final class MinecraftPhaseChanger implements PhaseChanger {

    private final SectionStoreManager stores;
    private volatile MinecraftServer server;

    public MinecraftPhaseChanger(SectionStoreManager stores) {
        this.stores = stores;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    @Override
    public void applyPhaseChanges(ThermalWorld.BatchEntry entry) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        ServerLevel level = levelFor(srv, entry.dimension());
        if (level == null) {
            return;
        }
        SubchunkKey key = entry.key();
        LevelChunk chunk = LiveMaterials.loadedChunk(level, key.cx(), key.cz());
        if (chunk == null) {
            return; // unloaded since the snapshot
        }
        LevelChunkSection section = LiveMaterials.sectionOrNull(chunk, key.sectionY());
        if (section == null) {
            return;
        }
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(key.cx(), key.cz())) {
            return;
        }
        SectionData data = store.get(key);

        // Read temps without forcing a UNIFORM→FULL promotion (temperatureAt works for both forms).
        float[] temps = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            temps[i] = data.temperatureAt(i);
        }

        ActiveMaterials.State mats = ActiveMaterials.current();
        List<PhasePlanner.Transition> plan = PhasePlanner.plan(
                temps,
                i -> LiveMaterials.materialFor(LiveMaterials.blockAt(section, i), mats),
                BuiltInRegistries.BLOCK::containsKey);
        if (plan.isEmpty()) {
            return;
        }

        int ox = key.cx() << 4;
        int oy = key.sectionY() << 4;
        int oz = key.cz() << 4;
        for (PhasePlanner.Transition t : plan) {
            int i = t.cellIndex();
            int x = i & 15;
            int y = (i >> 4) & 15;
            int z = (i >> 8) & 15;
            BlockState state = BuiltInRegistries.BLOCK.getValue(t.blockId()).defaultBlockState();
            level.setBlock(new BlockPos(ox + x, oy + y, oz + z), state, Block.UPDATE_CLIENTS);
        }
    }

    private static ServerLevel levelFor(MinecraftServer srv, Identifier dimension) {
        for (ServerLevel level : srv.getAllLevels()) {
            if (level.dimension().identifier().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `... ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Wire it into `Orge.java`**

Add the import:

```java
import net.rainbowcreation.orge.phase.MinecraftPhaseChanger;
```

Add a field beside `private static Scheduler scheduler;`:

```java
    private static MinecraftPhaseChanger phaseChanger;
```

In `init()`, in the §8 block, replace:

```java
        thermalWorld = new MinecraftThermalWorld(SECTION_STORES);
        Worker serverWorker = new Worker(
                UUID.randomUUID(), true,
                Scheduler.DEFAULT_RANGE, Scheduler.MAX_RANGE,
                Scheduler.COMPUTE_BUDGET_MILLIS, Scheduler.ON_TIME_TICKS_TO_CLIMB);
        scheduler = new Scheduler(engine, thermalWorld, stepRunner, serverWorker);

        LifecycleEvent.SERVER_STARTED.register(server -> thermalWorld.bindServer(server));
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            thermalWorld.unbindServer();
            stepRunner.shutdown();
        });
```

with:

```java
        thermalWorld = new MinecraftThermalWorld(SECTION_STORES);
        phaseChanger = new MinecraftPhaseChanger(SECTION_STORES);
        Worker serverWorker = new Worker(
                UUID.randomUUID(), true,
                Scheduler.DEFAULT_RANGE, Scheduler.MAX_RANGE,
                Scheduler.COMPUTE_BUDGET_MILLIS, Scheduler.ON_TIME_TICKS_TO_CLIMB);
        scheduler = new Scheduler(engine, thermalWorld, stepRunner, serverWorker, phaseChanger);

        // DESIGN §7 — phase change reacts to the temps the scheduler writes back each second.
        LifecycleEvent.SERVER_STARTED.register(server -> {
            thermalWorld.bindServer(server);
            phaseChanger.bindServer(server);
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            thermalWorld.unbindServer();
            phaseChanger.unbindServer();
            stepRunner.shutdown();
        });
```

- [ ] **Step 4: Full both-loader build (the headless gate)**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build`
Expected: BUILD SUCCESSFUL — both remapped jars build with `liborge.so` bundled and `orge:steam` registered; all `:core` tests (existing + new phase suite) pass.

- [ ] **Step 5: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/phase/MinecraftPhaseChanger.java core/src/main/java/net/rainbowcreation/orge/Orge.java
git commit -m "feat(phase): MinecraftPhaseChanger live adapter + wire into Orge.init

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 9: Tidy, memory, close-out

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/material/MaterialJsonLoader.java`
- Create: `/home/claude/.claude/projects/-home-claude-ORGE/memory/orge-phase-change.md`
- Modify: `/home/claude/.claude/projects/-home-claude-ORGE/memory/MEMORY.md`

- [ ] **Step 1: Remove the now-stale TODO in `MaterialJsonLoader.java`**

The trailing `// TODO(phase: phase-change): wire MaterialBindings.TagMembership to live block tags …` block is obsolete — the live bridge now exists as `LiveMaterials.LIVE_TAGS`. Delete that comment block (the §8 `MinecraftThermalWorld`/`LiveMaterials` already wired it). Leave the rest of the class unchanged.

- [ ] **Step 2: Full build + test once more**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build`
Expected: BUILD SUCCESSFUL; full `:core` suite green; both jars build.

- [ ] **Step 3: Write the memory entry**

Create `orge-phase-change.md` (frontmatter `type: project`): what shipped (§7 server-side second-boundary phase pass; pure `PhaseRule`/`PhasePlanner` behind the `PhaseChanger` seam + `MinecraftPhaseChanger`; `Scheduler` calls it after write-back); the resolved decisions (targets are **block ids**; new block only for new concepts → inert `orge:steam`, vanilla overrides for ice/stone; carry temperature only, mass deferred to Phase 2; `setBlock` `UPDATE_CLIENTS` only; strict-inequality stability; `LiveMaterials` extraction); deferred follow-ons (gas/liquid flow + viscosity + buoyancy + discrete fluid levels + mass conservation = Phase 2; broader vanilla-block material coverage = pure datapack; steam visual render layer). Link `[[orge-scheduler]]`, `[[orge-material-model]]`, `[[orge-section-store]]`, `[[orge-engine-ffi]]`. Add a one-line pointer to `MEMORY.md`.

- [ ] **Step 4: Commit & push**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/material/MaterialJsonLoader.java
git commit -m "chore(phase): drop obsolete TagMembership TODO (live bridge now in LiveMaterials)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```
(The memory files live outside the repo — no commit needed for them.)

---

## Final whole-subsystem review

After Task 9, run the proven close-out: a fresh whole-subsystem review (spec-compliance + code-quality) reading the final §7 code and re-running `:core:test` + `build`, then `superpowers:finishing-a-development-branch` (user convention: push to `origin/rebuild`, no PR / no merge to main while v1 is incomplete).

---

## Self-review notes (coverage check against the spec)

- Spec components → tasks: `PhaseRule` (T1), `PhasePlanner` (T2), `PhaseChanger`+NOOP (T3), `Scheduler` hook (T4), shared helper (T5), `orge:steam` block+assets (T6), datapack steam/ice/bindings (T7), `MinecraftPhaseChanger`+`Orge.init` (T8), tidy+memory (T9). DESIGN §7 already updated with the spec commit.
- Decisions covered: new-block-only-for-new-concepts (T6 comment + invisible marker), targets-are-block-ids (T1/T2/T7 data), carry-temperature-only (T8 leaves `SectionData` temps untouched; no mass writes), `UPDATE_CLIENTS` only (T8), strict-inequality stability (T1 tests), geometry auto-rescan (no task needed — §8 behaviour), unloaded-section skip (T8 guards).
- Types consistent across tasks: `PhasePlanner.Transition(int cellIndex, Identifier blockId)`, `PhaseChanger.applyPhaseChanges(ThermalWorld.BatchEntry)`, `LiveMaterials.materialFor/blockAt/loadedChunk/sectionOrNull`, 5-arg `Scheduler` ctor.
- No placeholders: every code step shows full code; every run step shows the exact gradle command + expected result.
