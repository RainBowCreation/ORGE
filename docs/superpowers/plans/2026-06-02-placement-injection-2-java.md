# Placement Injection — Plan 2: Java Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the engine's displace-and-inject ABI (shipped in Plan 1) into the Java scheduler so placing a movable fluid (bucket / `/setblock` / piston / dispenser) becomes a durable engine injection that displaces the incumbent gas instead of deleting it — killing both the "placed fluid appears-then-vanishes" race and the air-deletion mass leak.

**Architecture:** Placements are captured into a server-thread `PendingInjections` queue keyed `(dim, cx, cz, engineCell)` from the existing block-change wake path (a capturing `WakeSink` decorator), filtered to genuine movable→movable placements via the `CellMaterialTracker` engine-output signature. At snapshot, the queue is drained per column: each injected cell is **overridden back to its incumbent** (recorded-signature species + stored mass) so Java's reseed/seed pipeline leaves it alone, and an `EngineInjection{columnId, cell, species, mass, temp}` is emitted into the step. The step runs through a new `OrgeEngine` injection overload returning a `RegionStepResult(columns, injected[], sealedLoss[])`; the §9 `SpeciesMassLedger` gate is told the per-species `injected − sealedLoss` deltas so a legitimate placement doesn't HOLD the region. Drained intents clear only on a successful (non-held) write-back, so any number of stale in-flight steps can't lose a placement.

**Tech Stack:** Java 21, Architectury multiloader (`core` shared module), Gradle. Pure-Java logic tested in `:core:test` (no native lib); the real-`.so` path tested in `:core:integrationTest`.

**Spec:** `docs/superpowers/specs/2026-06-02-placement-injection-displace-design.md` (Part B + A4). Builds on Plan 1 (engine ABI already shipped: `NativeEngine.orgeStepWorld` has the `injCount + 5 inj arrays + ledgerOut` channel, currently fed empty).

**Plan-1 carry-ins (from the holistic review):**
- **Same-cell dedup** lives here: the engine no longer enforces "second placement dropped"; the `PendingInjections` queue is **last-write-wins per `(dim,cell)`**, collapsing multiple placements into one window (Task 3 + its test).
- **Marshalling guards** live here: `ledgerOut` is sized exactly `2*matCount` and `injSpecies` is a valid LUT index by construction (Task 2 builds them from the batch LUT).

---

## Verified codebase facts (do not re-derive; confirmed by reading the source)

- **Dim type** is `net.minecraft.resources.Identifier` everywhere, via `serverLevel.dimension().identifier()`.
- **`Material`** (`core/.../material/Material.java`) is a record. `movable()` is `Float.isFinite(viscosity())`. Accessors: `id()` (Identifier), `movable()`, `defaultMass()` (float), `defaultTemperature()` (float), `hasDefaultTemperature()` (`!Float.isNaN(defaultTemperature)`), `molarMass()`, `minMass()`, `maxMass()`, `viscosity()`, `thermalConductivity()`, `heatCapacity()`. There is **no** `fluid()` method.
- **Engine cell index** = `x + 16*y + 6144*z`, `y ∈ [0,384)`, length `RegionMarshaller.CHUNK_N = 98304`. `ColumnSectionCodec.engineY(sectionY, sy) = sectionY*16 + sy + 64`; section-local `i = x + 16*sy + 256*z` over `SectionData.CELLS = 4096`; sections `sectionY ∈ [ColumnAssembler.MIN_SECTION_Y=-4 .. MAX_SECTION_Y=19]`.
- **`OrgeEngine`** (`core/.../engine/OrgeEngine.java`) is one interface method `List<ColumnResult> stepWorld(List<ColumnTask>, List<Material> lut, double dt, int passes)` + `double lastStepMillis()`, constants `PASS_CONDUCTION=1`, `PASS_ADVECTION=2`. Implementations: `NativeEngine`, `StubEngine`, plus inline test fakes (`SeamEngine` in `RegionSchedulerTest`, `deltaEngine`/`RecordingEngine` in `SchedulerTest`). Adding a `default` overload keeps all of them compiling.
- **`ColumnTask`** = `record(int cx, int cz, char[] matIx, float[] mass, float[] temperature)`. **`ColumnResult`** = `record(char[] matIx, float[] mass, float[] temperature)`. Both arrays length `CHUNK_N`; result identity is positional.
- **`RegionMarshaller`** (`core/.../engine/RegionMarshaller.java`): `flatten(List<ColumnTask>, List<Material>) -> Flat(nCols, cx, cz, matIx, mass, tIn, LutArrays lut)` writes column `c` at flat offset `c*CHUNK_N`; `slice(matOut, massOut, tOut, nCols) -> List<ColumnResult>`. **`injColumn` therefore = 0-based position in the `List<ColumnTask>`.** `LutArrays.pack(lut)` indexes by LUT slot = `char species`.
- **`NativeEngine`** (`core/.../engine/NativeEngine.java`): native decl already has the 7 trailing injection params (lines 47-57); public 4-arg `stepWorld` passes `0 / EMPTY_INT / EMPTY_CHAR / EMPTY_FLOAT / EMPTY_FLOAT` (lines 69-76). Has a `ScratchPool scratch` (`temp(n)`/`mass(n)`→`float[≥n]`, `material(n)`→`char[≥n]`).
- **`Scheduler`** (`core/.../scheduler/Scheduler.java`): the **only** production caller. `submit()` (lines 180-208) builds `input = [e.task() for e in batch.entries()]`, `lut = batch.lut()`, `dt = nextDt()`, calls `engine.stepWorld(input, lut, dt, PASS_CONDUCTION|PASS_ADVECTION)` on the runner thread, stashing `pendingColumnResults`. `complete()`→`writeBackColumns(metDeadline)` (lines 255-288) builds a `StepValidator.SpeciesMassLedger`, feeds every column's `(after=r.mass(), before=e.task().mass(), inMat=e.task().matIx(), outMat=r.matIx(), lut)`; **if `!ledger.conserved()` it returns WITHOUT writing any column** (all-or-nothing HOLD); else writes each `world.writeBackColumn(...)`.
- **`StepValidator.SpeciesMassLedger`** (`core/.../scheduler/StepValidator.java:208+`): per-species `sumBefore`/`sumAfter` (index 0 = void, skipped), `conserved()` checks `|sumAfter[s]-sumBefore[s]| > tol` with `tol = MASS_EPSILON_PER_CELL(=1e-2f) * totalCells`. This is the gate that must learn the injection delta.
- **`MinecraftThermalWorld`** (`core/.../scheduler/MinecraftThermalWorld.java`): holds `SectionStoreManager stores`, `CellMaterialTracker cellMaterials`, `ActiveSet activeSet`; `wakeSink()` returns `activeSet`. `snapshotColumns(int range)` (263-325) builds `ColumnBatch(List<ColumnEntry>, lut.materials())`; per column it calls `columnSource(...)` then `ColumnAssembler.assemble(cx, cz, lut.materials(), src)`. Inside `columnSource` (336-370) it reads live block geometry → `geo.matIx()`, reads stored mass/temp, then `MaterialChangeReseed.apply(cellMaterials.prior(dim,key), geo.matIx(), lut.materials(), temps, mass, ambientK)` (359-362). `writeBackColumn(ColumnEntry, ColumnResult)` (373-424) slices per section, arraycopies into `SectionData`, then `recordCellMaterials` → `phaseChanger` → `fluidReconciler.reconcile`. `recordCellMaterials` (146-155) records the engine-OUTPUT species per cell into `CellMaterialTracker` (the B5 signature). `lastColumnLut` field carries the batch LUT to write-back.
- **`CellMaterialTracker`** (`core/.../scheduler/CellMaterialTracker.java`): `prior(Identifier dim, SubchunkKey key) -> Identifier[]` (per-cell recorded engine-output species, section-local index, or null); `record(dim, key, Identifier[])`. This is the self-write/incumbent signature.
- **`MaterialChangeReseed`** (`core/.../scheduler/MaterialChangeReseed.java`): `reseeds(Identifier prior, Material live) = prior != null && live.movable() && !live.id().equals(prior)`; `apply(Identifier[] prior, char[] matIx, List<Material> lut, float[] temps, float[] mass, float biomeAmbientK)` clears stale mass to `0f` for reseeding cells (skips `matIx[i]==VOID_IX(0)`). **No change needed** — overriding an injected cell back to its incumbent makes `reseeds` false there.
- **`ColumnAssembler`** (`core/.../scheduler/ColumnAssembler.java:58-73`): seeds `m.defaultMass()` only when `m.movable() && storedMass <= 0f && prior != mat`. The incumbent override (mass = stored, mat = incumbent == prior) fails this gate → no double-seed.
- **`MaterialLut`** (`core/.../scheduler/MaterialLut.java`): `indexOf(Material) -> char` (appends on first sight), `indexOf(Identifier) -> char` (0/void if unknown). Built fresh per `snapshotColumns`.
- **Wake call sites** all funnel `(dim, x, y, z)` into `WakeSink.wakeBlock`: common events `BlockEvent.PLACE/BREAK`, `PlayerEvent.FILL_BUCKET` in `Orge.java:181-203`, plus the platform setBlock hook (NeoForge `NeighborNotifyEvent`, Fabric `Level#setBlock` TAIL mixin). The reconciler's own `setBlock(UPDATE_CLIENTS)` writes also fire `wakeBlock`.

---

## File Structure

