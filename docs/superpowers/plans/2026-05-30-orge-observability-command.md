# `/orge` Observability Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a server-side `/orge get|section|set|fill` command that reads and injects per-cell temperature/mass so the thermal core can be audited in-game.

**Architecture:** Pure logic (`OrgeCommandLogic`) behind data-source seams (`ThermalReadSource` chain → client-cache-first, server fallback; `ThermalWriteSink` → server only), with a thin Brigadier adapter (`OrgeCommands`) registered via Architectury's common `CommandRegistrationEvent`. Mirrors the existing pure-core-behind-a-Minecraft-seam convention (`PhasePlanner`/`MinecraftPhaseChanger`, `Scheduler`/`ThermalWorld`). Reads are proximity-gated for non-ops using the scheduler's own sphere test.

**Tech Stack:** Java 21, Architectury (multiloader, MC 1.21.11), Brigadier, JUnit 5 (Jupiter). Build: Gradle.

**Spec:** `docs/superpowers/specs/2026-05-30-orge-observability-command-design.md`

---

## Conventions for every task

- **Package** for all new production classes: `net.rainbowcreation.orge.command` under `core/src/main/java/...`. Tests under `core/src/test/java/net/rainbowcreation/orge/command/` (except the `SphereUnion` change, which stays in the `scheduler` package).
- **Identifier:** this codebase uses `net.minecraft.resources.Identifier` (not `ResourceLocation`). Build ids with `Identifier.fromNamespaceAndPath(ns, path)`. A dimension's id is `level.dimension().identifier()`.
- **Run all core tests:** `./gradlew :core:test`
- **Run one test class:** `./gradlew :core:test --tests "net.rainbowcreation.orge.command.CellAddressTest"`
- **No non-ASCII in source strings** (avoid compiler-encoding surprises): use `C` for Celsius, `!=` for "not equal".
- **Commit** after each task with the message shown in its final step.

---

## File structure (created/modified by this plan)

**Created (`core/src/main/java/net/rainbowcreation/orge/command/`):**
- `CellAddress.java` — pure `BlockPos`-int → `(SubchunkKey, cell)`.
- `SectionView.java` — immutable read view of one section (temp/mass/form/ambient).
- `ThermalReadSource.java` — chainable read seam (`Optional<SectionView>`).
- `ThermalWriteSink.java` — server-authoritative write seam.
- `ReadRangeProvider.java` — supplies the configured section read-range.
- `OrgeCommandLogic.java` — pure command behavior (Request/Response, the four ops).
- `ServerStoreReadSource.java` — live read source over `SectionStoreManager`.
- `ServerStoreWriteSink.java` — live write sink over `SectionStoreManager`.
- `OrgeCommands.java` — thin Brigadier adapter + registration entry point.

**Modified:**
- `core/.../scheduler/SphereUnion.java` — add `contains(...)`, refactor `expand(...)` to use it.
- `core/.../section/SectionStore.java` — add `hasSection(SubchunkKey)`.
- `core/.../Orge.java` — register the command via `CommandRegistrationEvent`.

**Tests (created/extended):**
- `command/CellAddressTest.java`, `command/OrgeCommandLogicTest.java`, `command/ServerStoreSeamTest.java`
- `scheduler/SphereUnionTest.java` (extended), `section/SectionStoreTest.java` (extended)

---

## Task 1: `SphereUnion.contains` (shared sphere membership)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/scheduler/SphereUnion.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/scheduler/SphereUnionTest.java`

- [ ] **Step 1: Add the failing tests** — append these methods inside `class SphereUnionTest`:

```java
    @Test
    void containsRangeOneIsAnchorOnly() {
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        assertTrue(SphereUnion.contains(a, a, 1));
        assertFalse(SphereUnion.contains(a, new SubchunkKey(1, 0, 0), 1));
    }

    @Test
    void containsRangeTwoIncludesFaceNeighboursNotDiagonals() {
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        assertTrue(SphereUnion.contains(a, new SubchunkKey(0, 1, 0), 2));
        assertTrue(SphereUnion.contains(a, new SubchunkKey(-1, 0, 0), 2));
        assertFalse(SphereUnion.contains(a, new SubchunkKey(1, 1, 0), 2), "d2=2 excluded at range 2");
    }

    @Test
    void containsBoundaryAtRangeMinusOneSquared() {
        SubchunkKey a = new SubchunkKey(0, 0, 0);
        // range 3 -> r=2 -> r2=4; (2,0,0) d2=4 included, (2,1,0) d2=5 excluded
        assertTrue(SphereUnion.contains(a, new SubchunkKey(2, 0, 0), 3));
        assertFalse(SphereUnion.contains(a, new SubchunkKey(2, 1, 0), 3));
    }

    @Test
    void containsHandlesNegativeAnchors() {
        SubchunkKey a = new SubchunkKey(-5, -2, -8);
        assertTrue(SphereUnion.contains(a, new SubchunkKey(-6, -2, -8), 2));
        assertFalse(SphereUnion.contains(a, new SubchunkKey(-7, -2, -8), 2), "d2=4 at range 2 excluded");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SphereUnionTest"`
Expected: FAIL — `cannot find symbol: method contains`.

- [ ] **Step 3: Implement `contains` and refactor `expand`** — replace the body of `SphereUnion.java` (keep the package, imports, class declaration, and private constructor) so the two methods read:

```java
    /**
     * Whether {@code target} lies within the range-N sphere centred on {@code anchor}
     * (DESIGN §4): a section at offset {@code (dx,dy,dz)} is included iff
     * {@code dx*dx + dy*dy + dz*dz <= (range-1)^2}. {@code range <= 0} is treated as 1.
     */
    public static boolean contains(SubchunkKey anchor, SubchunkKey target, int range) {
        int r = Math.max(1, range) - 1;
        int dx = target.cx() - anchor.cx();
        int dy = target.sectionY() - anchor.sectionY();
        int dz = target.cz() - anchor.cz();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    public static Set<SubchunkKey> expand(Set<SubchunkKey> anchors, int range) {
        int r = Math.max(1, range) - 1;
        Set<SubchunkKey> out = new HashSet<>();
        for (SubchunkKey a : anchors) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        SubchunkKey candidate =
                                new SubchunkKey(a.cx() + dx, a.sectionY() + dy, a.cz() + dz);
                        if (contains(a, candidate, range)) {
                            out.add(candidate);
                        }
                    }
                }
            }
        }
        return out;
    }
```

