# Fix: native kernel doesn't recognize in-game air as a fluid sink

## Problem (root cause, verified in-game)

`minecraft:air → orge:air` (bindings/default.json). `orge:air` is a real material
(`default_mass 1.2`, conductivity 0.026), so it interns at a **non-zero** LUT index and is
seeded at 1.2 kg. The native kernel decides "can fluid go here?" with:

```cpp
auto isAir = [&](int cell){ return matIx[cell]==0 || massOut[cell] <= ADV_EPS_MASS; }; // 1e-4
```

In-game air is `matIx != 0` AND `mass 1.2 > 1e-4` → `isAir` is **false**, so fall/spread/wetting
find no sink. Fluid is frozen (mass never changes; blocks never update). Every headless test uses
`matIx 0` for air, so none reproduce it. No Java-only fix exists: `matIx 0` makes air inert (no
conduction) and `mass≈0` collapses conduction's `Cth = mass·heatCap` (solver blows up). The engine
must learn what air is.

## Fix

Add an `air` flag to the engine LUT, set for the `orge:air` material via a new `State.AIR`
(a new *value* of the existing `state` codec field — no new DFU field). The kernel treats an
`air` cell as a sink and lets fluid **adopt** the cell's species on deposit. Air keeps conducting
(it stays a non-void, non-fluid, conductive material). Bit-identical kernel/sim_engine; parity
test stays green.

## Constraints (non-negotiable)

- Two repos: ENGINE = `/home/claude/ORGE/ORGE-ENGINE` (submodule, own git), MAIN = `/home/claude/ORGE` on `rebuild`. `cd` into the right repo before `git`; NEVER `git add` across the boundary.
- `JAVA_HOME=/home/claude/jdk21` on every gradle call.
- Any advection-rule change in `orge_kernel.hpp` is mirrored in `sim_engine.hpp` in the SAME commit; the ENGINE parity test stays green.
- ABI: the Java `NativeEngine.orgeStep` native decl and the C++ `orge_jni.cpp` param list must match by position. Insert the new air array as `byte[] lutAir` / `jbyteArray jAir` **after gas, before passes** on both sides.
- ENGINE: commit per task on a work branch (`feat/air-sink`); merge/push to ENGINE main + rebuild `liborge.so` + bump MAIN gitlink + bundle the `.so` ONLY at the integration task (Task 4).
- MAIN: push `origin/rebuild` after every MAIN commit.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Tasks

