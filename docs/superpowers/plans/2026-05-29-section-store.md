# Section Store (DESIGN.md §5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the per-cell thermal-metadata store — the in-memory section model, its compressed on-disk region format under `world/orge/`, and the chunk load/unload hooks that persist it alongside vanilla chunks without ever touching the `.mca` files.

**Architecture:** A section holds two parallel `float[4096]` arrays (temperature K, mass kg), serialized as `UNIFORM` (one T + one mass) or `FULL` (deflate of both arrays). Sections persist per **chunk column** (the unit chunks load/unload in) into vanilla-style 4 KiB-sector region files `r.<x>.<z>.orge`. An in-memory `SectionStore` is the live authority; never-loaded sections materialize on demand as `UNIFORM(biome-ambient T, material defaultMass)`. Loader-specific chunk hooks live behind a seam, mirroring how §6 isolated its tag/loader glue.

**Tech Stack:** Java 21, Minecraft 1.21.11 (Mojang mappings), Architectury multiloader, `java.util.zip.Deflater/Inflater` (deflate — no zstd dependency), `java.nio` (ByteBuffer / RandomAccessFile), JUnit 5.

---

## Ground rules for every task

- **Repo:** `/home/claude/ORGE`, branch `rebuild`. Work from that directory. Mojang mappings, MC 1.21.11, Java 21. **Commit per task; do NOT push.**
- **Build/test command (no `java` on PATH — use this EXACT env):**
  ```
  JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH \
    GRADLE_USER_HOME=/home/claude/.gradle ./gradlew <tasks>
  ```
  - Unit tests (fast, deps cached): `:core:test`. Full both-loader build (slow, decompiles MC): `build`.
  - If `/home/claude/jdk21` is gone: `curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar -xz` and point `JAVA_HOME` at the extracted dir.
