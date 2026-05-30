# Native packaging — deferred multi-platform work (2026-05-29)

The engine seam (§2) ships a **linux-x64** `liborge.so` committed at
`core/src/main/resources/natives/linux-x64/liborge.so`, built from the ORGE-ENGINE
submodule via `native/build_liborge.sh`. The following is deferred (needs network +
the ORGE-ENGINE CI, neither available in the build sandbox):

- ORGE-ENGINE CI builds `liborge` for {windows, linux, macos} × {x64, arm64} and
  publishes them as release artifacts (pinned version).
- A Gradle task fetches the pinned artifacts into
  `src/main/resources/natives/<os>-<arch>/` so the jar bundles every platform; the
  runtime `NativeLoader` already extracts + `System.load`s the matching one (and
  falls back to `StubEngine` via `EngineFactory` when absent).
- Build the `.dll` (`orge.dll`) and `.dylib` (`liborge.dylib`) variants; `NativeLoader`
  already maps their names.

Until then, only linux-x64 runs the real engine; other platforms transparently fall
back to `StubEngine` (logged once).