### Task 1 [MAIN] — `State.AIR` + `Material.air()` + codec + air.json (ABI-independent)
- `Material.java`: add `AIR` to `enum State { SOLID, FLUID, GAS, ENTITY }` → `{ SOLID, FLUID, GAS, ENTITY, AIR }`. Add `public boolean air() { return state == State.AIR; }`. Verify `fluid()` (FLUID||GAS) and `gas()` (GAS) are unchanged → AIR is non-fluid, non-gas.
- `MaterialCodec.java`: the state codec already does `State.valueOf(s.toUpperCase())`, so `"air"` parses to `State.AIR` for free; update the doc comment listing solid/fluid/gas/entity to include air.
- `air.json`: add `"state": "air"`.
- Test (TDD): assert `orge:air` resolves to `State.AIR`, `air()==true`, `fluid()==false`, `gas()==false`; and that a material with no `state` still defaults SOLID.
- Build + full `:core:test` green (StubEngine + existing `.so` unaffected — engine doesn't see air yet). Commit MAIN, push `origin/rebuild`.

### Task 2 [ENGINE] — `MatLUT.air` + air-aware kernel (kernel + sim_engine, bit-identical) + gtest
- On a new ENGINE work branch `feat/air-sink`.
- `orge_kernel.hpp`: `struct MatLUT` gains `const uint8_t* air;` (append AFTER `gas`). Make air-aware (add `|| lut.air[...]`):
  - `:124` `isAir` → `matIx[cell]==0 || lut.air[matIx[cell]] || massOut[cell] <= ADV_EPS_MASS`
  - `:126` `depositSpecies` adopt → `if (matOut[dst]==0 || lut.air[matOut[dst]] || massOut[dst] <= ADV_EPS_MASS) matOut[dst]=species;`
  - `:227` spread-wetting adopt → add `|| lut.air[matOut[j]]`
  - `:289` `density()` air branch → add `|| lut.air[e]` (consistency; air `fullMass` is already 1.2)
- `sim_engine.hpp`: mirror ALL of the above **identically**.
- Update EVERY `MatLUT{...}` brace-init in ENGINE (JNI is Task 3; here it's the test harness / gtests) to pass an `air` array — existing cases pass an all-zero air array (behaviour unchanged → existing tests + parity stay green).
- New gtest `tests/test_air_sink.cpp`: a water column whose sink cell uses a material at a **non-zero** index with `air[]=1, fluid=0, fullMass=1.2` (the in-game shape). Assert water falls/wets into it AND the destination's `matOut` becomes water (species adoption). Add a kernel-vs-sim_engine parity assertion for the same input. Wire it into `tests/run_tests.sh`.
- Run the full ENGINE suite (`tests/run_tests.sh`) green. Commit on `feat/air-sink`.

### Task 3 [ENGINE] — JNI `jAir` param populates `lut.air`
- `orge_jni.cpp`: add `jbyteArray jAir` to the param list **after `jGas`, before `passes`**. `GetPrimitiveArrayCritical` → `uint8_t* air`; set `lut.air = air`; release with `JNI_ABORT` (mirror the gas release; release in reverse order). 
- Compile-check (the JNI builds via `native/build_liborge.sh`; do NOT bundle yet). Commit on `feat/air-sink`.

### Task 4 [MAIN] — §9 credits absorbed air mass to the fluid (ABI-independent)
When the kernel lets fluid fall/wet INTO a real `orge:air` cell, it ADOPTS the cell and absorbs
its ~1.2 kg (total mass conserved; the air species' mass becomes the fluid's). But
`StepValidator.massConservedPerSpecies` only credits `sumBefore` for **fluid** inputs, so an air
cell's 1.2 kg is never credited while the wetted cell's `after` (1.2+dm) IS added to
`sumAfter[fluid]`. Result: `sumAfter - sumBefore` grows by ~1.2 kg per wetted cell → §9 rejects
("holds previous mass") once a pool wets enough cells → fluid re-freezes in-game.
- Fix in `StepValidator.massConservedPerSpecies`: when a cell's INPUT material is air
  (`lut.get(in).air()`) and its OUTPUT is a fluid, credit `before[i]` to `sumBefore[out]` (the
  fluid that absorbed the air), not to air. Concretely, alongside the existing
  `if (in != 0 && lut.get(in).fluid()) sumBefore[in] += before[i];`, add:
  `else if (in != 0 && lut.get(in).air() && out != 0 && lut.get(out).fluid()) sumBefore[out] += before[i];`
  Update the now-stale comment that claims a wetted air cell's `before` is 0.
- TDD unit test in `StepValidatorTest` (or the per-species test): a section where one cell is
  air-in (1.2 kg, `air()==true`) / water-out (1.2+dm) and the donor water cell loses dm — assert
  `massConservedPerSpecies` returns TRUE (today it would falsely reject for a large enough pool),
  AND that a genuine water→lava mix (no air) is still REJECTED (protection intact).
- `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` green (still on the OLD `.so`; this is pure Java). Commit MAIN, push `origin/rebuild`.

### Task 5 [MAIN+ENGINE integration] — wire ABI, rebuild `.so`, bundle, gitlink, regression
- ENGINE: merge `feat/air-sink` → `main`, push `origin main`. Rebuild: `JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`. Copy the result to MAIN `core/src/main/resources/natives/linux-x64/liborge.so`.
- MAIN:
  - `NativeEngine.orgeStep`: add `byte[] lutAir` param after `lutGas`, before `passes`; pass `f.lutAir()` in the call.
  - `BatchMarshaller`: `Flat` record add `byte[] lutAir`; in `flatten`, build `byte[] air = new byte[m]` with `air[i] = mat.air() ? (byte)1 : 0`; include it in the returned `Flat` and thread through.
  - Bump gitlink: `cd /home/claude/ORGE && git add ORGE-ENGINE`.
  - Regression test (TDD, native-backed, MAIN): seed the sink as the **real `orge:air` material** (non-zero LUT index, `air()==true`, 1.2 kg) and assert a water column falls/wets and the destination cell's output material becomes water. This reproduces today's in-game freeze and must now pass. (Keep the existing `matIx 0` audits.)
  - `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` (native-backed) + `:fabric-1.21:build :neoforge-1.21:build` green; confirm bundled `.so` md5 matches the ENGINE build.
  - ONE MAIN commit (the `.so` + gitlink + Java ABI + regression test together, so MAIN is never half-wired), push `origin/rebuild`.

## Verification
- ENGINE parity + advection suites green; new air-sink gtest green.
- MAIN `:core:test` green incl. the new native-backed air-sink regression; both loader jars build.
- In-game (user): floating water now falls/pools; `/orge get-live` shows the top cell's mass dropping.
