> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.

# Durable per-cell Material + first-touch rule + unified place/break Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make per-cell ORGE material identity a durably-persisted quantity (like temp/mass), reduce vanilla-block→material to a first-touch-only rule, tear out the binding subsystem, then fix placement (solids inject+seed) and breaking (→ durable vacuum).

**Architecture:** The engine cell — material id + mass + temperature — is the source of truth; the vanilla block is a render proxy. Material id joins temp/mass in `SectionData`/region files (palette + index array). The block→`orge:<path>` name-match runs only for never-stored cells and explicit placements. Breaking sets durable `orge:vacuum`; placing routes any species (solid included) through the existing species-agnostic engine injection.

**Tech Stack:** Java 21, Architectury multiloader (NeoForge + Fabric, MC 1.21.11), Gradle (`JAVA_HOME=/home/claude/jdk21`), JUnit, native `liborge.so` via JNI (unchanged here). Spec: `docs/superpowers/specs/2026-06-03-block-material-unified-place-break-design.md`.

**Standing rules:** Push `origin/rebuild` after every commit (user tests live from it). `MaterialThreeMassJsonTest` may fail from the user's LOCAL water/lava json edits — do NOT alter it. No engine/.so/gitlink change. Gates per phase: `./gradlew :core:test`, `:core:integrationTest`, `./gradlew build`.

---

## File Structure

**New:**
- `core/src/main/java/net/rainbowcreation/orge/material/BlockMaterialRule.java` — the first-touch `minecraft:<path> → orge:<path>` else `generic_solid` rule.
- `core/src/test/java/net/rainbowcreation/orge/material/BlockMaterialRuleTest.java`
- `core/src/test/java/net/rainbowcreation/orge/section/SectionMaterialTest.java`
- `core/src/test/java/net/rainbowcreation/orge/section/SectionCodecMaterialTest.java`

**Modified (major):**
- `SectionData.java` — third per-cell layer: material palette + `char[4096]` index.
- `SectionCodec.java` — FORMAT_VERSION 1→2; serialize palette+index; read v1 (material-unknown) and v2.
- `ColumnAssembler.java` — read STORED material; first-touch only when unstored; drop `movable()` seed gate.
- `LiveMaterials.java` — replace binding `materialFor` with `BlockMaterialRule`.
- `PlacementInjectionPolicy.java` — drop movable gates.
- `InjectionDrain.java` / `PendingInjections.java` — `removal` intent variant → vacuum stomp.
- `MinecraftThermalWorld.java` — stored-material authority; PLACE/BREAK record identity; drain wiring.
- `Orge.java` — BREAK capture; remove bindings load.
- `ActiveMaterials.java`, `MaterialData.java`, `MaterialJsonLoader.java` — strip bindings.
- `MaterialLut.java` + comment sites — `VOID`→`VACUUM` rename.

**Deleted:** `MaterialBindings.java`, `BlockStatePredicate.java`, `PropertyView.java`, `data/orge/orge/bindings/default.json`, 9 material JSONs (5B), and binding tests.

---

## Phase A — First-touch rule + bindings teardown (spec Parts 1, 5A)

