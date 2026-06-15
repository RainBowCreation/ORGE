# Engine-B — DRIFT RE-AUDIT (after F1/F2 persistence fixes)

**Date:** 2026-06-15 (re-run) · read-only · spec=truth, code/tests/commit-msgs presumed buggy, comments discarded.
**HEADs:** parent `4bedc1e` · engine `a2a51cd` (**engine C++ UNCHANGED since the first audit** — all changes are Java-side, commits `5ab4dd2..3964796`).
Detail files: `REAUDIT-module-F.md`, `REAUDIT-enthalpy-curve.md`. First-pass report: `00-MASTER-drift-report.md`.

## What was re-checked & why
The diff since the first audit is **entirely the Java persistence layer** (the F1/F2 fix) plus a new `EnthalpyCurve.java`. So only Module F + the new file were re-audited. **The C++ findings carry forward unchanged** (engine HEAD is byte-identical): **C-1 (liquid boundary half-cell ghost) is STILL OPEN**; Modules B/D/E unchanged (B/E clean, D = labeled T4 debt).

---

## Verdicts

### ✅ F1 — enthalpy `E` persisted end-to-end — **RESOLVED** (live path)
Traced both directions in executable code:
- `SectionData` has a real extensive `float[] enthalpy` (J); no stored `temperature` field.
- `SectionCodec` v5 serializes E as absolute J (uniform + full), symmetric read/write; `FORMAT_VERSION=5`, pre-v5 hard-rejected (no migration).
- Column path carries an `enthalpy` channel (ColumnTask/Result/`Flat.eIn`/SectionCells).
- `NativeEngine.java:162` feeds `eIn = f.eIn()` from stored E — **the `mass·cp·T` synthesis is deleted** — and threads `eOut` back (`:196`), no longer discarded.
- JNI crosses E absolute both ways (`orge_jni.cpp:221/309`).
- Writeback stores `eOut` unclamped (non-finite→0 only); the [0,6000] clamp is **display-only** on derived Kelvin.
- Seed-E edge (prior regression): `ColumnAssembler.assemble:171-175` encodes `E=m·h(T_seed)` via `EnthalpyCurve.cellE` when stored E is unset — not E=0. Correct.

### ⚠️ F2 — extensive momentum — **PARTIALLY RESOLVED** (MAJOR remains, narrower)
- **Fixed:** the persisted SoT (RAM + disk) is now extensive momentum — `SectionData` stores `momX/Y/Z` (no velocity field); `SectionCodec` v5 serializes momentum symmetrically. **Save/load and quiescent cells preserve `p_old` exactly.**
- **Residual hole:** the **live column ABI is still velocity.** ColumnTask/Result/`Flat`/SectionCells carry `velX/Y/Z`; snapshot crosses `v=p/m` (`MinecraftThermalWorld.java:732-734`); JNI rebuilds `px=v·mass` in / `v=p/m` out (`orge_jni.cpp:207-209/314-319`); writeback rebuilds `momX = cleanM·vOut` (`:805-813`). When the engine's output mass is clamped (`cleanM ≠ m_engine`), stored momentum is rescaled by `cleanM/m_engine` ⇒ `p_stored ≠ p_engine`. The law-#7 velocity-ghost survives precisely for the mass-changing cell the bug was about — just no longer inside the stored struct.
- **To fully resolve:** thread `px/py/pz` (not velocity) through ColumnTask/Result/Flat/SectionCells + the JNI ABI (mirror the eIn/eOut precedent) and store `result.momentum()` directly, dropping the `cleanM·vOut` reconstruction. Severity MAJOR · CODE-BUG vs §1.1/§1.3.

### 🆕 NEW — dormant write-back re-encodes E from T (latent loss on the DORMANT path)
- `MinecraftThermalWorld.writeBack:357` (the **dormant per-section** path, whose `StepResult` has no extensive-E channel) re-encodes `E = cellE(m, tOut)` from the engine's *derived* Kelvin. Because the curve is plateau-pinned, a cell that goes dormant **mid-latent-plateau loses its latent offset** (re-encodes to the plateau bottom). Same F1 class, residual on the dormant channel only (the live column path is loss-free).
- Severity **MAJOR** (defeats INV-LAT for any cell that dormates mid-plateau) · CODE-BUG vs §1.1/law#6 · narrow scope. Fix = give the dormant `StepResult` an extensive-E channel too (same treatment as the live path).

### ✅ NEW `EnthalpyCurve.java` — **CLEAN** (0 findings)
Parity-grade mirror of the engine's `orge_enthalpy` chain-anchored curve (`sim_engine.hpp:131-220`). Two-sided latent plateaus present (no single-slope F1 regression); chain-anchored `h_anchor = h_donor(T*)+L`, ΔE≡0 by exact-double construction; all values from the LUT (no hardcoded constants); returns the same Kelvin (±1 ulp) as the engine's `derive_T` ⇒ DECODE/display agree; final/pure/derive-only, all callers display-only. Defensive `depth>8` cycle guard the engine lacks (benign; never trips on valid ≤3-hop data).

---

## Net status of the three discussed findings
| finding | first audit | now |
|---|---|---|
| **F1** enthalpy E not persisted | MAJOR | ✅ **RESOLVED** (live path) — but see NEW dormant-path latent loss |
| **F2** momentum-as-velocity | MAJOR | ⚠️ **PARTIAL** — persisted SoT fixed; live ABI still velocity → rescale on mass-clamp |
| **C-1** liquid boundary ghost | MAJOR | 🔴 **UNCHANGED / OPEN** (C++ untouched) |

**Carried MINORs:** F3 (swapReady in-mem only), F4 (void_ix not across ABI), F5 (stale sub-cycle comment). No NEW minors. No new BLOCKERs.

## Suggested next fixes
1. **F2 finish** — thread `px/py/pz` through the column ABI + JNI (drop `cleanM·vOut`). Closes the last law-#7 momentum hole.
2. **NEW dormant-E** — extensive-E channel on the dormant `StepResult` path (mirror the live fix). Closes the residual INV-LAT hole.
3. **C-1** — liquid boundary half-cell ghost in the C++ force (unchanged from first audit; agent `ac5fe364…` has the full analysis + options a/b/c).
