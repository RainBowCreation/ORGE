# Live Thermal Viewer — Design Spec

**Date:** 2026-05-31
**Status:** Approved for planning
**Branch target:** rebuild

## 1. Goal

Open the existing SDL3 thermal visualizer as a **standalone window against the live, in-game ORGE engine** — not its own self-driven `SimServer`. The window shows the live thermal map and the loaded-section view, navigated with the **same view-mode controls** as the current `sim_server` tool (WorldMap ↔ ChunkView, WASD/arrows, enter/esc, color-scale). The viewer is **read-only** and must impose **zero measurable cost on server TPS** when running, and **zero cost** when off.

## 2. Non-goals (YAGNI)

- No write-back from viewer (no pause, no paint). Read-only.
- No exact loaded-stack membership tracking (dormant/unloaded drift accepted — see §8).
- No `matIx`/material overlay in v1 (temperature-only color map).
- No Windows/macOS build. Linux dev only (POSIX `shm`/`mmap`).
- No multi-dimension view. One dimension per session (§7).
- No network transport. Same-machine shared memory only.

## 3. Decisions (locked)

| Fork | Decision |
|---|---|
| Transport | Shared memory (`mmap` of a file under `run/`), one-way Java→viewer |
| Interactivity | Read-only (navigation only) |
| Launch | Command-triggered: `/orge view` spawns the viewer process on demand, reopenable |
| Platform | Linux only — POSIX `shm_open`/`mmap`, g++/SDL3 |
| Publish site | **Java, in `writeBackResults` on the server thread** — single writer; only Java sees load/unload/dormancy |

## 4. Why publish from Java (churn correctness + cost)

Threading model (confirmed in `Scheduler.java`):

- **Server (game) thread** runs `onServerTick` → `snapshot` → `writeBackResults`; all counted in the ~30 ms server-thread budget and the health throttle (`Scheduler.java:48,66,261`).
- **Off-thread runner** runs *only* the native `orgeStep` via `runner.submit(...)` (`Scheduler.java:198`).

**Churn forces Java.** The loaded set refreshes every second — sections load and unload. Only Java sees this: `ThermalWorld.snapshot` does the loaded-union and **drops unloaded sections**; `ActiveSet` (the dormancy roster, "loader event hooks push wakes here", server-thread only) is mutated on chunk unload; `writeBack` skips sections that unloaded since snapshot. The native `orgeStep` sees only the **stepped batch** and **cannot distinguish "dormant" from "unloaded"** (both are simply "not in this batch"). A native-side publisher could therefore never reclaim unloaded slots → ghosts accumulate to `cap`. So membership ownership must live in Java.

**Cost is acceptable.** The per-publish payload is the **active batch's** result temps, already materialized on the server thread inside `writeBack`. memcpy of that into the shm is a few hundred KB–~1 MB → tens of µs to ~1 ms, once per second, flag-gated. Negligible against the 50 ms tick. Dormant sections keep their existing slot (no recopy). The earlier off-thread native option (Option A) saved ~1 ms/sec but cannot see unloads — to make it correct you would bolt a Java membership region onto it anyway (two writers, two regions, seqlock races). Not worth it.

Cost when off: the publisher never maps the file and never copies (flag-gated at init).

## 5. Architecture / data path

```
[runner thread]  orgeStep  → tOut          (unchanged; no shm, no coords passthrough)
[server thread]  writeBackResults
                   ├─ world.writeBack(entry, result)        (existing)
                   └─ if liveView on (≤1 Hz):
                        ThermalViewPublisher.publish(...)    ◄── NEW
                          • reconcile slot map vs loaded set (assign new / free unloaded)
                          • seqlock-write header + active-batch temps + per-slot state into shm
                                        │
                              run/orge_live.mmap  (named, pre-sized, pre-faulted)
                                        │
[separate process] live_view ──────────┘  mmap + seqlock-read → fill C++ World → sim_render (read-only)
```

- `/orge view` (server thread, rare) → `ProcessBuilder` spawns `live_view`. Fire-and-forget; does not touch the publish path.
- The shm region is created/mapped lazily on first `publish` when the flag is on (once), via Java NIO `MappedByteBuffer`.

## 6. Shared-memory layout (`run/orge_live.mmap`)

Fixed-size, pre-sized to a section cap, pre-faulted once.