**New files (`core/src/main/java/net/rainbowcreation/orge/`):**
- `engine/EngineInjection.java` — record carrying one injection for the engine call (Task 1).
- `engine/RegionStepResult.java` — record carrying `(List<ColumnResult> columns, float[] injected, float[] sealedLoss)` (Task 1).
- `scheduler/PlacementInjectionPolicy.java` — pure decision: is a `(live, incumbent)` material pair a displacement placement? (Task 4).
- `scheduler/PendingInjections.java` — server-thread queue keyed `(dim, cx, cz, engineCell)`, last-write-wins (Task 3).

**Modified files:**
- `engine/OrgeEngine.java` — add the `default` injection overload (Task 1).
- `engine/NativeEngine.java` — override the overload: marshal injections + read `ledgerOut` (Task 2).
- `scheduler/StepValidator.java` — `SpeciesMassLedger.expect(...)` + delta-aware `conserved()` (Task 5).
- `scheduler/MinecraftThermalWorld.java` — capturing `WakeSink` + queue drain + incumbent override + injection list on `ColumnBatch` (Tasks 6, 7).
- `scheduler/ThermalWorld.java` — `ColumnBatch` gains an `injections` component (Task 7).
- `scheduler/Scheduler.java` — call the overload, feed the ledger gate, clear-on-success (Task 8).

**New tests:** colocated under `core/src/test/java/net/rainbowcreation/orge/` (`:core:test` fast tier) + one `@Tag("integration")` in the integration source set (Task 2 + Task 9).

---

## Task 1: Engine injection types + `OrgeEngine` default overload

The seam the whole plan threads through, added without breaking any existing implementation.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/EngineInjection.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/engine/RegionStepResult.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/engine/OrgeEngineInjectionOverloadTest.java`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/engine/OrgeEngineInjectionOverloadTest.java`:

```java
package net.rainbowcreation.orge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** The default injection overload must delegate to the 4-arg stepWorld and return a zero ledger,
 *  so non-native engines (StubEngine, test fakes) keep working without implementing injections. */
class OrgeEngineInjectionOverloadTest {

    /** Minimal fake: identity step, records the columns it was handed. */
    private static final class IdentityEngine implements OrgeEngine {
        List<ColumnTask> lastColumns;
        @Override public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                                      double dtSeconds, int passes) {
            lastColumns = columns;
            ColumnTask t = columns.get(0);
            return List.of(new ColumnResult(t.matIx().clone(), t.mass().clone(), t.temperature().clone()));
        }
        @Override public double lastStepMillis() { return 0.0; }
    }

    @Test
    void defaultOverloadDelegatesAndReturnsZeroLedger() {
        IdentityEngine engine = new IdentityEngine();
        char[] mat = new char[RegionMarshaller.CHUNK_N];
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        List<ColumnTask> cols = List.of(new ColumnTask(0, 0, mat, mass, temp));
        List<Material> lut = List.of(
                Material.canonical("orge:void"),     // see note: use the project's test material factory
                Material.canonical("orge:water"));

        RegionStepResult result = engine.stepWorld(cols, lut, 0.25, OrgeEngine.PASS_ADVECTION,
                List.of(new EngineInjection(0, 42, (char) 1, 1000f, 290f)));

        assertSame(cols, engine.lastColumns);                 // delegated to the 4-arg form
        assertEquals(1, result.columns().size());
        assertEquals(lut.size(), result.injected().length);    // ledger sized to the LUT
        assertEquals(lut.size(), result.sealedLoss().length);
        for (float v : result.injected())   assertEquals(0f, v);   // default ignores injections
        for (float v : result.sealedLoss()) assertEquals(0f, v);
    }
}
```

> **Note on building a `Material` in tests:** read `core/src/test/java/.../material/` for the existing test-material factory (e.g. a `Material.canonical(id)` helper, a `MaterialFixtures`, or a direct record constructor). Use whatever the existing material tests use to construct a void + water `Material`. If there is no factory, construct the record directly with its real component list (read `Material.java` for the exact constructor) — void = lightest movable with zero molar/mass, water = movable with `defaultMass` 1000. Replace the `Material.canonical(...)` calls accordingly. The test's *behavioral* asserts (delegation + zero ledger sized to LUT) are what matter.

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*OrgeEngineInjectionOverloadTest*'`
Expected: FAIL — `EngineInjection` / `RegionStepResult` / the 5-arg `stepWorld` do not exist (compile error).

- [ ] **Step 3: Create `EngineInjection`**

Create `core/src/main/java/net/rainbowcreation/orge/engine/EngineInjection.java`:

```java
package net.rainbowcreation.orge.engine;

/**
 * One placement injection for a single engine step (Part A of the placement-injection spec).
 *
 * @param columnId    0-based index of the target column in the {@code List<ColumnTask>} handed to
 *                    {@link OrgeEngine#stepWorld(java.util.List, java.util.List, double, int, java.util.List)}
 *                    (the {@code injColumn} the native expects — purely positional).
 * @param cellIndex   engine cell index {@code x + 16*y + 6144*z} within that column.
 * @param species     LUT index (char) of the species to place — an index into the same {@code List<Material>}
 *                    lut passed to {@code stepWorld}.
 * @param mass        kg to place (the legitimate seed, e.g. water 1000).
 * @param temperature K to place.
 */
public record EngineInjection(int columnId, int cellIndex, char species, float mass, float temperature) {
}
```

- [ ] **Step 4: Create `RegionStepResult`**

Create `core/src/main/java/net/rainbowcreation/orge/engine/RegionStepResult.java`:

```java
package net.rainbowcreation.orge.engine;

import java.util.List;

/**
 * Result of an injection-aware {@link OrgeEngine#stepWorld(List, List, double, int, List)} call:
 * the next-state columns (same order as the input) plus the per-species placement ledger the §9
 * gate needs. {@code injected[s]} = Σ placed mass for species {@code s}; {@code sealedLoss[s]} =
 * Σ incumbent mass deleted when no escape existed. Both are indexed by LUT species index and have
 * length {@code lut.size()}. For non-injecting engines both are all-zero.
 */
public record RegionStepResult(List<ColumnResult> columns, float[] injected, float[] sealedLoss) {
}
```

- [ ] **Step 5: Add the `default` overload to `OrgeEngine`**

In `core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java`, after the existing `stepWorld(...)` method (before `lastStepMillis()`), add:

```java
    /**
     * Injection-aware step (placement displace-and-inject, spec Part A). Applies {@code injections}
     * once before advection, then steps as usual. The default implementation IGNORES injections and
     * delegates to the 4-arg {@link #stepWorld}, returning a zero placement ledger — so engines that
     * do not support the native injection channel (stub, test fakes) keep working. {@link NativeEngine}
     * overrides this to marshal the injection arrays and read back the ledger.
     *
     * @param injections placements for this step ({@code columnId} = position in {@code columns});
     *                   an empty list ⇒ identical to the 4-arg form.
     */
    default RegionStepResult stepWorld(java.util.List<ColumnTask> columns,
                                       java.util.List<net.rainbowcreation.orge.material.Material> lut,
                                       double dtSeconds, int passes,
                                       java.util.List<EngineInjection> injections) {
        java.util.List<ColumnResult> cols = stepWorld(columns, lut, dtSeconds, passes);
        int n = lut.size();
        return new RegionStepResult(cols, new float[n], new float[n]);
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*OrgeEngineInjectionOverloadTest*'`
Expected: PASS.

- [ ] **Step 7: Confirm nothing else broke**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:compileJava :core:compileTestJava`
Expected: BUILD SUCCESSFUL (StubEngine + the inline test fakes inherit the default — no edits needed).

- [ ] **Step 8: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/EngineInjection.java \
        core/src/main/java/net/rainbowcreation/orge/engine/RegionStepResult.java \
        core/src/main/java/net/rainbowcreation/orge/engine/OrgeEngine.java \
        core/src/test/java/net/rainbowcreation/orge/engine/OrgeEngineInjectionOverloadTest.java
git commit -m "feat(engine-java): OrgeEngine injection overload + EngineInjection/RegionStepResult"
git push origin rebuild
```

---