- [ ] **Step 4: Run to verify pass** (both the new tests and the existing `expand` tests)

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.scheduler.SphereUnionTest"`
Expected: PASS (all `expand*` and `contains*` tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/scheduler/SphereUnion.java \
        core/src/test/java/net/rainbowcreation/orge/scheduler/SphereUnionTest.java
git commit -m "feat(command): SphereUnion.contains shared sphere membership"
```

---

## Task 2: `SectionStore.hasSection` (stored-vs-ambient probe)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/section/SectionStore.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/SectionStoreTest.java`

- [ ] **Step 1: Add the failing test** — append inside `class SectionStoreTest` (use the same store-construction helper the class already uses; the snippet below uses `@TempDir`, add the import `import org.junit.jupiter.api.io.TempDir;` and `import java.nio.file.Path;` if not present):

```java
    @Test
    void hasSectionFalseUntilPut() {
        SectionStore store = new SectionStore(
                RegionStore.under(tempDir.resolve("orge")), AmbientProvider.FALLBACK);
        store.loadColumn(0, 0);
        SubchunkKey key = new SubchunkKey(0, 4, 0);
        assertFalse(store.hasSection(key), "never-written section is not stored");
        store.put(key, SectionData.uniform(400f, 0f));
        assertTrue(store.hasSection(key), "after put it is stored");
        assertFalse(store.hasSection(new SubchunkKey(0, 5, 0)), "sibling section still absent");
    }
```

> **Note:** If `SectionStoreTest` constructs its store differently (e.g. a helper method or a different `RegionStore` factory), copy that existing construction instead of the `RegionStore.under(...)` line above — match the file's established pattern. `tempDir` here is a `@TempDir Path` field; if the class lacks one, add `@TempDir Path tempDir;`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.section.SectionStoreTest"`
Expected: FAIL — `cannot find symbol: method hasSection`.

- [ ] **Step 3: Implement `hasSection`** — add to `SectionStore.java`, directly above `get(...)`:

```java
    /**
     * Whether a section is currently stored (column loaded AND this sectionY present).
     * Distinguishes an evolved/stored section from the synthesized ambient baseline that
     * {@link #get} returns for never-simulated sections.
     */
    public boolean hasSection(SubchunkKey key) {
        NavigableMap<Integer, SectionData> col = loaded.get(colKey(key.cx(), key.cz()));
        return col != null && col.get(key.sectionY()) != null;
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.section.SectionStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/section/SectionStore.java \
        core/src/test/java/net/rainbowcreation/orge/section/SectionStoreTest.java
git commit -m "feat(command): SectionStore.hasSection probe for stored-vs-ambient"
```

---

## Task 3: `CellAddress` (BlockPos -> section + cell)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/command/CellAddress.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/CellAddressTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.scheduler.LiveMaterials;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellAddressTest {

    @Test
    void originMapsToSectionZeroCellZero() {
        CellAddress a = CellAddress.of(0, 0, 0);
        assertEquals(new SubchunkKey(0, 0, 0), a.key());
        assertEquals(0, a.cell());
    }

    @Test
    void cellIndexIsXFastestThenYThenZ() {
        // x + 16*y + 256*z, each axis masked to 0..15
        assertEquals(1, CellAddress.of(1, 0, 0).cell());
        assertEquals(16, CellAddress.of(0, 1, 0).cell());
        assertEquals(256, CellAddress.of(0, 0, 1).cell());
        assertEquals(1 + 16 * 2 + 256 * 3, CellAddress.of(1, 2, 3).cell());
        assertEquals(4095, CellAddress.of(15, 15, 15).cell());
    }

    @Test
    void negativeCoordsFloorToSectionAndWrapCell() {
        CellAddress a = CellAddress.of(-1, -1, -1);
        assertEquals(new SubchunkKey(-1, -1, -1), a.key());
        assertEquals(15 + 16 * 15 + 256 * 15, a.cell(), "(-1 & 15) == 15 on each axis");
    }

    @Test
    void roundTripsAgainstLiveMaterialsBlockAt() {
        // CellAddress.of(...).cell() is the inverse of LiveMaterials' x+16y+256z decode.
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, y = (i >> 4) & 15, z = (i >> 8) & 15;
            assertEquals(i, CellAddress.of(x, y, z).cell());
        }
        assertNotNull(LiveMaterials.class); // anchor the inverse relationship in the test's intent
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.CellAddressTest"`
Expected: FAIL — `cannot find symbol: class CellAddress`.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Pure mapping from a world block position to the ORGE cell that holds its thermal state:
 * the containing {@link SubchunkKey} plus the section-local cell index. The cell index is
 * the exact inverse of {@code LiveMaterials.blockAt} (x-fastest, {@code x + 16*y + 256*z}),
 * so the command addresses the same cell the engine indexes. ({@code >>4} on an {@code int}
 * equals {@code Math.floorDiv(_,16)} and is correct for negative coordinates.)
 */
public record CellAddress(SubchunkKey key, int cell) {

    public static CellAddress of(int x, int y, int z) {
        SubchunkKey key = new SubchunkKey(x >> 4, y >> 4, z >> 4);
        int cell = (x & 15) | ((y & 15) << 4) | ((z & 15) << 8);
        return new CellAddress(key, cell);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.CellAddressTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/CellAddress.java \
        core/src/test/java/net/rainbowcreation/orge/command/CellAddressTest.java
git commit -m "feat(command): CellAddress block-pos to section+cell mapping"
```

---

## Task 4: Seam interfaces (`SectionView`, `ThermalReadSource`, `ThermalWriteSink`, `ReadRangeProvider`)

These are pure interfaces with no behavior, so there is no standalone test — they are exercised by Task 5+. Create all four, then compile.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/command/SectionView.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/command/ThermalReadSource.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/command/ThermalWriteSink.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/command/ReadRangeProvider.java`

- [ ] **Step 1: `SectionView.java`**

```java
package net.rainbowcreation.orge.command;

import net.rainbowcreation.orge.section.SectionData;

/** Immutable read view of one section's per-cell thermal state, for observability. */
public interface SectionView {

    /** Temperature (K) of cell {@code cell} (0..4095). */
    float tempAt(int cell);

    /** Mass (kg) of cell {@code cell} (0..4095). */
    float massAt(int cell);

    /** Storage form (UNIFORM/FULL) of the underlying section. */
    SectionData.Form form();

    /** True when this is the synthesized never-simulated ambient baseline (not stored/evolved). */
    boolean ambient();
}
```

- [ ] **Step 2: `ThermalReadSource.java`**

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

/**
 * One link in the read chain. {@code OrgeCommandLogic} tries sources in order and uses the
 * first present result. v1 has only the server-store source; a future client-cache source is
 * prepended so reads prefer the freshest client-computed data and fall back to the server.
 */
public interface ThermalReadSource {

    /**
     * The section for {@code (dimension, key)} if this source can serve it; {@code empty}
     * means "I don't have it, try the next source." The server-store source returns empty
     * only when the dimension has no store at all (otherwise it returns the ambient baseline).
     */
    Optional<SectionView> section(Identifier dimension, SubchunkKey key);
}
```

- [ ] **Step 3: `ThermalWriteSink.java`**

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SubchunkKey;

/** Server-authoritative single-cell writes. Writes only ever target the server store. */
public interface ThermalWriteSink {

    /** Whether the column owning {@code key} is loaded (precondition for a durable write). */
    boolean isLoaded(Identifier dimension, SubchunkKey key);

    /** Write temperature (K) into one cell, marking the column dirty. */
    void writeTemp(Identifier dimension, SubchunkKey key, int cell, float kelvin);

    /** Write mass (kg) into one cell, marking the column dirty. */
    void writeMass(Identifier dimension, SubchunkKey key, int cell, float kg);
}
```

- [ ] **Step 4: `ReadRangeProvider.java`**

```java
package net.rainbowcreation.orge.command;

/**
 * Supplies the configured section read-range (in sections) that bounds non-op
 * get/section. v1 returns a constant ({@code Scheduler.MAX_RANGE}); a future server-config
 * value and the client-side range calc read through this same seam.
 */
@FunctionalInterface
public interface ReadRangeProvider {
    int sectionReadRange();
}
```

- [ ] **Step 5: Compile, then commit**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL.

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/SectionView.java \
        core/src/main/java/net/rainbowcreation/orge/command/ThermalReadSource.java \
        core/src/main/java/net/rainbowcreation/orge/command/ThermalWriteSink.java \
        core/src/main/java/net/rainbowcreation/orge/command/ReadRangeProvider.java
git commit -m "feat(command): read-source/write-sink/read-range/section-view seams"
```

---

## Task 5: `OrgeCommandLogic` — types + `get` (proximity + read chain + ambient annotation)

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java`

This task creates the `Request`/`Response`/`Op` types, the constructor, the `get` op, and a shared test-fixture (fakes) used by Tasks 5-8.

- [ ] **Step 1: Write the failing test** (creates the fakes + `get` coverage)

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OrgeCommandLogicTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    static final int MIN_Y = -64, MAX_Y = 320; // exclusive max

    /** Array-backed fake view (FULL semantics; ambient flag set explicitly). */
    static SectionView view(float temp, float mass, boolean ambient) {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, temp);
        java.util.Arrays.fill(m, mass);
        return arrayView(t, m, ambient, SectionData.Form.UNIFORM);
    }

    static SectionView arrayView(float[] t, float[] m, boolean ambient, SectionData.Form form) {
        return new SectionView() {
            public float tempAt(int c) { return t[c]; }
            public float massAt(int c) { return m[c]; }
            public SectionData.Form form() { return form; }
            public boolean ambient() { return ambient; }
        };
    }

    /** Read source backed by an in-memory map; absent key -> empty. A null map -> always empty (no store). */
    static ThermalReadSource source(Map<SubchunkKey, SectionView> data) {
        return (dim, key) -> data == null ? Optional.empty() : Optional.ofNullable(data.get(key));
    }

    static OrgeCommandLogic logic(List<ThermalReadSource> reads, ThermalWriteSink sink, int range) {
        return new OrgeCommandLogic(reads, sink, () -> range);
    }

    static OrgeCommandLogic.Request get(int x, int y, int z, boolean op, SubchunkKey src) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.GET, DIM,
                x, y, z, x, y, z, null, null, op, src, MIN_Y, MAX_Y);
    }

    @Test
    void getReadsAmbientBaselineWithAnnotation() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);

        OrgeCommandLogic.Response r = logic.run(get(1, 2, 3, true, null));

        assertTrue(r.ok());
        assertEquals(1, r.lines().size());
        assertTrue(r.lines().get(0).contains("285.00 K"), r.lines().get(0));
        assertTrue(r.lines().get(0).contains("ambient"), "ambient baseline is annotated");
    }

    @Test
    void getReadSourceChainPrefersFirstHit() {
        SubchunkKey key = new SubchunkKey(0, 0, 0);
        Map<SubchunkKey, SectionView> client = new HashMap<>();
        client.put(key, view(500f, 0f, false));            // "client cache" - fresher
        Map<SubchunkKey, SectionView> server = new HashMap<>();
        server.put(key, view(285f, 0f, true));             // server fallback
        OrgeCommandLogic logic = logic(List.of(source(client), source(server)), null, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("500.00 K"), "first source wins: " + r.lines().get(0));
    }

    @Test
    void getFallsBackToSecondSourceWhenFirstMisses() {
        SubchunkKey key = new SubchunkKey(0, 0, 0);
        Map<SubchunkKey, SectionView> server = new HashMap<>();
        server.put(key, view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>()), source(server)), null, 4);

        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));

        assertTrue(r.lines().get(0).contains("285.00 K"));
    }

    @Test
    void getNoStoreForDimensionFails() {
        OrgeCommandLogic logic = logic(List.of(source(null)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("no ORGE data"), r.lines().get(0));
    }

    @Test
    void getYOutOfBuildHeightFails() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 999, 0, true, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"), r.lines().get(0));
    }

    // ---- proximity gate ----

    @Test
    void nonOpInSphereAllowed() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        // target section (0,0,0); source section (1,0,0): d2=1 <= (4-1)^2
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(1, 0, 0)));
        assertTrue(r.ok(), r.lines().toString());
    }

    @Test
    void nonOpOutOfSphereDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 2); // range 2 -> r=1 -> r2=1
        // source section (5,0,0): d2=25 > 1
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, new SubchunkKey(5, 0, 0)));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"), r.lines().get(0));
    }

    @Test
    void nonOpWithoutSourcePositionDenied() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);
        OrgeCommandLogic.Response r = logic.run(get(0, 0, 0, false, null));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"));
    }

    @Test
    void opReadsAnywhere() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(100, 0, 100), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 2);
        OrgeCommandLogic.Response r = logic.run(get(1600, 0, 1600, true, new SubchunkKey(0, 0, 0)));
        assertTrue(r.ok());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: FAIL — `cannot find symbol: class OrgeCommandLogic`.

