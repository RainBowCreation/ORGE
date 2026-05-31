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
| Publish site | **Inside the off-thread JNI `orgeStep`** (Option A) — memcpy live temps to shm on the runner thread |

## 4. Why publish inside `orgeStep` (performance rationale)

Threading model (confirmed in `Scheduler.java`):

- **Server (game) thread** runs `onServerTick` → `snapshot` → `writeBackResults`; all counted in the ~30 ms server-thread budget and the health throttle (`Scheduler.java:48,66,261`).
- **Off-thread runner** runs *only* the native `orgeStep` via `runner.submit(...)` (`Scheduler.java:198`).

The native step is the **only place that is both off the game thread and already holds every active section's new temperatures** (in `tOut`, hot in cache). Publishing there costs the server tick nothing. Publishing from Java (`writeBackResults`) would run on the server thread and eat the 50 ms tick budget — rejected.

Cost when on: one ~few-MB `memcpy` at 1 Hz on a non-game thread → unmeasurable on TPS. Cost when off: the JNI never maps and never copies (flag-gated at init).

## 5. Architecture / data path

```
[server thread]  snapshot ─┐
                            │  StepTask[] + coords + LUT  (BatchMarshaller.flatten)
[runner thread]  orgeStep ──┤  compute → tOut
                            └─► if liveView on: seqlock-write tOut+coords into shm  ◄── NEW
                                        │
                              run/orge_live.mmap  (named, pre-sized, pre-faulted)
                                        │
[separate process] live_view ──────────┘  mmap + seqlock-read → fill C++ World → sim_render (read-only)
```

- `/orge view` (server thread, rare) → `ProcessBuilder` spawns `live_view`. Does not touch the hot path.
- The shm region is created/mapped lazily on first `orgeStep` when the flag is on (once), or at lib load.

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

SectionRecord[sectionCap]  (each: 4 + 16384 = 16388 B, then padded to 16448 for alignment):
  i32   cx
  i32   sectionY
  i32   cz
  i32   _pad
  f32   temp[4096]      // SEC_N = 16*16*16, new temperatures (from tOut)