## Task 2: `NativeEngine` override — marshal injections + read `ledgerOut`

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`
- Test: `core/src/integrationTest/java/net/rainbowcreation/orge/engine/NativeEngineInjectionIT.java` (real `.so`, `@Tag("integration")`)

> **Confirm the integration source set + tag** by reading an existing integration test (e.g. `core/src/integrationTest/java/.../WholeRegionLivePipelineTest.java` or wherever `@Tag("integration")` tests live — the research found live-`.so` tests; mirror their directory, package, `@Tag`, and the `assumeTrue(native-loaded)` guard exactly). If the integration set is under `src/test` with a tag rather than a separate `src/integrationTest`, put the new IT there instead. The Java gate is `:core:integrationTest`.

- [ ] **Step 1: Write the failing integration test**

Create the IT mirroring the existing live-pipeline tests' setup (native-load assumption + building a tiny LUT + one full-height column). Core assertions:

```java
package net.rainbowcreation.orge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class NativeEngineInjectionIT {

    // Build void/air/water materials the SAME way the other live-pipeline ITs do — read one and copy
    // its material construction. molarMass is the density rank: water(1.0) > air(0.5) > void(0.0).
    // Fields per Material's real constructor; movable() == finite viscosity.

    @Test
    void injectWaterDisplacesAirAndReportsLedger() {
        assumeTrue(NativeLoader.isLoaded(), "native liborge.so not present");  // use the real load-probe

        NativeEngine engine = new NativeEngine();
        List<Material> lut = buildLut();                 // [0]=void, [1]=air, [2]=water  (helper below)
        final char AIR = 1, WATER = 2;

        // One column: an air cell at engine index ci with a vacuum (void) cell directly below it.
        int ci = cellIndex(3, 70, 4);                    // x + 16*y + 6144*z
        char[] mat = new char[RegionMarshaller.CHUNK_N];   // all 0 == void
        float[] mass = new float[RegionMarshaller.CHUNK_N];
        float[] temp = new float[RegionMarshaller.CHUNK_N];
        mat[ci] = AIR; mass[ci] = 1.2f; temp[ci] = 300f;
        for (int i = 0; i < temp.length; i++) if (temp[i] == 0f) temp[i] = 290f;  // avoid 0K artifacts

        List<ColumnTask> cols = new ArrayList<>();
        cols.add(new ColumnTask(0, 0, mat, mass, temp));

        RegionStepResult r = engine.stepWorld(cols, lut, 0.25,
                OrgeEngine.PASS_ADVECTION,
                List.of(new EngineInjection(0, ci, WATER, 1000f, 290f)));

        ColumnResult out = r.columns().get(0);
        assertEquals(WATER, out.matIx()[ci], "target cell became water");
        assertEquals(1000f, out.mass()[ci], 1e-2f, "water placed at 1000");
        // air conserved somewhere in the column (relocated, not deleted):
        double airTotal = 0; for (int i = 0; i < out.matIx().length; i++) if (out.matIx()[i] == AIR) airTotal += out.mass()[i];
        assertEquals(1.2, airTotal, 1e-2, "air relocated, not deleted");
        // ledger reports the placement source:
        assertEquals(1000f, r.injected()[WATER], 1e-2f, "ledger booked the injected water");
        assertEquals(0f, r.sealedLoss()[AIR], 1e-2f, "no sealed loss (air had a vacuum escape)");
        assertEquals(lut.size(), r.injected().length);
    }

    private static int cellIndex(int x, int y, int z) { return x + 16 * y + 6144 * z; }
    // private static List<Material> buildLut() { ... copy from a sibling IT ... }
}
```

> Fill `buildLut()` and the `assumeTrue` native-load probe by **reading a sibling live IT** (the research located `WholeRegionLivePipelineTest`, `UnifiedFluidLivePipelineTest`, `Section11LivePipelineReproTest`) — reuse their exact `Material` construction and native-presence guard so this IT runs under the same conditions. Keep the assertions above.

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*NativeEngineInjectionIT*'`
Expected: FAIL — `NativeEngine` has no 5-arg `stepWorld`, so it inherits the default (ignores injections): the cell stays air, `injected[WATER]==0`.

- [ ] **Step 3: Override the injection overload in `NativeEngine`**

In `core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java`, replace the existing public 4-arg `stepWorld(...)` body so it delegates to a new injection-aware override (keeps one marshalling path). Add the override and make the 4-arg form call it with an empty list:

```java
    @Override
    public List<ColumnResult> stepWorld(List<ColumnTask> columns, List<Material> lut,
                                        double dtSeconds, int passes) {
        return stepWorld(columns, lut, dtSeconds, passes, java.util.List.of()).columns();
    }

    @Override
    public RegionStepResult stepWorld(List<ColumnTask> columns, List<Material> lut,
                                      double dtSeconds, int passes,
                                      List<EngineInjection> injections) {
        int matCount = lut.size();
        if (columns.isEmpty()) {
            lastStepMillis = 0.0;
            return new RegionStepResult(new ArrayList<>(), new float[matCount], new float[matCount]);
        }
        RegionMarshaller.Flat f = RegionMarshaller.flatten(columns, lut);
        int total = f.nCols() * RegionMarshaller.CHUNK_N;
        float[] tOut = scratch.temp(total);
        float[] massOut = scratch.mass(total);
        char[] matOut = scratch.material(total);
        LutArrays L = f.lut();

        int injCount = injections.size();
        int[] injCol;
        int[] injCell;
        char[] injSp;
        float[] injMs;
        float[] injTp;
        float[] ledgerOut;
        if (injCount == 0) {
            injCol = EMPTY_INT; injCell = EMPTY_INT; injSp = EMPTY_CHAR;
            injMs = EMPTY_FLOAT; injTp = EMPTY_FLOAT; ledgerOut = EMPTY_FLOAT;
        } else {
            injCol = new int[injCount];
            injCell = new int[injCount];
            injSp = new char[injCount];
            injMs = new float[injCount];
            injTp = new float[injCount];
            for (int k = 0; k < injCount; k++) {
                EngineInjection in = injections.get(k);
                injCol[k] = in.columnId();
                injCell[k] = in.cellIndex();
                injSp[k] = in.species();
                injMs[k] = in.mass();
                injTp[k] = in.temperature();
            }
            ledgerOut = new float[2 * matCount];   // guard: exactly the size the native writes
        }

        lastStepMillis = orgeStepWorld(
                f.nCols(), f.cx(), f.cz(), f.matIx(), f.mass(), f.tIn(),
                L.cond(), L.heatCap(), L.molar(), L.minMass(), L.maxMass(), L.visc(),
                passes, dtSeconds, tOut, massOut, matOut,
                injCount, injCol, injCell, injSp, injMs, injTp, ledgerOut);

        float[] injected = new float[matCount];
        float[] sealedLoss = new float[matCount];
        if (injCount > 0) {
            System.arraycopy(ledgerOut, 0, injected, 0, matCount);
            System.arraycopy(ledgerOut, matCount, sealedLoss, 0, matCount);
        }
        return new RegionStepResult(
                RegionMarshaller.slice(matOut, massOut, tOut, f.nCols()), injected, sealedLoss);
    }
```

(Delete the old 4-arg body that inlined the native call + EMPTY arrays — it is now the one-line delegate above. Keep the `EMPTY_INT/EMPTY_CHAR/EMPTY_FLOAT` constants and the native decl unchanged.)

- [ ] **Step 4: Run the IT to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest --tests '*NativeEngineInjectionIT*'`
Expected: PASS (water placed, air relocated to the vacuum below, `injected[WATER]≈1000`, `sealedLoss[AIR]≈0`).

- [ ] **Step 5: Confirm the no-injection path is still byte-identical**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest`
Expected: BUILD SUCCESSFUL; `:core:test` 373/0 and the existing live ITs still green (the 4-arg form now delegates with `injCount=0`, so behavior is unchanged).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/engine/NativeEngine.java \
        core/src/integrationTest/java/net/rainbowcreation/orge/engine/NativeEngineInjectionIT.java
git commit -m "feat(engine-java): NativeEngine injection overload marshals inj arrays + reads ledgerOut"
git push origin rebuild
```

---

## Task 3: `PendingInjections` queue (durable, last-write-wins per cell)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/PendingInjections.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/PendingInjectionsTest.java`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/PendingInjectionsTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class PendingInjectionsTest {

    private static final Identifier DIM = Identifier.of("minecraft", "overworld");
    private static final Identifier WATER = Identifier.of("orge", "water");
    private static final Identifier LAVA = Identifier.of("orge", "lava");

    @Test
    void enqueueThenPeekColumnReturnsIntent() {
        PendingInjections q = new PendingInjections();
        int cell = 3 + 16 * 70 + 6144 * 4;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        PendingInjections.Intent in = got.get(0);
        assertEquals(cell, in.cell());
        assertEquals(WATER, in.species());
        assertEquals(1000f, in.mass());
        assertEquals(290f, in.temperature());
        assertTrue(q.peekColumn(DIM, 1, 0).isEmpty(), "other column has none");
    }

    @Test
    void sameCellLastWriteWins() {            // Plan-1 carry-in: same-cell dedup lives here
        PendingInjections q = new PendingInjections();
        int cell = 5 + 16 * 70 + 6144 * 4;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);
        q.enqueue(DIM, 0, 0, cell, LAVA, 3000f, 1500f);   // overwrites the same (dim,cell) key

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size(), "same cell collapses to one intent");
        assertEquals(LAVA, got.get(0).species());
        assertEquals(3000f, got.get(0).mass());
    }

    @Test
    void removeClearsOnlyTheGivenIntents() {
        PendingInjections q = new PendingInjections();
        int a = 1 + 16 * 70, b = 2 + 16 * 70;
        q.enqueue(DIM, 0, 0, a, WATER, 1000f, 290f);
        q.enqueue(DIM, 0, 0, b, WATER, 1000f, 290f);
        List<PendingInjections.Intent> drained = q.peekColumn(DIM, 0, 0);

        q.remove(List.of(drained.get(0)));                 // simulate one applied
        List<PendingInjections.Intent> left = q.peekColumn(DIM, 0, 0);
        assertEquals(1, left.size(), "the un-removed intent stays queued (durability)");
        assertEquals(drained.get(1).cell(), left.get(0).cell());
    }

    @Test
    void peekDoesNotRemove() {
        PendingInjections q = new PendingInjections();
        int cell = 7 + 16 * 70;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);
        q.peekColumn(DIM, 0, 0);
        assertEquals(1, q.peekColumn(DIM, 0, 0).size(), "peek leaves the intent (survives stale steps)");
    }
}
```

> **Confirm `Identifier.of(ns, path)`** is the real factory by reading an existing test that builds an `Identifier` (the research shows `level.dimension().identifier()` returns one; tests likely use `Identifier.of(...)`). If the factory differs (e.g. `new Identifier(...)` or `Identifier.fromNamespaceAndPath`), use the real one.

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PendingInjectionsTest*'`
Expected: FAIL — `PendingInjections` does not exist.