```
Header (64 B, 64-byte aligned):
  char  magic[8]    = "ORGEVIEW"
  u32   version     = 1
  u32   _pad
  u64   seq             // seqlock: odd = write in progress, even = stable
  i32   dim             // dimension id being published (see §7)
  u32   sectionCap      // pre-sized capacity
  u32   sectionCount    // live sections this frame (≤ cap)
  f32   scaleMin        // suggested color-scale bounds (publisher-computed or fixed)
  f32   scaleMax
  f32   lastStepMs      // native step ms (debug header readout)
  // pad to 64 B

SectionRecord[sectionCap]  (each: 16 + 16384 = 16400 B, 16-byte aligned):
  i32   cx
  i32   sectionY
  i32   cz
  u8    state          // 0=EMPTY (free slot), 1=LOADED_ACTIVE, 2=LOADED_DORMANT
  u8    _pad[3]
  f32   temp[4096]     // SEC_N = 16*16*16, current temperatures
```

- **Size:** ~16.4 KB/section. Default `sectionCap = 2048` → ~33 MB. Config-tunable.
- **Temperature-only.** Color map is temperature-only (`temperatureToColor` takes only temp). The viewer renders every slot whose `state != EMPTY`; `state` distinguishes active vs dormant for an optional tint/badge.
- **`state` semantics:** the publisher sets `EMPTY` on freed (unloaded) slots, `LOADED_DORMANT` for loaded-but-not-stepped sections (temps unchanged, slot retained), `LOADED_ACTIVE` for sections in the current batch (temps refreshed this frame). This is how churn stays correct — unloaded slots flip to `EMPTY` and stop rendering.
- **Seqlock protocol:**
  - Writer (Java): `seq++` (now odd) → write header + changed records → `seq++` (now even). Plain `MappedByteBuffer` stores; **no `force()`/`msync`**.
  - Reader (C++): read `seq` (retry if odd) → copy fields → read `seq` again; if changed, retry. Never blocks the writer.
- **UNIFORM sections:** `SectionData` may be `UNIFORM` (one temp for the whole section). The publisher expands it — fills all 4096 `temp[]` with `uniformTemperature()` — so the viewer needs no special case. (A future optimization could add a per-record uniform flag; not in v1.)

## 7. Dimension handling

`SubchunkKey` carries `(cx, sectionY, cz)` — no dimension, but `ThermalWorld.BatchEntry` and `ActiveSet` are keyed by `Identifier dimension`, so the publisher knows each section's dim. v1 publishes a **single dimension** (configurable, default overworld): the publisher filters its reconcile to that dim and records it in the `dim` header field. Sections in other dims are ignored. Multi-dimension (segregated views / dim switching) is out of scope — revisit only if needed. The slot map and `EMPTY`-reclaim from §8 mean switching the configured dim mid-session would just churn all slots over; not wired in v1.

## 8. Membership model — Java-owned slot map (handles churn)

`ThermalViewPublisher` owns a persistent `Map<SubchunkKey, Integer> slotOf` plus a free-slot list, both server-thread-confined (no locking). Each publish cadence it **reconciles** the slot map against the current loaded set:

1. **Membership source.** The loaded + dormancy state comes from `ActiveSet` (the roster mutated by loader hooks on load/unload, server thread) and the current batch entries. Active batch entries (with fresh result temps) are passed straight from `writeBackResults`.
2. **Reconcile:**
   - **New section** (loaded, no slot) → pop a free slot (or extend the high-water if none free), record `cx/cz/sectionY`, set state.
   - **Active section** (in this batch) → write its result `temp[]`, state `LOADED_ACTIVE`.
   - **Dormant section** (loaded, not in batch) → keep slot + temps, state `LOADED_DORMANT`.
   - **Unloaded section** (had a slot, no longer loaded) → set record state `EMPTY`, return slot to the free list. **This is the churn fix.**
3. **`sectionCount`** = high-water slot index + 1 (the scan bound). Freed slots are reused holes; the viewer scans `[0, sectionCount)` and **skips `EMPTY`**.

Consequences:

- **Thermal values:** correct — active refreshed each cadence, dormant retain last (settled = unchanged).
- **Membership:** correct under churn — loads claim slots, unloads free them, dormant stay visible. No ghost accumulation.
- `sectionCap` bounds concurrent loaded sections; overflow → the new section is skipped + one-time log (§10). With slot reuse, only the *peak concurrent* loaded count must fit, not the session total.

## 9. Components & changes

> **No native/JNI changes.** `orge_jni.cpp`, `orge_kernel.hpp`, and the `orgeStep` signature are untouched. The bridge is entirely Java (publisher) + a new C++ *viewer* binary that only reads the shm.

### 9.1 C++ / viewer (read side only)