```

- **Size:** ~16.4 KB/section. Default `sectionCap = 2048` → ~33 MB. Config-tunable.
- **Temperature-only.** Viewer derives "loaded vs empty" from `sectionCount`/presence; color map is temperature-only (`temperatureToColor` already takes only temp).
- **Seqlock protocol:**
  - Writer: `seq++` (now odd) → write header fields + records → `seq++` (now even). Plain stores; **no `msync`/`fsync`**.
  - Reader: read `seq` (retry if odd) → copy fields → read `seq` again; if changed, retry. Never blocks the writer.
- **UNIFORM sections:** by the time data reaches `orgeStep`, `BatchMarshaller.flatten` has already expanded every section to a full 4096-cell `tIn`/`tOut` array. The native publisher copies `tOut` directly — no UNIFORM special case native-side.

## 7. Dimension handling

`SubchunkKey` carries `(cx, sectionY, cz)` — no dimension. v1 publishes a **single dimension's** sections (the engine's active world; default overworld). The `dim` header field records which. Multi-dimension is out of scope; if the engine runs multiple dims through one `orgeStep` batch, v1 tags all with the world's dim id and does not segregate. (Revisit only if multi-dim sim lands.)

## 8. Persistent union (membership model — locked)

Native sees only the **active batch** each step (Phase-2b dormancy → stepped set ⊂ loaded set; unloads never reported to native). To keep a usable thermal map rather than sections blinking out when they stop being stepped, the publisher maintains a **persistent union** within a session:

- A native-side `unordered_map<SubchunkKey, slot>` that **only grows** within a process session. First time a section appears in a batch, it claims the next free slot.
- Each frame the publisher updates `temp[]` for the slots of the **current batch**; slots not in the batch keep their **last written temps**.
- `sectionCount` = high-water count of distinct sections seen (number of claimed slots). The viewer renders all claimed slots `[0, sectionCount)`.

Consequences:

- **Thermal values:** correct. Dormant = settled = unchanged; persisted temps stay valid.
- **Membership:** exact for loaded/dormant sections within a session. **Unloads are not reflected** (an unloaded section lingers with its last temps until the viewer/engine restarts) — accepted as cosmetic for a debug view; exact unload tracking (a Java-written table) is deferred (YAGNI).
- `sectionCap` bounds the union; overflow → drop + one-time log (§10).

## 9. Components & changes

### 9.1 C++ / native

- **`orge_jni.cpp`** — extend `orgeStep` signature with an `int[] coords` param (3 ints/section: cx, sectionY, cz). After compute, if live-view enabled, call the publisher with `tOut` + `coords` + `n` + `lastStepMs`.
- **`orge_live_shm.hpp`** (new) — SDL-free. Owns: lazy `shm` create/map/pre-fault, the section→slot map (persistent union), seqlock write of header+records, flag read (env `ORGE_LIVE_VIEW=1` or system-property bridged via a JNI init call). Pure POSIX. Keeps `orge_kernel.hpp` SDL-free guarantee intact.
- **`sim_render.hpp`** — refactor view/nav functions to take `const World&` + a `RenderState&` instead of reaching into `SimServer`. Behavior unchanged for the existing tool.
- **`live_view.cpp`** (new) — `mmap` `run/orge_live.mmap`, per frame seqlock-read into a reused `World` (`ensureChunk`, set `T_curr`, mark `sectionLoaded`, set `section_ms_last`), then run the shared `sim_render` loop. Disables SPACE-pause and paint; keeps WorldMap/ChunkView, WASD/arrows, enter/esc, color-scale, Q.
- **`sim_server` tool** — updated to pass `server.world` into the refactored render functions. Still builds, still self-driven, unchanged behavior.
- **Build** — `Makefile` (or extend the `main.cpp` g++ line) with two targets sharing `sim_render.hpp`: `sim_server` and `live_view`. Linux + SDL3 + SDL3_ttf.

### 9.2 Java

- **`BatchMarshaller`** — `flatten` also emits `int[] coords` (length `3n`) from each `StepTask.key()` (`cx, sectionY, cz`). Marginal server-thread cost.
- **`NativeEngine`** — native method declaration updated to pass `coords` through to `orgeStep`.
- **Config** — `orge.liveView.enabled` (default `false`) and `orge.liveView.sectionCap` (default 2048), plus viewer binary path (default `ORGE-ENGINE/build/live_view`). On enable, a JNI init call sets the native flag + maps the region; on disable, native never maps.
- **`OrgeCommands`** — add `/orge view` literal: `ProcessBuilder` launches the viewer binary (path from config), inherits IO, non-blocking, fire-and-forget. If config flag is off, command fails with a clear message ("enable orge.liveView first"). Re-runnable.

## 10. Failure modes

| Situation | Behavior |
|---|---|
| Viewer launched, liveView off / file absent | Viewer prints "no live data (enable orge.liveView)", idles, retries `open` |
| Game shuts down, file stale | `seq` stops advancing → viewer shows "stale" banner, holds last frame |
| sectionCount would exceed cap | Native drops overflow sections, logs once |
| Torn frame (read during write) | Reader sees odd/changed `seq` → retries; never renders torn data |
| Viewer crash | No effect on engine (separate process, one-way) |

## 11. Testing

- **Java unit** — `BatchMarshaller.flatten` emits correct `coords` (cx, sectionY, cz per task, right order/length).
- **C++ unit** (reuse `tests/` harness) — `orge_live_shm`:
  - write known sections → mmap-read back → assert header + records + seqlock parity (even after publish).
  - persistent-union slot map: a section reappearing keeps its slot; dormant section stays visible.
  - torn-frame: simulate odd `seq` mid-read → reader rejects/retries.
- **C++ unit** — `live_view` deserialize: hand-crafted region → assert `World` populated (`T_curr`, `sectionLoaded`, `section_ms_last`) including dim/coord placement.
- **Manual** — enable flag, run game, `/orge view`, confirm thermal map matches `/orge get-live` readings; confirm server TPS unchanged with viewer on vs off.

## 12. Performance rules (binding)

1. Publish only on the **off-thread runner** (inside `orgeStep`); never the server thread.
2. **No `msync`/`fsync`** on the publish path — plain memory stores, OS flushes lazily, reader reads page cache.
3. **Map + pre-fault once** at init (touch all pages); never map or grow mid-step.
4. **Throttle to conduction cadence (~1 Hz)**; skip advection sub-steps.
5. **Seqlock, no mutex** — writer never blocks.
6. **Flag-gated at init** — off → never map, never copy, zero cost.