### Task A1: `BlockMaterialRule` — the first-touch name-match

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/material/BlockMaterialRule.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/material/BlockMaterialRuleTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockMaterialRuleTest {
    private static Identifier id(String ns, String p) { return Identifier.fromNamespaceAndPath(ns, p); }

    private MaterialRegistry registryWith(String... paths) {
        MaterialRegistry r = new MaterialRegistry();
        for (String p : paths) {
            r.put(Material.builder(id("orge", p))
                    .thermalConductivity(1f).heatCapacity(1f).molarMass(1f)
                    .defaultMass(1f).defaultTemperature(290f).build());
        }
        return r;
    }

    @Test void mapsMatchingPathToOrge() {
        MaterialRegistry r = registryWith("stone", "water", "air", "generic_solid");
        assertEquals(id("orge", "stone"), BlockMaterialRule.firstTouch(id("minecraft", "stone"), r));
        assertEquals(id("orge", "water"), BlockMaterialRule.firstTouch(id("minecraft", "water"), r));
        assertEquals(id("orge", "air"),   BlockMaterialRule.firstTouch(id("minecraft", "air"),   r));
    }

    @Test void missFallsBackToGenericSolid() {
        MaterialRegistry r = registryWith("stone", "generic_solid");
        assertEquals(id("orge", "generic_solid"),
                BlockMaterialRule.firstTouch(id("minecraft", "diamond_ore"), r));
    }

    @Test void droppsNamespaceForModdedBlocks() {
        MaterialRegistry r = registryWith("copper", "generic_solid");
        assertEquals(id("orge", "copper"),
                BlockMaterialRule.firstTouch(id("somemod", "copper"), r));
    }
}
```

- [ ] **Step 2: Run test, verify it fails** — `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test --tests '*BlockMaterialRuleTest*'`. Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.orge.material;

import net.minecraft.resources.Identifier;
import net.rainbowcreation.orge.Orge;

/**
 * The block → material FIRST-TOUCH rule (spec Part 1). Maps a vanilla (or modded) block id to
 * {@code orge:<path>} if such a material is registered, else the global fallback
 * {@code orge:generic_solid}. Used ONLY for a cell with no stored material (freshly generated /
 * never simulated) or an explicit player PLACE — never to re-derive identity for a stored cell.
 */
public final class BlockMaterialRule {
    private BlockMaterialRule() {}

    public static Identifier firstTouch(Identifier blockId, MaterialRegistry registry) {
        Identifier candidate = Identifier.fromNamespaceAndPath(Orge.MOD_ID, blockId.getPath());
        return registry.get(candidate).isPresent() ? candidate : MaterialRegistry.FALLBACK_ID;
    }

    /** Convenience: resolve straight to the {@link Material} (fallback guaranteed registered). */
    public static Material firstTouchMaterial(Identifier blockId, MaterialRegistry registry) {
        return registry.getOrFallback(firstTouch(blockId, registry));
    }
}
```

- [ ] **Step 4: Run test, verify PASS.**

- [ ] **Step 5: Commit + push**

```bash
git add core/src/main/java/net/rainbowcreation/orge/material/BlockMaterialRule.java core/src/test/java/net/rainbowcreation/orge/material/BlockMaterialRuleTest.java
git commit -m "feat(material): first-touch block->orge name-match rule (replaces bindings lookup)"
git push origin rebuild
```

### Task A2: Repoint `LiveMaterials` to the rule

**Files:** Modify `core/src/main/java/net/rainbowcreation/orge/scheduler/LiveMaterials.java`

- [ ] **Step 1: Update the materialFor test** — in `core/src/test/java/.../scheduler/LiveMaterialsTest.java` (if present) or add one asserting `materialFor(blockId, registry)` returns the first-touch result and ignores blockstate. (If no test file exists, create `LiveMaterialsRuleTest` mirroring A1 but through `LiveMaterials.materialFor(Block, MaterialRegistry)`.)

- [ ] **Step 2: Run, verify fail** (signature change).

- [ ] **Step 3: Implement.** Replace both `materialFor(...)` overloads with a single registry-based one; delete `LIVE_TAGS`, `propertyValue`, `nameOf`, and the `MaterialBindings`/`PropertyView` imports:

```java
/** The {@link Material} a block FIRST-TOUCHES to (spec Part 1). Blockstate is irrelevant. */
public static Material materialFor(Block block, MaterialRegistry registry) {
    Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
    return BlockMaterialRule.firstTouchMaterial(blockId, registry);
}
```

Update the two callers in `MinecraftThermalWorld` (capture) and `MinecraftPhaseChanger` to pass `mats.registry()` instead of the whole `ActiveMaterials.State`. (They currently call `mats.bindings()` indirectly via `materialFor(BlockState, mats)`; change to `materialFor(state.getBlock(), mats.registry())`.)

- [ ] **Step 4: Run `:core:test`** for the touched scheduler tests. Expected PASS.

- [ ] **Step 5: Commit + push** — `refactor(material): LiveMaterials uses first-touch rule, drop tag/blockstate lookup`.

### Task A3: Delete the binding subsystem

**Files:** Delete `MaterialBindings.java`, `BlockStatePredicate.java`, `PropertyView.java`, `data/orge/orge/bindings/default.json`; modify `MaterialData.java`, `ActiveMaterials.java`, `MaterialJsonLoader.java`, `Orge.java`.