- [ ] **Step 3: Implement `OrgeCommandLogic` with types + `get`** (the other ops are stubbed to throw until their tasks; this keeps the file compiling and Task 5 green):

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.scheduler.SphereUnion;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.List;
import java.util.Optional;

/**
 * Pure behavior of the {@code /orge} command (DESIGN observability track, Topic A). No Brigadier,
 * no Minecraft text: it takes a {@link Request} and returns a {@link Response} of plain feedback
 * lines, so it is fully headless-testable. Reads walk an ordered {@link ThermalReadSource} chain
 * (client cache -> server fallback); writes go to a single server-authoritative {@link ThermalWriteSink}.
 */
public final class OrgeCommandLogic {

    public enum Op { GET, SECTION, SET, FILL }

    /** Max cells a single fill may touch (32^3). */
    public static final int FILL_CELL_CAP = 32 * 32 * 32;

    /**
     * One parsed command invocation. For non-FILL ops the second position equals the first.
     * {@code temperatureK}/{@code massKg} are null when not supplied. {@code sourceSection} is
     * null when the source has no position. {@code maxBuildY} is exclusive.
     */
    public record Request(
            Op op,
            Identifier dimension,
            int x1, int y1, int z1,
            int x2, int y2, int z2,
            Float temperatureK,
            Float massKg,
            boolean operator,
            SubchunkKey sourceSection,
            int minBuildY,
            int maxBuildY) {}