- [ ] **Step 3: Implement `PendingInjections`**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/PendingInjections.java`:

```java
package net.rainbowcreation.orge.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Server-thread-confined queue of placement intents (spec Part B2), keyed by {@code (dim, cx, cz,
 * engineCell)}. The queue is the durable source of truth: an intent persists across any number of
 * stale in-flight engine steps until a successful, non-held write-back {@link #remove}s it — so a
 * stale background step cannot lose a placement (the vanish-race fix). Last-write-wins per cell
 * (Plan-1 carry-in: same-cell placements in one window collapse to the newest intent).
 *
 * <p>NOT thread-safe; all access is on the server thread (the wake hooks, snapshot, and write-back
 * all run there).</p>
 */
public final class PendingInjections {

    /** One placement intent. {@code cell} is the engine index {@code x + 16*y + 6144*z}. */
    public record Intent(Identifier dim, int cx, int cz, int cell,
                         Identifier species, float mass, float temperature) {
    }

    /** Pack (cx,cz,cell) into a stable key within a dimension. cell < 98304 fits in 17 bits. */
    private static long key(int cx, int cz, int cell) {
        return ((cx & 0xFFFFFFFFL))
             | ((cz & 0xFFFFFFFFL) << 32)
             | ((long) cell << 47);   // cell uses bits [47..); cx/cz already consume [0..47) effectively
    }

    // dim -> (packed (cx,cz,cell)) -> Intent.  Last enqueue for a key overwrites (dedup).
    private final Map<Identifier, Map<Long, Intent>> byDim = new HashMap<>();

    /** Record/replace a placement intent for {@code (dim, cx, cz, cell)} (last-write-wins). */
    public void enqueue(Identifier dim, int cx, int cz, int cell,
                        Identifier species, float mass, float temperature) {
        byDim.computeIfAbsent(dim, d -> new HashMap<>())
             .put(key(cx, cz, cell), new Intent(dim, cx, cz, cell, species, mass, temperature));
    }

    /** Intents whose cell lies in column {@code (dim, cx, cz)}, in deterministic ascending-cell order.
     *  Does NOT remove them (they stay queued until {@link #remove} after a successful write-back). */
    public List<Intent> peekColumn(Identifier dim, int cx, int cz) {
        Map<Long, Intent> d = byDim.get(dim);
        if (d == null || d.isEmpty()) {
            return List.of();
        }
        List<Intent> out = new ArrayList<>();
        for (Intent in : d.values()) {
            if (in.cx() == cx && in.cz() == cz) {
                out.add(in);
            }
        }
        out.sort((a, b) -> Integer.compare(a.cell(), b.cell()));   // deterministic, loader-independent
        return out;
    }

    /** Remove the given intents (called only after a successful, non-held write-back). */
    public void remove(Collection<Intent> intents) {
        for (Intent in : intents) {
            Map<Long, Intent> d = byDim.get(in.dim());
            if (d != null) {
                d.remove(key(in.cx(), in.cz(), in.cell()));
            }
        }
    }

    /** Drop everything for one chunk column (call on chunk unload to bound memory). */
    public void forgetColumn(Identifier dim, int cx, int cz) {
        Map<Long, Intent> d = byDim.get(dim);
        if (d == null) {
            return;
        }
        d.values().removeIf(in -> in.cx() == cx && in.cz() == cz);
    }

    /** Drop all intents (server stop). */
    public void clear() {
        byDim.clear();
    }

    /** Total queued intents (test/diagnostics). */
    public int size() {
        int n = 0;
        for (Map<Long, Intent> d : byDim.values()) {
            n += d.size();
        }
        return n;
    }
}
```

> **Key-packing caveat:** the `key(...)` above must be collision-free for distinct `(cx,cz,cell)`. `cx`/`cz` are arbitrary 32-bit chunk coords and `cell ∈ [0,98304)` (17 bits) — three 32/17-bit fields do **not** fit in 64 bits without overlap. **Simplify to a guaranteed-unique key**: use a `record ColKey(int cx, int cz, int cell)` (or `Map<Long,Intent>` keyed by `cx,cz` with an inner `Map<Integer,Intent>` by `cell`, mirroring `CellMaterialTracker`'s nesting). Implement whichever is collision-free; the test only requires correct dedup + per-column peek. Recommended: nest `dim -> packedColumn(cx,cz) long -> cell int -> Intent` exactly like `CellMaterialTracker.col(cx,cz)`. Rewrite the three methods against that structure; keep the public API (`enqueue/peekColumn/remove/forgetColumn/clear/size`) identical so the test is unchanged.

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PendingInjectionsTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/PendingInjections.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/PendingInjectionsTest.java
git commit -m "feat(scheduler): PendingInjections durable queue (last-write-wins per cell)"
git push origin rebuild
```

---

## Task 4: `PlacementInjectionPolicy` — pure displacement-placement decision

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicy.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicyTest.java`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicyTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** B1/B5 decision: enqueue an injection ONLY for a genuine movable→movable placement where the new
 *  species differs from the recorded incumbent (so reconciler self-writes and solid breaks are skipped). */
class PlacementInjectionPolicyTest {

    // Build via the project's test material factory (same helper used by other scheduler tests).
    private static Material air()   { return TestMaterials.movable("orge:air", 1.2f); }
    private static Material water() { return TestMaterials.movable("orge:water", 1000f); }
    private static Material stone() { return TestMaterials.frozen("orge:stone", 3000f); }   // visc = +inf

    @Test
    void waterOverAirIsDisplacement() {
        assertTrue(PlacementInjectionPolicy.isDisplacement(water(), air()));
    }

    @Test
    void sameSpeciesIsNotDisplacement() {              // reconciler self-write: live == incumbent
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), water()));
    }

    @Test
    void nonMovableIncumbentIsNotDisplacement() {      // /setblock water over stone: existing seed path
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), stone()));
    }

    @Test
    void nonMovableNewSpeciesIsNotDisplacement() {     // placing stone: not an injection
        assertFalse(PlacementInjectionPolicy.isDisplacement(stone(), air()));
    }

    @Test
    void nullIncumbentIsNotDisplacement() {            // untracked cell: no known incumbent to displace
        assertFalse(PlacementInjectionPolicy.isDisplacement(water(), null));
        assertFalse(PlacementInjectionPolicy.isDisplacement(null, air()));
    }
}
```

> **`TestMaterials` factory:** read the existing scheduler/material tests for the real helper that constructs a movable `Material` with a given id + defaultMass and a frozen (`viscosity=+inf`) one. Reuse it; if none exists, construct the `Material` record directly (read `Material.java` for the constructor) — `movable` = finite viscosity, `frozen` = `Float.POSITIVE_INFINITY` viscosity. Adjust the three helpers accordingly.

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PlacementInjectionPolicyTest*'`
Expected: FAIL — `PlacementInjectionPolicy` does not exist.

- [ ] **Step 3: Implement the policy**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicy.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;

/**
 * Pure decision for the placement-injection capture (spec B1/B5): a block change is a displace-and-
 * inject placement iff a movable new species replaced a DIFFERENT movable incumbent. Everything else
 * keeps the existing reseed/seed path:
 * <ul>
 *   <li>same species (reconciler self-write, engine-output repaint) → not an injection;</li>
 *   <li>non-movable incumbent (stone/ice/void over which a fluid was set) → no fluid mass to
 *       displace, existing {@code ColumnAssembler} seed handles it;</li>
 *   <li>non-movable new species (placing a solid) → not a fluid placement;</li>
 *   <li>unknown incumbent (untracked cell, {@code null}) → nothing known to displace.</li>
 * </ul>
 */
public final class PlacementInjectionPolicy {

    private PlacementInjectionPolicy() {
    }

    /**
     * @param live      material of the newly-placed block at the cell (from the live world).
     * @param incumbent material of the cell's recorded engine-output species (the thing to displace),
     *                  or {@code null} if the cell is untracked.
     */
    public static boolean isDisplacement(Material live, Material incumbent) {
        return live != null && incumbent != null
                && live.movable() && incumbent.movable()
                && !live.id().equals(incumbent.id());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PlacementInjectionPolicyTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicy.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPolicyTest.java
git commit -m "feat(scheduler): PlacementInjectionPolicy (movable→movable displacement decision)"
git push origin rebuild
```

---

## Task 5: §9 ledger learns the injection deltas

`StepValidator.SpeciesMassLedger.conserved()` currently demands net-zero per species. A placement legitimately makes `after − before = +injected − sealedLoss` per species; the gate must accept exactly that, while still HOLDing a genuine fabrication.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SpeciesMassLedgerInjectionTest.java`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/SpeciesMassLedgerInjectionTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

class SpeciesMassLedgerInjectionTest {

    // [0]=void, [1]=air, [2]=water. Use the project test-material factory (same as other ledger tests).
    private static List<Material> lut() {
        return List.of(TestMaterials.movable("orge:void", 0f),
                       TestMaterials.movable("orge:air", 1.2f),
                       TestMaterials.movable("orge:water", 1000f));
    }
    private static final char AIR = 1, WATER = 2;

    /** A placement: one cell goes air(1.2) -> water(1000), air relocates into a second (vacuum) cell.
     *  Net per species: water +1000 (injected), air 0 (relocated). With the injected delta declared,
     *  the gate must accept it. */
    @Test
    void acceptsDeclaredInjectionDelta() {
        List<Material> lut = lut();
        // cell 0: air -> water ; cell 1: void -> air (relocation)
        char[] inMat  = { AIR,  0   };
        char[] outMat = { WATER, AIR };
        float[] before = { 1.2f, 0f   };
        float[] after  = { 1000f, 1.2f };

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected = new float[lut.size()];   injected[WATER] = 1000f;
        float[] sealedLoss = new float[lut.size()];  // none
        ledger.expect(injected, sealedLoss);

        assertTrue(ledger.conserved(), "declared placement source is allowed");
    }

    @Test
    void acceptsDeclaredSealedLoss() {
        List<Material> lut = lut();
        char[] inMat  = { AIR };
        char[] outMat = { WATER };
        float[] before = { 1.2f };
        float[] after  = { 1000f };               // air vanished (sealed), water placed

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);

        float[] injected = new float[lut.size()];   injected[WATER] = 1000f;
        float[] sealedLoss = new float[lut.size()]; sealedLoss[AIR] = 1.2f;
        ledger.expect(injected, sealedLoss);

        assertTrue(ledger.conserved(), "declared sealed-loss sink is allowed");
    }

    @Test
    void stillHoldsGenuineFabrication() {
        List<Material> lut = lut();
        char[] inMat  = { WATER };
        char[] outMat = { WATER };
        float[] before = { 1000f };
        float[] after  = { 1500f };               // 500 conjured, NOT declared

        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);
        ledger.expect(new float[lut.size()], new float[lut.size()]);   // no injection declared

        assertFalse(ledger.conserved(), "undeclared mass gain still HOLDs");
    }

    @Test
    void zeroDeltaIsBackwardCompatible() {        // no expect() call ⇒ exactly today's behaviour
        List<Material> lut = lut();
        char[] inMat  = { WATER };
        char[] outMat = { WATER };
        float[] before = { 1000f };
        float[] after  = { 1000f };
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        ledger.add(after, before, inMat, outMat, lut);
        assertTrue(ledger.conserved(), "conserved step with no declared delta still passes");
    }
}
```

> The `add(...)` bound check rejects `after > maxMass + eps`. Water `maxMass` is `defaultMass`-ish (1000) in these fixtures, and `after = 1000`, so it is within bound. If the test material's `maxMass` is below 1000, the `add` bound flag would trip; ensure `TestMaterials.movable("orge:water", 1000f)` sets `maxMass >= 1000`. Read the factory; if `maxMass` is a separate arg, pass `1000f`.

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SpeciesMassLedgerInjectionTest*'`
Expected: FAIL — `expect(...)` does not exist; and `acceptsDeclaredInjectionDelta` fails because `conserved()` sees `+1000` for water.

- [ ] **Step 3: Add `expect(...)` + delta-aware `conserved()`**

In `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java`, inside `SpeciesMassLedger`, add fields + method and update `conserved()`:

```java
        // Declared per-species placement deltas (spec A4): expected after-before = injected - sealedLoss.
        private double[] injected = new double[0];
        private double[] sealedLoss = new double[0];

        /** Declare the engine's placement ledger for this region step (indexed by LUT species). */
        public void expect(float[] injectedDelta, float[] sealedLossDelta) {
            this.injected = toDoubles(injectedDelta);
            this.sealedLoss = toDoubles(sealedLossDelta);
        }

        private static double[] toDoubles(float[] src) {
            if (src == null) {
                return new double[0];
            }
            double[] out = new double[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = src[i];
            }
            return out;
        }
```

Replace the body of `conserved()` with the delta-aware check:

```java
        public boolean conserved() {
            double tol = (double) MASS_EPSILON_PER_CELL * totalCells;
            for (int s = 1; s < sumAfter.length; s++) {
                double expectedDelta =
                        (s < injected.length ? injected[s] : 0.0)
                      - (s < sealedLoss.length ? sealedLoss[s] : 0.0);
                if (Math.abs(sumAfter[s] - sumBefore[s] - expectedDelta) > tol) {
                    return false;
                }
            }
            return true;
        }
```

(When `expect` is never called, `injected`/`sealedLoss` stay length 0 ⇒ `expectedDelta == 0` ⇒ identical to today.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SpeciesMassLedgerInjectionTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Confirm existing StepValidator tests still pass**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*StepValidator*' --tests '*Ledger*'`
Expected: PASS (no regression — the zero-delta default preserves old behavior).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SpeciesMassLedgerInjectionTest.java
git commit -m "feat(scheduler): SpeciesMassLedger accepts declared injected/sealedLoss deltas"
git push origin rebuild
```

---

## Task 6: Capture placements into the queue (capturing `WakeSink`)

Route the existing block-change wake path through a server-thread capture that enqueues a `PendingInjections.Intent` for a genuine movable→movable placement, filtering reconciler self-writes via the `CellMaterialTracker` signature.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementCaptureTest.java`

**Design:** add a `PendingInjections pending` field to `MinecraftThermalWorld`. Factor the capture into a **pure, testable** method `captureDecision(...)` that takes the live material + the recorded incumbent material + their cell coords and returns an `Optional<PendingInjections.Intent>` (or enqueues directly). The thin MC glue (reading the live block + recorded signature) wraps it. Expose the capture as a `WakeSink` so all wake call sites drive it; the existing `wakeSink()` returns a composite that captures then delegates to `activeSet`.

- [ ] **Step 1: Write the failing test (pure capture decision)**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementCaptureTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** The pure capture step: given the live placed material and the recorded incumbent material at a
 *  cell, enqueue an intent iff it is a displacement placement (delegating to the policy). */
class PlacementCaptureTest {

    private static final Identifier DIM = Identifier.of("minecraft", "overworld");
    private static Material air()   { return TestMaterials.movable("orge:air", 1.2f); }
    private static Material water() { return TestMaterials.movable("orge:water", 1000f); }

    @Test
    void displacementPlacementEnqueuesIntentWithNewSpeciesSeed() {
        PendingInjections q = new PendingInjections();
        Material live = water();
        int cell = 3 + 16 * 70 + 6144 * 4;
        float ambientK = 295f;

        PlacementCapture.capture(q, DIM, 0, 0, cell, live, air(), ambientK);

        List<PendingInjections.Intent> got = q.peekColumn(DIM, 0, 0);
        assertEquals(1, got.size());
        assertEquals(water().id(), got.get(0).species());        // place the NEW species
        assertEquals(water().defaultMass(), got.get(0).mass());  // its defaultMass seed
        // temperature = material default if present, else biome ambient:
        float expectT = live.hasDefaultTemperature() ? live.defaultTemperature() : ambientK;
        assertEquals(expectT, got.get(0).temperature());
    }

    @Test
    void selfWriteDoesNotEnqueue() {                 // live == incumbent (reconciler repaint)
        PendingInjections q = new PendingInjections();
        PlacementCapture.capture(q, DIM, 0, 0, 100, water(), water(), 295f);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty());
    }

    @Test
    void breakToAirOverSolidDoesNotEnqueue() {       // incumbent non-movable -> existing seed path
        PendingInjections q = new PendingInjections();
        Material stone = TestMaterials.frozen("orge:stone", 3000f);
        PlacementCapture.capture(q, DIM, 0, 0, 100, air(), stone, 295f);
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PlacementCaptureTest*'`
Expected: FAIL — `PlacementCapture` does not exist.

- [ ] **Step 3: Implement the pure capture helper**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/PlacementCapture.java`:

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure capture step for placement injection (spec B2/B5). Given the live placed material and the
 * recorded incumbent material at a cell, enqueue a placement intent iff it is a movable→movable
 * displacement ({@link PlacementInjectionPolicy}). The intent carries the NEW species' id +
 * {@code defaultMass} seed + seed temperature (material default, else biome ambient) — the same
 * values {@code ColumnAssembler}/{@code MaterialChangeReseed} would have used, now owned by the engine.
 */
public final class PlacementCapture {

    private PlacementCapture() {
    }

    public static void capture(PendingInjections queue, Identifier dim, int cx, int cz, int cell,
                               Material live, Material incumbent, float biomeAmbientK) {
        if (!PlacementInjectionPolicy.isDisplacement(live, incumbent)) {
            return;
        }
        float temp = live.hasDefaultTemperature() ? live.defaultTemperature() : biomeAmbientK;
        queue.enqueue(dim, cx, cz, cell, live.id(), live.defaultMass(), temp);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*PlacementCaptureTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire the capturing `WakeSink` into `MinecraftThermalWorld`**

In `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`:

(a) Add a field + accessor near the other fields (after `activeSet`):

```java
    private final PendingInjections pendingInjections = new PendingInjections();

    /** The placement-injection queue (server-thread). Drained at snapshot, cleared on successful write-back. */
    public PendingInjections pendingInjections() {
        return this.pendingInjections;
    }
```

(b) Replace `wakeSink()` so it returns a composite that runs the placement capture, then delegates to `activeSet`. The capture must read the live block material + the recorded incumbent signature for the cell. Add this inner class and update `wakeSink()`:

```java
    @Override
    public WakeSink wakeSink() {
        return capturingWakeSink;
    }

    private final WakeSink capturingWakeSink = new WakeSink() {
        @Override
        public void wakeBlock(Identifier dim, int blockX, int blockY, int blockZ) {
            captureBlockChange(dim, blockX, blockY, blockZ);   // enqueue intent if a displacement placement
            activeSet.wakeBlock(dim, blockX, blockY, blockZ);   // unchanged wake behaviour
        }
        @Override
        public void wakeFlowSection(Identifier dim, SubchunkKey key) { activeSet.wakeFlowSection(dim, key); }
        @Override
        public void wakeThermalSection(Identifier dim, SubchunkKey key) { activeSet.wakeThermalSection(dim, key); }
    };

    /** Server-thread: read the live block + the recorded incumbent at this cell; enqueue a placement
     *  intent iff it is a movable→movable displacement (the reconciler's own air/fluid repaint has
     *  live==recorded incumbent and is filtered out). */
    private void captureBlockChange(Identifier dim, int blockX, int blockY, int blockZ) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return;
        }
        ServerLevel level = levelFor(srv, dim);                 // reuse the existing dim->level resolver
        if (level == null) {
            return;
        }
        int cx = SectionPos.blockToSectionCoord(blockX);
        int cz = SectionPos.blockToSectionCoord(blockZ);
        if (LiveMaterials.loadedChunk(level, cx, cz) == null) {
            return;
        }
        int sectionY = SectionPos.blockToSectionCoord(blockY);
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        ActiveMaterials.State mats = ActiveMaterials.current();

        // live material from the world block:
        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        Material live = LiveMaterials.materialFor(level.getBlockState(pos), mats);

        // recorded incumbent (engine-output species) for this cell:
        Identifier[] prior = cellMaterials.prior(dim, key);
        int lx = blockX & 15, ly = blockY & 15, lz = blockZ & 15;
        int sectionCell = lx + 16 * ly + 256 * lz;              // section-local index (CellMaterialTracker space)
        Material incumbent = (prior != null && sectionCell < prior.length && prior[sectionCell] != null)
                ? materialById(prior[sectionCell], mats) : null;

        int engineY = sectionY * 16 + ly + 64;                  // ColumnSectionCodec.engineY(sectionY, ly)
        int engineCell = lx + 16 * engineY + 6144 * lz;         // engine column index
        float ambientK = biomeAmbientK(level, key);

        PlacementCapture.capture(pendingInjections, dim, cx, cz, engineCell, live, incumbent, ambientK);
    }
```

> **Resolve the helpers against the real code:** confirm/replace `levelFor(srv, dim)` (the dim→`ServerLevel` resolver — `MinecraftFluidReconciler` has a `levelFor`; `MinecraftThermalWorld` may have its own — use the one in scope or copy the small resolver), `biomeAmbientK(level, key)` (exists at `MinecraftThermalWorld.java:235-241`), `LiveMaterials.materialFor(BlockState, ActiveMaterials.State)`, `LiveMaterials.loadedChunk(level, cx, cz)`, and a `materialById(Identifier, ActiveMaterials.State)` lookup (find how the codebase maps an `Identifier` → `Material`; e.g. `mats.byId(id)` or an `ActiveMaterials` accessor — read `ActiveMaterials` and `LiveMaterials`). If no direct id→Material exists, resolve via the active material set the same way `materialFor` does. Match the real `engineY`/cell formulas to `ColumnSectionCodec` (the research gives `engineY = sectionY*16 + sy + 64`, engine cell `x + 16*engineY + 6144*z`). The capture must be side-effect-free except for `pendingInjections.enqueue`.

(c) On chunk unload, drop the column's intents. Find where `cellMaterials.forgetColumn(...)` is already called (chunk-unload hook) and add the sibling `pendingInjections.forgetColumn(dim, cx, cz);` right next to it. If no such hook exists in this class, search for `forgetColumn` call sites and mirror them.

- [ ] **Step 6: Run the full fast suite to confirm no regression**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: BUILD SUCCESSFUL (capture is additive; no existing test exercises the live block read, which is null-guarded when `server == null`).

- [ ] **Step 7: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/PlacementCapture.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/PlacementCaptureTest.java
git commit -m "feat(scheduler): capture movable→movable placements into PendingInjections via wake path"
git push origin rebuild
```

---

## Task 7: Drain the queue at snapshot — incumbent override + build the injection list

At snapshot, for each column drain its queued intents: override each injected cell back to its **incumbent** (recorded-signature species + stored mass) so Java's reseed/seed skips it, and emit an `EngineInjection{columnId, engineCell, species=lut.indexOf(newId), mass, temp}`. Carry the list (and the drained intents, for clear-on-success) on `ColumnBatch`.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java` (`ColumnBatch` shape)
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/InjectionDrainTest.java`

**Design note (incumbent override is the B1 split):** the override sets `matIx[cell] = lut.indexOf(incumbentId)` and `mass[cell] = storedMass`. Because that equals the cell's `prior` species, `MaterialChangeReseed.reseeds(prior, incumbent)` is false (same id) and `ColumnAssembler`'s seed gate `prior != mat` is false — so neither fabricates the new species. The engine's injection then places it. **No change to `MaterialChangeReseed`/`ColumnAssembler` is needed.**

- [ ] **Step 1: Extend `ColumnBatch` with the injection list + drained intents**

In `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java`, change the `ColumnBatch` record (currently `record ColumnBatch(List<ColumnEntry> entries, List<Material> lut)`) to:

```java
    record ColumnBatch(List<ColumnEntry> entries, List<Material> lut,
                       List<net.rainbowcreation.orge.engine.EngineInjection> injections,
                       List<PendingInjections.Intent> drained) {
        /** Back-compat convenience for call sites/tests that build a batch with no injections. */
        public ColumnBatch(List<ColumnEntry> entries, List<Material> lut) {
            this(entries, lut, List.of(), List.of());
        }
    }
```

> Update every `new ColumnBatch(entries, lut)` construction that should stay injection-free to keep compiling (the 2-arg convenience ctor covers them). `snapshotColumns` will use the 4-arg form.

- [ ] **Step 2: Write the failing test (pure drain logic)**

Factor the drain/override into a pure helper so it is testable without a server. Create `core/src/test/java/net/rainbowcreation/orge/scheduler/InjectionDrainTest.java`:

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

/** Draining an intent overrides the column cell back to its incumbent (so Java won't reseed the new
 *  species) and emits an EngineInjection that places the new species, resolved against the batch LUT. */
class InjectionDrainTest {

    private static final Identifier DIM = Identifier.of("minecraft", "overworld");

    @Test
    void drainOverridesIncumbentAndEmitsInjection() {
        // batch LUT: [0]=void, [1]=air, [2]=water
        Material air = TestMaterials.movable("orge:air", 1.2f);
        Material water = TestMaterials.movable("orge:water", 1000f);
        List<Material> lut = List.of(TestMaterials.movable("orge:void", 0f), air, water);

        int cell = 3 + 16 * 70 + 6144 * 4;
        char[] matIx = new char[net.rainbowcreation.orge.engine.RegionMarshaller.CHUNK_N];
        float[] mass = new float[matIx.length];
        // live snapshot currently shows WATER at the cell (player placed it):
        matIx[cell] = 2; mass[cell] = 0f;

        // incumbent recorded for the cell = air, stored mass 1.2:
        float storedIncumbentMass = 1.2f;
        Identifier incumbentId = air.id();

        PendingInjections.Intent intent =
                new PendingInjections.Intent(DIM, 0, 0, cell, water.id(), 1000f, 290f);

        List<EngineInjection> out = new ArrayList<>();
        InjectionDrain.applyToColumn(
                /*columnId*/ 0, matIx, mass, lut,
                List.of(intent),
                /*incumbentSpeciesId*/ id -> incumbentId,           // resolver stub: cell -> air
                /*incumbentMass*/ c -> storedIncumbentMass,
                out);

        // cell overridden back to incumbent (air@1.2), NOT water:
        assertEquals(1, matIx[cell], "cell reset to incumbent species (air)");
        assertEquals(1.2f, mass[cell], "cell reset to stored incumbent mass");
        // one injection emitted to place water:
        assertEquals(1, out.size());
        EngineInjection ej = out.get(0);
        assertEquals(0, ej.columnId());
        assertEquals(cell, ej.cellIndex());
        assertEquals(2, ej.species(), "species resolved to water's LUT index");
        assertEquals(1000f, ej.mass());
        assertEquals(290f, ej.temperature());
    }
}
```

> The two functional params (`incumbentSpeciesId`, `incumbentMass`) abstract the MC reads (recorded signature + stored mass) so the helper is pure. Their real implementations in `snapshotColumns` read `cellMaterials.prior(...)` and `SectionData.massAt(...)`. Choose simple functional-interface types (e.g. `java.util.function.IntFunction<Identifier>` keyed by engine cell, and `java.util.function.IntToDoubleFunction` or a `float`-returning small interface). Match the test's lambda shapes; if you change the signatures, change the test lambdas to match.

- [ ] **Step 3: Run it to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*InjectionDrainTest*'`
Expected: FAIL — `InjectionDrain` does not exist.

- [ ] **Step 4: Implement `InjectionDrain`**

Create `core/src/main/java/net/rainbowcreation/orge/scheduler/InjectionDrain.java`:

```java
package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import net.rainbowcreation.orge.material.Material;

/**
 * Pure drain step (spec B3): for each placement intent in a column, override the cell back to its
 * incumbent (recorded engine-output species + stored mass) so the Java reseed/seed pipeline leaves
 * it alone (the incumbent equals the cell's {@code prior}, so {@code MaterialChangeReseed}/
 * {@code ColumnAssembler} both skip it), and emit an {@link EngineInjection} that places the new
 * species — resolved against the batch LUT — which the engine applies as a displace-and-inject.
 */
public final class InjectionDrain {

    /** Resolves the engine-cell's stored incumbent mass (kg). */
    @FunctionalInterface
    public interface IncumbentMass {
        float massAt(int engineCell);
    }

    private InjectionDrain() {
    }

    /**
     * @param columnId         this column's position in the batch (the injection's {@code columnId}).
     * @param matIx            the assembled column material indices (length CHUNK_N) — mutated in place.
     * @param mass             the assembled column masses (length CHUNK_N) — mutated in place.
     * @param lut              the batch LUT (species index space).
     * @param intents          this column's drained intents.
     * @param incumbentId      engine-cell → recorded incumbent species id (or {@code null} if unknown).
     * @param incumbentMass    engine-cell → stored incumbent mass.
     * @param out              accumulates the emitted injections.
     */
    public static void applyToColumn(int columnId, char[] matIx, float[] mass, List<Material> lut,
                                     List<PendingInjections.Intent> intents,
                                     IntFunction<Identifier> incumbentId, IncumbentMass incumbentMass,
                                     List<EngineInjection> out) {
        for (PendingInjections.Intent in : intents) {
            int cell = in.cell();
            if (cell < 0 || cell >= matIx.length) {
                continue;
            }
            char species = lutIndexOf(lut, in.species());
            if (species == 0) {
                continue;   // new species not in this batch LUT (shouldn't happen) — skip, keep live cell
            }
            Identifier incId = incumbentId.apply(cell);
            char incIx = incId != null ? lutIndexOf(lut, incId) : 0;
            // Override the cell back to its incumbent so Java's reseed/seed won't fabricate the new
            // species; the engine injection re-places it (displacing this incumbent).
            matIx[cell] = incIx;
            mass[cell] = incIx != 0 ? incumbentMass.massAt(cell) : 0f;
            out.add(new EngineInjection(columnId, cell, species, in.mass(), in.temperature()));
        }
    }

    private static char lutIndexOf(List<Material> lut, Identifier id) {
        for (int i = 0; i < lut.size(); i++) {
            if (lut.get(i).id().equals(id)) {
                return (char) i;
            }
        }
        return 0;   // void/unknown
    }
}
```

> If the batch `MaterialLut` is available at the drain site (it is — `snapshotColumns` holds the `MaterialLut lut`), prefer `lut.indexOf(id)` over the linear scan for the real wiring; the pure helper uses a linear scan over `List<Material>` only because the test passes a plain list. Either is correct; keep the helper as-is for testability and let the caller pass `lut.materials()`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*InjectionDrainTest*'`
Expected: PASS.

- [ ] **Step 6: Wire the drain into `snapshotColumns`**

In `MinecraftThermalWorld.snapshotColumns(...)` (after the per-column `entries.add(...)` loop builds all `ColumnEntry`s, where `lastColumnLut = lut.materials()` is set, before `return`), drain per column and build the region injection list. Because `EngineInjection.columnId` is the column's index in `entries`, iterate `entries` with its index:

```java
        // ---- Drain placement intents into this batch's injection list (spec B3) ----
        List<net.rainbowcreation.orge.engine.EngineInjection> injections = new ArrayList<>();
        List<PendingInjections.Intent> drained = new ArrayList<>();
        for (int columnId = 0; columnId < entries.size(); columnId++) {
            ColumnEntry e = entries.get(columnId);
            List<PendingInjections.Intent> colIntents =
                    pendingInjections.peekColumn(e.dimension(), e.cx(), e.cz());
            if (colIntents.isEmpty()) {
                continue;
            }
            SectionStore store = stores.store(e.dimension());
            char[] colMat = e.task().matIx();
            float[] colMass = e.task().mass();
            InjectionDrain.applyToColumn(columnId, colMat, colMass, lut.materials(), colIntents,
                    engineCell -> recordedIncumbentId(e.dimension(), e.cx(), e.cz(), engineCell),
                    engineCell -> storedMassAt(store, e.cx(), e.cz(), engineCell),
                    injections);
            drained.addAll(colIntents);
        }
        lastColumnLut = lut.materials();
        return new ColumnBatch(entries, lut.materials(), injections, drained);
```

Add the two small server-thread readers used above (resolve them against the real `CellMaterialTracker`/`SectionStore`/`ColumnSectionCodec` API):

```java
    /** Recorded engine-output species id for an engine-cell in a column, or null if untracked. */
    private Identifier recordedIncumbentId(Identifier dim, int cx, int cz, int engineCell) {
        int x = engineCell & 15;
        int engineY = (engineCell / 16) % 384;
        int z = engineCell / 6144;
        int sectionY = Math.floorDiv(engineY - 64, 16);      // inverse of engineY = sectionY*16 + sy + 64
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        Identifier[] prior = cellMaterials.prior(dim, key);
        int sectionCell = x + 16 * sy + 256 * z;
        return (prior != null && sectionCell < prior.length) ? prior[sectionCell] : null;
    }

    /** Stored mass (kg) for an engine-cell in a column. */
    private float storedMassAt(SectionStore store, int cx, int cz, int engineCell) {
        if (store == null) {
            return 0f;
        }
        int x = engineCell & 15;
        int engineY = (engineCell / 16) % 384;
        int z = engineCell / 6144;
        int sectionY = Math.floorDiv(engineY - 64, 16);
        int sy = engineY - 64 - sectionY * 16;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        if (!store.isLoaded(cx, cz)) {
            return 0f;
        }
        SectionData data = store.get(key);
        int sectionCell = x + 16 * sy + 256 * z;
        return data.massAt(sectionCell);
    }
```

> **Verify the index math against `ColumnSectionCodec`**: the research gives `engineY = sectionY*16 + sy + 64` and section-local cell `i = x + 16*sy + 256*z`. Confirm `MIN_SECTION_Y=-4` ⇒ `engineY=0` at `sectionY=-4, sy=0` (`-4*16 + 0 + 64 = 0` ✓) and the inverse `sectionY = floorDiv(engineY-64,16)`, `sy = engineY-64 - sectionY*16` is correct for the whole `[0,384)` range. If `ColumnSectionCodec` exposes helpers (`engineY(sectionY,sy)`, a cell decomposer), prefer calling them over re-deriving. Confirm `SectionData.massAt(int)` and `store.get(key)`/`store.isLoaded(cx,cz)` signatures.

- [ ] **Step 7: Run the fast suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: BUILD SUCCESSFUL (no awake intents in existing tests ⇒ empty injection list ⇒ behavior unchanged). Fix any `new ColumnBatch(...)` call sites the record change broke (the 2-arg convenience ctor should cover them).

- [ ] **Step 8: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/InjectionDrain.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/InjectionDrainTest.java
git commit -m "feat(scheduler): drain injections at snapshot — incumbent override + EngineInjection list"
git push origin rebuild
```

---

## Task 8: Scheduler wiring — pass injections, gate the ledger, clear-on-success

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerInjectionDurabilityTest.java`

**Design:** `submit()` already has `batch`; thread `batch.injections()` into the engine call via the new overload, and stash the `RegionStepResult` (carrying the ledger). `writeBackColumns` feeds the ledger to the §9 gate via `expect(...)`. On a successful (non-held) write-back, `pendingInjections.remove(batch.drained())`; on HOLD, leave them queued (durability). Store the drained list + ledger alongside the existing `pendingColumnResults`.

- [ ] **Step 1: Write the failing durability test**

Create `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerInjectionDurabilityTest.java`. This is the race regression: an enqueued intent survives stale steps and is drained into a step; and is cleared only after a successful write-back.

```java
package net.rainbowcreation.orge.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineInjection;
import org.junit.jupiter.api.Test;

/** B2 durability: an intent stays queued across peeks/steps until explicitly removed (success). */
class SchedulerInjectionDurabilityTest {

    private static final Identifier DIM = Identifier.of("minecraft", "overworld");
    private static final Identifier WATER = Identifier.of("orge", "water");

    @Test
    void intentSurvivesUntilRemoved() {
        PendingInjections q = new PendingInjections();
        int cell = 3 + 16 * 70;
        q.enqueue(DIM, 0, 0, cell, WATER, 1000f, 290f);

        // Three "stale" snapshot drains (peek) — intent must persist each time:
        for (int i = 0; i < 3; i++) {
            List<PendingInjections.Intent> drained = q.peekColumn(DIM, 0, 0);
            assertEquals(1, drained.size(), "still queued on stale step " + i);
            // simulate a HELD region: do NOT remove
        }
        // a successful write-back removes it:
        q.remove(q.peekColumn(DIM, 0, 0));
        assertTrue(q.peekColumn(DIM, 0, 0).isEmpty(), "cleared after success");
    }
}
```

> This test asserts the durability contract at the queue level (no MC server needed). The `Scheduler` wiring that actually calls `peek`/`remove`/`expect` is exercised end-to-end by the integration test in Task 9. If the existing `SchedulerTest` harness can drive a fake engine through `submit/complete` with an injected batch, add a stronger scheduler-level assertion there too (optional — read `SchedulerTest` to see whether its fakes make that practical without a live world).

- [ ] **Step 2: Run it to verify it fails or passes**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*SchedulerInjectionDurabilityTest*'`
Expected: PASS already (it only uses `PendingInjections` from Task 3) — this pins the contract the scheduler wiring must honor. If you prefer strict red-green, write it before Task 3 instead; here it characterizes the durability the wiring below must not break.

- [ ] **Step 3: Thread injections + ledger + clear-on-success through the scheduler**

In `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`:

(a) Add fields beside `pendingColumnResults` to carry the ledger + drained intents:

```java
    private net.rainbowcreation.orge.engine.RegionStepResult pendingRegionResult;
    private List<PendingInjections.Intent> pendingDrained = List.of();
```

(b) In `submit()`, capture the batch injections + drained list and call the new overload. Replace the engine call block:

```java
        List<ColumnTask> input = new ArrayList<>(batch.entries().size());
        for (ThermalWorld.ColumnEntry e : batch.entries()) input.add(e.task());
        List<Material> lut = batch.lut();
        pendingMaterials = lut;
        pendingAdvection = true;
        pendingColumns = batch.entries();
        pendingColumnResults = null;
        pendingRegionResult = null;
        final List<net.rainbowcreation.orge.engine.EngineInjection> injections = batch.injections();
        pendingDrained = batch.drained();
        final double dt = nextDt();
        pending = runner.submit(() -> {
            if (input.isEmpty()) {
                pendingColumnResults = List.of();
            } else {
                net.rainbowcreation.orge.engine.RegionStepResult rr =
                        engine.stepWorld(input, lut, dt,
                                OrgeEngine.PASS_CONDUCTION | OrgeEngine.PASS_ADVECTION, injections);
                pendingRegionResult = rr;
                pendingColumnResults = rr.columns();
            }
            return List.of();
        });
```

(c) In `writeBackColumns(metDeadline)`, feed the ledger the declared deltas and clear-on-success. Update the advection branch:

```java
        StepValidator.SpeciesMassLedger ledger = new StepValidator.SpeciesMassLedger();
        for (int i = 0; i < n; i++) {
            ThermalWorld.ColumnEntry e = pendingColumns.get(i);
            ColumnResult r = results.get(i);
            ledger.add(r.mass(), e.task().mass(), e.task().matIx(), r.matIx(), pendingMaterials);
        }
        if (pendingRegionResult != null) {
            ledger.expect(pendingRegionResult.injected(), pendingRegionResult.sealedLoss());   // A4
        }
        if (!ledger.conserved()) {
            LOGGER.warn("[ORGE] region step mass not conserved (per-species); holding {} columns this cycle", n);
            return;   // HELD — drained intents stay queued for the next try (durability)
        }
        for (int i = 0; i < n; i++) {
            world.writeBackColumn(pendingColumns.get(i), results.get(i));
        }
        if (!pendingDrained.isEmpty()) {
            world.pendingInjections().remove(pendingDrained);   // success ⇒ clear the applied placements
            pendingDrained = List.of();
        }
```

> **`world.pendingInjections()`:** `Scheduler` holds a `ThermalWorld world`. The `pendingInjections()` accessor is on `MinecraftThermalWorld` (Task 6). Either (a) add `PendingInjections pendingInjections();` to the `ThermalWorld` interface with a `default` returning a shared no-op/empty queue (so other `ThermalWorld` impls/tests compile), or (b) cast `world` to `MinecraftThermalWorld` guarded by `instanceof`. Prefer (a): add to the interface as a `default` returning `EMPTY` (a shared empty `PendingInjections` whose `remove` is a no-op is fine — or a real one; the default just must not NPE). Implement whichever keeps `RegionSchedulerTest`/`SchedulerTest` fakes compiling. Read those tests to confirm they use a `ThermalWorld` fake; if so, the interface-default route is required.

- [ ] **Step 4: Run the scheduler tests**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*Scheduler*' --tests '*RegionScheduler*'`
Expected: BUILD SUCCESSFUL — existing scheduler tests pass (empty injections ⇒ `expect` with zero deltas ⇒ gate unchanged; no drained ⇒ no clear).

- [ ] **Step 5: Full fast suite**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test`
Expected: BUILD SUCCESSFUL (373+ tests, 0 failures, plus the new ones).

- [ ] **Step 6: Commit**

```bash
cd /home/claude/ORGE
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java \
        core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerInjectionDurabilityTest.java
git commit -m "feat(scheduler): thread injections to engine, declare ledger deltas, clear intents on success"
git push origin rebuild
```

---

## Task 9: End-to-end integration + final review

**Files:**
- Test: `core/src/integrationTest/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPipelineIT.java` (real `.so`, `@Tag("integration")`)

- [ ] **Step 1: Write the E2E integration test**

Mirroring the existing live-pipeline ITs (which already stand up a fake/real server world and run the scheduler against the native `.so`), add a test that: enqueues a water placement over an air cell that has a vacuum neighbour (via `MinecraftThermalWorld.pendingInjections().enqueue(...)` or by driving the capture path), runs a scheduler step, and asserts (a) the target cell's stored species is water at its seed mass, (b) total air mass is conserved (relocated, not deleted), (c) the region was **not** held (the §9 gate accepted the injection). Use the closest existing live-pipeline IT as the template for world/scheduler setup.

```java
// Skeleton — fill world/scheduler setup from the sibling live-pipeline IT you copy.
@Tag("integration")
class PlacementInjectionPipelineIT {
    @Test
    void placedWaterDisplacesAirAndPersists() {
        assumeTrue(/* native loaded */ true);
        // 1. stand up the live pipeline (MinecraftThermalWorld + Scheduler + NativeEngine) per sibling IT.
        // 2. arrange an air cell with stored air mass and a vacuum neighbour in a loaded column.
        // 3. enqueue a water placement intent at that cell (pendingInjections().enqueue(...)).
        // 4. run one scheduler submit+complete cycle.
        // 5. assert: stored species at the cell == water @ ~defaultMass; total air mass unchanged;
        //    region not held (write-back happened); intent cleared from the queue.
    }
}
```

> If standing up the full live scheduler is impractical in the integration harness, fall back to a focused IT that drives `MinecraftThermalWorld.snapshotColumns` → `NativeEngine.stepWorld(overload)` → `writeBackColumns`-equivalent assertions directly, reusing the real `.so`. The non-negotiable assertions: water placed + air conserved + ledger not held. Read the sibling ITs first to pick the cheapest faithful setup.

- [ ] **Step 2: Run the integration gate**

Run: `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest`
Expected: PASS (the new E2E + Task 2's `NativeEngineInjectionIT` + all existing live ITs green on the real `.so`).

- [ ] **Step 3: Full Java gate + both loaders build**

Run:
```bash
cd /home/claude/ORGE
JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test :core:integrationTest
JAVA_HOME=/home/claude/jdk21 ./gradlew build       # both loaders compile (neoforge + fabric)
```
Expected: all green. (No engine/`.so` change in Plan 2 ⇒ no gitlink bump.)

- [ ] **Step 4: Commit**

```bash
cd /home/claude/ORGE
git add core/src/integrationTest/java/net/rainbowcreation/orge/scheduler/PlacementInjectionPipelineIT.java
git commit -m "test(scheduler): E2E placement injection on the real liborge.so (water displaces air, no vanish)"
git push origin rebuild
```

- [ ] **Step 5: Final whole-plan review**

Dispatch a holistic reviewer over the full Plan-2 diff (the range from before Task 1 to HEAD) checking: the capture→queue→drain→engine→ledger→clear data path is coherent; the incumbent override truly bypasses reseed/seed (B1); the §9 gate accepts placements but still HOLDs fabrication; durability (HELD region keeps intents); same-cell dedup; no `.so`/engine change leaked in. Address any Critical/Important findings before declaring done.

---

## Self-Review (run before declaring Plan 2 done)

**Spec coverage (Part B + A4):**
- **B1 (split reseed vs injection):** ✓ realized via the incumbent override (Task 7) — injected cells are reset to their incumbent, so `MaterialChangeReseed`/`ColumnAssembler` skip them; non-movable→movable + broken→air keep the existing seed (capture policy excludes them, Task 4/6). The "exactly one seed" invariant holds (engine for displacement, assembler otherwise; mutually exclusive per cell).
- **B2 (pending queue, race-killer):** ✓ `PendingInjections` last-write-wins, peek-not-remove, clear-on-success (Tasks 3, 8). Durability test (Task 8).
- **B3 (drain into the step):** ✓ `snapshotColumns` drains per column, builds the injection list with `columnId` = batch index, species resolved vs batch LUT (Task 7); `Scheduler` passes it (Task 8).
- **B4 (reconciler unchanged):** ✓ no reconciler change; it renders the engine `matOut` for both the placed cell and the relocated incumbent.
- **B5 (self-write filter):** ✓ capture enqueues only when live species ≠ recorded engine-output incumbent and both movable (Tasks 4, 6); reconciler repaints have live == recorded ⇒ filtered.
- **A4 (ledger):** ✓ `SpeciesMassLedger.expect(injected, sealedLoss)` + delta-aware `conserved()` (Task 5); fed from `RegionStepResult` (Tasks 2, 8).
- **A5 ABI (Java side):** ✓ `OrgeEngine` injection overload + `NativeEngine` marshalling + `ledgerOut` readback (Tasks 1, 2).

**Plan-1 carry-ins:** same-cell dedup (Task 3 last-write-wins + test) ✓; `ledgerOut` sized `2*matCount` + `injSpecies` valid-by-construction (Task 2) ✓.

**Placeholder scan:** every step has real code or an exact command. The MC-glue steps (Tasks 6, 7, 9) explicitly name the real helpers to resolve (`levelFor`, `biomeAmbientK`, `LiveMaterials.materialFor`, `materialById`, `ColumnSectionCodec`, `SectionData.massAt`, sibling-IT setup) — these are existing symbols the implementer confirms, not invented APIs.

**Type consistency:** `EngineInjection{columnId,cellIndex,species,mass,temperature}`, `RegionStepResult{columns,injected,sealedLoss}`, `PendingInjections.Intent{dim,cx,cz,cell,species,mass,temperature}`, `PlacementInjectionPolicy.isDisplacement(live,incumbent)`, `PlacementCapture.capture(queue,dim,cx,cz,cell,live,incumbent,ambientK)`, `InjectionDrain.applyToColumn(...)`, `SpeciesMassLedger.expect(injected,sealedLoss)` — names consistent across tasks.

**Open implementer decisions (flag to the controller if a fork appears):**
- `ThermalWorld.pendingInjections()` interface-default vs `instanceof` cast (Task 8 note) — pick the one that keeps the test fakes compiling.
- Integration-harness depth for Task 9 (full scheduler vs focused pipeline) — pick the cheapest faithful setup from the sibling ITs.

---

## Execution Handoff

Plan 2 (Java) complete and saved. Execute with **superpowers:subagent-driven-development** (fresh subagent per task + two-stage review), same as Plan 1. Each implementer follows **superpowers:test-driven-development**. After Task 9 + the final review, use **superpowers:finishing-a-development-branch**.
