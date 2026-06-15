# RE-AUDIT — Module F: Java persistence after the F1/F2 fix (commits 5ab4dd2..3964796)

**Date:** 2026-06-15 · **Method:** executable code only; comments/tests/commit-msgs discarded; SPEC is truth.
**Scope:** §1.1 persisted extensive set, §1.3 retired symbols, law #6/#7, §9. Engine C++ UNCHANGED.
**Files traced (executable paths only):** `SectionData.java`, `SectionCodec.java`, `ColumnTask.java`,
`ColumnResult.java`, `RegionMarshaller.java`, `ColumnAssembler.java`, `NativeEngine.java`,
`MinecraftThermalWorld.java` (snapshot + writeBackColumn + dormant per-section writeBack),
`EnthalpyCurve.java`, `StepValidator.java`, `orge_jni.cpp`, `ServerStoreReadSource/WriteSink.java`,
`MinecraftPhaseChanger.java`.

---

## F1 — Enthalpy `E` persisted end-to-end, `eOut` consumed?  →  **RESOLVED**

Traced both directions; NO `mass·cp·T` reconstruction survives on feed-in; `eOut` is NOT discarded.

- **In-memory store (SectionData):** has a real extensive `float[] enthalpy` channel (`SectionData.java:45`),
  stored not derived (`enthalpyAt`/`setEnthalpy`/`enthalpyArray` :73-75,152-155,180-183). NO stored
  `temperature` field exists (grep clean — `SectionData.java:15,32` are only a doc-comment + an ambient
  fallback constant). UNIFORM form carries `uniformEnthalpy` (E [J]).
- **On-disk (SectionCodec v5):** UNIFORM writes `uniformEnthalpy()`; FULL deflates `enthalpyArray()`
  (`SectionCodec.java:177,181`) — i.e. absolute extensive J, NOT m·cp·T. Read is symmetric
  (`:240-254`). `FORMAT_VERSION=5` (`:24`); `readColumn` REJECTS any version≠5 with a clear
  "delete world/orge, no migration" message (`:350-355`). Confirmed.
- **Column path:** `ColumnTask`/`ColumnResult` carry a 10th `enthalpy` channel (`ColumnTask.java:10-12`,
  `ColumnResult.java:6-8`); `RegionMarshaller.Flat` carries `eIn` and `flatten` copies
  `t.enthalpy()→eIn` (`RegionMarshaller.java:18,46`); `slice` copies `eOut→ColumnResult.enthalpy`
  (`:65,74-75`). `ColumnAssembler.SectionCells` has an `enthalpy` field (`ColumnAssembler.java:66`).