- [ ] **Step 1: Delete the binding tests first** so the suite compiles after the source deletes: remove `MaterialBindingsTest`, `HotSourceBindingsTest`, `ColdSourceBindingsTest`; trim the bindings cases from `MaterialDataTest`, `ActiveMaterialsTest`, `DefaultPhaseDataTest`.

```bash
git rm core/src/test/java/net/rainbowcreation/orge/material/MaterialBindingsTest.java \
       core/src/test/java/net/rainbowcreation/orge/material/HotSourceBindingsTest.java \
       core/src/test/java/net/rainbowcreation/orge/material/ColdSourceBindingsTest.java
```

- [ ] **Step 2: Strip `MaterialData.loadBindings`** (delete the method + `JsonArray`/`BlockStatePredicate` imports).

- [ ] **Step 3: Strip bindings from `ActiveMaterials`** — remove the `bindings` field, the `bindings()` accessors, and the `List<JsonElement> bindings` parameter from `State`, `buildState`, `reloadFrom`; the initial empty state becomes `new State(new MaterialRegistry())`. Update `State` to hold only the registry.

- [ ] **Step 4: Strip bindings from `MaterialJsonLoader`** — delete `BINDINGS`, `readBindings`; `prepare` becomes `ActiveMaterials.buildState(readMaterials(rm))`.

- [ ] **Step 5: Delete the source files + datapack:**

```bash
git rm core/src/main/java/net/rainbowcreation/orge/material/MaterialBindings.java \
       core/src/main/java/net/rainbowcreation/orge/material/BlockStatePredicate.java \
       core/src/main/java/net/rainbowcreation/orge/material/PropertyView.java \
       core/src/main/resources/data/orge/orge/bindings/default.json
```

- [ ] **Step 6: Build both loaders** — `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test build`. Fix any remaining references (grep `bindings`, `MaterialBindings`, `BlockStatePredicate`, `PropertyView`). Expected: GREEN (heat sources now regress to first-touch — accepted).

- [ ] **Step 7: Commit + push** — `refactor(material): delete binding subsystem; block->material is first-touch only`.

---

## Phase B — Material set buckets (spec Part 5B)

### Task B1: Delete blockstate-gated heat-source materials

**Files:** Delete 9 material JSONs.

- [ ] **Step 1:** 

```bash
cd core/src/main/resources/data/orge/orge/materials
git rm campfire.json candle.json copper_bulb.json redstone_lamp.json \
       redstone_torch.json soul_campfire.json lightning_rod.json \
       furnace_lit.json powered_redstone.json
```

- [ ] **Step 2:** Grep these ids across `core/src` (esp. phase-change defaults + any test fixtures) and remove dangling references. Run `:core:test`. Expected GREEN.

- [ ] **Step 3: Commit + push** — `feat(material): drop blockstate-gated heat sources (option A regression)`.

### Task B2: Rename unconditional emitters to match block path

**Files:** Rename `magma.json`→`magma_block.json`, `portal.json`→`nether_portal.json`; update internal `id` references if any test asserts them.

- [ ] **Step 1:**

```bash
git mv core/src/main/resources/data/orge/orge/materials/magma.json  core/src/main/resources/data/orge/orge/materials/magma_block.json
git mv core/src/main/resources/data/orge/orge/materials/portal.json core/src/main/resources/data/orge/orge/materials/nether_portal.json
```

- [ ] **Step 2:** Grep `orge:magma"`, `orge:portal"` across `core/src` (phase targets, tests) and update to `orge:magma_block` / `orge:nether_portal`. Run `:core:test`. Expected GREEN.

- [ ] **Step 3: Commit + push** — `feat(material): rename magma->magma_block, portal->nether_portal for first-touch hit`.

---

## Phase C — Rename `orge:void` → `orge:vacuum` (spec Part 6)

### Task C1: Rename the sentinel

**Files:** Modify `MaterialLut.java`; update comment/string sites in `MaterialChangeReseed.java`, `StepValidator.java`, `ColumnAssembler.java`, `MinecraftThermalWorld.java`.

