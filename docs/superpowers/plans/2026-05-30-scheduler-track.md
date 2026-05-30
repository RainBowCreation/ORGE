# §8 Scheduler (v1, single-node async) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the DESIGN §8 per-second server scheduler that gathers loaded sections around players, runs one `OrgeEngine.step(...)` on a background thread, validates the results, and writes new temperatures back into the §5 `SectionStore` — single-node only (the server is the sole worker; no client networking).

**Architecture:** A set of small **pure** units (sphere union, material LUT, geometry/halo assembly, result validation, worker health throttle) compose behind a `ThermalWorld` seam. The MC-free orchestrator (`Scheduler`) drives a tiny IDLE→AWAITING state machine: on a step boundary it asks the `ThermalWorld` for a dimension-tagged `Batch`, submits it to a `StepRunner` (background thread), and on completion validates + writes results back; a missed deadline holds previous temps. The only `net.minecraft`-coupled code is one adapter (`MinecraftThermalWorld`) and the tick wiring in `Orge.init()`.

**Tech Stack:** Java 21, `:core` (loader-agnostic, vanilla `net.minecraft.*` allowed, NO `net.fabricmc.*`/`net.neoforged.*`), JUnit 5 (`org.junit.jupiter`), Architectury common `TickEvent`/`LifecycleEvent`, the existing §2 `OrgeEngine`/`StepTask`/`NeighborHalo`, §5 `SectionStore`/`SectionData`/`SubchunkKey`, §6 `Material`/`MaterialBindings`/`ActiveMaterials`.

**Build env (no `java` on PATH — use exactly this):**
```
cd /home/claude/ORGE && JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
```
Fast unit loop: `:core:test`. Full both-loader build: `build`. Run a single test class: `:core:test --tests 'net.rainbowcreation.orge.scheduler.SphereUnionTest'`.

**Commit convention:** one commit per task; trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Push `origin rebuild` after each task. Work directly on `rebuild` (no worktree).