- **`sim_render.hpp`** — refactor view/nav functions to take `const World&` + a `RenderState&` instead of reaching into `SimServer`. Behavior unchanged for the existing tool.
- **`live_view.cpp`** (new) — `mmap` `run/orge_live.mmap` read-only, per frame seqlock-read into a reused `World`: for each non-`EMPTY` record, `ensureChunk(cx,cz)`, write `temp[]`→`T_curr` for that `sectionY`, mark `sectionLoaded[sectionY]`, optionally tint by `state`. Then run the shared `sim_render` loop. Disables SPACE-pause and paint; keeps WorldMap/ChunkView, WASD/arrows, enter/esc, color-scale, Q.
- **`sim_server` tool** — updated to pass `server.world` into the refactored render functions. Still builds, still self-driven, unchanged behavior.
- **Build** — `Makefile` (or extend the `main.cpp` g++ line) with two targets sharing `sim_render.hpp`: `sim_server` and `live_view`. Linux + SDL3 + SDL3_ttf.

### 9.2 Java (write side)

- **`ThermalViewPublisher`** (new) — owns the `MappedByteBuffer` over `run/orge_live.mmap`, the `Map<SubchunkKey,Integer> slotOf` + free-slot list, and the seqlock. API: `publish(activeEntries+results, loadedRoster, dormancyView, lastStepMs)`. Lazily creates + sizes + (optionally) pre-touches the file on first call. Does the §8 reconcile. Server-thread-confined, no locking.
- **`Scheduler.writeBackResults`** — after the existing write-back loop, if live-view enabled and on the conduction cadence (~1 Hz), call `publisher.publish(...)` with the results it already holds + the loaded/dormancy roster (`ActiveSet`). One extra bounded memcpy; no new thread.
- **Membership access** — expose the current loaded-in-range section set + dormancy flags to the publisher (from `ActiveSet`/`ThermalWorld`). Minimal read-only accessor; exact shape decided in the plan.
- **Config** — `orge.liveView.enabled` (default `false`), `orge.liveView.sectionCap` (default 2048), `orge.liveView.binaryPath` (default `ORGE-ENGINE/build/live_view`). When disabled: publisher never maps, `writeBack` skips it → zero cost.
- **`OrgeCommands`** — add `/orge view` literal: `ProcessBuilder` launches the viewer binary (path from config), inherits IO, non-blocking, fire-and-forget. If the flag is off, fail with a clear message ("enable orge.liveView first"). Re-runnable.

## 10. Failure modes

| Situation | Behavior |
|---|---|
| Viewer launched, liveView off / file absent | Viewer prints "no live data (enable orge.liveView)", idles, retries `open` |
| Game shuts down, file stale | `seq` stops advancing → viewer shows "stale" banner, holds last frame |
| Loaded sections exceed cap | Publisher skips the new section (no slot), logs once |
| Torn frame (read during write) | Reader sees odd/changed `seq` → retries; never renders torn data |
| Viewer crash | No effect on engine (separate process, one-way) |
| Section unloads | Publisher flips its slot to `EMPTY`, frees it; viewer stops rendering it next frame |

## 11. Testing

- **Java unit** — `ThermalViewPublisher`:
  - publish known active sections → read the `MappedByteBuffer` back → assert header + records + seqlock parity (even after publish).
  - churn: load A,B,C → unload B → assert B's slot flips `EMPTY` and is reused by a later load D; A,C retain slots/temps.
  - dormancy: a loaded section absent from the batch stays `LOADED_DORMANT` with prior temps.
  - UNIFORM section → all 4096 `temp[]` filled with the uniform value.
  - cap overflow → new section skipped, others intact.
- **C++ unit** (reuse `tests/` harness) — `live_view` deserialize: hand-crafted region → assert `World` populated (`T_curr`, `sectionLoaded`) from non-`EMPTY` records at the right `cx/cz/sectionY`; `EMPTY` records ignored; torn-frame (odd/changed `seq`) rejected/retried.
- **Manual** — enable flag, run game, `/orge view`; walk to load/unload chunks and confirm sections appear/disappear live; confirm thermal map matches `/orge get-live` readings; confirm server TPS unchanged with viewer on vs off.

## 12. Performance rules (binding)

1. Publish on the **server thread inside `writeBackResults`**, reusing the result temps already in hand — one bounded memcpy of the **active batch only** (not the full loaded set).
2. **No `MappedByteBuffer.force()`/`msync`** on the publish path — plain stores; OS flushes lazily, reader reads page cache.
3. **Map + (optionally) pre-touch once** on first publish; never remap or grow mid-session (fixed `cap`).
4. **Throttle to conduction cadence (~1 Hz)**; do not publish on advection sub-steps.
5. **Seqlock, no mutex** — writer never blocks; reader retries.
6. **Flag-gated** — disabled → publisher never maps, `writeBack` skips it, zero cost.
7. Dormant sections are **not recopied** — only `state` may change; their `temp[]` stays as last written.