- [ ] **Step 1: Update/extend `MaterialLutTest`** to assert `MaterialLut.VACUUM.id()` equals `orge:vacuum` and index 0 is the vacuum sentinel.

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement** — rename `VOID`→`VACUUM`, the `Identifier.fromNamespaceAndPath("orge","void")`→`"vacuum"`, and the constructor at `MaterialLut.java:42-45`. Replace `VOID` identifiers in callers (`MaterialChangeReseed.java:34`, etc.) with `VACUUM`. Update doc comments that say "void/edge" to "vacuum" where they mean index-0; leave the word "void" only where it means an unloaded/absent cell.

- [ ] **Step 4: Run `:core:test` + `:core:integrationTest`.** Expected GREEN (behavior byte-identical).

- [ ] **Step 5: Commit + push** — `refactor: rename index-0 sentinel orge:void -> orge:vacuum (it is flow-accepting)`.

---

## Phase D — Durable per-cell material store (spec Part 0, the keystone)

### Task D1: `SectionData` material layer

**Files:** Modify `core/src/main/java/net/rainbowcreation/orge/section/SectionData.java`; Test `core/src/test/java/net/rainbowcreation/orge/section/SectionMaterialTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionMaterialTest {
    private static Identifier id(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    @Test void uniformSectionHasNoMaterialLayerUntilSet() {
        SectionData s = SectionData.uniform(290f, 1000f);
        assertFalse(s.hasMaterials());
    }

    @Test void settingACellPromotesAndStoresMaterial() {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(5, id("water"));
        assertTrue(s.hasMaterials());
        assertEquals(id("water"), s.materialAt(5));
        // unset cells read the vacuum sentinel until written
        assertEquals(MaterialPalette.VACUUM_ID, s.materialAt(6));
    }

    @Test void paletteDedupesRepeatedMaterials() {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(0, id("stone"));
        s.setMaterialAt(1, id("stone"));
        s.setMaterialAt(2, id("water"));
        assertEquals(id("stone"), s.materialAt(0));
        assertEquals(id("stone"), s.materialAt(1));
        assertEquals(id("water"), s.materialAt(2));
        assertEquals(3, s.palette().size()); // vacuum(0) + stone + water
    }
}
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** Add a `MaterialPalette` helper (`core/.../section/MaterialPalette.java`) holding `List<Identifier>` (index 0 reserved for `orge:vacuum` = `VACUUM_ID`) + `char[4096]` indices, with `indexOf(id)` appending on first sight. Add to `SectionData`: `private MaterialPalette materials;` (null until first material write), `boolean hasMaterials()`, `Identifier materialAt(int i)` (returns `VACUUM_ID` when `materials==null` or the cell index is 0), `void setMaterialAt(int i, Identifier id)` (lazily allocates the palette, also promotes temp/mass to FULL so the three layers stay aligned), `List<Identifier> palette()`, and array accessors for the codec. Keep temp/mass untouched.

> NOTE: the SectionData **on-disk** `MaterialPalette` legitimately stays first-seen (it is `Identifier`-keyed
> and stable); only the **engine-bound** `matIx` encoding became global. Do not conflate the two.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Commit + push** — `feat(section): per-cell material palette layer in SectionData`.

### Task D2: Codec v2 — serialize palette + index, read v1/v2

**Files:** Modify `SectionCodec.java`; Test `SectionCodecMaterialTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.orge.section;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.NavigableMap;
import static org.junit.jupiter.api.Assertions.*;

class SectionCodecMaterialTest {
    private static Identifier id(String p) { return Identifier.fromNamespaceAndPath("orge", p); }

    @Test void roundTripsMaterialLayer() throws Exception {
        SectionData s = SectionData.uniform(290f, 1000f);
        s.setMaterialAt(0, id("stone"));
        s.setMaterialAt(42, id("water"));
        byte[] blob = SectionCodec.writeColumn(Map.of(3, s));
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(blob);
        SectionData r = back.get(3);
        assertTrue(r.hasMaterials());
        assertEquals(id("stone"), r.materialAt(0));
        assertEquals(id("water"), r.materialAt(42));
        assertEquals(MaterialPalette.VACUUM_ID, r.materialAt(1));
    }