    public record Response(boolean ok, List<String> lines) {
        public static Response ok(String line) { return new Response(true, List.of(line)); }
        public static Response ok(List<String> lines) { return new Response(true, lines); }
        public static Response fail(String line) { return new Response(false, List.of(line)); }
    }

    private final List<ThermalReadSource> readSources;
    private final ThermalWriteSink writeSink;
    private final ReadRangeProvider readRange;

    public OrgeCommandLogic(List<ThermalReadSource> readSources,
                            ThermalWriteSink writeSink,
                            ReadRangeProvider readRange) {
        this.readSources = List.copyOf(readSources);
        this.writeSink = writeSink;
        this.readRange = readRange;
    }

    public Response run(Request r) {
        return switch (r.op()) {
            case GET -> get(r);
            case SECTION -> section(r);
            case SET -> set(r);
            case FILL -> fill(r);
        };
    }

    // ----- GET -----

    private Response get(Request r) {
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        CellAddress addr = CellAddress.of(r.x1(), r.y1(), r.z1());
        if (!readAllowed(r, addr.key())) {
            return Response.fail(rangeError());
        }
        Optional<SectionView> v = resolve(r.dimension(), addr.key());
        if (v.isEmpty()) {
            return Response.fail(noStore(r.dimension()));
        }
        SectionView view = v.get();
        float t = view.tempAt(addr.cell());
        float m = view.massAt(addr.cell());
        String formStr = view.form() + (view.ambient() ? " (ambient)" : "");
        return Response.ok(String.format(
                "cell (%d,%d,%d) [%s]: %.2f K (%.2f C), %.1f kg, form=%s",
                r.x1(), r.y1(), r.z1(), r.dimension(), t, t - 273.15f, m, formStr));
    }

    // ----- ops implemented in later tasks -----

    private Response section(Request r) { throw new UnsupportedOperationException("Task 6"); }
    private Response set(Request r) { throw new UnsupportedOperationException("Task 7"); }
    private Response fill(Request r) { throw new UnsupportedOperationException("Task 8"); }

    // ----- helpers -----