- **Feed-in (NativeEngine):** the old `eIn = mass·cp·T` synthesis is DELETED. `eIn = f.eIn()`
  (`NativeEngine.java:162`) — sourced straight from the stored-E channel, exactly like `vxIn`/`swapReadyIn`.
  No cp multiply anywhere in the feed (grep of NativeEngine/scheduler finds `heatCap` only in the LUT
  *registration* docstring/packing — legitimate; the engine's own §8.1 curve consumes cp).
- **Read-out (NativeEngine):** `eOut = scratch.eOut(total)` is passed (`:172,181`) and threaded back via
  `RegionMarshaller.slice(... eOut ...)` (`:196`) into `ColumnResult.enthalpy()` — NOT discarded.
- **Writeback (MinecraftThermalWorld.writeBackColumn):** stores `result.enthalpy()` (engine eOut) into
  `data.enthalpyArray()` UNCLAMPED, sanitising only non-finite→0 (`:785-790`). The `[0,6000]` clamp is
  applied ONLY to the derived Kelvin diagnostic, never to E.
- **Snapshot (MinecraftThermalWorld.snapshot):** `enthalpy[i] = sd.enthalpyAt(i)` raw (`:739`); the engine
  `tIn` channel is a DERIVED, clamped `T = h⁻¹(E/m)` diagnostic (`:746-748`), never raw E in the Kelvin slot.
- **JNI:** `C->E[i] = (double)eIn[i]` in (`orge_jni.cpp:221`), `eOut[i] = (float)C->E[i]` out (`:309`) —
  absolute both ways, never reconstructed from T. `C->T_curr = tIn` is loaded only as the massless/zero-cp
  derive seed (`:199-202`), not as truth.
- **Seed-E edge case (prior regression):** `ColumnAssembler.assemble` encodes
  `E = m·h(T_seed)` via `EnthalpyCurve.cellE` when stored E is unset, NOT E=0
  (`ColumnAssembler.java:171-175`): `enthalpy[ci] = (inE != 0f) ? inE : (m==null||seeded<=0 ? 0 :
  cellE(seeded, m, lookup, temp))`. So a never-simulated/seed cell reads its real T, not 0 K. The dormant
  per-section `writeBack` (`MinecraftThermalWorld.java:352-357`) likewise ENCODES E=m·h(Tout), never stores
  raw T as E. `EnthalpyCurve` is a genuine chain-anchored curve with two-sided latent plateaus
  (`EnthalpyCurve.java:98-144`), so the encode/decode honors INV-LAT.

**Verdict F1: RESOLVED.** The loss-free E seam now runs SectionData ↔ codec ↔ ColumnTask/Result ↔
Flat.eIn/eOut ↔ JNI E. The `m·cp·T` reconstruction is gone; eOut is stored. INV-LAT can hold across the
tick boundary (no single-slope collapse).

---

## F2 — Momentum extensive, or still velocity?  →  **PARTIALLY RESOLVED**

The persistent stores (RAM struct + disk) now hold EXTENSIVE momentum. But the LIVE engine round-trip
(snapshot → JNI → writeback) still down-converts to velocity and rebuilds momentum from `v·m_written`,
re-introducing the very rescaling §1.1/law-#7 forbid whenever a cell's mass changes inside a tick.

**What IS now extensive (good):**
- **SectionData:** stores `momX/momY/momZ` [kg·m/s] (`SectionData.java:48-50,233-239`); NO stored velocity
  field. `demoteIfUniform` guards a nonzero-momentum cell from collapsing (`:379-385`).
- **On-disk codec v5:** the "momentum block" deflates `momXArray/momYArray/momZArray`
  (`SectionCodec.java:203-213`) and reads them back into `momXArray()` etc. (`:274-291`) — extensive
  momentum on disk, symmetric. So a **save/load** round-trip preserves `p_old` exactly regardless of mass.

**Where velocity is STILL the crossing/derivation currency (the hole):**
- The whole column ABI is still velocity: `ColumnTask`/`ColumnResult` carry `velX/velY/velZ`
  (`ColumnTask.java:10`, `ColumnResult.java:6`); `RegionMarshaller.Flat` carries `vxIn` and slices `vxOut`
  (`RegionMarshaller.java:17,41-43,69-71`); `ColumnAssembler.SectionCells` carries `velX/velY/velZ`
  (`ColumnAssembler.java:65`) copied straight through (`:160-162`).
- **Snapshot derives v then crosses v:** `velX[i] = sd.momXAt(i)/mi` (`MinecraftThermalWorld.java:732-734`)
  — deriving v=p/m at the boundary is itself LEGAL, but the value that crosses to the engine is `v`, not `p`.
- **JNI rebuilds p from v·m:** `C->px[i] = vxIn[i]*C->mass_kg[i]` (`orge_jni.cpp:207-209`); reads out
  `vxOut = px/m` (`:314-319`). Velocity, not momentum, is the ABI currency.
- **Writeback rebuilds stored momentum from v·m_NEW (the rescaling bug):**
  `momX[i] = cleanM[i] * cleanVx[i]` (`MinecraftThermalWorld.java:805-813`), where `cleanVx = result.velX()`
  (= engine `p_engine/m_engine`) and `cleanM = StepValidator.cleanMass(outMass, fullMassBound)` (`:776`).
  So the stored momentum becomes `p_stored = cleanM · (p_engine/m_engine)`. When the engine's output mass is
  clamped/sanitised by `cleanMass` (over-max or non-finite cells) so `cleanM ≠ m_engine`, the stored
  momentum is silently rescaled by `cleanM/m_engine ≠ 1` — `p_stored ≠ p_engine`. The engine's authoritative
  output momentum is never stored directly; it is reconstituted through a velocity intermediary tied to a
  *different* (validated) mass.

**Why this is still a §1.1/law-#7 divergence, narrower than before:**
- Storing extensive `p` end-to-end (§1.1) would make the cross-tick value exact and mass-change-immune.
  The persisted RAM/disk structs now do hold `p`, so a **quiescent** cell and a **save/load** are exact.
  But the per-tick value that actually re-enters the next snapshot is `p = m_written · (p_engine/m_engine)`,
  which equals `p_engine` only when `cleanMass` is the identity on that cell — i.e. the velocity-ghost is
  removed for the steady case but survives precisely for the mass-changing case the bug was about.
- The §1.3 retired symbol "persisted vx/vy/vz" is no longer a *stored struct field*, but velocity is still
  the engine-crossing and writeback-reconstruction currency, so the spirit of §1.3 (extensive momentum is
  the carrier across the boundary) is not yet met at the live seam.

**Verdict F2: PARTIALLY RESOLVED.** The persisted source-of-truth (RAM + disk) is now MOMENTUM, which fixes
the save/load and quiescent-cell case. The LIVE step boundary still crosses and reconstructs via velocity
(`ColumnTask/Result/Flat/SectionCells` velX/Y/Z; JNI `px=v·m` / `v=p/m`; writeback `p=cleanM·vOut`), so a
cell whose mass is altered by the validated-mass clamp between snapshot and writeback has its momentum
rescaled — the law-#7 velocity-ghost persists at the tick seam, just no longer in the stored struct.
To fully resolve: thread `px/py/pz` (not velX/Y/Z) through ColumnTask/Result/Flat/SectionCells + the JNI
ABI (mirror the eIn/eOut precedent) and write `result.momentum()` straight into SectionData, dropping the
`cleanM·vOut` reconstruction.

---

## NEW drift scan (changed files are large rewrites)

- **No stored raw `T` as SoT (§1.3 T_curr/T_next ban):** SectionData has no temperature field; every site
  that needs T derives it (`deriveT`) and every site that stores thermal state encodes E (`cellE`):
  snapshot tIn (`MinecraftThermalWorld.java:746-748`), `/orge get` (`ServerStoreReadSource.java:55`),
  `/orge set` (`ServerStoreWriteSink.java:66-67`), phase changer (`MinecraftPhaseChanger.java:93,152-153`),
  dormant writeBack (`:352-357`). COMPLIANT.
- **No E-mutating clamp:** the `[0,6000]` clamp (`clampDeriveBoundary` → `StepValidator.clampDerivedKelvin`)
  is applied ONLY to derived Kelvin at the feed/display boundary (`MinecraftThermalWorld.java:420-421,748`);
  E writeback is stored unclamped, non-finite→0 only (`:787-789`). COMPLIANT (matches law §6/§7 comment).
- **Round-trip holes / zeroing:** E and momentum are not dropped/defaulted on any traced live path. eOut is
  threaded (not discarded). swapReady round-trips through the JNI and writeback (`:824-826`). The
  `ColumnTask`/`ColumnResult`/`RegionMarshaller`/`SectionCells` back-compat constructors zero-fill the
  enthalpy/momentum/swapReady channels — used only by tests/legacy callers; the LIVE path
  (ColumnAssembler→flatten→JNI→slice→writeBackColumn) always supplies the full channels. No live hole.
- **E conservation when mass changes:** E is stored as the engine's authoritative eOut directly (no Java
  recompute from the new mass), so E is NOT fabricated/lost at the Java seam on a mass change — unlike
  momentum (F2), which IS rescaled via cleanM. This asymmetry is itself evidence the fix went further for E
  than for momentum.