    @Test void readsLegacyV1AsMaterialUnknown() throws Exception {
        // Hand-build a v1 blob (version byte 1, one UNIFORM section, no material layer).
        byte[] v1 = LegacyV1.uniformColumn(3, 290f, 1000f); // test helper writing the OLD format
        NavigableMap<Integer, SectionData> back = SectionCodec.readColumn(v1);
        assertFalse(back.get(3).hasMaterials()); // material-unknown: reconstruct from block later
    }
}
```

(Provide `LegacyV1.uniformColumn` as a tiny test helper that writes `version=1, count=1, sectionY, form=UNIFORM, tFloat, mFloat` — the exact pre-change bytes.)

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** Bump `FORMAT_VERSION = 2`. In `writeSection`, after the temp/mass payload, append a material block: `byte hasMaterials` (0/1); if 1, `short paletteCount`, then each id as `writeUTF`, then the deflated `char[4096]` index array (`charsToBytes`). In `readSection`, read the material block when present. In `readColumn`, accept `version == 1` (read sections in the OLD layout — no material block — yielding `hasMaterials()==false`) and `version == 2`. Keep v1 read path so existing saves load.

- [ ] **Step 4: Run, verify PASS** (both round-trip and legacy).

- [ ] **Step 5: Commit + push** — `feat(section): SectionCodec v2 serializes material palette; reads legacy v1`.

### Task D3: `SectionStore.materialAt` / `setMaterialAt` authority

**Files:** Modify `SectionStore.java` (+ `SectionStoreManager` passthrough if needed); Test in `SectionStoreTest`.

- [ ] **Step 1: Write the failing test** asserting `store.setMaterialAt(cx,cz,sectionY,cell,id)` then `store.materialAt(...)` returns it, survives `saveColumn`+`loadColumn` round-trip via the manager, and a never-written cell reads `VACUUM_ID`.

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement** thin delegations to `SectionData.materialAt/setMaterialAt`, materializing the section on demand exactly like the temp/mass accessors. Ensure `dirty` marking on `setMaterialAt`.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Commit + push** — `feat(section): SectionStore is the per-cell material authority`.

---

## Phase E — Assembler reads stored material + retire trackers (spec Part 2)

### Task E1: `ColumnAssembler` reads stored material, first-touch fallback

**Files:** Modify `ColumnAssembler.java` + the `SectionSource`/`columnSource` in `MinecraftThermalWorld.java`; Test `ColumnAssemblerTest`.

- [ ] **Step 1: Write the failing test.** Extend `ColumnAssemblerTest` with a `SectionSource` whose `SectionCells` carries a stored-material array: assert a cell with a stored material id resolves to that material's LUT index (NOT the block's first-touch), and a cell with `VACUUM_ID`/unstored resolves via first-touch of the supplied block id.

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** Extend `SectionCells` with a `Identifier[] storedMaterial` (length 4096, `VACUUM_ID`/null = unstored). In `assemble`, per cell: `Identifier sid = cells.storedMaterial()[si]; char mat = (sid != null && stored) ? lut.indexOf(registry.get(sid)) : firstTouchIndex(block, lut, registry)`. Drop the block→matIx path; the block id now only feeds first-touch for unstored cells. Update `columnSource` in `MinecraftThermalWorld` to fill `storedMaterial` from `SectionStore.materialAt`.

- [ ] **Step 4: Drop the `m.movable()` seed gate** in the same edit (spec Part 3 prep): `ColumnAssembler.java:66` becomes `if (storedMass <= 0f && prior != mat)`. Update the test to assert a fresh STONE cell seeds `defaultMass` (was excluded before).

- [ ] **Step 5: Run `:core:test` + `:core:integrationTest`.** Expected PASS.

- [ ] **Step 6: Commit + push** — `feat(assembler): read stored material (authoritative); first-touch only when unstored; seed solids`.

### Task E2: Retire `CellMaterialTracker` + `MaterialChangeReseed`

**Files:** Modify `MinecraftThermalWorld.java`; delete or reduce `CellMaterialTracker.java`, `MaterialChangeReseed.java` + their tests.

- [ ] **Step 1:** Identify what the write-back still needs (the engine-OUTPUT species recorder feeding `recordedIncumbentId`/reconciler). Keep ONLY that recorder; the block-vs-stored *diff* (`MaterialChangeReseed`) is obsolete because identity is durable and changes arrive as events. Write a test asserting: after a snapshot→writeback cycle, `SectionStore.materialAt` reflects the engine-output species (the recorder still works) and no reseed-from-block-diff occurs.

- [ ] **Step 2: Run, verify fail / compile error** as you remove the reseed call.

- [ ] **Step 3: Implement** — remove the `MaterialChangeReseed.apply(...)` call from `snapshotColumns`; route engine-output recording into `SectionStore.setMaterialAt` at write-back; delete `MaterialChangeReseed.java` + `MaterialChangeReseedTest` if nothing else references them; reduce `CellMaterialTracker` to the in-memory prior recorder only if still needed by `recordedIncumbentId`, else delete it and source incumbent identity from `SectionStore.materialAt`.

- [ ] **Step 4: Run `:core:test` + `:core:integrationTest` + `build`.** Expected GREEN.

- [ ] **Step 5: Commit + push** — `refactor: retire MaterialChangeReseed/CellMaterialTracker; SectionStore records engine-output identity`.

---

## Phase F — Unified PLACE (spec Part 3, bugs 2+3)

### Task F1: Drop the movable gates in `PlacementInjectionPolicy`

**Files:** Modify `PlacementInjectionPolicy.java`; Test `PlacementInjectionPolicyTest`.

- [ ] **Step 1: Write the failing test** — assert a SOLID `live` over a fluid incumbent returns `true` (was false); a solid over `null` incumbent returns `true`; `live==incumbent` (same id) returns `false`; differing ids return `true`.

```java
@Test void solidOverFluidIsDisplacement() {
    Material stone = solid("orge:stone"); Material water = fluid("orge:water");
    assertTrue(PlacementInjectionPolicy.isDisplacement(stone, water));
}
@Test void sameSpeciesIsNotDisplacement() {
    Material water = fluid("orge:water");
    assertFalse(PlacementInjectionPolicy.isDisplacement(water, water));
}
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.**