    private Optional<SectionView> resolve(Identifier dim, SubchunkKey key) {
        for (ThermalReadSource s : readSources) {
            Optional<SectionView> v = s.section(dim, key);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    private boolean inBuildRange(int y, Request r) {
        return y >= r.minBuildY() && y < r.maxBuildY();
    }

    private boolean readAllowed(Request r, SubchunkKey target) {
        if (r.operator()) {
            return true;
        }
        if (r.sourceSection() == null) {
            return false;
        }
        return SphereUnion.contains(r.sourceSection(), target, readRange.sectionReadRange());
    }

    private String yError(Request r) {
        return String.format("Y out of build height [%d,%d)", r.minBuildY(), r.maxBuildY());
    }

    private String rangeError() {
        return "out of range; you can read cells within " + readRange.sectionReadRange()
                + " sections of you (op to read anywhere)";
    }

    private static String noStore(Identifier dim) {
        return "no ORGE data for dimension " + dim;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: PASS (all `get*`, proximity, and chain tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java \
        core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java
git commit -m "feat(command): OrgeCommandLogic get op + proximity + read-source chain"
```

---

## Task 6: `OrgeCommandLogic` — `section` op

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java`

- [ ] **Step 1: Add the failing tests** — append inside `OrgeCommandLogicTest`:

```java
    static OrgeCommandLogic.Request section(int x, int y, int z, boolean op, SubchunkKey src) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.SECTION, DIM,
                x, y, z, x, y, z, null, null, op, src, MIN_Y, MAX_Y);
    }

    @Test
    void sectionSummarizesUniformAmbient() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);

        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, true, null));

        assertTrue(r.ok());
        String joined = String.join("\n", r.lines());
        assertTrue(joined.contains("(ambient)"), joined);
        assertTrue(joined.contains("285.00 / 285.00 / 285.00 K"), joined);
        assertTrue(joined.contains("0 / 4096"), "uniform -> 0 non-uniform cells: " + joined);
    }

    @Test
    void sectionReportsGradientMinAvgMaxAndNonUniformCount() {
        float[] t = new float[SectionData.CELLS];
        float[] m = new float[SectionData.CELLS];
        java.util.Arrays.fill(t, 300f);
        t[0] = 300f;       // cell0 baseline
        t[1] = 400f;       // one hotter cell
        t[2] = 200f;       // one colder cell
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), arrayView(t, m, false, SectionData.Form.FULL));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 4);

        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, true, null));

        String joined = String.join("\n", r.lines());
        assertTrue(joined.contains("200.00 / "), "min reflects coldest: " + joined);
        assertTrue(joined.contains(" / 400.00 K"), "max reflects hottest: " + joined);
        assertTrue(joined.contains("2 / 4096"), "two cells differ from cell0: " + joined);
        assertFalse(joined.contains("(ambient)"), "stored section not annotated ambient");
    }

    @Test
    void sectionProximityGatedForNonOp() {
        Map<SubchunkKey, SectionView> data = new HashMap<>();
        data.put(new SubchunkKey(0, 0, 0), view(285f, 0f, true));
        OrgeCommandLogic logic = logic(List.of(source(data)), null, 2);
        OrgeCommandLogic.Response r = logic.run(section(0, 0, 0, false, new SubchunkKey(9, 0, 0)));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("out of range"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: FAIL — `section` throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `section`** — replace the `section` stub in `OrgeCommandLogic.java` with:

```java
    private Response section(Request r) {
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        SubchunkKey key = CellAddress.of(r.x1(), r.y1(), r.z1()).key();
        if (!readAllowed(r, key)) {
            return Response.fail(rangeError());
        }
        Optional<SectionView> v = resolve(r.dimension(), key);
        if (v.isEmpty()) {
            return Response.fail(noStore(r.dimension()));
        }
        SectionView view = v.get();
        float tMin = Float.POSITIVE_INFINITY, tMax = Float.NEGATIVE_INFINITY, tSum = 0f;
        float mMin = Float.POSITIVE_INFINITY, mMax = Float.NEGATIVE_INFINITY, mSum = 0f;
        float t0 = view.tempAt(0);
        int nonUniform = 0;
        int cells = net.rainbowcreation.orge.section.SectionData.CELLS;
        for (int i = 0; i < cells; i++) {
            float t = view.tempAt(i);
            float m = view.massAt(i);
            tMin = Math.min(tMin, t); tMax = Math.max(tMax, t); tSum += t;
            mMin = Math.min(mMin, m); mMax = Math.max(mMax, m); mSum += m;
            if (t != t0) {
                nonUniform++;
            }
        }
        String formStr = view.form() + (view.ambient() ? " (ambient)" : "");
        return Response.ok(List.of(
                String.format("section (%d,%d,%d) [%s]: form=%s",
                        key.cx(), key.sectionY(), key.cz(), r.dimension(), formStr),
                String.format("  T    min/avg/max = %.2f / %.2f / %.2f K",
                        tMin, tSum / cells, tMax),
                String.format("  mass min/avg/max = %.1f / %.1f / %.1f kg",
                        mMin, mSum / cells, mMax),
                String.format("  non-uniform cells (T!=cell0): %d / %d", nonUniform, cells)));
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java \
        core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java
git commit -m "feat(command): OrgeCommandLogic section summary op"
```

---

## Task 7: `OrgeCommandLogic` — `set` op (op gate, Y validation, not-loaded)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java`

- [ ] **Step 1: Add the failing tests** — append inside `OrgeCommandLogicTest` (this adds a recording fake write sink):

```java
    /** Records writes; isLoaded controlled by a set of loaded columns (cx,cz packed as "cx,cz"). */
    static final class FakeSink implements ThermalWriteSink {
        final java.util.Set<String> loaded = new java.util.HashSet<>();
        final List<String> temps = new ArrayList<>();
        final List<String> masses = new ArrayList<>();
        FakeSink load(int cx, int cz) { loaded.add(cx + "," + cz); return this; }
        public boolean isLoaded(Identifier dim, SubchunkKey key) {
            return loaded.contains(key.cx() + "," + key.cz());
        }
        public void writeTemp(Identifier dim, SubchunkKey key, int cell, float k) {
            temps.add(key.cx() + "," + key.sectionY() + "," + key.cz() + ":" + cell + "=" + k);
        }
        public void writeMass(Identifier dim, SubchunkKey key, int cell, float kg) {
            masses.add(key.cx() + "," + key.sectionY() + "," + key.cz() + ":" + cell + "=" + kg);
        }
    }

    static OrgeCommandLogic.Request set(int x, int y, int z, Float k, Float mass, boolean op) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.SET, DIM,
                x, y, z, x, y, z, k, mass, op, null, MIN_Y, MAX_Y);
    }

    @Test
    void setRequiresOperator() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, null, false));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("operator"), r.lines().get(0));
        assertTrue(sink.temps.isEmpty(), "no write when denied");
    }

    @Test
    void setWritesTempLeavesMassUnchangedWhenOmitted() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(set(1, 2, 3, 400f, null, true));
        assertTrue(r.ok(), r.lines().toString());
        assertEquals(1, sink.temps.size());
        assertTrue(sink.temps.get(0).endsWith("=400.0"), sink.temps.get(0));
        assertTrue(sink.masses.isEmpty(), "mass omitted -> not written");
        assertTrue(r.lines().get(0).contains("mass unchanged"), r.lines().get(0));
    }

    @Test
    void setWritesTempAndMassWhenProvided() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, 1000f, true));
        assertTrue(r.ok());
        assertEquals(1, sink.temps.size());
        assertEquals(1, sink.masses.size());
        assertTrue(sink.masses.get(0).endsWith("=1000.0"));
    }

    @Test
    void setNotLoadedFails() {
        FakeSink sink = new FakeSink(); // nothing loaded
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 0, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("not loaded"), r.lines().get(0));
        assertTrue(sink.temps.isEmpty());
    }

    @Test
    void setYOutOfRangeFails() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(set(0, 999, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: FAIL — `set` throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `set`** — replace the `set` stub with:

```java
    private Response set(Request r) {
        if (!r.operator()) {
            return Response.fail(opError());
        }
        if (!inBuildRange(r.y1(), r)) {
            return Response.fail(yError(r));
        }
        CellAddress addr = CellAddress.of(r.x1(), r.y1(), r.z1());
        if (!writeSink.isLoaded(r.dimension(), addr.key())) {
            return Response.fail(notLoaded());
        }
        writeSink.writeTemp(r.dimension(), addr.key(), addr.cell(), r.temperatureK());
        String massPart;
        if (r.massKg() != null) {
            writeSink.writeMass(r.dimension(), addr.key(), addr.cell(), r.massKg());
            massPart = String.format(", %.1f kg", r.massKg());
        } else {
            massPart = " (mass unchanged)";
        }
        return Response.ok(String.format("set (%d,%d,%d) -> %.2f K%s",
                r.x1(), r.y1(), r.z1(), r.temperatureK(), massPart));
    }
```

And add these two helpers next to the other error helpers:

```java
    private static String opError() {
        return "requires operator (permission level 2)";
    }

    private static String notLoaded() {
        return "target section not loaded; move closer";
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java \
        core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java
git commit -m "feat(command): OrgeCommandLogic set op (op gate, not-loaded, Y check)"
```

---

## Task 8: `OrgeCommandLogic` — `fill` op (box, cell cap, skipped count)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java`

- [ ] **Step 1: Add the failing tests** — append inside `OrgeCommandLogicTest`:

```java
    static OrgeCommandLogic.Request fill(int x1, int y1, int z1, int x2, int y2, int z2,
                                         Float k, Float mass, boolean op) {
        return new OrgeCommandLogic.Request(OrgeCommandLogic.Op.FILL, DIM,
                x1, y1, z1, x2, y2, z2, k, mass, op, null, MIN_Y, MAX_Y);
    }

    @Test
    void fillRequiresOperator() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 1, 1, 400f, null, false));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("operator"));
    }

    @Test
    void fillWritesEveryCellInBoxAndReportsCount() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        // 3x3x3 box at origin = 27 cells, all in column (0,0)
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 2, 2, 2, 400f, null, true));
        assertTrue(r.ok(), r.lines().toString());
        assertEquals(27, sink.temps.size());
        assertTrue(r.lines().get(0).contains("filled 27 cells"), r.lines().get(0));
    }

    @Test
    void fillWritesMassWhenProvided() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 0, 0, 400f, 500f, true));
        assertTrue(r.ok());
        assertEquals(2, sink.temps.size());
        assertEquals(2, sink.masses.size());
    }

    @Test
    void fillSkipsUnloadedColumnsAndReports() {
        FakeSink sink = new FakeSink().load(0, 0); // only column (0,0) loaded
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        // span x 0..16 crosses into column (1,0) at x=16, which is not loaded
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 16, 0, 0, 400f, null, true));
        assertTrue(r.ok());
        assertEquals(16, sink.temps.size(), "x=0..15 loaded, x=16 skipped");
        assertTrue(r.lines().get(0).contains("1 skipped"), r.lines().get(0));
    }

    @Test
    void fillAllUnloadedFails() {
        FakeSink sink = new FakeSink(); // nothing loaded
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 1, 1, 1, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("0 cells"), r.lines().get(0));
        assertTrue(sink.temps.isEmpty());
    }

    @Test
    void fillOverCapRejected() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        // 33x33x33 = 35937 > 32768 cap
        OrgeCommandLogic.Response r = logic.run(fill(0, 0, 0, 32, 32, 32, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("too large"), r.lines().get(0));
        assertTrue(sink.temps.isEmpty(), "rejected before any write");
    }

    @Test
    void fillYOutOfRangeFails() {
        FakeSink sink = new FakeSink().load(0, 0);
        OrgeCommandLogic logic = logic(List.of(source(new HashMap<>())), sink, 4);
        OrgeCommandLogic.Response r = logic.run(fill(0, -100, 0, 0, 999, 0, 400f, null, true));
        assertFalse(r.ok());
        assertTrue(r.lines().get(0).contains("build height"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: FAIL — `fill` throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `fill`** — replace the `fill` stub with:

```java
    private Response fill(Request r) {
        if (!r.operator()) {
            return Response.fail(opError());
        }
        int xlo = Math.min(r.x1(), r.x2()), xhi = Math.max(r.x1(), r.x2());
        int ylo = Math.min(r.y1(), r.y2()), yhi = Math.max(r.y1(), r.y2());
        int zlo = Math.min(r.z1(), r.z2()), zhi = Math.max(r.z1(), r.z2());
        long cells = (long) (xhi - xlo + 1) * (yhi - ylo + 1) * (zhi - zlo + 1);
        if (cells > FILL_CELL_CAP) {
            return Response.fail(String.format("fill too large: %d cells (max %d)", cells, FILL_CELL_CAP));
        }
        if (ylo < r.minBuildY() || yhi >= r.maxBuildY()) {
            return Response.fail(yError(r));
        }
        int written = 0, skipped = 0;
        for (int x = xlo; x <= xhi; x++) {
            for (int y = ylo; y <= yhi; y++) {
                for (int z = zlo; z <= zhi; z++) {
                    CellAddress addr = CellAddress.of(x, y, z);
                    if (!writeSink.isLoaded(r.dimension(), addr.key())) {
                        skipped++;
                        continue;
                    }
                    writeSink.writeTemp(r.dimension(), addr.key(), addr.cell(), r.temperatureK());
                    if (r.massKg() != null) {
                        writeSink.writeMass(r.dimension(), addr.key(), addr.cell(), r.massKg());
                    }
                    written++;
                }
            }
        }
        if (written == 0) {
            return Response.fail("fill wrote 0 cells (none loaded); move closer");
        }
        String msg = String.format("filled %d cells in [(%d,%d,%d)..(%d,%d,%d)] -> %.2f K",
                written, xlo, ylo, zlo, xhi, yhi, zhi, r.temperatureK());
        if (skipped > 0) {
            msg += String.format(" (%d skipped: not loaded)", skipped);
        }
        return Response.ok(msg);
    }
```

- [ ] **Step 4: Run to verify pass** (also run the whole logic class)

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.OrgeCommandLogicTest"`
Expected: PASS (every op now covered).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommandLogic.java \
        core/src/test/java/net/rainbowcreation/orge/command/OrgeCommandLogicTest.java
git commit -m "feat(command): OrgeCommandLogic fill op (box, cap, skipped count)"
```

---

## Task 9: Live seams — `ServerStoreReadSource` + `ServerStoreWriteSink`

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/command/ServerStoreReadSource.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/command/ServerStoreWriteSink.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/command/ServerStoreSeamTest.java`

- [ ] **Step 1: Write the failing integration test** (drives a real `SectionStoreManager` over a `@TempDir`):

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.AmbientProvider;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ServerStoreSeamTest {

    static final Identifier DIM = Identifier.fromNamespaceAndPath("minecraft", "overworld");

    private SectionStoreManager managerWithLoadedColumn(Path dir) {
        SectionStoreManager mgr = new SectionStoreManager();
        mgr.onLevelLoad(DIM, dir, AmbientProvider.FALLBACK);
        mgr.onChunkLoad(DIM, 0, 0); // load column (0,0)
        return mgr;
    }

    @Test
    void readSourceReturnsAmbientBeforeWriteThenStoredAfter(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        SectionView before = src.section(DIM, key).orElseThrow();
        assertTrue(before.ambient(), "never-written section is ambient");
        assertEquals(285.0f, before.tempAt(0), 0.001f);

        sink.writeTemp(DIM, key, 5, 400f);

        SectionView after = src.section(DIM, key).orElseThrow();
        assertFalse(after.ambient(), "after write it is stored");
        assertEquals(400f, after.tempAt(5), 0.001f);
    }

    @Test
    void writeTempThenMassDoNotClobber(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        SubchunkKey key = new SubchunkKey(0, 4, 0);

        sink.writeTemp(DIM, key, 7, 350f);
        sink.writeMass(DIM, key, 7, 1000f);

        SectionView v = src.section(DIM, key).orElseThrow();
        assertEquals(350f, v.tempAt(7), 0.001f);
        assertEquals(1000f, v.massAt(7), 0.001f);
    }

    @Test
    void isLoadedReflectsColumnState(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreWriteSink sink = new ServerStoreWriteSink(mgr);
        assertTrue(sink.isLoaded(DIM, new SubchunkKey(0, 4, 0)));
        assertFalse(sink.isLoaded(DIM, new SubchunkKey(9, 4, 9)));
    }

    @Test
    void unknownDimensionYieldsEmptyRead(@TempDir Path dir) {
        SectionStoreManager mgr = managerWithLoadedColumn(dir);
        ServerStoreReadSource src = new ServerStoreReadSource(mgr);
        Optional<SectionView> v = src.section(
                Identifier.fromNamespaceAndPath("minecraft", "the_end"),
                new SubchunkKey(0, 4, 0));
        assertTrue(v.isEmpty(), "no store for dimension -> empty (let logic report it)");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.ServerStoreSeamTest"`
Expected: FAIL — `cannot find symbol: class ServerStoreReadSource`.

- [ ] **Step 3: Implement both seams**

`ServerStoreReadSource.java`:

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.Optional;

/**
 * The permanent last link in the read chain: the server's authoritative {@link SectionStore}.
 * Returns {@code empty} only when the dimension has no store; otherwise returns the stored
 * section, or the synthesized ambient baseline for a never-simulated section (flagged via
 * {@link SectionStore#hasSection}). Server-thread only.
 */
public final class ServerStoreReadSource implements ThermalReadSource {

    private final SectionStoreManager stores;

    public ServerStoreReadSource(SectionStoreManager stores) {
        this.stores = stores;
    }

    @Override
    public Optional<SectionView> section(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return Optional.empty();
        }
        SectionData data = store.get(key);
        boolean ambient = !store.hasSection(key);
        return Optional.of(new View(data, ambient));
    }

    private record View(SectionData data, boolean ambient) implements SectionView {
        @Override public float tempAt(int cell) { return data.temperatureAt(cell); }
        @Override public float massAt(int cell) { return data.massAt(cell); }
        @Override public SectionData.Form form() { return data.form(); }
        // ambient() is provided by the record component
    }
}
```

`ServerStoreWriteSink.java`:

```java
package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.section.SectionData;
import net.rainbowcreation.orge.section.SectionStore;
import net.rainbowcreation.orge.section.SectionStoreManager;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Server-authoritative writes into the live {@link SectionStore}. Each write reads the section
 * (materializing ambient if needed), sets the cell, and {@code put}s it back so the column is
 * marked dirty and the next scheduler snapshot picks the change up. Server-thread only.
 */
public final class ServerStoreWriteSink implements ThermalWriteSink {

    private final SectionStoreManager stores;

    public ServerStoreWriteSink(SectionStoreManager stores) {
        this.stores = stores;
    }

    @Override
    public boolean isLoaded(Identifier dimension, SubchunkKey key) {
        SectionStore store = stores.store(dimension);
        return store != null && store.isLoaded(key.cx(), key.cz());
    }

    @Override
    public void writeTemp(Identifier dimension, SubchunkKey key, int cell, float kelvin) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return;
        }
        SectionData data = store.get(key);
        data.setTemperature(cell, kelvin);
        store.put(key, data);
    }

    @Override
    public void writeMass(Identifier dimension, SubchunkKey key, int cell, float kg) {
        SectionStore store = stores.store(dimension);
        if (store == null) {
            return;
        }
        SectionData data = store.get(key);
        data.setMass(cell, kg);
        store.put(key, data);
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:test --tests "net.rainbowcreation.orge.command.ServerStoreSeamTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/ServerStoreReadSource.java \
        core/src/main/java/net/rainbowcreation/orge/command/ServerStoreWriteSink.java \
        core/src/test/java/net/rainbowcreation/orge/command/ServerStoreSeamTest.java
git commit -m "feat(command): live ServerStore read-source + write-sink over SectionStore"
```

---

## Task 10: Brigadier adapter `OrgeCommands` + wire into `Orge.init`

This is the only Minecraft-API-heavy class; keep it thin (it just parses args, builds a `Request`, calls `OrgeCommandLogic`, and prints `Response` lines). There is no `runClient` in the sandbox, so it is validated by a compile + manual smoke test, not a unit test.

> **Mapping verification (do this first):** this build uses a layered Mojang-based mapping where `ResourceLocation` is named `Identifier`. Before writing, confirm these member names compile in `core` (they are standard MC 1.21 names; adjust only if the build complains):
> - `CommandSourceStack`: `hasPermission(int)`, `getPosition()` (`Vec3`), `getLevel()` (`ServerLevel`), `sendSuccess(java.util.function.Supplier<Component>, boolean)`, `sendFailure(Component)`.
> - `ServerLevel`/`Level`: `getMinY()` (inclusive) and `getMaxY()` (inclusive top block Y). The `Request.maxBuildY` is **exclusive**, so pass `level.getMaxY() + 1`. If this mapping instead exposes `getMaxBuildHeight()` (exclusive) / `getMinBuildHeight()`, use those directly (min inclusive, max exclusive, no `+1`).
> - `BlockPosArgument.blockPos()` and `BlockPosArgument.getBlockPos(ctx, "name")`; `FloatArgumentType.floatArg()` / `floatArg(min)` / `getFloat(ctx, "name")`; `Commands.literal/argument`; `Component.literal(String)`.

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/command/OrgeCommands.java`
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java`

- [ ] **Step 1: Write `OrgeCommands.java`**

```java
package net.rainbowcreation.orge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.rainbowcreation.orge.section.SubchunkKey;

/**
 * Thin Brigadier adapter for {@code /orge}. Parses arguments, builds an
 * {@link OrgeCommandLogic.Request}, runs the pure logic, and prints the {@link OrgeCommandLogic.Response}
 * lines. Read subcommands (get/section) are open; write subcommands (set/fill) require op (level 2).
 * Register from {@code Orge.init} via Architectury's common {@code CommandRegistrationEvent}.
 */
public final class OrgeCommands {

    private final OrgeCommandLogic logic;

    public OrgeCommands(OrgeCommandLogic logic) {
        this.logic = logic;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("orge")
                .then(Commands.literal("get")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> read(ctx, OrgeCommandLogic.Op.GET))))
                .then(Commands.literal("section")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> read(ctx, OrgeCommandLogic.Op.SECTION))))
                .then(Commands.literal("set")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("k", FloatArgumentType.floatArg())
                                        .executes(ctx -> set(ctx, null))
                                        .then(Commands.argument("mass", FloatArgumentType.floatArg(0f))
                                                .executes(ctx -> set(ctx, FloatArgumentType.getFloat(ctx, "mass")))))))
                .then(Commands.literal("fill")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("k", FloatArgumentType.floatArg())
                                                .executes(ctx -> fill(ctx, null))
                                                .then(Commands.argument("mass", FloatArgumentType.floatArg(0f))
                                                        .executes(ctx -> fill(ctx, FloatArgumentType.getFloat(ctx, "mass")))))))));
    }

    private int read(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Op op) {
        BlockPos p = BlockPosArgument.getBlockPos(ctx, "pos");
        return dispatch(ctx, request(ctx, op, p, p, null, null));
    }

    private int set(CommandContext<CommandSourceStack> ctx, Float mass) {
        BlockPos p = BlockPosArgument.getBlockPos(ctx, "pos");
        float k = FloatArgumentType.getFloat(ctx, "k");
        return dispatch(ctx, request(ctx, OrgeCommandLogic.Op.SET, p, p, k, mass));
    }

    private int fill(CommandContext<CommandSourceStack> ctx, Float mass) {
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        float k = FloatArgumentType.getFloat(ctx, "k");
        return dispatch(ctx, request(ctx, OrgeCommandLogic.Op.FILL, from, to, k, mass));
    }

    private OrgeCommandLogic.Request request(CommandContext<CommandSourceStack> ctx,
                                             OrgeCommandLogic.Op op, BlockPos p1, BlockPos p2,
                                             Float k, Float mass) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Identifier dim = level.dimension().identifier();
        Vec3 sp = src.getPosition();
        SubchunkKey sourceSection = new SubchunkKey(
                ((int) Math.floor(sp.x)) >> 4,
                ((int) Math.floor(sp.y)) >> 4,
                ((int) Math.floor(sp.z)) >> 4);
        boolean operator = src.hasPermission(2);
        int minY = level.getMinY();
        int maxYExclusive = level.getMaxY() + 1; // see mapping note in Task 10
        return new OrgeCommandLogic.Request(op, dim,
                p1.getX(), p1.getY(), p1.getZ(),
                p2.getX(), p2.getY(), p2.getZ(),
                k, mass, operator, sourceSection, minY, maxYExclusive);
    }

    private int dispatch(CommandContext<CommandSourceStack> ctx, OrgeCommandLogic.Request req) {
        OrgeCommandLogic.Response resp = logic.run(req);
        CommandSourceStack src = ctx.getSource();
        for (String line : resp.lines()) {
            if (resp.ok()) {
                src.sendSuccess(() -> Component.literal(line), false);
            } else {
                src.sendFailure(Component.literal(line));
            }
        }
        return resp.ok() ? Command.SINGLE_SUCCESS : 0;
    }
}
```

- [ ] **Step 2: Wire into `Orge.init`** — in `core/src/main/java/net/rainbowcreation/orge/Orge.java`, add imports and a registration block. Add these imports near the other imports:

```java
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.rainbowcreation.orge.command.OrgeCommandLogic;
import net.rainbowcreation.orge.command.OrgeCommands;
import net.rainbowcreation.orge.command.ReadRangeProvider;
import net.rainbowcreation.orge.command.ServerStoreReadSource;
import net.rainbowcreation.orge.command.ServerStoreWriteSink;
import net.rainbowcreation.orge.scheduler.Scheduler;
import java.util.List;
```

Then, immediately after the `TickEvent.SERVER_POST.register(server -> scheduler.onServerTick());` line (the last line of the init body), add:

```java
        // DESIGN observability track (Topic A): /orge get|section|set|fill. Reads walk a
        // source chain (client cache -> server fallback; v1 = server only); writes are
        // server-authoritative and op-gated. One common Architectury event covers both loaders.
        OrgeCommandLogic commandLogic = new OrgeCommandLogic(
                List.of(new ServerStoreReadSource(SECTION_STORES)),
                new ServerStoreWriteSink(SECTION_STORES),
                (ReadRangeProvider) () -> Scheduler.MAX_RANGE);
        OrgeCommands orgeCommands = new OrgeCommands(commandLogic);
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
                orgeCommands.register(dispatcher));
