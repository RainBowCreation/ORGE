# Issue #2 — Material LUT = law §8 fixed schema — in-game audit checklist

**Headless green is NOT the final oracle (PRD acceptance gate).** This note is the human in-game
gate. The change is a SCHEMA + PLUMBING change with no intended behaviour change, so the audit is a
"nothing regressed" pass.

## What changed
- Engine `Material` struct (`ORGE-ENGINE/core/sim_engine.hpp`) is now EXACTLY the law §8 fixed schema:
  the 8 physics floats (`heatCapacity, thermalConductivity, molarMass, minMass, maxMass, viscosity,
  defaultMass, yieldStress`) **plus** the phase quadruple (`minTemp→minTarget`, `maxTemp→maxTarget`),
  with `minTarget`/`maxTarget` stored as globally-stable `matIx` (`MAT_NO_TARGET = 0xFFFF` = none).
- JNI `orgeRegisterMaterials` ABI grew the new arrays (yieldStress + phase quadruple); `liborge.so`
  rebuilt. Java: `Material` record + builder, `MaterialCodec` (`yield_stress` optional, default 0),
  `LutArrays` (§8 schema + target id→matIx resolution), `NativeEngine` native decl/call.

## Deferred (present-but-no-op, by design — like yieldStress)
- **Relabel-in-DECODE is NOT implemented here.** The phase quadruple is now engine-resident so a
  FUTURE DECODE can relabel locally keeping `E`. Phase change still runs in the Java `PhaseRule`
  path this PRD does not touch. `yieldStress` is plumbed but `0` for all current fluids (no granular
  yield yet). Both are schema-present / behaviour-deferred.

## In-game checks (expect NO behaviour change vs. pre-#2)
1. `/reload` with the real datapack — materials register cleanly, no LUT/JNI error in the log.
2. Water still flows / levels / falls exactly as before; lava still sinks under water; air/steam
   behave as before. (Schema-only change ⇒ motion + thermal must be byte-for-byte unchanged.)
3. Grand + per-species mass still conserved (no fabrication, no vanish) over a few minutes.
4. A material with a `yield_stress` in its JSON loads without error (value carried, no-op at runtime).

## Headless evidence (gate, not oracle)
- ORGE-ENGINE cheap tier GREEN incl. new `engine_b_lut_schema_test` + golden parity
  (`test_phase2b_lut`, `resident_lut_test` byte-identical — physics unchanged).
- `:core:test` + `:core:integrationTest` GREEN on the rebuilt real `liborge.so`; real material JSON
  round-trips Java → JNI → engine. `LutPackTest` updated to the law §8 set (stale "exactly seven" → §8).