```java
public static boolean isDisplacement(Material live, Material incumbent) {
    if (live == null) return false;
    if (incumbent == null) return true;            // untracked cell — enqueue for durability
    return !live.id().equals(incumbent.id());      // different species → displace-and-inject
}
```

Update the class Javadoc (drop the "movable" language). Update `logCapture` in `MinecraftThermalWorld` so its SKIP-reason branches no longer reference `incumbent.movable()`.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Commit + push** — `fix(place): drop movable() gates — any placed species injects+displaces (bug 2)`.

### Task F2: PLACE records stored identity + integration

**Files:** Modify `MinecraftThermalWorld.captureBlockChange` (place path) + `Orge.java` PLACE handler.

- [ ] **Step 1: Write/extend the integration test** (`PlacementInjectionPipelineIT`) — place a solid over water: assert the cell's stored material becomes the solid, the water incumbent is displaced (engine injection emitted), the solid seeds `defaultMass`, and `conserved()` holds.

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** On the PLACE path, after enqueuing the injection, `SectionStore.setMaterialAt(...)` = `firstTouch(placedBlock)` so the durable identity matches the placed block immediately (capture already computes `live`). Confirm bug-3 `defaultMass` flows through the engine ledger (`injected[solid]`); no new ledger code expected (verify).

- [ ] **Step 4: Run `:core:integrationTest`.** Expected PASS (`bug 2` push + `bug 3` mass both green on the real `.so`).

- [ ] **Step 5: Commit + push** — `fix(place): solid placement seeds defaultMass + records durable identity (bug 3)`.

---

## Phase G — BREAK → vacuum (spec Part 4)

### Task G1: `removal` intent + drain branch

**Files:** Modify `PendingInjections.java` (Intent gains `boolean removal`, plus an `enqueueRemoval` helper), `InjectionDrain.java`; Test `InjectionDrainTest`.

- [ ] **Step 1: Write the failing test** — a removal intent for cell N: after `applyToColumn`, assert `matIx[N]==0 && mass[N]==0f`, NO `EngineInjection` is emitted for it, and the intent is added to `emittedOut` (clear-on-success). A normal injection intent still behaves as before (regression guard).

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** Add `boolean removal` to `Intent` (default false; add `enqueueRemoval(dim,cx,cz,cell)` storing species=`VACUUM_ID`, mass 0, `removal=true`). In `applyToColumn`, branch first on `in.removal()`: set `matIx[cell]=0; mass[cell]=0f; emittedOut.add(in); continue;` — BEFORE the `resolver.indexOf(...)==0` skip (so a vacuum removal is never swallowed). Leave the injection path unchanged.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Commit + push** — `feat(break): removal intent stomps cell to vacuum in the drain`.