```

> **Note:** `SECTION_STORES` is the existing `static final SectionStoreManager` field referenced elsewhere in `Orge.init`. The `(ReadRangeProvider)` cast pins the lambda's target type. If `List` is already imported, drop the duplicate import.

- [ ] **Step 3: Compile both loaders**

Run: `./gradlew :core:compileJava :fabric-1.21:compileJava :neoforge-1.21:compileJava`
Expected: BUILD SUCCESSFUL. If a method name from the mapping note fails to resolve, fix per the Task 10 verification note, then recompile.

- [ ] **Step 4: Full test suite (no regressions)**

Run: `./gradlew :core:test`
Expected: PASS — all command tests plus the pre-existing suite.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/rainbowcreation/orge/command/OrgeCommands.java \
        core/src/main/java/net/rainbowcreation/orge/Orge.java
git commit -m "feat(command): /orge Brigadier adapter wired via CommandRegistrationEvent"
```

---

## Task 11: Manual smoke test + push

- [ ] **Step 1: Build the full project**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (both loader jars produced).

- [ ] **Step 2: Manual in-game smoke (document results, do not block on sandbox limitations)**

If a dev client/server is available (outside the headless sandbox), verify:
1. `/orge get ~ ~ ~` as op → prints a temperature line (ambient ~285 K on a fresh area).
2. `/orge set ~ ~ ~ 1400` → echoes the write; a follow-up `/orge get ~ ~ ~` shows the value diffusing downward over the next seconds.
3. `/orge section ~ ~ ~` → prints form + min/avg/max.
4. As a non-op (`/deop` a test player), `get` a nearby block works; `get` a far block prints "out of range"; `set` prints "requires operator".