**Conventions reused from §2 (must match bit-for-bit):** `sidx(x,y,z) = x + 16*y + 256*z`; halo face order `negX, posX, negY, posY, negZ, posZ`; face-cell index X-faces `y + 16*z`, Y-faces `x + 16*z`, Z-faces `x + 16*y`; void = LUT index 0 (a material with `thermalConductivity = 0`, so the engine's `k ≤ 0` skip makes edges inert).

---

## File Structure

New files (all under `core/src/main/java/net/rainbowcreation/orge/scheduler/`):
- `SphereUnion.java` — pure: anchors + range → set of section keys.
- `MaterialLut.java` — pure: builds the per-batch `List<Material>`; index 0 = void.
- `GeometryAssembler.java` — pure: 4096 cells → `matIx` + `mass` (via injected `CellMaterials`).
- `HaloAssembler.java` — pure: 6 neighbour sections → `NeighborHalo`.
- `StepValidator.java` — pure: sanitize a result array (non-finite → fallback; clamp `[0,6000]`).
- `StepRunner.java` — seam: `submit(Callable) → Handle{isDone,result,cancel}`.
- `ExecutorStepRunner.java` — production `StepRunner` over a single-thread `ExecutorService`.
- `ThermalWorld.java` — seam: `snapshot(range) → Batch`, `writeBack(BatchEntry, float[])`; records `Batch`, `BatchEntry`.
- `MinecraftThermalWorld.java` — the only `net.minecraft`-coupled adapter (chunks, players, forced chunks, live tag bridge).

Modified files:
- `scheduler/Worker.java` — complete the health throttle (climb logic, `noteStep`, max range).
- `scheduler/Scheduler.java` — replace the throwing stub with the orchestrator + config constants.
- `Orge.java` — instantiate the scheduler and wire the tick driver + server lifecycle.

New test files (under `core/src/test/java/net/rainbowcreation/orge/scheduler/`):
- `SphereUnionTest`, `MaterialLutTest`, `GeometryAssemblerTest`, `HaloAssemblerTest`, `StepValidatorTest`, `WorkerTest`, `SchedulerTest`, `SchedulerEngineIntegrationTest`.

---

## Task 1: MaterialLut (per-batch material table, index 0 = void)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialLut.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/MaterialLutTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialLutTest {

    private static Material mat(String path, float k) {
        return new Material(Identifier.fromNamespaceAndPath("orge", path),
                k, 1000f, 0f, 2000f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    @Test
    void indexZeroIsVoidWithZeroConductivity() {
        MaterialLut lut = new MaterialLut();
        List<Material> materials = lut.materials();
        assertEquals(1, materials.size(), "fresh LUT holds only the void sentinel");
        assertEquals(0f, materials.get(0).thermalConductivity(), "void must be inert (k=0)");
    }

    @Test
    void firstRealMaterialGetsIndexOne_andRepeatsReuseIt() {
        MaterialLut lut = new MaterialLut();
        Material stone = mat("stone", 2.5f);
        char first = lut.indexOf(stone);
        char again = lut.indexOf(stone);
        assertEquals(1, first);
        assertEquals(1, again, "same material id reuses its index");
        assertEquals(2, lut.materials().size());
    }

    @Test
    void distinctMaterialsGetSequentialIndices() {
        MaterialLut lut = new MaterialLut();
        assertEquals(1, lut.indexOf(mat("stone", 2.5f)));
        assertEquals(2, lut.indexOf(mat("water", 0.6f)));
        assertEquals(3, lut.indexOf(mat("iron", 80f)));
        assertEquals(4, lut.materials().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.MaterialLutTest'`
Expected: FAIL — `MaterialLut` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the per-batch material lookup table passed to {@link
 * net.rainbowcreation.orge.engine.OrgeEngine#step}. Index 0 is always the
 * {@link #VOID} sentinel — a material with {@code thermalConductivity = 0}, so the
 * engine's {@code k <= 0} skip makes void/edge cells inert (matches §2's tests).
 * Real materials are appended in first-seen order and keyed by id, so a repeated
 * material reuses its index. Rebuilt fresh each step; no cross-tick stability needed.
 */
public final class MaterialLut {

    /** The index-0 inert sentinel: conductivity 0 ⇒ no flux across it. */
    public static final Material VOID = new Material(
            Identifier.fromNamespaceAndPath("orge", "void"),
            0f,     // thermalConductivity — inert
            1f,     // heatCapacity (never divided by: void cells are never stepped)
            0f, 0f, 0f,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);

    private final List<Material> lut = new ArrayList<>();
    private final Map<Identifier, Character> byId = new HashMap<>();

    public MaterialLut() {
        lut.add(VOID);
        byId.put(VOID.id(), (char) 0);
    }

    /** Returns the LUT index for {@code material}, appending it on first sight. */
    public char indexOf(Material material) {
        Character existing = byId.get(material.id());
        if (existing != null) {
            return existing;
        }
        char ix = (char) lut.size();
        lut.add(material);
        byId.put(material.id(), ix);
        return ix;
    }

    /** The table to hand to the engine; index 0 = {@link #VOID}. */
    public List<Material> materials() {
        return lut;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.MaterialLutTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/MaterialLut.java core/src/test/java/net/rainbowcreation/orge/scheduler/MaterialLutTest.java
git commit -m "feat(scheduler): per-batch MaterialLut with index-0 void sentinel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 2: SphereUnion (player anchors + range → section keys)

Convention (resolves DESIGN §4's loose wording): **range 1 = the anchor section only**; a section at offset `(dx,dy,dz)` is included iff `dx² + dy² + dz² <= (range-1)²`. So range 1 → 1 section, range 2 → 7, range 3 → 33. Multiple anchors union with natural dedup.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/SphereUnion.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SphereUnionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SphereUnionTest {

    @Test
    void rangeOneIsTheAnchorOnly() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 1);
        assertEquals(Set.of(new SubchunkKey(0, 0, 0)), out);
    }

    @Test
    void rangeTwoIsAnchorPlusSixFaceNeighbours() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 2);
        assertEquals(7, out.size());
        assertTrue(out.contains(new SubchunkKey(0, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(1, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(-1, 0, 0)));
        assertTrue(out.contains(new SubchunkKey(0, 1, 0)));
        assertTrue(out.contains(new SubchunkKey(0, -1, 0)));
        assertTrue(out.contains(new SubchunkKey(0, 0, 1)));
        assertTrue(out.contains(new SubchunkKey(0, 0, -1)));
        assertFalse(out.contains(new SubchunkKey(1, 1, 0)), "diagonal excluded at range 2");
    }

    @Test
    void rangeThreeHas33Sections() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(0, 0, 0)), 3);
        assertEquals(33, out.size());
        assertTrue(out.contains(new SubchunkKey(1, 1, 0)), "face-diagonal included at range 3");
        assertFalse(out.contains(new SubchunkKey(1, 1, 1)), "corner (d2=3) excluded at range 3");
        assertFalse(out.contains(new SubchunkKey(2, 0, 0)), "(d2=4) excluded at range 3");
    }

    @Test
    void overlappingAnchorsAreDeduped() {
        Set<SubchunkKey> a = SphereUnion.expand(
                Set.of(new SubchunkKey(0, 0, 0), new SubchunkKey(1, 0, 0)), 2);
        // Two range-2 spheres (7 each) sharing (0,0,0)<->(1,0,0) overlap; union < 14.
        assertTrue(a.contains(new SubchunkKey(0, 0, 0)));
        assertTrue(a.contains(new SubchunkKey(1, 0, 0)));
        assertTrue(a.size() < 14);
    }

    @Test
    void handlesNegativeCoordinates() {
        Set<SubchunkKey> out = SphereUnion.expand(Set.of(new SubchunkKey(-5, -2, -8)), 2);
        assertTrue(out.contains(new SubchunkKey(-5, -2, -8)));
        assertTrue(out.contains(new SubchunkKey(-6, -2, -8)));
        assertEquals(7, out.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SphereUnionTest'`
Expected: FAIL — `SphereUnion` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Expands a set of player anchor sections into the union of their range-N spheres
 * (DESIGN §4). Range 1 = the anchor section only; a section at offset
 * {@code (dx,dy,dz)} is included iff {@code dx²+dy²+dz² <= (range-1)²}. Overlapping
 * spheres dedup naturally via the {@link Set}.
 */
public final class SphereUnion {

    private SphereUnion() {}

    public static Set<SubchunkKey> expand(Set<SubchunkKey> anchors, int range) {
        int r = Math.max(1, range) - 1;
        int r2 = r * r;
        Set<SubchunkKey> out = new HashSet<>();
        for (SubchunkKey a : anchors) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= r2) {
                            out.add(new SubchunkKey(a.cx() + dx, a.sectionY() + dy, a.cz() + dz));
                        }
                    }
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SphereUnionTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/SphereUnion.java core/src/test/java/net/rainbowcreation/orge/scheduler/SphereUnionTest.java
git commit -m "feat(scheduler): SphereUnion range-N section expansion (DESIGN §4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 3: GeometryAssembler (cells → matIx + mass)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/GeometryAssembler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/GeometryAssemblerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryAssemblerTest {

    private static Material mat(String path, float defaultMass) {
        return new Material(Identifier.fromNamespaceAndPath("orge", path),
                2.5f, 1000f, 0f, defaultMass, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);
    }

    @Test
    void uniformSectionGetsOneRealIndexAndItsDefaultMass() {
        Material stone = mat("stone", 2700f);
        MaterialLut lut = new MaterialLut();
        GeometryAssembler.Geometry g = GeometryAssembler.assemble(i -> stone, lut);

        assertEquals(SectionData.CELLS, g.matIx().length);
        assertEquals(SectionData.CELLS, g.mass().length);
        for (int i = 0; i < SectionData.CELLS; i++) {
            assertEquals(1, g.matIx()[i], "all cells map to the single real material at index 1");
            assertEquals(2700f, g.mass()[i]);
        }
    }

    @Test
    void heterogeneousSectionGetsPerCellIndicesAndMasses() {
        Material stone = mat("stone", 2700f);
        Material air = mat("air", 1.2f);
        MaterialLut lut = new MaterialLut();
        // Even cells = stone, odd cells = air.
        GeometryAssembler.Geometry g =
                GeometryAssembler.assemble(i -> (i % 2 == 0) ? stone : air, lut);

        assertEquals(2700f, g.mass()[0]);
        assertEquals(1.2f, g.mass()[1]);
        assertEquals(g.matIx()[0], g.matIx()[2], "all stone cells share an index");
        assertNotEquals(g.matIx()[0], g.matIx()[1], "stone and air differ");
        assertEquals(3, lut.materials().size(), "void + stone + air");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.GeometryAssemblerTest'`
Expected: FAIL — `GeometryAssembler` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;

/**
 * Assembles one section's geometry for a {@link net.rainbowcreation.orge.engine.StepTask}:
 * the per-cell material index ({@code char[4096]}) and per-cell mass ({@code float[4096]},
 * each cell's {@link Material#defaultMass()}). Material identity is supplied per cell by an
 * injected {@link CellMaterials} seam, so this stays pure and unit-testable; the live
 * implementation reads the world's blocks (see {@code MinecraftThermalWorld}).
 */
public final class GeometryAssembler {

    /** Per-cell material lookup: cell index 0..4095 (x-fastest, {@code x+16y+256z}) → material. */
    @FunctionalInterface
    public interface CellMaterials {
        Material at(int cellIndex);
    }

    /** Assembled per-cell geometry arrays (both length {@value SectionData#CELLS}). */
    public record Geometry(char[] matIx, float[] mass) {}

    private GeometryAssembler() {}

    public static Geometry assemble(CellMaterials cells, MaterialLut lut) {
        char[] matIx = new char[SectionData.CELLS];
        float[] mass = new float[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            Material m = cells.at(i);
            matIx[i] = lut.indexOf(m);
            mass[i] = m.defaultMass();
        }
        return new Geometry(matIx, mass);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.GeometryAssemblerTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/GeometryAssembler.java core/src/test/java/net/rainbowcreation/orge/scheduler/GeometryAssemblerTest.java
git commit -m "feat(scheduler): GeometryAssembler — cells to matIx + per-cell mass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 4: HaloAssembler (6 neighbour sections → NeighborHalo)

This is the most index-sensitive unit — it MUST match §2 exactly: `sidx(x,y,z)=x+16y+256z`; face-cell index X-faces `y+16z`, Y-faces `x+16z`, Z-faces `x+16y`; constructor order `negXT,posXT,negYT,posYT,negZT,posZT, negXM,…`. A `null` neighbour (world/loaded edge) → temp face all `0f`, matIx face all `0` (void).

The contributing plane of each neighbour is the cell layer touching our section:
- `-X` neighbour contributes its `x=15` layer; `+X` neighbour its `x=0` layer.
- `-Y` neighbour its `y=15`; `+Y` neighbour its `y=0`.
- `-Z` neighbour its `z=15`; `+Z` neighbour its `z=0`.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/HaloAssemblerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.section.SectionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HaloAssemblerTest {

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    /** A neighbour whose temperature[i] == i and matIx[i] == (char) i, for index assertions. */
    private static HaloAssembler.Neighbor identityNeighbor() {
        float[] t = new float[SectionData.CELLS];
        char[] m = new char[SectionData.CELLS];
        for (int i = 0; i < SectionData.CELLS; i++) {
            t[i] = i;
            m[i] = (char) i;
        }
        return new HaloAssembler.Neighbor(t, m);
    }

    @Test
    void nullNeighboursProduceVoidFaces() {
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, null, null);
        for (float[] face : h.tempFaces()) {
            assertEquals(NeighborHalo.FACE_CELLS, face.length);
            for (float v : face) assertEquals(0f, v);
        }
        for (char[] face : h.matFaces()) {
            for (char v : face) assertEquals(0, v, "absent neighbour is void (index 0)");
        }
    }

    @Test
    void negXFacePullsTheNeighboursX15Layer() {
        // Only the -X neighbour present.
        NeighborHalo h = HaloAssembler.assemble(identityNeighbor(), null, null, null, null, null);
        float[] negXT = h.tempFaces()[0]; // canonical order: negX is index 0
        // X-face cell index is y + 16*z; value should be the neighbour's sidx(15,y,z).
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                assertEquals((float) sidx(15, y, z), negXT[y + 16 * z]);
            }
        }
    }

    @Test
    void posYFacePullsTheNeighboursY0Layer() {
        // Only the +Y neighbour present. Canonical face order: negX,posX,negY,posY,... → posY is index 3.
        NeighborHalo h = HaloAssembler.assemble(null, null, null, identityNeighbor(), null, null);
        float[] posYT = h.tempFaces()[3];
        // Y-face cell index is x + 16*z; value should be the neighbour's sidx(x,0,z).
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((float) sidx(x, 0, z), posYT[x + 16 * z]);
            }
        }
    }

    @Test
    void negZFacePullsTheNeighboursZ15LayerForMatIx() {
        // Only the -Z neighbour present. negZ is index 4.
        NeighborHalo h = HaloAssembler.assemble(null, null, null, null, identityNeighbor(), null);
        char[] negZM = h.matFaces()[4];
        // Z-face cell index is x + 16*y; value should be the neighbour's sidx(x,y,15).
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals((char) sidx(x, y, 15), negZM[x + 16 * y]);
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.HaloAssemblerTest'`
Expected: FAIL — `HaloAssembler` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;

/**
 * Builds a {@link NeighborHalo} from the six neighbouring sections (or {@code null} at a
 * world/loaded edge → a void face). Face layouts and index conventions match §2 exactly:
 * {@code sidx(x,y,z)=x+16y+256z}; X-faces indexed {@code y+16z}, Y-faces {@code x+16z},
 * Z-faces {@code x+16y}; face order {@code negX,posX,negY,posY,negZ,posZ}. The contributing
 * plane of each neighbour is the layer of cells touching our section (e.g. the {@code -X}
 * neighbour's {@code x=15} layer).
 */
public final class HaloAssembler {

    /** A neighbouring section's data needed to fill one halo face. */
    public record Neighbor(float[] temperature, char[] matIx) {}

    private HaloAssembler() {}

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    public static NeighborHalo assemble(Neighbor negX, Neighbor posX,
                                        Neighbor negY, Neighbor posY,
                                        Neighbor negZ, Neighbor posZ) {
        float[] negXT = new float[NeighborHalo.FACE_CELLS];
        float[] posXT = new float[NeighborHalo.FACE_CELLS];
        float[] negYT = new float[NeighborHalo.FACE_CELLS];
        float[] posYT = new float[NeighborHalo.FACE_CELLS];
        float[] negZT = new float[NeighborHalo.FACE_CELLS];
        float[] posZT = new float[NeighborHalo.FACE_CELLS];
        char[] negXM = new char[NeighborHalo.FACE_CELLS];
        char[] posXM = new char[NeighborHalo.FACE_CELLS];
        char[] negYM = new char[NeighborHalo.FACE_CELLS];
        char[] posYM = new char[NeighborHalo.FACE_CELLS];
        char[] negZM = new char[NeighborHalo.FACE_CELLS];
        char[] posZM = new char[NeighborHalo.FACE_CELLS];

        // X faces: face index = y + 16*z; neighbour plane x = 15 (negX) / x = 0 (posX).
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int f = y + 16 * z;
                copyCell(negX, sidx(15, y, z), negXT, negXM, f);
                copyCell(posX, sidx(0, y, z), posXT, posXM, f);
            }
        }
        // Y faces: face index = x + 16*z; neighbour plane y = 15 (negY) / y = 0 (posY).
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int f = x + 16 * z;
                copyCell(negY, sidx(x, 15, z), negYT, negYM, f);
                copyCell(posY, sidx(x, 0, z), posYT, posYM, f);
            }
        }
        // Z faces: face index = x + 16*y; neighbour plane z = 15 (negZ) / z = 0 (posZ).
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int f = x + 16 * y;
                copyCell(negZ, sidx(x, y, 15), negZT, negZM, f);
                copyCell(posZ, sidx(x, y, 0), posZT, posZM, f);
            }
        }

        return new NeighborHalo(
                negXT, posXT, negYT, posYT, negZT, posZT,
                negXM, posXM, negYM, posYM, negZM, posZM);
    }

    /** Copies one source cell into a face slot; a {@code null} neighbour leaves the void default (0). */
    private static void copyCell(Neighbor n, int srcIndex, float[] tFace, char[] mFace, int faceIndex) {
        if (n == null) {
            return; // arrays default to 0f / 0 (void)
        }
        tFace[faceIndex] = n.temperature()[srcIndex];
        mFace[faceIndex] = n.matIx()[srcIndex];
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.HaloAssemblerTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/HaloAssembler.java core/src/test/java/net/rainbowcreation/orge/scheduler/HaloAssemblerTest.java
git commit -m "feat(scheduler): HaloAssembler — 6 neighbour sections to NeighborHalo (§2 conventions)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 5: StepValidator (DESIGN §9 trust — sanitize results)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepValidatorTest {

    @Test
    void finiteInRangeValuesPassThroughUnchanged() {
        float[] result = {300f, 0f, 6000f, 285.5f};
        float[] fallback = {1f, 1f, 1f, 1f};
        float[] out = StepValidator.clean(result, fallback);
        assertArrayEquals(new float[]{300f, 0f, 6000f, 285.5f}, out);
    }

    @Test
    void nonFiniteValuesKeepTheFallback() {
        float[] result = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 300f};
        float[] fallback = {285f, 286f, 287f, 288f};
        float[] out = StepValidator.clean(result, fallback);
        assertEquals(285f, out[0], "NaN -> fallback");
        assertEquals(286f, out[1], "+Inf -> fallback");
        assertEquals(287f, out[2], "-Inf -> fallback");
        assertEquals(300f, out[3], "finite unchanged");
    }

    @Test
    void outOfRangeValuesAreClampedTo0_6000() {
        float[] result = {-50f, 9999f};
        float[] fallback = {1f, 1f};
        float[] out = StepValidator.clean(result, fallback);
        assertEquals(0f, out[0], "below 0 clamps to 0");
        assertEquals(6000f, out[1], "above 6000 clamps to 6000");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.StepValidatorTest'`
Expected: FAIL — `StepValidator` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package net.rainbowcreation.orge.scheduler;

/**
 * Validates an engine result array before it is written to the {@link
 * net.rainbowcreation.orge.section.SectionStore} (DESIGN §9 trust model). Per cell:
 * a non-finite value (NaN / ±Inf) keeps the supplied {@code fallback} (the snapshot input
 * temperature), so corruption can't spread; a finite value is clamped to the engine's
 * {@code [0, 6000]} K range. The §9 "assigned?" check is moot single-node (no remote results).
 */
public final class StepValidator {

    public static final float MIN_K = 0f;
    public static final float MAX_K = 6000f;

    private StepValidator() {}

    /** Returns a fresh sanitized copy of {@code result}, using {@code fallback[i]} where non-finite. */
    public static float[] clean(float[] result, float[] fallback) {
        float[] out = new float[result.length];
        for (int i = 0; i < result.length; i++) {
            float v = result[i];
            if (!Float.isFinite(v)) {
                out[i] = fallback[i];
            } else if (v < MIN_K) {
                out[i] = MIN_K;
            } else if (v > MAX_K) {
                out[i] = MAX_K;
            } else {
                out[i] = v;
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.StepValidatorTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/StepValidator.java core/src/test/java/net/rainbowcreation/orge/scheduler/StepValidatorTest.java
git commit -m "feat(scheduler): StepValidator — finite-check + [0,6000] clamp (DESIGN §9)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 6: Worker — complete the health throttle

Evolve the existing stub (`scheduler/Worker.java`). Add a max range, a compute budget, and an on-time-streak climb threshold via the constructor; add `noteStep(millis, metDeadline)` (climb on under-budget on-time steps, drop on late/over-budget) and an `onTimeStreak()` getter for tests. Keep `reportLate()` for the cancel/deadline-miss path.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Worker.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/WorkerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTest {

    /** budget 250ms, climb after 3 on-time steps, range 2..4. */
    private static Worker worker(int initialRange) {
        return new Worker(UUID.randomUUID(), true, initialRange, 4, 250.0, 3);
    }

    @Test
    void lateDropsRangeByOneDownToMin() {
        Worker w = worker(3);
        w.reportLate();
        assertEquals(2, w.range());
        w.reportLate();
        assertEquals(1, w.range());
        w.reportLate();
        assertEquals(1, w.range(), "never below MIN_RANGE");
    }

    @Test
    void overBudgetCompletionDropsRangeAndResetsStreak() {
        Worker w = worker(3);
        w.noteStep(300.0, true); // on time but over the 250ms budget
        assertEquals(2, w.range());
        assertEquals(0, w.onTimeStreak());
    }

    @Test
    void missedDeadlineCompletionDropsRange() {
        Worker w = worker(3);
        w.noteStep(10.0, false); // fast but missed the deadline (completed during grace)
        assertEquals(2, w.range());
    }

    @Test
    void climbsAfterKOnTimeUnderBudgetSteps() {
        Worker w = worker(2);
        w.noteStep(10.0, true);
        assertEquals(2, w.range(), "streak 1 < 3");
        w.noteStep(10.0, true);
        assertEquals(2, w.range(), "streak 2 < 3");
        w.noteStep(10.0, true);
        assertEquals(3, w.range(), "streak reached 3 -> climb");
        assertEquals(0, w.onTimeStreak(), "streak resets after a climb");
    }

    @Test
    void neverClimbsAboveMax() {
        Worker w = worker(4);
        for (int i = 0; i < 6; i++) w.noteStep(10.0, true);
        assertEquals(4, w.range());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.WorkerTest'`
Expected: FAIL — the 6-arg constructor / `noteStep` / `onTimeStreak` don't exist.

- [ ] **Step 3: Replace `Worker.java` with the completed version**

```java
package net.rainbowcreation.orge.scheduler;

import java.util.UUID;

/**
 * A compute worker in the server-orchestrated pool (DESIGN §3/§8). In the single-node v1
 * there is exactly one, the {@code serverFallback} worker (the server itself).
 *
 * <p>Health throttle (DESIGN §8): a step that misses its deadline or runs over the compute
 * budget drops {@code range} by 1 (to {@link #MIN_RANGE}); after {@code ticksToClimb}
 * consecutive on-time, under-budget steps the range climbs by 1 (to {@code maxRange}).</p>
 */
public final class Worker {

    public static final int MIN_RANGE = 1;

    private final UUID id;
    private final boolean serverFallback;
    private final int maxRange;
    private final double budgetMillis;
    private final int ticksToClimb;

    private int range;
    private int onTimeStreak;

    public Worker(UUID id, boolean serverFallback, int initialRange,
                  int maxRange, double budgetMillis, int ticksToClimb) {
        this.id = id;
        this.serverFallback = serverFallback;
        this.maxRange = Math.max(MIN_RANGE, maxRange);
        this.budgetMillis = budgetMillis;
        this.ticksToClimb = Math.max(1, ticksToClimb);
        this.range = clampRange(initialRange);
    }

    public UUID id() {
        return id;
    }

    public boolean isServerFallback() {
        return serverFallback;
    }

    public int range() {
        return range;
    }

    public int onTimeStreak() {
        return onTimeStreak;
    }

    /** A step missed its deadline (or was cancelled): drop range, reset streak. */
    public void reportLate() {
        onTimeStreak = 0;
        range = clampRange(range - 1);
    }

    /**
     * Records a completed step. {@code metDeadline} = the result arrived within the 1 s
     * deadline; {@code millis} = {@code engine.lastStepMillis()}. Drops range on a late or
     * over-budget step; otherwise advances the on-time streak and climbs after
     * {@code ticksToClimb} consecutive healthy steps.
     */
    public void noteStep(double millis, boolean metDeadline) {
        if (!metDeadline || millis > budgetMillis) {
            reportLate();
            return;
        }
        onTimeStreak++;
        if (onTimeStreak >= ticksToClimb) {
            onTimeStreak = 0;
            range = clampRange(range + 1);
        }
    }

    private int clampRange(int r) {
        return Math.max(MIN_RANGE, Math.min(maxRange, r));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.WorkerTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Worker.java core/src/test/java/net/rainbowcreation/orge/scheduler/WorkerTest.java
git commit -m "feat(scheduler): complete Worker health throttle (drop/climb, budget, max)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 7: StepRunner seam + ExecutorStepRunner

A tiny async seam so the orchestrator is deterministic to test (a fake runner) and threading stays isolated. The production runner wraps a single-thread `ExecutorService`.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/StepRunner.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/ExecutorStepRunner.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/ExecutorStepRunnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorStepRunnerTest {

    @Test
    void runsTheTaskAndExposesResult() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            float[] arr = {1f, 2f};
            StepRunner.Handle h = runner.submit(() -> List.of(arr));
            // Busy-wait briefly for the single background thread (test-only).
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!h.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(h.isDone(), "task should finish well within 2s");
            assertSame(arr, h.result().get(0));
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void failedTaskSurfacesAsRuntimeFromResult() throws Exception {
        ExecutorStepRunner runner = new ExecutorStepRunner();
        try {
            StepRunner.Handle h = runner.submit(() -> { throw new IllegalStateException("boom"); });
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!h.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(h.isDone());
            assertThrows(RuntimeException.class, h::result);
        } finally {
            runner.shutdown();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.ExecutorStepRunnerTest'`
Expected: FAIL — `StepRunner` / `ExecutorStepRunner` don't exist.

- [ ] **Step 3: Write the seam interface**

```java
package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs one conduction step off the server thread. Abstracted so the {@link Scheduler} can be
 * driven deterministically in tests (a fake runner) while production uses a real background
 * thread ({@link ExecutorStepRunner}).
 */
public interface StepRunner {

    /** Submit a step; the returned handle is polled by the scheduler on later ticks. */
    Handle submit(Callable<List<float[]>> task);

    /** A submitted step in flight. */
    interface Handle {
        /** True once the task has finished (successfully or not). Non-blocking. */
        boolean isDone();

        /**
         * The completed result. Precondition: {@link #isDone()} is true.
         * @throws RuntimeException if the task threw (the cause is attached).
         */
        List<float[]> result();

        /** Best-effort cancel of a not-yet-finished step. */
        void cancel();
    }
}
```

- [ ] **Step 4: Write the production runner**

```java
package net.rainbowcreation.orge.scheduler;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link StepRunner} backed by a single daemon background thread — the one server-side
 * fallback engine instance (DESIGN §3 keeps exactly one). Daemon so it never blocks JVM exit.
 */
public final class ExecutorStepRunner implements StepRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orge-conduction");
        t.setDaemon(true);
        return t;
    });

    @Override
    public Handle submit(Callable<List<float[]>> task) {
        Future<List<float[]>> future = executor.submit(task);
        return new Handle() {
            @Override
            public boolean isDone() {
                return future.isDone();
            }

            @Override
            public List<float[]> result() {
                try {
                    return future.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException("conduction step failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted awaiting step result", e);
                }
            }

            @Override
            public void cancel() {
                future.cancel(true);
            }
        };
    }

    /** Stop the background thread (call on server stop). */
    public void shutdown() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.ExecutorStepRunnerTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/StepRunner.java core/src/main/java/net/rainbowcreation/orge/scheduler/ExecutorStepRunner.java core/src/test/java/net/rainbowcreation/orge/scheduler/ExecutorStepRunnerTest.java
git commit -m "feat(scheduler): StepRunner async seam + ExecutorStepRunner (single daemon thread)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 8: ThermalWorld seam + Batch/BatchEntry

The orchestrator's only window onto the world. `snapshot(range)` assembles a dimension-tagged batch + the shared LUT; `writeBack` persists one validated result. `BatchEntry` carries the dimension as a vanilla `Identifier` (already used throughout `:core`) so `(0,0,0)` can't collide across dimensions; the scheduler treats the entry opaquely.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java`

(No dedicated test — it is an interface; it is exercised through `SchedulerTest`'s fake in Task 9.)

- [ ] **Step 1: Write the interface + records**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;

/**
 * The scheduler's window onto the world (the only seam it touches for I/O). The pure
 * orchestrator stays testable behind it; the live implementation ({@code MinecraftThermalWorld})
 * reads chunks/players and writes to the {@link net.rainbowcreation.orge.section.SectionStore}.
 */
public interface ThermalWorld {

    /** One section's task plus the dimension it belongs to (for write-back/halo routing). */
    record BatchEntry(Identifier dimension, SubchunkKey key, StepTask task) {}

    /** A step's worth of work: the dimension-tagged tasks + the shared material LUT. */
    record Batch(List<BatchEntry> entries, List<Material> lut) {}

    /**
     * Assemble this step's batch on the server thread: build the player-sphere (+ forced)
     * union at {@code range}, drop unloaded sections, and assemble each surviving section's
     * {@link StepTask} (geometry + a COPY of its temperatures + halo). Returns an empty batch
     * when there is nothing to simulate.
     */
    Batch snapshot(int range);

    /**
     * Persist one validated result on the server thread: write {@code newTemperatures} into the
     * entry's section and mark its column dirty (§5). A section that has unloaded since the
     * snapshot is skipped.
     */
    void writeBack(BatchEntry entry, float[] newTemperatures);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `... ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/ThermalWorld.java
git commit -m "feat(scheduler): ThermalWorld seam + dimension-tagged Batch/BatchEntry

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 9: Scheduler orchestrator (IDLE→AWAITING state machine)

Replace the throwing stub. The scheduler is MC-free (talks only to `ThermalWorld` + `StepRunner` + `OrgeEngine` + `Worker`), so it is fully unit-testable. Driven by `onServerTick()` once per server tick.

State machine:
- **IDLE:** count ticks; at `TICKS_PER_STEP` (20) submit a snapshot (if non-empty) and go AWAITING.
- **AWAITING:** each tick, if the handle is done → validate + write back each result, `worker.noteStep(lastStepMillis, metDeadline)`, back to IDLE. If not done past the deadline (1 step window) keep holding previous temps; after the grace window (a second step window) cancel + `worker.reportLate()` + back to IDLE.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    // ---- fakes -------------------------------------------------------------

    /** A controllable StepRunner: the test decides when the step is "done". */
    private static final class FakeRunner implements StepRunner {
        Callable<List<float[]>> task;
        boolean done;
        boolean cancelled;
        List<float[]> canned;       // if set, returned instead of running the task
        RuntimeException failure;   // if set, result() throws

        @Override
        public Handle submit(Callable<List<float[]>> t) {
            this.task = t;
            this.done = false;
            this.cancelled = false;
            return new Handle() {
                @Override public boolean isDone() { return done; }
                @Override public List<float[]> result() {
                    if (failure != null) throw failure;
                    if (canned != null) return canned;
                    try { return task.call(); } catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void cancel() { cancelled = true; }
            };
        }
    }

    /** A ThermalWorld that hands back a fixed batch and records write-backs. */
    private static final class FakeWorld implements ThermalWorld {
        Batch batch;
        final List<float[]> writes = new ArrayList<>();
        final List<SubchunkKey> writeKeys = new ArrayList<>();
        int snapshots;

        @Override public Batch snapshot(int range) { snapshots++; return batch; }
        @Override public void writeBack(BatchEntry entry, float[] t) {
            writeKeys.add(entry.key());
            writes.add(t);
        }
    }

    private static StepTask task(float fill) {
        float[] t = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(t, fill);
        char[] m = new char[net.rainbowcreation.orge.section.SectionData.CELLS];
        float[] mass = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        NeighborHalo halo = HaloAssembler.assemble(null, null, null, null, null, null);
        return new StepTask(new SubchunkKey(0, 0, 0), m, mass, t, halo);
    }

    private static ThermalWorld.Batch oneSectionBatch(float fill) {
        StepTask st = task(fill);
        return new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "overworld"), st.key(), st)),
                List.of(MaterialLut.VOID));
    }

    private static Worker worker() {
        return new Worker(UUID.randomUUID(), true, 2, 4, 250.0, 3);
    }

    /** An engine that returns each input temperature + delta, recording a fixed lastStepMillis. */
    private static OrgeEngine deltaEngine(float delta, double millis) {
        return new OrgeEngine() {
            @Override public List<float[]> step(List<StepTask> tasks, List<Material> lut, double dt) {
                List<float[]> out = new ArrayList<>();
                for (StepTask t : tasks) {
                    float[] r = t.temperature().clone();
                    for (int i = 0; i < r.length; i++) r[i] += delta;
                    out.add(r);
                }
                return out;
            }
            @Override public double lastStepMillis() { return millis; }
        };
    }

    // ---- tests -------------------------------------------------------------

    @Test
    void submitsOnlyEveryTwentyTicks() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 19; i++) s.onServerTick();
        assertEquals(0, world.snapshots, "no snapshot before tick 20");
        s.onServerTick(); // tick 20
        assertEquals(1, world.snapshots, "snapshot taken at the step boundary");
        assertNotNull(runner.task, "a step was submitted");
    }

    @Test
    void writesBackValidatedResultsWhenDone_andRecordsOnTimeStep() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 20; i++) s.onServerTick(); // submit
        runner.done = true;
        s.onServerTick(); // observe completion, write back
        assertEquals(1, world.writes.size());
        assertEquals(305f, world.writes.get(0)[0], "input 300 + delta 5, validated");
        assertEquals(1, w.onTimeStreak(), "on-time, under-budget step advanced the streak");
    }

    @Test
    void nonFiniteResultsKeepFallbackInput() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        // Engine result is irrelevant: we feed canned NaN through the runner.
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 20; i++) s.onServerTick();
        float[] nan = new float[net.rainbowcreation.orge.section.SectionData.CELLS];
        java.util.Arrays.fill(nan, Float.NaN);
        runner.canned = List.of(nan);
        runner.done = true;
        s.onServerTick();
        assertEquals(300f, world.writes.get(0)[0], "NaN -> fallback (snapshot input 300)");
    }

    @Test
    void deadlineMissHoldsPreviousThenCancelsAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker(); // range 2
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 20; i++) s.onServerTick(); // submit; never mark done
        // Hold for the deadline window + grace window without completing.
        for (int i = 0; i < Scheduler.TICKS_PER_STEP * 2; i++) s.onServerTick();
        assertTrue(runner.cancelled, "step cancelled after the grace window");
        assertEquals(0, world.writes.size(), "no write-back on a missed deadline (held previous)");
        assertEquals(1, w.range(), "late step dropped the range from 2 to 1");
    }

    @Test
    void completionDuringGraceCountsAsLate() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 20; i++) s.onServerTick(); // submit
        // Pass the deadline (one more full step window) but stay within grace, then complete.
        for (int i = 0; i < Scheduler.TICKS_PER_STEP; i++) s.onServerTick();
        runner.done = true;
        s.onServerTick();
        assertEquals(1, world.writes.size(), "a late-but-finished step still writes back");
        assertEquals(1, w.range(), "but it counts as late -> range dropped");
    }

    @Test
    void engineFailureHoldsPreviousAndDropsRange() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = oneSectionBatch(300f);
        Worker w = worker();
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, w);

        for (int i = 0; i < 20; i++) s.onServerTick();
        runner.failure = new RuntimeException("engine boom");
        runner.done = true;
        s.onServerTick();
        assertEquals(0, world.writes.size(), "failed step writes nothing");
        assertEquals(1, w.range(), "failed step treated as late");
    }

    @Test
    void emptyBatchDoesNotSubmitAndStaysIdle() {
        FakeRunner runner = new FakeRunner();
        FakeWorld world = new FakeWorld();
        world.batch = new ThermalWorld.Batch(List.of(), List.of(MaterialLut.VOID));
        Scheduler s = new Scheduler(deltaEngine(5f, 10.0), world, runner, worker());

        for (int i = 0; i < 40; i++) s.onServerTick();
        assertNull(runner.task, "nothing submitted for an empty batch");
        assertTrue(world.snapshots >= 1, "but it still tried to snapshot at the boundary");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SchedulerTest'`
Expected: FAIL — the new `Scheduler(engine, world, runner, worker)` constructor / `onServerTick()` don't exist (the stub still throws on `assign()`).

- [ ] **Step 3: Replace `Scheduler.java` with the orchestrator**

```java
package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The per-second server conduction scheduler (DESIGN §8), single-node v1: the server is the
 * sole worker. Driven by {@link #onServerTick()} once per server tick, it runs a small
 * IDLE→AWAITING state machine over a single in-flight {@link StepRunner} step.
 *
 * <ul>
 *   <li><b>IDLE</b>: count ticks; at {@link #TICKS_PER_STEP} ask the {@link ThermalWorld} for a
 *       batch and, if non-empty, submit {@code engine.step(...)} to the runner.</li>
 *   <li><b>AWAITING</b>: when the step finishes, validate (§9) and write each result back; a
 *       result missing the 1 s deadline <i>holds previous temps</i>, and after a grace window
 *       the step is cancelled. {@code engine.lastStepMillis()} drives the {@link Worker}
 *       health throttle.</li>
 * </ul>
 *
 * <p>This class is loader- and Minecraft-free: all world access is behind {@link ThermalWorld}.
 * Confined to the server thread (no internal locking), exactly like the §5 store.</p>
 */
public final class Scheduler {

    /** dt and cadence (DESIGN §4): one step per real second = every 20 ticks. */
    public static final int TICKS_PER_STEP = 20;
    public static final double STEP_DT_SECONDS = 1.0;

    /** Range bounds + health-throttle tunables (DESIGN §8; v1 constants). */
    public static final int DEFAULT_RANGE = 2;
    public static final int MAX_RANGE = 4;
    public static final double COMPUTE_BUDGET_MILLIS = 250.0;
    public static final int ON_TIME_TICKS_TO_CLIMB = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger("ORGE");

    private enum State { IDLE, AWAITING }

    private final OrgeEngine engine;
    private final ThermalWorld world;
    private final StepRunner runner;
    private final Worker worker;

    private State state = State.IDLE;
    private int tickCounter;
    private int ticksSinceSubmit;
    private StepRunner.Handle pending;
    private List<ThermalWorld.BatchEntry> pendingEntries;

    public Scheduler(OrgeEngine engine, ThermalWorld world, StepRunner runner, Worker worker) {
        this.engine = engine;
        this.world = world;
        this.runner = runner;
        this.worker = worker;
    }

    /** Advance the scheduler by one server tick (call from the server-tick hook). */
    public void onServerTick() {
        if (state == State.IDLE) {
            if (++tickCounter >= TICKS_PER_STEP) {
                tickCounter = 0;
                submit();
            }
            return;
        }
        // AWAITING
        ticksSinceSubmit++;
        if (pending.isDone()) {
            complete(ticksSinceSubmit <= TICKS_PER_STEP);
        } else if (ticksSinceSubmit >= TICKS_PER_STEP * 2) {
            // Past deadline + grace and still not done: cancel, hold previous, drop range.
            pending.cancel();
            worker.reportLate();
            reset();
        }
        // else: past the deadline but within grace — hold previous temps (do nothing).
    }

    private void submit() {
        ThermalWorld.Batch batch = world.snapshot(worker.range());
        if (batch.entries().isEmpty()) {
            return; // stay IDLE; nothing to simulate this step
        }
        List<StepTask> tasks = batch.entries().stream().map(ThermalWorld.BatchEntry::task).toList();
        pending = runner.submit(() -> engine.step(tasks, batch.lut(), STEP_DT_SECONDS));
        pendingEntries = batch.entries();
        ticksSinceSubmit = 0;
        state = State.AWAITING;
    }

    private void complete(boolean metDeadline) {
        List<float[]> results;
        try {
            results = pending.result();
        } catch (RuntimeException e) {
            // A failed step never corrupts the store: hold previous temps, treat as late.
            LOGGER.warn("[ORGE] conduction step failed; holding previous temps", e);
            worker.reportLate();
            reset();
            return;
        }
        int n = Math.min(results.size(), pendingEntries.size());
        for (int i = 0; i < n; i++) {
            ThermalWorld.BatchEntry entry = pendingEntries.get(i);
            float[] cleaned = StepValidator.clean(results.get(i), entry.task().temperature());
            world.writeBack(entry, cleaned);
        }
        worker.noteStep(engine.lastStepMillis(), metDeadline);
        reset();
    }

    private void reset() {
        pending = null;
        pendingEntries = null;
        ticksSinceSubmit = 0;
        tickCounter = 0;
        state = State.IDLE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SchedulerTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the whole `:core` suite to confirm no regressions**

Run: `... ./gradlew :core:test`
Expected: PASS — the existing 101 tests plus the new scheduler tests.

- [ ] **Step 6: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/Scheduler.java core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerTest.java
git commit -m "feat(scheduler): Scheduler IDLE/AWAITING orchestrator (async step + deadline + §9)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 10: Scheduler ↔ real engine integration test

Drives the real native engine (via `EngineFactory.create()`) through the whole `Scheduler` cycle with a `FakeRunner` that runs the callable inline. Asserts that a hot cell in an otherwise-uniform section cools and a neighbour warms after one step, and that the result is written back. `assumeTrue`-guarded so it skips where no native is bundled (mirrors §2's native tests).

**Files:**
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerEngineIntegrationTest.java`

- [ ] **Step 1: Write the test**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.NativeEngine;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerEngineIntegrationTest {

    private static int sidx(int x, int y, int z) { return x + 16 * y + 256 * z; }

    /** Inline runner: runs the step synchronously and reports done immediately. */
    private static final class InlineRunner implements StepRunner {
        @Override public Handle submit(Callable<List<float[]>> task) {
            final List<float[]> out;
            final RuntimeException err;
            List<float[]> r = null; RuntimeException e = null;
            try { r = task.call(); } catch (Exception ex) { e = new RuntimeException(ex); }
            out = r; err = e;
            return new Handle() {
                @Override public boolean isDone() { return true; }
                @Override public List<float[]> result() { if (err != null) throw err; return out; }
                @Override public void cancel() {}
            };
        }
    }

    private static final class CapturingWorld implements ThermalWorld {
        final Batch batch;
        float[] written;
        CapturingWorld(Batch b) { this.batch = b; }
        @Override public Batch snapshot(int range) { return batch; }
        @Override public void writeBack(BatchEntry entry, float[] t) { this.written = t; }
    }

    @Test
    void hotCellDiffusesThroughTheRealEngineAndIsWrittenBack() {
        OrgeEngine engine = EngineFactory.create();
        assumeTrue(engine instanceof NativeEngine, "native liborge not bundled; skipping");

        // A solid, conductive material (k>0, mass>0, heatCapacity>0).
        Material solid = new Material(Identifier.fromNamespaceAndPath("orge", "stone"),
                5.0f, 800f, 0f, 2700f, 0f,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null, null, null);

        MaterialLut lut = new MaterialLut();
        GeometryAssembler.Geometry geo = GeometryAssembler.assemble(i -> solid, lut);

        // Uniform 300 K except one hot interior cell at (8,8,8).
        float[] temps = new float[SectionData.CELLS];
        Arrays.fill(temps, 300f);
        int hot = sidx(8, 8, 8);
        temps[hot] = 1000f;
        float[] before = temps.clone();

        NeighborHalo voidHalo = HaloAssembler.assemble(null, null, null, null, null, null);
        StepTask st = new StepTask(new SubchunkKey(0, 0, 0), geo.matIx(), geo.mass(), temps, voidHalo);

        ThermalWorld.Batch batch = new ThermalWorld.Batch(
                List.of(new ThermalWorld.BatchEntry(
                        Identifier.fromNamespaceAndPath("minecraft", "overworld"), st.key(), st)),
                lut.materials());

        CapturingWorld world = new CapturingWorld(batch);
        Worker worker = new Worker(UUID.randomUUID(), true, 2, 4, 1000.0, 5);
        Scheduler s = new Scheduler(engine, world, new InlineRunner(), worker);

        for (int i = 0; i < Scheduler.TICKS_PER_STEP; i++) s.onServerTick(); // submit
        s.onServerTick(); // inline step is already done -> write back

        assertNotNull(world.written, "a result was written back");
        assertTrue(world.written[hot] < before[hot], "hot cell cooled");
        int neighbour = sidx(9, 8, 8);
        assertTrue(world.written[neighbour] > before[neighbour], "adjacent cell warmed");
        for (float v : world.written) {
            assertTrue(Float.isFinite(v) && v >= 0f && v <= 6000f, "validated range");
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `... ./gradlew :core:test --tests 'net.rainbowcreation.orge.scheduler.SchedulerEngineIntegrationTest'`
Expected: PASS (the native `liborge.so` is bundled for linux-x64 in this repo, so the assumption holds and the diffusion assertions pass).

- [ ] **Step 3: Commit & push**

```bash
git add core/src/test/java/net/rainbowcreation/orge/scheduler/SchedulerEngineIntegrationTest.java
git commit -m "test(scheduler): end-to-end Scheduler + real engine hot-cell diffusion

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 11: MinecraftThermalWorld (the live adapter)

The single `net.minecraft`-coupled class implementing `ThermalWorld`. It enumerates loaded levels/players/forced chunks, assembles tasks via the pure units, and writes results back through `SectionStoreManager`. It is exercised only at runtime (no server in the sandbox), so keep it thin — all logic lives in the pure units already tested.

> **API drift is the known risk** (the multiloader skill's 1.21.11 examples are stale — §5 verified the real names with `javap`). Before/while implementing, confirm each `net.minecraft` call below with:
> `JAVA_HOME=/home/claude/jdk21 /home/claude/jdk21/bin/javap -cp "$(find /home/claude/.gradle -name 'minecraft-merged-*.jar' | head -1)" net.minecraft.server.level.ServerLevel net.minecraft.world.level.chunk.LevelChunk net.minecraft.world.level.chunk.LevelChunkSection net.minecraft.core.registries.BuiltInRegistries net.minecraft.world.level.block.state.BlockState`
> Confirm specifically: `ServerLevel.getChunkSource()`, getting a loaded `LevelChunk` without forcing generation (`getChunkSource().getChunkNow(cx, cz)` returns `LevelChunk` or null), `LevelChunk.getSectionsCount()` / `getMinSectionY()` (or `LevelChunk.getSection(int arrayIndex)` + `getSectionIndexFromSectionY`), `LevelChunkSection.getBlockState(int x,int y,int z)` (0..15), `BlockState.getBlock()`, `BuiltInRegistries.BLOCK.getKey(Block) -> Identifier`, `level.players()`, `ServerPlayer.blockPosition()`, `level.getForcedChunks() -> LongSet`, `ChunkPos.x/.z` and `SectionPos`. Adjust the code to the confirmed signatures; the structure below stays the same.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java`

- [ ] **Step 1: Verify the MC signatures with `javap`** (see the box above). Note any that differ and adapt Step 2 accordingly.

- [ ] **Step 2: Write the adapter**

```java
package net.rainbowcreation.orge.scheduler;

import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.rainbowcreation.orge.engine.NeighborHalo;
import net.rainbowcreation.orge.engine.StepTask;
import net.rainbowcreation.orge.material.ActiveMaterials;
import net.rainbowcreation.orge.material.Material;
import net.rainbowcreation.orge.material.MaterialBindings;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live {@link ThermalWorld} over a running {@link MinecraftServer} (DESIGN §8). Builds the
 * player-sphere (+ forced-chunk) union across all loaded levels, assembles each loaded
 * section's {@link StepTask} via the pure {@link SphereUnion}/{@link GeometryAssembler}/
 * {@link HaloAssembler} units, and writes validated results back through the §5
 * {@link SectionStoreManager}. The only Minecraft-coupled class in the scheduler.
 */
public final class MinecraftThermalWorld implements ThermalWorld {

    private final SectionStoreManager stores;
    private volatile MinecraftServer server;

    public MinecraftThermalWorld(SectionStoreManager stores) {
        this.stores = stores;
    }

    /** Bind the running server (on SERVER_STARTED); unbind on stop. */
    public void bindServer(MinecraftServer server) { this.server = server; }
    public void unbindServer() { this.server = null; }

    /** Live tag-membership bridge — §6's deferred {@link MaterialBindings.TagMembership} consumer. */
    private static final MaterialBindings.TagMembership LIVE_TAGS = (tagId, blockId) -> {
        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
        return block.builtInRegistryHolder().is(tag);
    };

    private Material materialFor(Block block, ActiveMaterials.State mats) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        Identifier matId = mats.bindings().materialFor(blockId, LIVE_TAGS);
        return mats.registry().getOrFallback(matId);
    }

    @Override
    public Batch snapshot(int range) {
        MinecraftServer srv = this.server;
        if (srv == null) {
            return new Batch(List.of(), List.of(MaterialLut.VOID));
        }
        ActiveMaterials.State mats = ActiveMaterials.current();
        MaterialLut lut = new MaterialLut();
        List<BatchEntry> entries = new ArrayList<>();

        for (ServerLevel level : srv.getAllLevels()) {
            Identifier dim = level.dimension().identifier();

            // 1. Player anchors (the section each player stands in) + forced chunks → union.
            Set<SubchunkKey> anchors = new HashSet<>();
            for (ServerPlayer p : level.players()) {
                anchors.add(new SubchunkKey(
                        SectionPos.blockToSectionCoord(p.getBlockX()),
                        SectionPos.blockToSectionCoord(p.getBlockY()),
                        SectionPos.blockToSectionCoord(p.getBlockZ())));
            }
            if (anchors.isEmpty() && level.getForcedChunks().isEmpty()) {
                continue;
            }
            Set<SubchunkKey> union = SphereUnion.expand(anchors, range);
            addForcedSections(level, union);

            // 2. Assemble a task per loaded section.
            for (SubchunkKey key : union) {
                LevelChunk chunk = loadedChunk(level, key.cx(), key.cz());
                if (chunk == null) {
                    continue; // not loaded → skip this step
                }
                LevelChunkSection section = sectionOrNull(chunk, key.sectionY());
                if (section == null) {
                    continue;
                }
                GeometryAssembler.Geometry geo = GeometryAssembler.assemble(
                        i -> materialFor(blockAt(section, i), mats), lut);
                SectionStore store = stores.store(dim);
                float[] temps = (store != null)
                        ? store.get(key).temperatureArray().clone()
                        : ambientTemps();
                NeighborHalo halo = buildHalo(level, dim, key, lut, mats);
                entries.add(new BatchEntry(dim, key,
                        new StepTask(key, geo.matIx(), geo.mass(), temps, halo)));
            }
        }
        return new Batch(entries, lut.materials());
    }

    @Override
    public void writeBack(BatchEntry entry, float[] newTemperatures) {
        SectionStore store = stores.store(entry.dimension());
        if (store == null || !store.isLoaded(entry.key().cx(), entry.key().cz())) {
            return; // section/column unloaded since snapshot → skip
        }
        SectionData data = store.get(entry.key());
        float[] dst = data.temperatureArray(); // promotes UNIFORM→FULL
        System.arraycopy(newTemperatures, 0, dst, 0, SectionData.CELLS);
        store.put(entry.key(), data); // mark column dirty so §5 persists it
    }

    // ---- helpers (each a thin wrapper over a javap-confirmed MC call) -------

    private static float[] ambientTemps() {
        float[] t = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, SectionData.DEFAULT_AMBIENT_K);
        return t;
    }

    /** The block at section-local cell index i (x-fastest, x+16y+256z); 0..15 per axis. */
    private static Block blockAt(LevelChunkSection section, int i) {
        int x = i & 15;
        int y = (i >> 4) & 15;
        int z = (i >> 8) & 15;
        return section.getBlockState(x, y, z).getBlock();
    }

    /** A loaded chunk, or null if not currently loaded (never forces generation). */
    private static LevelChunk loadedChunk(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    /** The chunk's section at vanilla sectionY, or null if out of the chunk's Y range. */
    private static LevelChunkSection sectionOrNull(LevelChunk chunk, int sectionY) {
        int idx = chunk.getSectionIndexFromSectionY(sectionY);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return null;
        }
        return chunk.getSection(idx);
    }

    private void addForcedSections(ServerLevel level, Set<SubchunkKey> union) {
        it.unimi.dsi.fastutil.longs.LongIterator it = level.getForcedChunks().iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = loadedChunk(level, cx, cz);
            if (chunk == null) continue;
            int min = chunk.getMinSectionY();
            int count = chunk.getSectionsCount();
            for (int s = 0; s < count; s++) {
                union.add(new SubchunkKey(cx, min + s, cz));
            }
        }
    }

    /** Build the 6-face halo: neighbour temps from the store, neighbour matIx from geometry. */
    private NeighborHalo buildHalo(ServerLevel level, Identifier dim, SubchunkKey k,
                                   MaterialLut lut, ActiveMaterials.State mats) {
        return HaloAssembler.assemble(
                neighbor(level, dim, k.cx() - 1, k.sectionY(), k.cz(), lut, mats),
                neighbor(level, dim, k.cx() + 1, k.sectionY(), k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY() - 1, k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY() + 1, k.cz(), lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() - 1, lut, mats),
                neighbor(level, dim, k.cx(), k.sectionY(), k.cz() + 1, lut, mats));
    }

    /** A neighbour's (temperature, matIx) or null (void) when not loaded. */
    private HaloAssembler.Neighbor neighbor(ServerLevel level, Identifier dim,
                                            int cx, int sectionY, int cz,
                                            MaterialLut lut, ActiveMaterials.State mats) {
        LevelChunk chunk = loadedChunk(level, cx, cz);
        if (chunk == null) return null;
        LevelChunkSection section = sectionOrNull(chunk, sectionY);
        if (section == null) return null;
        SubchunkKey key = new SubchunkKey(cx, sectionY, cz);
        SectionStore store = stores.store(dim);
        float[] temps = (store != null) ? store.get(key).temperatureArray().clone() : ambientTemps();
        GeometryAssembler.Geometry geo =
                GeometryAssembler.assemble(i -> materialFor(blockAt(section, i), mats), lut);
        return new HaloAssembler.Neighbor(temps, geo.matIx());
    }
}
```

> **Note for the implementer:** if `javap` shows a different forced-chunks iterator type than `LongIterator`, adapt `addForcedSections` (e.g. `for (long packed : level.getForcedChunks())` if it is iterable). If `BuiltInRegistries.BLOCK.getValue(Identifier)` is named `get` in the confirmed mappings, adjust `LIVE_TAGS`. Keep the structure; only fix names/types `javap` proves wrong.

- [ ] **Step 3: Verify `:core` compiles**

Run: `... ./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL. If a symbol is unresolved, it is an API-name drift — re-run `javap` for that type and fix the call, then recompile.

- [ ] **Step 4: Run the full `:core` suite (no behavioural change expected; pure units still green)**

Run: `... ./gradlew :core:test`
Expected: PASS.

- [ ] **Step 5: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/MinecraftThermalWorld.java
git commit -m "feat(scheduler): MinecraftThermalWorld adapter — live union, geometry, halo, write-back

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

---

## Task 12: Wire the scheduler into Orge.init (tick driver + lifecycle) + build both loaders + memory

Instantiate the scheduler in `Orge.init()`, bind/unbind the server via `LifecycleEvent`, and drive `onServerTick()` from the common `TickEvent.SERVER_POST`. Then build both loader jars and record the track in memory.

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java`

- [ ] **Step 1: Verify the Architectury event names with `javap`**

Run: `JAVA_HOME=/home/claude/jdk21 /home/claude/jdk21/bin/javap -cp "$(find /home/claude/.gradle -name 'architectury-fabric-*.jar' | head -1)" dev.architectury.event.events.common.TickEvent dev.architectury.event.events.common.LifecycleEvent`
Expected: confirms `TickEvent.SERVER_POST` (field, type `Event<TickEvent.Server>`) and `LifecycleEvent.SERVER_STARTED` / `LifecycleEvent.SERVER_STOPPING` (type `Event<LifecycleEvent.ServerState>`). Adjust the handler signatures below to the confirmed functional-interface shapes (the `Server`/`ServerState` callbacks both receive the `MinecraftServer`).

- [ ] **Step 2: Add the scheduler fields + wiring to `Orge.java`**

Add these imports near the existing ones:

```java
import dev.architectury.event.events.common.TickEvent;
import net.rainbowcreation.orge.engine.EngineFactory;
import net.rainbowcreation.orge.engine.OrgeEngine;
import net.rainbowcreation.orge.scheduler.ExecutorStepRunner;
import net.rainbowcreation.orge.scheduler.MinecraftThermalWorld;
import net.rainbowcreation.orge.scheduler.Scheduler;
import net.rainbowcreation.orge.scheduler.Worker;

import java.util.UUID;
```

Add static fields beside `SECTION_STORES`:

```java
    /** DESIGN §8 — the single-node conduction scheduler and its background runner. */
    private static ExecutorStepRunner stepRunner;
    private static MinecraftThermalWorld thermalWorld;
    private static Scheduler scheduler;
```

Inside `init()`, after the `SectionStorePlatform.registerChunkHooks(SECTION_STORES);` line, add:

```java
        // DESIGN §8 — scheduler: one fallback engine on a background thread, driven once per
        // real second from the common server-tick event. Single-node v1 (the server is the
        // sole worker); client-distributed workers + the wire protocol are a follow-on track.
        OrgeEngine engine = EngineFactory.create();
        stepRunner = new ExecutorStepRunner();
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
        TickEvent.SERVER_POST.register(server -> scheduler.onServerTick());
```

- [ ] **Step 3: Build both loader jars (full verification — this is the headless gate)**

Run: `JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build`
Expected: BUILD SUCCESSFUL; both `fabric-1.21` and `neoforge-1.21` remapped jars build with `liborge.so` bundled, and all `:core` tests (existing 101 + new scheduler suite) pass.

- [ ] **Step 4: Commit & push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/Orge.java
git commit -m "feat(scheduler): wire Scheduler into Orge.init (SERVER_POST tick driver + lifecycle)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push origin rebuild
```

- [ ] **Step 5: Record the track in memory**

Create `/home/claude/.claude/projects/-home-claude-ORGE/memory/orge-scheduler.md` mirroring the §2/§5/§6 entries (frontmatter `type: project`; what's done, the single-node/async/rescan/geometry-mass decisions, the seam layout, deferred follow-ons: multi-worker + wire protocol, geometry version cache, §7 phase change, Phase-2 mass), and add a one-line pointer to `MEMORY.md`. Link `[[orge-engine-ffi]]`, `[[orge-section-store]]`, `[[orge-material-model]]`.

---

## Final whole-subsystem review

After Task 12, run the proven close-out: a fresh whole-subsystem review (spec-compliance + code-quality) reading the final code and re-running `:core:test` + `build`, then `superpowers:finishing-a-development-branch` (user convention: push to `origin/rebuild`, no PR / no merge to main while v1 is incomplete).