### Task G2: BREAK capture + durable vacuum + integration

**Files:** Modify `Orge.java` BREAK handler, `MinecraftThermalWorld` (a `captureBreak` method); Test the break IT.

- [ ] **Step 1: Write the integration test** — break a stone cell adjacent to water: assert the cell's stored material becomes `orge:vacuum`, the engine steps it as vacuum (matIx 0), water flows in over subsequent steps, and `conserved()` holds across the break step (no `sealedLoss` needed). Add a persistence assertion: after a snapshot→writeback, `SectionStore.materialAt(brokenCell)` stays `vacuum` (NOT re-seeded to air) across a second cycle with no neighbour inflow.

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Implement.** In `Orge.java`'s `BlockEvent.BREAK` handler, call a new `thermalWorld.captureBreak(dim, x, y, z)` (distinct from the place/wake path) — it enqueues a removal intent via `pendingInjections.enqueueRemoval(...)` AND `SectionStore.setMaterialAt(cell, VACUUM_ID)` so identity is durable from the next assemble. Keep the existing `wake.wakeBlock(...)` call. Do NOT read the post-break block.

- [ ] **Step 4: Run `:core:integrationTest`.** Expected PASS (vacuum durable; conservation holds). If conservation fails, add the `sealedLoss[incumbent]` declaration per spec Part 4 and re-run — only then.

- [ ] **Step 5: Commit + push** — `feat(break): event-driven break -> durable vacuum, neighbours flow in (no air re-seed)`.

---

## Phase H — Docs + diagnostics

### Task H1: Update `DESIGN.md`

- [ ] **Step 1:** §6 "Registration API" — replace the tag-bindings/overrides bullet with the first-touch `minecraft:<path>→orge:<path>` else `generic_solid` rule; note the durable per-cell material store. §5 — add the per-cell material field beside temperature + mass; note the codec v2 + back-compat. §7 — confirm `representative_block` render path unchanged.

- [ ] **Step 2: Commit + push** — `docs: DESIGN §5/§6 — durable material + first-touch rule supersede bindings`.

### Task H2: Strip stale inject diagnostics (optional, after in-game confirm)

- [ ] **Step 1:** Per the prior handoff, once the in-game audit confirms place+break, strip the `[fabric-chunkset]`/`[common-event]` per-side probes (keep the wake calls) and decide whether `InjectDebug` defaults OFF. Leave ON until the user confirms in-game.

- [ ] **Step 2: Commit + push** — `chore: strip wake/inject diagnostics after in-game confirm`.

---

## Final gate (before declaring done)

- [ ] `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` — green.
- [ ] `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:integrationTest` — green on the real `.so`.
- [ ] `JAVA_HOME=/home/claude/jdk21 ./gradlew build` — both loaders.
- [ ] `git push origin rebuild` up to date.
- [ ] **In-game audit (the project's standing final gate):** place solid→fluid (pushes, no delete), place solid (full mass), break block (→ vacuum, fluids flow in, no fabricated air), reload a pre-change save (self-heals via first-touch).

## Self-review notes (author)
- Spec coverage: Part 0→D, Part 1→A1/A2, Part 2→E1/E2, Part 3→F1/F2, Part 4→G1/G2, Part 5A→A3, Part 5B→B1/B2, Part 6→C1. All parts have tasks.
- Type consistency: `VACUUM_ID` (on `MaterialPalette`) and `MaterialLut.VACUUM` are distinct (palette uses an `Identifier` sentinel; LUT uses index 0 + the `Material`); both name `orge:vacuum`. `firstTouch` returns `Identifier`, `firstTouchMaterial` returns `Material`.
- Ordering: Phase A regresses heat sources before D restores durability — acceptable (heat sources are deleted in B regardless). C (rename) precedes E (which deletes `MaterialChangeReseed`) so the rename's comment edits don't touch a deleted file twice — if E deletes the file, drop its rename edit there.