If no client is available, record that the adapter is compile-verified and the behavior is covered by `OrgeCommandLogicTest` + `ServerStoreSeamTest`, and that in-game verification is pending a dev environment.

- [ ] **Step 3: Push the branch**

```bash
git push origin rebuild
```

---

## Self-review (completed by plan author)

**Spec coverage:**
- Command surface get/section/set/fill — Tasks 5-8 (logic), Task 10 (Brigadier surface). ✓
- Mass read+write, omitted-mass-unchanged — Tasks 7, 8 (`massKg` nullable). ✓
- Access: set/fill op-only (Brigadier `requires` + logic re-check) — Task 10 `requires`, Task 7/8 `operator` check. ✓
- Proximity-gated non-op reads via shared sphere test; R = `Scheduler.MAX_RANGE` via `ReadRangeProvider` — Tasks 1, 4, 5, 10. ✓
- Read-source chain (client cache → server) / write-sink split — Tasks 4, 5 (chain + ordering tests), 9 (live), 10 (wiring uses server-only chain). ✓
- Error handling: no store, not-loaded, Y out of build height, fill cap, ambient annotation — Tasks 5-9. ✓
- `SphereUnion.contains` shared helper; `SectionStore.hasSection` — Tasks 1, 2. ✓
- Headless TDD, commit-per-task, push origin/rebuild — every task + Task 11. ✓
- Forward-compat notes (client cache transport deferred) — encoded as the chain seam; no code in v1. ✓

**Placeholder scan:** No TBD/TODO; the `section`/`set`/`fill` stubs in Task 5 throw and are replaced with full code in Tasks 6/7/8 (intentional, each shown in full). ✓

**Type consistency:** `Request`/`Response`/`Op` defined in Task 5 and reused verbatim in Tasks 6-10; `SectionView`/`ThermalReadSource`/`ThermalWriteSink`/`ReadRangeProvider` defined in Task 4 and used consistently; `CellAddress.of(...)`, `SphereUnion.contains(...)`, `SectionStore.hasSection(...)`, `SectionStoreManager.onChunkLoad/store(...)` match their defining tasks. ✓