- **1.21.11 gotcha:** the namespaced-id class is `net.minecraft.resources.Identifier` (**NOT** `ResourceLocation`); factory `Identifier.fromNamespaceAndPath(ns, path)`, `Identifier.parse("ns:path")`. NeoForge 21.11 uses `FMLEnvironment.getDist()` (method).
- **`:core` purity:** `:core` must NOT import `net.fabricmc.*` or `net.neoforged.*` — vanilla MC + Architectury only. All pure logic (Tasks 1–5) must be unit-testable in `:core` with no loader/world access. Loader glue (Task 6) goes behind an `@ExpectPlatform` seam or an Architectury event, exactly as §6 did.
- **Binary discipline:** all multi-byte values are **big-endian**. Use `DataOutputStream`/`DataInputStream` over `ByteArrayOutputStream`/`ByteArrayInputStream` for the codec, and `ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN)` for bulk float↔byte conversion. Compression is `java.util.zip.Deflater` with `Deflater.DEFAULT_COMPRESSION`; decompression `Inflater`. (DESIGN says "deflate/zstd"; deflate keeps `:core` dependency-free — zstd is a possible later swap behind the same codec.)
- **TDD:** write the failing test FIRST, run it, watch it fail for the right reason, then implement the minimal code to pass. Tests live in `core/src/test/java/net/rainbowcreation/orge/section/`. File-based tests use JUnit's `@TempDir Path` — never write into the repo. Run `:core:test` green before committing.
- **Scope discipline:** build only what the task says. No scheduler, no engine FFI, no phase-change. Material identity is **never** stored per cell (DESIGN §5). Do not create blocks.
- **Verify real APIs before using them** (the modding skill's 1.21.11 examples are stale): `javap -classpath <jar> <Class>` against the architectury jar and the Mojang-mapped MC jar at
  `/home/claude/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/*/minecraft-merged-*.jar`.
- **Every commit message** ends with the trailer:
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

## File layout (within `:core`, package `net.rainbowcreation.orge.section`)

```
section/
  SubchunkKey.java      (exists — addresses one section: cx, sectionY, cz)
  SectionData.java      (exists, stub — T1 finishes: mutable arrays, promote/demote, views, factories)
  SectionCodec.java     (T2 — new: SectionData <-> bytes; column blob <-> bytes; deflate helpers)
  RegionFile.java       (T3 — new: 4 KiB-sector container, 1024-slot location table, by local (lx,lz))
  RegionStore.java      (exists, throwing stub — T4 re-implements: key<->region mapping, loadColumn/saveColumn, open-file cache)
  AmbientProvider.java  (T5 — new: seam supplying biome-ambient T + default mass for a never-stored section)
  SectionStore.java     (T5 — new: in-memory live authority; materialize-on-demand; dirty tracking; flush)
  SectionStorePlatform.java (T6 — new in :core: @ExpectPlatform seam for chunk hooks + world dir, IF Architectury events don't suffice)
fabric-1.21 / neoforge-1.21 (T6 — chunk load/unload/save wiring; ExpectPlatform impls if used)
```
Tests mirror under `core/src/test/java/net/rainbowcreation/orge/section/`.

---

## Task 1 — `SectionData`: finish the in-memory model

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/orge/section/SectionData.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/SectionDataTest.java`

The class already has: `CELLS = 4096`, `DEFAULT_AMBIENT_K = 285.0f`, `Form{UNIFORM,FULL}`, private fields, `uniform(t,m)` factory, `form()`, `temperatureAt(i)`, `massAt(i)`. Finish it: per-cell writes that promote `UNIFORM → FULL`, a `demoteIfUniform()` that collapses `FULL → UNIFORM` when every cell is equal, raw array views for the engine/scheduler, and value equality for round-trip tests.

- [ ] **Step 1: Write the failing test** — `SectionDataTest`. Cover:
  - `uniform(290f, 1000f)` → `form()==UNIFORM`, `temperatureAt(0)==290f`, `massAt(4095)==1000f`.
  - `setTemperature(i, v)` on a UNIFORM section promotes it to FULL: after `s.setTemperature(10, 500f)`, `s.form()==FULL`, `s.temperatureAt(10)==500f`, **and every other cell still equals the old uniform value** (e.g. `s.temperatureAt(0)` == the prior uniform T) — promotion must back-fill both arrays from the uniform values, not zero them. Same for `setMass`.
  - `temperatureArray()` / `massArray()` on a FULL section return `float[4096]` whose mutations are visible via `temperatureAt`/`massAt` (these are the *raw* views the engine writes into — document that they are live, not copies). On a UNIFORM section, calling `temperatureArray()` promotes to FULL first (so the engine always gets a real array), then returns it.
  - `demoteIfUniform()`: a FULL section whose 4096 temps are all `T` and 4096 masses all `M` collapses to `UNIFORM` with those values and returns `true`; a FULL section with one differing cell stays FULL and returns `false`; a section already UNIFORM returns `true` and stays UNIFORM.
  - `equalsValue(SectionData other)` (a helper used by codec round-trip tests): two sections are value-equal iff same effective per-cell temps and masses (a UNIFORM(290,1000) is value-equal to a FULL whose cells are all 290/1000). Implement by comparing `temperatureAt(i)`/`massAt(i)` across all 4096 cells. (Use this instead of overriding `equals`, to keep identity semantics elsewhere unsurprising.)
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*SectionDataTest'`. Expected: compile failure / FAIL (`setTemperature` etc. undefined).
- [ ] **Step 3: Implement** in `SectionData`:
  - `private void promote()`: if `form==UNIFORM`, allocate `temperature=new float[CELLS]; mass=new float[CELLS];`, `Arrays.fill` each with the uniform values, set `form=FULL`. No-op if already FULL.
  - `public void setTemperature(int i, float v)` / `setMass(int i, float v)`: `promote(); temperature[i]=v;` (resp. `mass[i]=v;`).
  - `public float[] temperatureArray()` / `massArray()`: `promote(); return temperature;` (resp. `mass`) — live views; document the contract.
  - `public boolean demoteIfUniform()`: if UNIFORM return true; else scan — if all `temperature[i]` equal `temperature[0]` and all `mass[i]` equal `mass[0]`, set `uniformTemperature=temperature[0]; uniformMass=mass[0]; temperature=null; mass=null; form=UNIFORM; return true;` else return false. (Exact `==` float compare is correct here: demotion only fires when the engine genuinely left them identical.)
  - `public float uniformTemperature()` / `uniformMass()` accessors (used by the codec for the UNIFORM case) — only meaningful when `form==UNIFORM`.
  - `public boolean equalsValue(SectionData o)`: loop 0..CELLS-1 comparing `temperatureAt(i)` and `massAt(i)` with `Float.compare(...)==0`.
  - Replace the `// TODO(phase: section-store)` block with the implemented methods.
- [ ] **Step 4: Run the test, watch it pass** — `:core:test --tests '*SectionDataTest'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(section): finish SectionData in-memory model (promote/demote, raw views)` + trailer.

**Acceptance:** UNIFORM↔FULL promotion back-fills correctly; raw array views are live and force-promote; `demoteIfUniform` collapses only when truly uniform; `equalsValue` ignores form.

---

## Task 2 — `SectionCodec`: section + column binary (de)serialization

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/section/SectionCodec.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/SectionCodecTest.java`

Pure, no files, no Minecraft. Serializes one `SectionData` to bytes and a whole **column** (`sectionY → SectionData`) to a single blob. This is the format that lands inside region-file slots in Task 3.

**Exact wire format (all big-endian):**

*Single section* — `writeSection(DataOutputStream, SectionData)` / `readSection(DataInputStream) -> SectionData`:
```
byte  form           ; 0 = UNIFORM, 1 = FULL
-- if UNIFORM:
float uniformTempK
float uniformMassKg
-- if FULL:
int   tCompressedLen ; deflate(temperature[4096] as 16384 big-endian bytes)
byte[tCompressedLen]
int   mCompressedLen ; deflate(mass[4096] as 16384 big-endian bytes)
byte[mCompressedLen]
```
On read of a FULL section: inflate each block, assert the inflated length is exactly `4096*4 == 16384` bytes (else throw `IOException("corrupt section: expected 16384 bytes, got N")`), then read 4096 floats into the array and build the section via the raw setters / a package-private FULL factory.

*Column blob* — `writeColumn(Map<Integer,SectionData>) -> byte[]` / `readColumn(byte[]) -> NavigableMap<Integer,SectionData>`:
```
byte  version        ; = 1
short sectionCount    ; number of sections present in this column (>= 0)
repeat sectionCount times:
  int   sectionY      ; signed vanilla section index (e.g. -4..19); int is overkill-safe
  <section payload as writeSection above>
```
Reject `version != 1` on read with a clear `IOException`. Use a `TreeMap<Integer,SectionData>` for the result so ordering is deterministic.

- [ ] **Step 1: Write the failing test** — `SectionCodecTest`:
  - **UNIFORM round-trip:** write `SectionData.uniform(287.5f, 1000f)`, read back, assert `equalsValue`.
  - **FULL round-trip:** build a section, write a gradient (e.g. `for i: s.setTemperature(i, 270f + i*0.01f); s.setMass(i, i % 2 == 0 ? 1000f : 0f);`), serialize, deserialize, assert `equalsValue` AND `form()==FULL`.
  - **FULL compresses:** a section with all-equal temps but written via `setTemperature` (so `form==FULL`) serializes to **fewer than 32768 bytes** (sanity that deflate ran; the raw arrays are 2×16384). Not a strict ratio — just `< 32768`.
  - **Column round-trip:** a `TreeMap` with sections at `sectionY = -4` (UNIFORM), `0` (FULL gradient), `19` (UNIFORM) round-trips: same keys, each `equalsValue`.
  - **Empty column:** `writeColumn(Map.of())` then `readColumn` → empty map (sectionCount 0).
  - **Bad version:** hand-craft a 1-byte array `{2}` (or flip the version byte of a real blob) → `readColumn` throws `IOException`.
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*SectionCodecTest'`. Expected: FAIL (`SectionCodec` undefined).
- [ ] **Step 3: Implement** `SectionCodec`:
  - `static byte[] deflate(byte[] raw)`: `Deflater d=new Deflater(Deflater.DEFAULT_COMPRESSION); d.setInput(raw); d.finish();` drain into a `ByteArrayOutputStream` with a reusable buffer, `d.end()`, return bytes.
  - `static byte[] inflate(byte[] comp, int expectedLen)`: `Inflater inf=new Inflater(); inf.setInput(comp);` drain into a `byte[expectedLen]` (or BAOS then check length), `inf.end()`; throw if total != expectedLen.
  - `static byte[] floatsToBytes(float[] a)`: `ByteBuffer.allocate(a.length*4).order(BIG_ENDIAN)`, `putFloat` each, `array()`.
  - `static float[] bytesToFloats(byte[] b)`: wrap, `getFloat` into `float[b.length/4]`.
  - `writeSection` / `readSection` and `writeColumn` / `readColumn` per the format above. Add a package-private `SectionData.full(float[] t, float[] m)` factory in Task-1's class if you didn't already, OR reconstruct via `uniform` + raw setters; prefer adding the `full(...)` factory (cleaner) and add it under Task 1's file with a one-line test there.
  - All methods `static`; private constructor.
- [ ] **Step 4: Run the test, watch it pass** — `:core:test --tests '*SectionCodecTest'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(section): SectionCodec — section + column binary (de)serialization` + trailer.

**Acceptance:** UNIFORM/FULL/column/empty all round-trip via `equalsValue`; FULL uses deflate; corrupt length and bad version throw with context.

---

## Task 3 — `RegionFile`: 4 KiB-sector container

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/section/RegionFile.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/RegionFileTest.java`

A single `r.<x>.<z>.orge` file holding up to 1024 **column blobs** (the Task-2 bytes), addressed by local chunk coords `(lx, lz)` each in `0..31`. Mirrors vanilla's `.mca` sector scheme (1-sector location header + 4 KiB-aligned payloads) but stores ORGE column blobs, not chunks. Uses `RandomAccessFile`; tests use `@TempDir`.

**Exact file format (big-endian):**
```
Sector 0 (4096 bytes): location table — 1024 ints.
  slot index for (lx,lz)  = lx + lz*32          (0..1023)
  entry int               = (sectorOffset << 8) | sectorCount
  sectorOffset == 0       => slot empty
Sector >=1: payloads, each 4 KiB-aligned. A payload is:
  int   blobLength        ; length of the column blob in bytes (excludes this 4-byte prefix)
  byte[blobLength]        ; the Task-2 column blob
  (zero padding to the next 4096-byte boundary)
```

- [ ] **Step 1: Write the failing test** — `RegionFileTest` (use `@TempDir Path dir`):
  - **Create + round-trip:** open `new RegionFile(dir.resolve("r.0.0.orge"))`, `write(1, 2, blobA)`, `write(5, 5, blobB)`, `close()`; reopen a new `RegionFile` on the same path, assert `Arrays.equals(read(1,2), blobA)`, `read(5,5)==blobB`, and `read(0,0)==null` (empty slot).
  - **Overwrite smaller→larger (forces reallocation):** `write(3,3, small)` then `write(3,3, large)` (large spans more sectors), close/reopen, `read(3,3)==large`; and a neighbor written before the realloc (`write(4,4, other)` between them) is still intact — proves the freed/realloc'd sectors didn't corrupt others.
  - **Delete:** `write(7,7, blob)`, `delete(7,7)`, `read(7,7)==null`; then `write(7,7, blob2)` reuses space and reads back `blob2`.
  - **Boundary slots:** `write(0,0,..)`, `write(31,31,..)`, `write(31,0,..)`, `write(0,31,..)` all independent and round-trip (verifies `lx + lz*32` indexing).
  - **Empty/short file open:** opening a non-existent path creates it with a zeroed header and every `read` returns `null`.
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*RegionFileTest'`. Expected: FAIL (`RegionFile` undefined).
- [ ] **Step 3: Implement** `RegionFile implements Closeable`:
  - Constants: `SECTOR_BYTES=4096`, `SLOTS=1024`, `HEADER_SECTORS=1`.
  - Ctor `RegionFile(Path)`: open `RandomAccessFile(file.toFile(),"rw")`. If `length() < SECTOR_BYTES`, write a full zero header and (if needed) pad so length is a sector multiple. Read the 1024-int header into `int[] locations`. Build `BitSet usedSectors`: mark sector 0 used; for each non-empty entry mark `[offset, offset+count)` used. Track `int totalSectors = (int)(length()/SECTOR_BYTES)`.
  - `int slot(int lx,int lz)`: bounds-check 0..31 each (`throw IllegalArgumentException` otherwise), return `lx + lz*32`.
  - `byte[] read(int lx,int lz)`: entry=locations[slot]; if `(entry>>>8)==0` return null; seek `offset*SECTOR_BYTES`, `readInt()` length, `readFully(new byte[length])`, return it.
  - `void write(int lx,int lz,byte[] blob)`: `needBytes = 4 + blob.length`; `needSectors = ceilDiv(needBytes, SECTOR_BYTES)` (throw if `needSectors > 255` — slot can't exceed a 1-byte count; column shouldn't get that big, but guard). Current entry's sectors: if present and `oldCount==needSectors`, reuse `oldOffset`; else free old run (clear bits) and `allocate(needSectors)` a fresh first-fit run (extend file + bitmap if no run found). Seek the chosen offset, `writeInt(blob.length)`, `write(blob)`, zero-pad to the sector boundary. Set `locations[slot]=(offset<<8)|needSectors`, mark bits used, and persist the header (`writeHeaderEntry(slot)` — seek `slot*4`, `writeInt(entry)`).
  - `void delete(int lx,int lz)`: free the run, `locations[slot]=0`, persist that header entry.
  - `int allocate(int count)`: scan `usedSectors` for the first run of `count` clear sectors starting at >=1; if none, append at `totalSectors`, grow file length (`raf.setLength`), bump `totalSectors`. Mark used, return offset.
  - `void close()`: `raf.close()`.
  - Keep `usedSectors`/`locations`/`totalSectors` consistent on every mutation; persist header eagerly (no separate flush needed for correctness).
- [ ] **Step 4: Run the test, watch it pass** — `:core:test --tests '*RegionFileTest'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(section): RegionFile — 4 KiB-sector column container` + trailer.

**Acceptance:** blobs round-trip across reopen; grow-in-place reallocation preserves neighbors; delete frees and space is reusable; out-of-range local coords rejected; new files self-initialize.

---

## Task 4 — `RegionStore`: key↔region mapping + open-file cache

**Files:**
- Modify (replace throwing stub): `core/src/main/java/net/rainbowcreation/orge/section/RegionStore.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/RegionStoreTest.java`

Maps chunk columns to region files under `world/orge/`, (de)serializes column blobs via `SectionCodec`, and caches open `RegionFile`s. The existing stub keeps `DIR_NAME="orge"`, `RegionStore(Path worldDir)`, `directory()`. Replace the two throwing methods and add the column API.

Mapping: `rx = cx >> 5`, `rz = cz >> 5`; local `lx = cx & 31`, `lz = cz & 31`; file `r.<rx>.<rz>.orge` inside `orgeDir`. (Arithmetic shift + mask gives correct local coords for negative `cx`/`cz`: `-1 >> 5 == -1`, `-1 & 31 == 31` ⇒ region -1, local 31. Test this explicitly.)

- [ ] **Step 1: Write the failing test** — `RegionStoreTest` (`@TempDir Path world`):
  - **Column round-trip:** `store.saveColumn(2, 3, map)` where map has sectionY -4 (UNIFORM), 0 (FULL gradient), 7 (UNIFORM); `store.loadColumn(2,3)` returns a map with the same keys and each `equalsValue`. Then a **fresh** `RegionStore(world)` (after `store.closeAll()`) `loadColumn(2,3)` still returns them (persisted to disk).
  - **Absent column:** `loadColumn(100,100)` on an empty store returns an empty map (not null).
  - **Negative coords + file naming:** `saveColumn(-1, -1, map)` creates `world/orge/r.-1.-1.orge` (assert `Files.exists`), and `saveColumn(0,0,..)` creates `r.0.0.orge`. `loadColumn(-1,-1)` round-trips. (`-1` lands in region -1, local 31,31.)
  - **Two columns, one region:** `saveColumn(0,0,a)` and `saveColumn(1,1,b)` both live in `r.0.0.orge` (one file) and load back independently.
  - **Empty save deletes:** `saveColumn(0,0, Map.of())` after a prior non-empty save makes `loadColumn(0,0)` return empty (slot cleared).
  - **Per-section convenience:** `save(new SubchunkKey(2,5,3), uniform(300,1000))` then `load(new SubchunkKey(2,5,3))` `equalsValue` it, and `load(new SubchunkKey(2,6,3))` returns `null` (section absent in column).
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*RegionStoreTest'`. Expected: FAIL (methods throw `UnsupportedOperationException`).
- [ ] **Step 3: Implement** in `RegionStore`:
  - Field `Map<Long,RegionFile> open = new HashMap<>();` keyed by `regionKey(rx,rz) = ((long)rx << 32) | (rz & 0xffffffffL)`.
  - `private RegionFile region(int rx,int rz)`: compute, `open.computeIfAbsent(key, k -> { Files.createDirectories(orgeDir); return new RegionFile(orgeDir.resolve("r."+rx+"."+rz+".orge")); })` — wrap `IOException` in `UncheckedIOException` (these are file ops; surface clearly).
  - `public NavigableMap<Integer,SectionData> loadColumn(int cx,int cz)`: `byte[] blob = region(cx>>5, cz>>5).read(cx&31, cz&31);` return `blob==null ? new TreeMap<>() : SectionCodec.readColumn(blob);` (IOException → UncheckedIOException).
  - `public void saveColumn(int cx,int cz, Map<Integer,SectionData> sections)`: `RegionFile rf = region(cx>>5, cz>>5);` if `sections.isEmpty()` `rf.delete(cx&31, cz&31);` else `rf.write(cx&31, cz&31, SectionCodec.writeColumn(sections));`.
  - Re-spec the existing per-section methods as **column-backed convenience** (document that the column is the primary persistence unit; per-section save is read-modify-write):
    - `public SectionData load(SubchunkKey key)`: `return loadColumn(key.cx(), key.cz()).get(key.sectionY());` (may be null).
    - `public void save(SubchunkKey key, SectionData data)`: `var col = loadColumn(key.cx(), key.cz()); col.put(key.sectionY(), data); saveColumn(key.cx(), key.cz(), col);`.
  - `public void closeAll()`: close every cached `RegionFile`, clear the map (wrap IOException). (Also call from a `close()` if you add `Closeable`.)
- [ ] **Step 4: Run the test, watch it pass** — `:core:test --tests '*RegionStoreTest'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(section): RegionStore — column persistence + region-file mapping/cache` + trailer.

**Acceptance:** columns round-trip across store reopen; negative coords map to the right region/local + file name; multiple columns share a region file; empty save clears the slot; per-section convenience works.

---

## Task 5 — `SectionStore` + ambient defaults

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/orge/section/AmbientProvider.java`
- Create: `core/src/main/java/net/rainbowcreation/orge/section/SectionStore.java`
- Test: `core/src/test/java/net/rainbowcreation/orge/section/SectionStoreTest.java`

The live in-memory authority other subsystems read/write. Loaded columns sit in memory; a never-stored section materializes on demand as `UNIFORM(biome-ambient T, material defaultMass)` (DESIGN §5) **without** being stored, so unsimulated regions stay free. Dirty columns flush to the `RegionStore` on unload/autosave. The biome/material lookups are injected via an `AmbientProvider` seam (kept pure here exactly as §6 kept `MaterialBindings.TagMembership` injectable — the real biome+material-backed impl is wired in Task 6 or deferred).

`AmbientProvider`:
```java
@FunctionalInterface  // single abstract method; mass has a default
public interface AmbientProvider {
    /** Biome-derived ambient temperature (K) for this section; impls fall back to
     *  SectionData.DEFAULT_AMBIENT_K (~285) when no biome value is available. */
    float ambientTemperatureK(SubchunkKey key);

    /** Material defaultMass (kg) for a never-simulated cell in this section.
     *  Default 0 — a far/empty (air) section carries ~no mass until simulated. */
    default float ambientMassKg(SubchunkKey key) { return 0.0f; }

    /** Constant fallback used when nothing better is available. */
    AmbientProvider FALLBACK = key -> SectionData.DEFAULT_AMBIENT_K;
}
```

- [ ] **Step 1: Write the failing test** — `SectionStoreTest` (`@TempDir Path world`), using a fake `AmbientProvider` (e.g. `key -> 300f` with `ambientMassKg` overridden to `key -> 1.2f`) and a real `RegionStore(world)`:
  - **Materialize ambient (not stored):** on a fresh store, `get(new SubchunkKey(0,0,0))` returns a UNIFORM section with `temperatureAt(0)==300f`, `massAt(0)==1.2f`; calling `get` again returns an equal section; and `loadColumn(0,0)` on the underlying `RegionStore` is still **empty** (ambient was not persisted).
  - **Put + get live:** `store.put(key, uniform(500,1000))`; `get(key).equalsValue(uniform(500,1000))`.
  - **Dirty flush on unload:** after `put`, `store.unloadColumn(0,0)` persists; a fresh `SectionStore` over the same world+RegionStore returns the put section from `get(key)` (loaded from disk, not ambient).
  - **Clean column does not write:** load a column that was never modified (e.g. `loadColumn`/`get` on an absent column, no `put`) then `unloadColumn` — assert no region file was created for it (ambient reads never dirty the column).
  - **flushAll:** `put` into two different columns, `flushAll()`, then a fresh store reads both back.
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*SectionStoreTest'`. Expected: FAIL (`SectionStore`/`AmbientProvider` undefined).
- [ ] **Step 3: Implement** `AmbientProvider` (above) and `SectionStore`:
  - Ctor `SectionStore(RegionStore region, AmbientProvider ambient)`.
  - State: `Map<Long, NavigableMap<Integer,SectionData>> loaded` keyed by `colKey(cx,cz)=((long)cx<<32)|(cz&0xffffffffL)`; `Set<Long> dirty`.
  - `public void loadColumn(int cx,int cz)`: `loaded.put(colKey, region.loadColumn(cx,cz));` (called on chunk load). Idempotent — skip if already loaded.
  - `public SectionData get(SubchunkKey key)`: find the loaded column; if present and it has `sectionY`, return it; otherwise return a **fresh** `SectionData.uniform(ambient.ambientTemperatureK(key), ambient.ambientMassKg(key))` (do NOT insert it). If the column isn't loaded at all, also return ambient (a caller asking before load gets ambient; document this).
  - `public void put(SubchunkKey key, SectionData data)`: ensure the column map exists (create empty if absent — but do not read disk here; chunk-load path is `loadColumn`), `col.put(sectionY, data)`, `dirty.add(colKey)`.
  - `public void unloadColumn(int cx,int cz)`: if `dirty` contains it, `region.saveColumn(cx,cz, loaded.get(colKey))`; remove from `loaded` and `dirty` regardless. (Called on chunk unload.)
  - `public void flushAll()`: for each dirty colKey, `region.saveColumn(...)`; clear `dirty` (keep columns loaded — autosave doesn't unload).
  - Document thread-confinement: this is server-thread state; no internal locking in v1.
- [ ] **Step 4: Run the test, watch it pass** — `:core:test --tests '*SectionStoreTest'`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(section): SectionStore live authority + ambient materialization` + trailer.

**Acceptance:** ambient sections materialize on demand and are never persisted; puts are live and survive an unload→reload; clean columns never write; `flushAll` persists all dirty columns.

---

## Task 6 — Loader integration: chunk load/unload/save hooks (both loaders)

**Files:**
- Create (maybe): `core/src/main/java/net/rainbowcreation/orge/section/SectionStorePlatform.java` (`@ExpectPlatform` seam — ONLY if Architectury's common events don't cover chunk load/unload + level save + world-dir resolution).
- Modify: `core/src/main/java/net/rainbowcreation/orge/Orge.java` (register hooks in `init()`, next to the §6 listener).
- Create as needed: `fabric-1.21/.../SectionStorePlatformImpl.java`, `neoforge-1.21/.../SectionStorePlatformImpl.java` (only if the seam is used).
- Test: `core/src/test/java/net/rainbowcreation/orge/section/SectionStoreManagerTest.java` (a focused, loader-free integration check feeding fake column coords through a manager — see step 1).

This task is **integration-heavy**; verify by full `build` on both loaders, plus a focused non-loader test of whatever logic you can isolate. Per §6's experience: prefer one Architectury registration; only drop to `@ExpectPlatform` per-loader impls if Architectury lacks the event.

**Before writing anything, verify the real API** with `javap` against the architectury jar (under `/home/claude/.gradle/...architectury...`) and the MC jar:
- Architectury chunk events — look for `dev.architectury.event.events.common.ChunkEvent` (load/unload) and a level-save / lifecycle hook (`dev.architectury.event.events.common.LifecycleEvent` — e.g. `SERVER_LEVEL_SAVE` / `SERVER_LEVEL_UNLOAD`). If present, register listeners directly in `Orge.init()` (one call each, both loaders), mirroring the `ReloadListenerRegistry.register(...)` call already there.
- If Architectury does **not** expose chunk load/unload, add `SectionStorePlatform` `@ExpectPlatform` methods that register the loader-native events and implement them per loader:
  - Fabric: `net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD / CHUNK_UNLOAD` and `ServerLifecycleEvents` / `ServerWorldEvents` for save.
  - NeoForge: `net.neoforged.neoforge.event.level.ChunkEvent.Load / ChunkEvent.Unload` and `LevelEvent.Save`.
- World directory: from a `ServerLevel`, resolve its own storage path (e.g. via `MinecraftServer#getWorldPath(LevelResource)` / the level's `LevelStorageAccess`), so each dimension gets its **own** `orge/` dir alongside that dimension's `region/` (`.mca`) — overworld and nether keep separate section data. **Never touch `.mca`.** Confirm the exact accessor with `javap` on `ServerLevel`/`MinecraftServer`.

**Manager wiring (in `:core`):**
- Add a small `SectionStoreManager` (server-side, package `net.rainbowcreation.orge.section`) that owns a `Map<dimensionId, SectionStore>` (key by the level's `Identifier`/dimension key — keep it loader-agnostic, pass the `Identifier` and `Path worldDir` in from the loader hook). API the hooks call:
  - `onLevelLoad(Identifier dim, Path levelDir, AmbientProvider ambient)` → create `SectionStore(new RegionStore(levelDir), ambient)`.
  - `onChunkLoad(Identifier dim, int cx, int cz)` → `store.loadColumn(cx,cz)`.
  - `onChunkUnload(Identifier dim, int cx, int cz)` → `store.unloadColumn(cx,cz)`.
  - `onLevelSave(Identifier dim)` → `store.flushAll()`.
  - `onLevelUnload(Identifier dim)` → `store.flushAll()`, `region.closeAll()`, drop the entry.
- For the `AmbientProvider`, supply a real impl that reads the level's biome temperature at the section center and falls back to `DEFAULT_AMBIENT_K`. If wiring biome lookup is uncertain within scope, use `AmbientProvider.FALLBACK` (constant 285 K) for now and **leave a `TODO(phase: section-store)` noting the biome-temp + material-defaultMass bridge is deferred** — mirror how §6 deferred the live `TagMembership` bridge. State this gap honestly in the task report.

- [ ] **Step 1: Write the failing test** — `SectionStoreManagerTest` (no loader classes): drive the manager directly. `onLevelLoad(dim, tempDir, key->300f)`; `onChunkLoad(dim,0,0)`; `manager.store(dim).put(new SubchunkKey(0,0,0), uniform(500,1000))`; `onChunkUnload(dim,0,0)`; then a fresh manager `onLevelLoad` on the same dir + `onChunkLoad` + `get` returns the persisted 500/1000 section. Also: `get` on an absent section returns ambient 300. (This tests all manager logic that doesn't require a running server.)
- [ ] **Step 2: Run the test, watch it fail** — `:core:test --tests '*SectionStoreManagerTest'`. Expected: FAIL (`SectionStoreManager` undefined).
- [ ] **Step 3: Implement** `SectionStoreManager` + the loader hooks. Register in `Orge.init()` (Architectury events) or via the `SectionStorePlatform` seam + per-loader impls. Replace the `// DESIGN.md §5 — section store: hook chunk load/unload...` comment in `Orge.init()` with the real registration.
- [ ] **Step 4: Verify** — `:core:test` green (manager test passes), then the **full both-loader build**:
  ```
  JAVA_HOME=/home/claude/jdk21 PATH=/home/claude/jdk21/bin:$PATH GRADLE_USER_HOME=/home/claude/.gradle ./gradlew build
  ```
  Expected: BUILD SUCCESSFUL, both remapped jars produced. A live in-game `/save-all` + chunk-churn test isn't automatable here — assert via the manager test + build, and report any non-unit-testable gap honestly.
- [ ] **Step 5: Commit** — `feat(section): chunk load/unload/save hooks wire SectionStore on both loaders` + trailer.

**Acceptance:** both jars build; chunk load→`loadColumn`, unload→`unloadColumn` (flush if dirty), level save→`flushAll`, level unload→`closeAll`, each per-dimension under its own `orge/` dir; `.mca` untouched; deferred biome/material `AmbientProvider` bridge (if any) is TODO-marked and reported.

---

## After all tasks

1. Final whole-subsystem code review (dispatch a fresh reviewer that reads all of `section/` + tests and re-runs `:core:test` and `build` — trust the code, not the reports).
2. Update memory: add/refresh `orge-section-store.md` (what's done, deferred bits) + the `MEMORY.md` index line; link `[[orge-material-model]]`, `[[orge-phase1-scaffold]]`.
3. `superpowers:finishing-a-development-branch` to close out and push `rebuild`.

**Next subsystem** (per the v1 dependency map): the scheduler (§8) consumes the live `SectionStore` + the engine FFI (§2); the engine track is the independent long pole (strip ORGE-ENGINE's `SimServer`, expose a stateless `step()` C header for jextract+Panama).

## Self-review notes (spec coverage vs DESIGN §5)

- "only temperature + mass, two parallel float[4096]" → T1. "material identity never stored per cell" → enforced (no material field anywhere in `section/`).
- "separate compressed region store under world/orge/, r.<x>.<z>.orge, loaded/unloaded alongside chunk, .mca never touched" → T3 (format) + T4 (mapping/files) + T6 (hooks, per-dimension dir).
- "UNIFORM vs FULL, deflate of both arrays once a gradient forms" → T1 (form transitions) + T2 (codec).
- "never-simulated section implicitly UNIFORM(biome-ambient T, material defaultMass), ~285 K fallback" → T5 (`AmbientProvider`, `DEFAULT_AMBIENT_K`) + T6 (real biome bridge or TODO-deferred).
- "mass = fluid level from day one (1000 kg ≈ full 1 m³ water), no Phase-2 storage rework" → satisfied: mass is a first-class `float[4096]`, no schema change needed for the Phase-2 fluid pass.