- **Codec v5 symmetry:** write order form→materials→momentum→pressure (`SectionCodec.java:174-227`); read
  order identical (`:236-303`). Field-for-field symmetric. swapReady intentionally not serialized
  (in-memory-only, MINOR-F3 below). No write≠read asymmetry. COMPLIANT.
- **MINOR-F3 (carried):** `swapReady` in-memory only, never serialized; resets on reload
  (`SectionData.java:52,313`; not in SectionCodec). §1.1 lists it persisted; documented-acceptable DEBT.
- **MINOR-F4 (carried):** `void_ix` does not cross the JNI ABI; engine resets to slot-0 default per call.
  Harmless under stable slot-0 vacuum. §1.1 lists it persisted; spec-acknowledged DEBT.
- **MINOR-F5 (carried):** stale comments — `orge_jni.cpp:282-284` "sub-cycle" wording while executable code
  does ONE `step_world_b` per call (`:285-293`, FORK-4 honored). Comment drift only.

---

## Confirmed-compliant (high-value negatives)

- F1 E seam loss-free end-to-end (store→codec→column→JNI→writeback→snapshot); no `m·cp·T` survives; eOut consumed.
- SectionCodec v5: E + momentum extensive on disk; pre-v5 hard-rejected, no migration; write/read symmetric.
- Seed-E path encodes `E=m·h(T)` (never E=0 / 0 K) for never-simulated/seed cells.
- No stored raw T or raw v as a struct field anywhere; all T/v are derived at boundaries.
- E-clamp is display-only; stored E never magnitude-clamped.
- EnthalpyCurve: genuine chain-anchored curve, two-sided latent plateaus, forward `cellE` / inverse `deriveT`.
- JNI ABI carries E (absolute), P, swapReady both directions; one step per call (no sub-cycle).

---

## Bottom line

- **F1 (enthalpy E): RESOLVED.** Extensive E is persisted end-to-end (RAM + disk + column + JNI); the
  `mass·cp·T` synthesis is deleted; eOut is stored, not discarded; seed cells encode E=m·h(T).
- **F2 (momentum): PARTIALLY RESOLVED.** Persisted SoT (SectionData + codec v5) is now extensive momentum,
  fixing save/load and quiescent cells. The LIVE step boundary still crosses + reconstructs via velocity
  (`px=v·m` in JNI, `p_stored=cleanM·vOut` in writeback), so a cell whose mass is altered by the validated
  mass clamp between snapshot and writeback has its momentum silently rescaled — the law-#7 velocity-ghost
  persists at the tick seam. Severity MAJOR, CODE-BUG vs §1.1/§1.3 (narrower than the pre-fix MAJOR-F2,
  which had velocity as the *stored* SoT too).
- **NEW findings:** none of BLOCKER/MAJOR class beyond the F2 residue. MINOR-F3/F4/F5 carried unchanged.
  No new drift introduced by the rewrite.
